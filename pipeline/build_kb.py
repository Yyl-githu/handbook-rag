"""Build chapter-level Markdown files from the student handbook PDF.

Strategy: the PDF prints its own page number as a leading "-NNN-" marker on
every text page. We map printed numbers to PDF pages, then use the table of
contents (which lists printed page numbers) to cut chapters.
"""
import json
import os
import re
import sys

pages_json, out_dir = sys.argv[1], sys.argv[2]
pages = json.load(open(pages_json, encoding="utf-8"))
n = len(pages)

PAGE_NO = re.compile(r"^\s*-?\s*(\d{1,3})\s*-\s*")

def page_head(t: str) -> str:
    """Text of a page with the leading printed page number removed."""
    lines = [ln.strip() for ln in t.splitlines() if ln.strip()]
    if not lines:
        return ""
    first = PAGE_NO.sub("", lines[0], count=1).strip()
    body = [first] if first else []
    body.extend(lines[1:])
    return re.sub(r"\s+", "", "\n".join(body))

heads = [page_head(t) for t in pages]

# printed number -> pdf page (1-based)
printed_map = {}
for i, t in enumerate(pages):
    m = PAGE_NO.match(t or "")
    if m:
        printed_map.setdefault(int(m.group(1)), i + 1)

# ---------- table of contents ----------
toc_raw = "\n".join(pages[2:5])
entries, part, buf = [], "", ""
for ln in [x.strip() for x in toc_raw.splitlines() if x.strip()]:
    mp = re.match(r"^(第[一二三四五六七八九十]+部分)\s*(.*)$", ln)
    if mp and not re.search(r"\.{3,}", ln):
        part = (mp.group(1) + " " + mp.group(2)).strip()
        continue
    m = re.match(r"^(.*?)\.{3,}\s*(\d{1,3})\s*$", ln)
    if m:
        title = re.sub(r"\s+", "", buf + m.group(1))
        buf = ""
        if len(title) >= 4 and "目录" not in title:
            entries.append([part, title, int(m.group(2))])
    elif re.match(r"^第[一二三四五六七八九十]+部分", ln):
        part = re.sub(r"\s+", " ", ln)
    else:
        buf += re.sub(r"\s+", "", ln)

# ---------- resolve each entry to a PDF page ----------
resolved, unresolved = [], 0
for part, title, printed in entries:
    target = title[:14]
    candidates = []
    if printed in printed_map:
        candidates.append(printed_map[printed])
    candidates += [printed + d for d in range(0, 8)]  # fallback: nearby pages

    best = None
    for c in candidates:
        if not (1 <= c <= n):
            continue
        if heads[c - 1].startswith(target) or target in heads[c - 1][:40]:
            best = c
            break
    if best is None:
        # last resort: scan forward for the title
        for c in range(max(1, printed), min(n, printed + 12) + 1):
            if heads[c - 1].startswith(target):
                best = c
                break
    if best is None:
        unresolved += 1
        best = printed_map.get(printed, printed + 4)
    resolved.append([part, title, best])

print(f"toc={len(entries)} unresolved={unresolved}")

# keep monotonically increasing, drop duplicates
clean = []
for e in resolved:
    if clean and e[2] <= clean[-1][2]:
        continue
    clean.append(e)
print(f"chapters={len(clean)}")

# ---------- clean + merge ----------
END_PUNCT = "。！？；：）】》”」!?;:)]}"

def merge_paragraphs(text: str) -> str:
    lines = [ln.strip() for ln in text.split("\n") if ln.strip()]
    if not lines:
        return ""
    widths = sorted(len(ln) for ln in lines)
    full = widths[int(len(widths) * 0.75)] or 40
    out, cur = [], ""
    for ln in lines:
        starts_new = bool(re.match(
            r"^(第[一二三四五六七八九十百]+[章节条]|[一二三四五六七八九十]+、|（[一二三四五六七八九十]+）|\([一二三四五六七八九十]+\))", ln))
        if not cur:
            cur = ln
        elif starts_new or (len(cur) >= full * 0.95 and cur[-1] in END_PUNCT):
            out.append(cur)
            cur = ln
        else:
            cur += ln
    if cur:
        out.append(cur)
    return "\n\n".join(out)

def strip_leading_title(body: str, title: str) -> str:
    """Remove a repeated heading at the start of the body text."""
    target = re.sub(r"\s+", "", title)
    i = j = 0
    while i < len(body) and j < len(target):
        ch = body[i]
        if ch.isspace():
            i += 1
            continue
        if ch == target[j]:
            i += 1
            j += 1
            continue
        break
    return body[i:].lstrip() if j == len(target) else body


os.makedirs(out_dir, exist_ok=True)
for f in os.listdir(out_dir):
    if f.endswith(".md"):
        os.remove(os.path.join(out_dir, f))

report = []
for i, (part, title, start) in enumerate(clean):
    end = (clean[i + 1][2] - 1) if i + 1 < len(clean) else n
    chunks = []
    for p in range(start, end + 1):
        t = pages[p - 1]
        lines = [ln.strip() for ln in t.splitlines() if ln.strip()]
        if lines:
            lines[0] = PAGE_NO.sub("", lines[0], count=1).strip()
        chunks.append("\n".join([x for x in lines if x]))
    body = merge_paragraphs("\n".join(chunks))
    body = strip_leading_title(body, title)
    safe = re.sub(r"[^\w\u4e00-\u9fff]+", "_", title)[:40] or f"chapter{i+1}"
    fname = f"{i+1:02d}_{safe}.md"
    fm = ("---\n"
          f"title: {json.dumps(title, ensure_ascii=False)}\n"
          f"part: {json.dumps(part, ensure_ascii=False)}\n"
          "source: 贵财学生手册.pdf\n"
          f"pages: {json.dumps(f'{start}-{end}', ensure_ascii=False)}\n"
          "---\n\n"
          f"# {title}\n\n")
    with open(os.path.join(out_dir, fname), "w", encoding="utf-8") as fh:
        fh.write(fm + body.strip() + "\n")
    report.append({"idx": i + 1, "title": title, "part": part,
                   "pages": [start, end], "chars": len(body), "file": fname})

json.dump(report, open(os.path.join(out_dir, "_index.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)

total = sum(r["chars"] for r in report)
print(f"total_chars={total} min={min(r['chars'] for r in report)} max={max(r['chars'] for r in report)}")
for r in report:
    print(f"  {r['idx']:02d} p{r['pages'][0]:03d}-{r['pages'][1]:03d} {r['chars']:6d} {r['title'][:34]}")