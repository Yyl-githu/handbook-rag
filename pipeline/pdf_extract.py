import sys, json, re
from pypdf import PdfReader

path = sys.argv[1]
json_out = sys.argv[2]
starts_out = sys.argv[3]

reader = PdfReader(path)
pages = []
for i, p in enumerate(reader.pages):
    try:
        t = p.extract_text() or ""
    except Exception as e:
        t = ""
    pages.append(t)

with open(json_out, "w", encoding="utf-8") as f:
    json.dump(pages, f, ensure_ascii=False)

lines = []
for i, t in enumerate(pages):
    flat = re.sub(r"\s+", " ", t).strip()
    lines.append(f"p{i+1:03d} len={len(flat):4d} | {flat[:80]}")
with open(starts_out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))

print(f"pages={len(pages)} written")