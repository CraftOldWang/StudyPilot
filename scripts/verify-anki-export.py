"""Real local Anki acceptance with authored fixtures and a one-shot lost-response proxy.

Run the eval backend with STUDY_AGENT_ANKI_ENDPOINT=http://host.docker.internal:8766.
This script never reads unrelated notes or triggers a model/ingestion request.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
import uuid

import requests


def encoded(value):
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=root", "study-agent-mysql", "mysql",
        "--default-character-set=utf8mb4", "--raw", "-uroot", "-D", "study_agent_eval", "-N"],
        input=statement.encode("utf-8"), capture_output=True, check=True)
    return result.stdout.decode("utf-8").strip()


def call(action, params=None):
    response = requests.post("http://127.0.0.1:8765", json={"action": action, "version": 6, "params": params or {}}, timeout=15)
    response.raise_for_status()
    result = response.json()
    assert result["error"] is None, result
    return result["result"]


def api(method, path, body=None, user=1):
    response = requests.request(method, "http://127.0.0.1:8080" + path,
                                headers={"X-User-Id": str(user)}, json=body, timeout=90)
    return {"httpStatus": response.status_code, "body": response.json()}


def attempts():
    return sum(json.loads(line).get("status") == "STARTED"
               for line in Path(".eval/model-calls.jsonl").read_text(encoding="utf-8").splitlines() if line.strip())


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    root = args.run_dir
    root.mkdir(parents=True, exist_ok=True)
    assert not (root / "fixture.json").exists(), "Use a new run directory; prior failures remain preserved"
    installed = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    env = dict(item.split("=", 1) for item in installed["Config"]["Env"])
    assert env.get("STUDY_AGENT_ANKI_ENDPOINT") == "http://host.docker.internal:8766", "Expected fault-injection endpoint"
    assert "study_agent_upload_eval" not in env.get("SPRING_DATASOURCE_URL", ""), "Restore course eval DB first"
    assert call("version") == 6
    with socket.socket() as check:
        check.settimeout(1)
        assert check.connect_ex(("127.0.0.1", 8766)) != 0, "Fault-injection port must initially be closed"
    before = attempts()
    kb = api("POST", "/api/knowledge-bases", {"name": "Anki 导出验收 · 合成资料"})
    assert kb["body"]["code"] == 0, kb
    kid = int(kb["body"]["data"]["id"])
    base = 6_100_000_000_000_000_000 + uuid.uuid4().int % 100_000_000_000 * 100
    sid, pid, fid, did, cid = [base + i for i in range(5)]
    chunk = "anki-" + str(uuid.uuid4())
    source = "队列按照先进先出顺序处理元素。栈按照后进先出顺序处理元素。二分查找要求数据有序。"
    digest = hashlib.sha256(source.encode()).hexdigest()
    pairs = [("队列按什么顺序处理元素？", "先进先出（FIFO）。"),
             ("栈按什么顺序处理元素？", "后进先出（LIFO）。"),
             ("二分查找对数据有什么前提？", "数据必须有序。")]
    cards = [{"id": str(base + 10 + i), "front": f, "back": b, "sourceChunkId": chunk} for i, (f, b) in enumerate(pairs)]
    fixture = {"kind": "authored SQL fixture, not generated learning or ingestion acceptance", "knowledgeBaseId": str(kid),
               "sessionId": str(sid), "pointId": str(pid), "documentId": str(did), "chunkId": chunk, "cards": cards}
    (root / "fixture.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding="utf-8")
    statement = f"""
      START TRANSACTION;
      INSERT INTO file_records(id,user_id,knowledge_base_id,filename,file_size,file_hash,storage_key,status,created_at)
        VALUES({fid},1,{kid},'Anki合成验收.txt',{len(source.encode())},{encoded(digest)},'fixtures/anki-not-uploaded','FAILED',UTC_TIMESTAMP());
      INSERT INTO logical_documents(id,user_id,knowledge_base_id) VALUES({did},1,{kid});
      INSERT INTO documents(id,file_record_id,user_id,knowledge_base_id,logical_document_id,title,content_type,pipeline_status,error_message,created_at,updated_at)
        VALUES({did},{fid},1,{kid},{did},'Anki合成验收.txt','text/plain','FAILED','Authored fixture: ingestion intentionally not executed',UTC_TIMESTAMP(),UTC_TIMESTAMP());
      INSERT INTO document_chunks(id,document_id,chunk_id,chunk_type,chunk_index,content,content_hash,source_location,created_at)
        VALUES({cid},{did},{encoded(chunk)},'CHILD',0,{encoded(source)},{encoded(digest)},{encoded({'kind':'authored-fixture','lineStart':1,'lineEnd':1})},UTC_TIMESTAMP());
      INSERT INTO learning_sessions(id,user_id,knowledge_base_id,learning_goal,agentscope_session_id,status,created_at,updated_at)
        VALUES({sid},1,{kid},'Anki导出验收：三张合成卡片',{encoded(str(uuid.uuid4()))},'COMPLETED',UTC_TIMESTAMP(),UTC_TIMESTAMP());
      INSERT INTO knowledge_points(id,session_id,user_id,sequence_no,topic,subtopics_json,estimated_minutes,status,created_at,updated_at)
        VALUES({pid},{sid},1,1,'数据结构基础（合成验收）','[]',5,'COMPLETED',UTC_TIMESTAMP(),UTC_TIMESTAMP());
    """
    for card in cards:
        statement += f"INSERT INTO review_cards(id,user_id,knowledge_point_id,knowledge_base_id,front,back,source_chunk_id,created_at) VALUES({card['id']},1,{pid},{kid},{encoded(card['front'])},{encoded(card['back'])},{encoded(chunk)},UTC_TIMESTAMP());\n"
    statement += "COMMIT;"
    (root / "fixture.sql").write_text(statement, encoding="utf-8")
    sql(statement)
    first = "/api/review/cards/" + cards[0]["id"] + "/anki"
    offline = api("POST", first)
    (root / "offline.json").write_text(json.dumps(offline, ensure_ascii=False, indent=2), encoding="utf-8")
    assert offline["body"]["code"] != 0, offline
    offline_status = api("GET", first)
    assert offline_status["body"]["data"]["status"] == "FAILED", offline_status

    events = []
    dropped = threading.Event()

    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            response = requests.post("http://127.0.0.1:8765", json=body, timeout=15)
            result = response.json()
            event = {"action": body["action"], "error": result.get("error")}
            if body["action"] == "addNote":
                event["noteId"] = result.get("result")
                if result.get("error") is None and not dropped.is_set():
                    dropped.set()
                    event["responseDroppedAfterActualCreation"] = True
                    events.append(event)
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
            events.append(event)
            self.send_response(response.status_code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response.content)))
            self.end_headers()
            self.wfile.write(response.content)

    proxy = ThreadingHTTPServer(("127.0.0.1", 8766), Proxy)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    try:
        lost = api("POST", first)
        (root / "lost-response.json").write_text(json.dumps(lost, ensure_ascii=False, indent=2), encoding="utf-8")
        assert dropped.is_set() and lost["body"]["code"] != 0, lost
        retry = api("POST", first)
        assert retry["body"]["code"] == 0, retry
        with ThreadPoolExecutor(max_workers=2) as pool:
            parallel = list(pool.map(lambda _: api("POST", "/api/review/cards/" + cards[1]["id"] + "/anki"), range(2)))
        assert all(r["body"]["code"] == 0 for r in parallel), parallel
        assert len({r["body"]["data"]["noteId"] for r in parallel}) == 1, parallel
        third = api("POST", "/api/review/cards/" + cards[2]["id"] + "/anki")
        assert third["body"]["code"] == 0, third
        repeated = api("POST", first)
        denied = api("POST", first, user=2)
        assert repeated["body"]["data"]["noteId"] == retry["body"]["data"]["noteId"], repeated
        assert denied["body"]["code"] != 0, denied
        notes, rendered = [], []
        for card in cards:
            ids = call("findNotes", {"query": "StudyPilotId:studypilot-u1-c" + card["id"]})
            assert len(ids) == 1, ids
            info = call("notesInfo", {"notes": ids})[0]
            assert info["fields"]["Front"]["value"] == card["front"], info
            assert chunk in info["fields"]["Source"]["value"], info
            assert len(info["cards"]) == 1, info
            notes.append({k: info[k] for k in ["noteId", "modelName", "fields", "cards"]})
            card_info = call("cardsInfo", {"cards": info["cards"]})[0]
            assert card["front"] in card_info["question"] and card["back"] in card_info["answer"], card_info
            rendered.append({k: card_info[k] for k in ["cardId", "deckName", "question", "answer"]})
        assert call("guiDeckReview", {"name": rendered[0]["deckName"]}) is True
        try:
            current = call("guiCurrentCard")
            assert current["cardId"] in {c["cardId"] for c in rendered}, current["cardId"]
            assert call("guiShowAnswer") is True
            gui_review = {"cardId": current["cardId"], "question": current["question"],
                          "answer": current["answer"], "showAnswer": True, "ratingSubmitted": False}
        finally:
            call("guiDeckBrowser")
        after = attempts()
        assert after == before
        database = sql(f"SELECT id,anki_export_status,anki_note_id,anki_export_attempts,IFNULL(anki_export_error,'NULL') FROM review_cards WHERE knowledge_point_id={pid} ORDER BY id;")
        report = {"status": "PASSED", "verifiedAt": datetime.now(timezone.utc).isoformat(), "fixture": fixture,
                  "ankiConnectVersion": 6, "offline": offline, "offlineStatus": offline_status,
                  "lostResponse": lost, "retry": retry, "parallel": parallel, "third": third,
                  "repeated": repeated, "otherUserDenied": denied, "notes": notes, "rendered": rendered,
                  "database": database, "guiReview": gui_review, "modelCallsBefore": before, "modelCallsAfter": after,
                  "limitations": ["Cards and source were authored SQL fixtures; no model/ingestion claim.",
                    "Offline was a closed test endpoint; user Anki stayed running.",
                    "Lost-response proxy forwarded creation to real Anki then closed the connection.",
                    "cardsInfo verifies Anki-rendered question/answer, not a native desktop screenshot."]}
        (root / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": "PASSED", "sessionId": str(sid), "noteIds": [n["noteId"] for n in notes], "modelCallsAdded": after-before}, ensure_ascii=False))
    finally:
        proxy.shutdown(); proxy.server_close()
        (root / "proxy-events.json").write_text(json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
