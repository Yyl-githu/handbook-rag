# -*- coding: utf-8 -*-
"""Baseline retrieval evaluation for the handbook knowledge base.

Calls the retrieval-only endpoint (no LLM generation) and reports:
  hit@1 / hit@3 / hit@5, MRR, mean top-1 score, and the misses.
"""
import base64
import json
import os
import statistics
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL_FILE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "handbook_eval_questions.jsonl")
OUT_FILE = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "baseline_results.json")
TOPK = int(sys.argv[3]) if len(sys.argv) > 3 else 8
THRESHOLD = float(sys.argv[4]) if len(sys.argv) > 4 else 0.3
MODE = sys.argv[5] if len(sys.argv) > 5 else "vector"
BASE = os.environ.get("RAG_BASE", "http://localhost:8080")
PASSWORD = os.environ.get("RAG_PASS", "")


def auth_header():
    """The API is behind HTTP Basic; set RAG_USER/RAG_PASS to run the harness."""
    if not PASSWORD:
        return {}
    user = os.environ.get("RAG_USER", "admin")
    token = base64.b64encode(f"{user}:{PASSWORD}".encode()).decode()
    return {"Authorization": "Basic " + token}


def load_cases():
    cases = []
    with open(EVAL_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases


def retrieve_batch(queries):
    url = f"{BASE}/api/v2/retrieval/batch?topK={TOPK}&threshold={THRESHOLD}&mode={MODE}"
    body = json.dumps(queries, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, method="POST", headers={"Content-Type": "application/json", **auth_header()}
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode("utf-8"))


def chapter_of(source):
    """Turn a stored source path/filename into its chapter id, e.g. 15_xxx.md -> '15'."""
    if not source:
        return ""
    name = str(source).replace("\\", "/").split("/")[-1]
    head = name.split("_", 1)[0]
    return head if head.isdigit() else ""


def main():
    cases = load_cases()
    print(f"cases={len(cases)} topK={TOPK} threshold={THRESHOLD}")
    started = time.time()
    batches = retrieve_batch([c["question"] for c in cases])
    elapsed = time.time() - started
    print(f"retrieval took {elapsed:.1f}s ({elapsed/len(cases):.2f}s per query)")

    results = []
    for case, hits in zip(cases, batches):
        expected = set(case["expected_source_ids"])
        ranked = [chapter_of(h.get("source")) for h in hits]
        # keep first occurrence of each chapter
        seen, deduped = set(), []
        for ch in ranked:
            if ch and ch not in seen:
                seen.add(ch)
                deduped.append(ch)
        rank = next((i + 1 for i, ch in enumerate(deduped) if ch in expected), None)
        top = hits[0] if hits else {}
        # hybrid modes report an RRF score; keep the cosine separately so numbers stay comparable
        cosine = top.get("vectorScore")
        results.append(
            {
                "id": case["id"],
                "category": case["category"],
                "style": case.get("style", ""),
                "question": case["question"],
                "expected_source_ids": sorted(expected),
                "retrieved_chapters": deduped,
                "rank": rank,
                "hit@1": rank == 1,
                "hit@3": rank is not None and rank <= 3,
                "hit@5": rank is not None and rank <= 5,
                "top1_score": round(float(top["score"]), 4) if top.get("score") else None,
                "top1_cosine": round(float(cosine), 4) if cosine is not None else None,
                "top1_rerank": round(float(top["rerankScore"]), 4) if top.get("rerankScore") else None,
                "num_chunks": len(hits),
                "chunks_per_chapter": {ch: ranked.count(ch) for ch in deduped},
            }
        )

    n = len(results)
    mrr = sum(1.0 / r["rank"] for r in results if r["rank"]) / n
    cosines = [r["top1_cosine"] for r in results if r["top1_cosine"] is not None]
    summary = {
        "metric_scope": "retrieval only (no query rewrite, no LLM generation)",
        "mode": MODE,
        "topK": TOPK,
        "threshold": THRESHOLD,
        "total": n,
        "hit@1": round(sum(r["hit@1"] for r in results) / n, 4),
        "hit@3": round(sum(r["hit@3"] for r in results) / n, 4),
        "hit@5": round(sum(r["hit@5"] for r in results) / n, 4),
        "mrr": round(mrr, 4),
        "mean_top1_cosine": round(statistics.mean(cosines), 4) if cosines else None,
        "misses": [r["id"] for r in results if r["rank"] is None],
        "by_style": {},
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    for style in ("verbatim", "paraphrase"):
        sub = [r for r in results if r["style"] == style]
        if sub:
            summary["by_style"][style] = {
                "n": len(sub),
                "hit@1": round(sum(r["hit@1"] for r in sub) / len(sub), 4),
                "hit@3": round(sum(r["hit@3"] for r in sub) / len(sub), 4),
                "mrr": round(sum(1.0 / r["rank"] for r in sub if r["rank"]) / len(sub), 4),
            }

    payload = {"summary": summary, "results": results}
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)

    print("\n=== baseline ===")
    for k in ("hit@1", "hit@3", "hit@5", "mrr", "mean_top1_cosine"):
        print(f"{k:>16}: {summary[k]}")
    for style, m in summary["by_style"].items():
        print(f"  {style:<11} n={m['n']} hit@1={m['hit@1']} hit@3={m['hit@3']} mrr={m['mrr']}")
    if summary["misses"]:
        print("misses:", ", ".join(summary["misses"]))
    print(f"written -> {OUT_FILE}")


if __name__ == "__main__":
    main()