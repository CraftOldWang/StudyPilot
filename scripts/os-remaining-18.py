"""Complete the 28 user questions against the existing full OS knowledge base."""
import json
import sys
import time
from pathlib import Path

import requests

sys.stdout.reconfigure(encoding="utf-8")
out = Path("output/os-remaining-18")
out.mkdir(parents=True, exist_ok=True)
session = requests.Session()
session.trust_env = False
session.headers["X-User-Id"] = "1"
kbid = "2098021244222922753"
previous = {1, 5, 6, 7, 10, 14, 15, 18, 26, 28}


def api(method, path, **kwargs):
    response = session.request(method, "http://127.0.0.1:8080" + path,
                               timeout=180, **kwargs)
    response.raise_for_status()
    body = response.json()
    if body["code"] != 0:
        raise RuntimeError(str(body))
    return body["data"]


def save(name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


documents = api("GET", f"/api/knowledge-bases/{kbid}/documents")
if len(documents) != 18 or any(d["pipelineStatus"] != "INDEXED" for d in documents):
    raise RuntimeError("Expected the existing 18 indexed OS documents; no automatic re-ingest")
save("documents.json", documents)
questions = [json.loads(line) for line in Path("eval/rag/os-human-draft.jsonl")
             .read_text(encoding="utf-8").splitlines()]
questions = [q for q in questions if int(q["id"].split("-")[-1]) not in previous]
save("questions.json", questions)
for q in questions:
    for mode in ("BM25", "VECTOR", "RRF", "PARENT"):
        filename = f'{q["id"]}-{mode}.json'
        if (out / filename).exists():
            cached = json.loads((out / filename).read_text(encoding="utf-8"))
            if cached["question"]["query"] != q["query"]:
                raise RuntimeError(f"Saved query differs: {filename}; use a fresh output directory")
            continue
        start = time.perf_counter()
        result = api("POST", f"/api/knowledge-bases/{kbid}/search",
                     json={"query": q["query"], "topK": 5, "mode": mode})
        save(filename, {"question": q, "mode": mode,
                        "elapsedSeconds": time.perf_counter() - start, "response": result})
        print(f'{q["id"]} {mode} done', flush=True)
    rrf = json.loads((out / f'{q["id"]}-RRF.json').read_text(encoding="utf-8"))["response"]
    parent = json.loads((out / f'{q["id"]}-PARENT.json').read_text(encoding="utf-8"))["response"]
    if [h["chunkId"] for h in rrf["rankedChildren"]] != [h["chunkId"] for h in parent["rankedChildren"]]:
        raise RuntimeError(f'{q["id"]}: RRF/PARENT ranking differs; inspect before paired comparison')
