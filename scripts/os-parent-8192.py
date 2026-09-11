"""Run the 28 OS questions against an eval backend configured with an 8192-token budget."""
import json
import sys
from pathlib import Path

import requests

sys.stdout.reconfigure(encoding="utf-8")
out = Path("output/os-parent-8192")
out.mkdir(parents=True, exist_ok=True)
session = requests.Session()
session.trust_env = False
session.headers["X-User-Id"] = "1"
questions = [json.loads(line) for line in Path("eval/rag/os-human-draft.jsonl")
             .read_text(encoding="utf-8").splitlines()]
for q in questions:
    target = out / f'{q["id"]}.json'
    if target.exists():
        continue
    response = session.post(
        "http://127.0.0.1:8080/api/eval/knowledge-bases/2098021244222922753/context-comparison",
        json={"query": q["query"], "topK": 5}, timeout=120)
    response.raise_for_status()
    body = response.json()
    if body["code"] != 0:
        raise RuntimeError(str(body))
    target.write_text(json.dumps({"question": q, "contextBudget": 8192,
                                 "response": body["data"]}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(q["id"], "done", flush=True)
