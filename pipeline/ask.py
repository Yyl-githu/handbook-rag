"""Ask the running RAG service a list of questions and dump the answers.

Usage:
    python pipeline/ask.py out.txt "问题一" "问题二" ...

Environment:
    RAG_BASE  service base URL (default http://localhost:8080)
    RAG_USER  login user       (default admin)
    RAG_PASS  login password   (required when the service has a password)
"""
import base64
import json
import os
import sys
import urllib.request

BASE = os.environ.get("RAG_BASE", "http://localhost:8080")
API = BASE + "/api/v2/chat"
questions = sys.argv[2:]
out_path = sys.argv[1]


def auth_header():
    password = os.environ.get("RAG_PASS", "")
    if not password:
        return {}
    user = os.environ.get("RAG_USER", "admin")
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    return {"Authorization": "Basic " + token}


results = []
for q in questions:
    body = json.dumps({"message": q, "conversationId": "eval-" + str(abs(hash(q)) % 10000)}).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    headers.update(auth_header())
    req = urllib.request.Request(API, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            data = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        data = {"content": f"ERROR {e}", "sources": []}
    results.append({"q": q, "a": data.get("content", ""), "sources": data.get("sources", [])})

open(out_path, "w", encoding="utf-8").write(
    "\n\n".join(f"Q: {r['q']}\nA: {r['a']}\nS: {', '.join(r['sources'])}" for r in results)
)
print("done", len(results))