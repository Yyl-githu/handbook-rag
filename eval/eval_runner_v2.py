# -*- coding: utf-8 -*-
"""Evaluation v2 for the handbook assistant.

Two things are measured separately, because they fail for different reasons:

  1. gate   - should an unsupported question be declined instead of answered?
              (checks /api/v2/gate, costs one embedding call per case)
  2. recall - for answerable questions, is the right chapter ranked first?
              (checks /api/v2/retrieval, no chat-model call)

Negative cases (`expect=no_answer`) only contribute to the gate metric; answerable cases
contribute to both. `must_not_be_top1` lists chapters that must not win the first place,
which is how confusable-rule mistakes are counted.
"""
import base64
import json
import os
import statistics
import sys
import time
import urllib.request

BASE = os.environ.get("RAG_BASE", "http://localhost:8080")
USER = os.environ.get("RAG_USER", "admin")
PASSWORD = os.environ.get("RAG_PASS", "")

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL_FILE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "handbook_eval_v2.jsonl")
OUT_FILE = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "v2_results.json")
TOPK = int(sys.argv[3]) if len(sys.argv) > 3 else 8
MODE = sys.argv[4] if len(sys.argv) > 4 else "hybrid"


def headers():
    h = {"Content-Type": "application/json"}
    if PASSWORD:
        token = base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
        h["Authorization"] = "Basic " + token
    return h


def post(path, payload, timeout=900):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers(),
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def load_cases():
    cases = []
    with open(EVAL_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases


def chapter_of(source):
    if not source:
        return ""
    name = str(source).replace("\\", "/").split("/")[-1]
    head = name.split("_", 1)[0]
    return head if head.isdigit() else ""


def main():
    cases = load_cases()
    negatives = [c for c in cases if c.get("expect") == "no_answer"]
    answerable = [c for c in cases if c.get("expect") != "no_answer"]
    print(f"cases={len(cases)} (answerable={len(answerable)}, negative={len(negatives)}) "
          f"topK={TOPK} mode={MODE}")

    started = time.time()
    gate_rows = post("/api/v2/gate/batch", [c["question"] for c in cases])
    gate_elapsed = time.time() - started

    started = time.time()
    retrieval_rows = (
        post(
            f"/api/v2/retrieval/batch?topK={TOPK}&threshold=0.3&mode={MODE}",
            [c["question"] for c in answerable],
        )
        if answerable
        else []
    )
    retrieval_elapsed = time.time() - started
    print(f"gate {gate_elapsed:.1f}s, retrieval {retrieval_elapsed:.1f}s")

    gate_by_q = {row["question"]: row for row in gate_rows}
    results = []
    for case in cases:
        gate = gate_by_q.get(case["question"], {})
        decision = gate.get("decision", "?")
        expects_answer = case.get("expect") != "no_answer"
        gate_ok = decision != "no_answer" if expects_answer else decision == "no_answer"
        results.append(
            {
                "id": case["id"],
                "hard_type": case.get("hard_type", ""),
                "expect": case.get("expect", "answer"),
                "question": case["question"],
                "decision": decision,
                "gate_ok": gate_ok,
                "best_cosine": round(gate["bestCosine"], 4) if gate.get("bestCosine") is not None else None,
                "suggestions": gate.get("suggestions", []),
            }
        )

    by_id = {r["id"]: r for r in results}
    for case, hits in zip(answerable, retrieval_rows):
        row = by_id[case["id"]]
        expected = set(case.get("expected_source_ids", []))
        ranked, seen = [], set()
        for hit in hits:
            chapter = chapter_of(hit.get("source"))
            if chapter and chapter not in seen:
                seen.add(chapter)
                ranked.append(chapter)
        rank = next((i + 1 for i, ch in enumerate(ranked) if ch in expected), None)
        top1 = ranked[0] if ranked else ""
        forbidden = set(case.get("must_not_be_top1", []))
        row.update(
            {
                "expected": sorted(expected),
                "ranked": ranked,
                "rank": rank,
                "hit@1": rank == 1,
                "hit@3": rank is not None and rank <= 3,
                "top1": top1,
                "top1_forbidden": top1 in forbidden if forbidden else False,
                "top1_cosine": round(hits[0]["vectorScore"], 4)
                if hits and hits[0].get("vectorScore") is not None
                else None,
            }
        )

    def rate(rows, key):
        return round(sum(1 for r in rows if r.get(key)) / len(rows), 4) if rows else None

    mrr = (
        sum(1.0 / r["rank"] for r in results if r.get("rank")) / len(answerable)
        if answerable
        else None
    )
    summary = {
        "mode": MODE,
        "topK": TOPK,
        "total": len(cases),
        "answerable": len(answerable),
        "negative": len(negatives),
        "gate_accuracy": rate(results, "gate_ok"),
        "negative_rejection": rate([r for r in results if r["expect"] == "no_answer"], "gate_ok"),
        "answerable_accepted": rate(
            [r for r in results if r["expect"] != "no_answer"], "gate_ok"
        ),
        "hit@1": rate([r for r in results if r["expect"] != "no_answer"], "hit@1"),
        "hit@3": rate([r for r in results if r["expect"] != "no_answer"], "hit@3"),
        "mrr": round(mrr, 4) if mrr is not None else None,
        "confusable_violations": [
            r["id"] for r in results if r.get("top1_forbidden")
        ],
        "by_hard_type": {},
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    for hard_type in ("confusable", "precise", "multi_hop", "regression", "negative"):
        sub = [r for r in results if r["hard_type"] == hard_type]
        if not sub:
            continue
        entry = {"n": len(sub), "gate_ok": rate(sub, "gate_ok")}
        sub_answer = [r for r in sub if r["expect"] != "no_answer"]
        if sub_answer:
            entry["hit@1"] = rate(sub_answer, "hit@1")
            entry["hit@3"] = rate(sub_answer, "hit@3")
            entry["mrr"] = round(
                sum(1.0 / r["rank"] for r in sub_answer if r.get("rank")) / len(sub_answer), 4
            )
            entry["top1_forbidden"] = [r["id"] for r in sub_answer if r.get("top1_forbidden")]
        summary["by_hard_type"][hard_type] = entry

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": results}, f, ensure_ascii=False, indent=1)

    print("\n=== v2 ===")
    for key in ("gate_accuracy", "negative_rejection", "answerable_accepted", "hit@1", "hit@3", "mrr"):
        print(f"{key:>20}: {summary[key]}")
    print("\nby hard type:")
    for hard_type, metrics in summary["by_hard_type"].items():
        print(f"  {hard_type:<11} {json.dumps(metrics, ensure_ascii=False)}")

    missed = [r for r in results if not r["gate_ok"] or r.get("hit@1") is False]
    if missed:
        print("\nfailures:")
        for r in missed:
            print(f"  {r['id']:<9} {r['hard_type']:<11} decision={r['decision']:<9} "
                  f"rank={r.get('rank')} top1={r.get('top1')} cos={r.get('best_cosine')} "
                  f"| {r['question'][:28]}")
    print(f"\nwritten -> {OUT_FILE}")


if __name__ == "__main__":
    main()