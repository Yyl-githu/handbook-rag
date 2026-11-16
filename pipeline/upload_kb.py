"""Upload the chapter Markdown files to a running RAG service.

Usage:
    python pipeline/upload_kb.py [kb_dir] [delay_seconds]

Environment:
    RAG_BASE  service base URL      (default http://localhost:8080)
    RAG_USER  login user            (default admin)
    RAG_PASS  login password        (required when the service has a password)

The service must be running and its knowledge base empty; otherwise the same chapters
are ingested twice. See pipeline/README.md for the full rebuild sequence.
"""
import base64
import glob
import json
import mimetypes
import os
import sys
import time
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("RAG_BASE", "http://localhost:8080")
API = BASE + "/api/v2/documents"
KB_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "kb")
DELAY = float(sys.argv[2]) if len(sys.argv) > 2 else 0.6


def auth_header():
    password = os.environ.get("RAG_PASS", "")
    if not password:
        return {}
    user = os.environ.get("RAG_USER", "admin")
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    return {"Authorization": "Basic " + token}


files = sorted(glob.glob(os.path.join(KB_DIR, "*.md")))
print(f"files={len(files)}")


def post_file(path):
    boundary = "----dsh" + uuid.uuid4().hex
    name = os.path.basename(path)
    data = open(path, "rb").read()
    ctype = mimetypes.guess_type(path)[0] or "text/markdown"
    body = b""
    body += f"--{boundary}\r\n".encode()
    body += (
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
    ).encode("utf-8")
    body += f"Content-Type: {ctype}\r\n\r\n".encode()
    body += data + b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    headers.update(auth_header())
    req = urllib.request.Request(API, data=body, method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read().decode("utf-8"))


results = []
ok = fail = 0
for i, path in enumerate(files, 1):
    try:
        r = post_file(path)
        status = r.get("status")
        if status == "SUCCESS":
            ok += 1
            print(f"[{i:02d}/{len(files)}] OK   {r['chunksCreated']:3d} chunks  {r['filename']}")
        else:
            fail += 1
            print(f"[{i:02d}/{len(files)}] FAIL {r.get('message', '')[:120]}")
        results.append({"file": os.path.basename(path), **r})
    except Exception as e:
        fail += 1
        print(f"[{i:02d}/{len(files)}] ERR  {e}")
        results.append({"file": os.path.basename(path), "status": "ERROR", "message": str(e)})
    time.sleep(DELAY)

with open(os.path.join(HERE, "_upload.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=1)
print(f"done ok={ok} fail={fail} chunks={sum(r.get('chunksCreated') or 0 for r in results)}")