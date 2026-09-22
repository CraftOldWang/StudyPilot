"""Small resumable probes through the real application API; records each HTTP result."""
import argparse
import hashlib
import json
import mimetypes
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["ready", "create", "search", "hello", "upload", "retry", "status"])
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--file", type=Path)
    parser.add_argument("--query", default="什么是稳定匹配？")
    parser.add_argument("--mode", choices=["BM25", "VECTOR", "RRF", "PARENT"])
    parser.add_argument("--top-k", type=int)
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    state_path = args.run_dir / "state.json"
    state = json.loads(state_path.read_text(encoding="utf-8-sig")) if state_path.exists() else {}
    session = requests.Session()
    session.headers["X-User-Id"] = "1"

    def request(method, path, **kwargs):
        start = time.perf_counter()
        event = {"stage": args.stage, "method": method, "path": path,
                 "recordedAt": datetime.now(timezone.utc).isoformat()}
        try:
            response = session.request(method, args.base_url + path, timeout=(10, 120), **kwargs)
            event.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"))
            payload = response.json()
            event["response"] = payload
            response.raise_for_status()
            if payload.get("code") != 0:
                raise RuntimeError("Application returned a failure; see the recorded response")
            return payload["data"]
        except requests.RequestException as error:
            event["transportError"] = type(error).__name__
            raise
        finally:
            event["elapsedMillis"] = (time.perf_counter() - start) * 1000
            with (args.run_dir / "http.jsonl").open("a", encoding="utf-8") as output:
                output.write(json.dumps(event, ensure_ascii=False) + "\n")

    if args.stage == "ready":
        deadline = time.monotonic() + 45
        while True:
            try:
                response = session.get(args.base_url + "/api/knowledge-bases", timeout=(2, 2))
                response.raise_for_status()
                if response.json().get("code") == 0:
                    print(json.dumps({"ready": True, "knowledgeBases": response.json()["data"]}, ensure_ascii=False))
                    return
            except requests.RequestException:
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError("Application did not become ready within 45 seconds")
            time.sleep(1)
    elif args.stage == "create":
        if "knowledgeBaseId" in state:
            raise RuntimeError("This run already has a knowledge base; reuse the recorded id")
        data = request("POST", "/api/knowledge-bases", json={"name": "M3 smoke " + args.run_dir.name})
        state["knowledgeBaseId"] = data["id"]
    elif args.stage == "retry":
        document_id = state["files"][-1]["result"]["documentId"]
        data = request("POST", f"/api/documents/{document_id}/retry")
    elif args.stage == "hello":
        data = request("POST", "/api/agent/hello")
    else:
        kb = state["knowledgeBaseId"]
        if args.stage == "search":
            payload = {"query": args.query}
            if args.mode is not None:
                payload["mode"] = args.mode
            if args.top_k is not None:
                payload["topK"] = args.top_k
            data = request("POST", f"/api/knowledge-bases/{kb}/search", json=payload)
        elif args.stage == "status":
            data = request("GET", f"/api/knowledge-bases/{kb}/documents")
        else:
            if args.file is None:
                raise ValueError("upload requires --file")
            with args.file.open("rb") as source:
                sha256 = hashlib.file_digest(source, "sha256").hexdigest()
                source.seek(0)
                data = request("POST", "/api/files/upload", data={"knowledgeBaseId": kb}, files={
                    "file": (args.file.name, source, mimetypes.guess_type(args.file.name)[0]
                             or "application/octet-stream")})
            state.setdefault("files", []).append({"path": str(args.file.resolve()), "sha256": sha256,
                                                    "bytes": args.file.stat().st_size, "result": data})
    state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(data, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
