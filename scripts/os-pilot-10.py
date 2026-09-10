"""Ten user-written questions, one complete OS course, real retrieval APIs."""
import json
import sys
import time
from pathlib import Path
import requests

sys.stdout.reconfigure(encoding="utf-8")
out = Path("output/os-pilot-10")
out.mkdir(parents=True, exist_ok=True)
session = requests.Session()
session.trust_env = False
session.headers["X-User-Id"] = "1"


def api(method, path, **kwargs):
    response = session.request(method, "http://127.0.0.1:8080" + path, timeout=180, **kwargs)
    response.raise_for_status()
    body = response.json()
    if body["code"] != 0:
        raise RuntimeError(str(body))
    return body["data"]


def save(name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


name = "操作系统 · 2024完整课件"
existing = [kb for kb in api("GET", "/api/knowledge-bases") if kb["name"] == name]
kb = existing[0] if existing else api("POST", "/api/knowledge-bases", json={"name": name})
save("knowledge-base.json", kb)
kbid = kb["id"]
folder = Path("D:/Download/BDNetdisk_DL/操作系统/课件")
files = sorted(file for file in folder.glob("*.pptx") if not file.name.startswith("~$"))
assert len(files) == 18, f"Unexpected course scope: {len(files)}"
documents = api("GET", f"/api/knowledge-bases/{kbid}/documents")
titles = {doc["title"] for doc in documents}
for file in files:
    if file.name in titles:
        continue
    with file.open("rb") as stream:
        result = api("POST", "/api/files/upload", data={"knowledgeBaseId": kbid},
                     files={"file": (file.name, stream, "application/vnd.openxmlformats-officedocument.presentationml.presentation")})
    print(json.dumps({"uploaded": file.name, "documentId": result["documentId"]}, ensure_ascii=False), flush=True)

deadline = time.monotonic() + 900
previous = None
while True:
    documents = api("GET", f"/api/knowledge-bases/{kbid}/documents")
    save("documents.json", documents)
    failed = [doc for doc in documents if doc["pipelineStatus"] == "FAILED"]
    if failed:
        raise RuntimeError(json.dumps(failed, ensure_ascii=False))
    statuses = {}
    for doc in documents:
        status = doc["pipelineStatus"]
        statuses[status] = statuses.get(status, 0) + 1
    if statuses != previous:
        print(json.dumps(statuses), flush=True)
        previous = statuses
    if statuses.get("INDEXED") == 18:
        break
    if time.monotonic() > deadline:
        raise RuntimeError("Ingest still running; rerun without uploading existing documents")
    time.sleep(3)

selected = {1, 5, 6, 7, 10, 14, 15, 18, 26, 28}
questions = [json.loads(line) for line in Path("eval/rag/os-human-draft.jsonl").read_text(encoding="utf-8").splitlines()]
questions = [q for q in questions if int(q["id"].split("-")[-1]) in selected]
save("questions.json", questions)
for q in questions:
    for mode in ("BM25", "VECTOR", "RRF", "PARENT"):
        filename = f'{q["id"]}-{mode}.json'
        if (out / filename).exists():
            continue
        request = {"query": q["query"], "topK": 5}
        endpoint = f"/api/knowledge-bases/{kbid}/search"
        request["mode"] = mode
        start = time.perf_counter()
        result = api("POST", endpoint, json=request)
        save(filename, {"question": q, "mode": mode, "elapsedSeconds": time.perf_counter() - start, "response": result})
        print(f'{q["id"]} {mode} done', flush=True)
    rrf = json.loads((out / f'{q["id"]}-RRF.json').read_text(encoding="utf-8"))["response"]
    parent = json.loads((out / f'{q["id"]}-PARENT.json').read_text(encoding="utf-8"))["response"]
    assert [h["chunkId"] for h in rrf["rankedChildren"]] == [h["chunkId"] for h in parent["rankedChildren"]], "RRF/PARENT child ranking changed; cannot treat as paired"
