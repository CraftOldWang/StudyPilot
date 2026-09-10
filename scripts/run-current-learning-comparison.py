"""Current outline/chat/quiz/card APIs: NONE vs LOCAL on five shared outline leaves."""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import sys
import time
import requests

sys.stdout.reconfigure(encoding="utf-8")
parser = argparse.ArgumentParser()
parser.add_argument("stage", choices=["plan", "learn", "report"])
args = parser.parse_args()
root = Path("output/current-learning-comparison")
root.mkdir(parents=True, exist_ok=True)
state_path = root / "state.json"
state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def api(method, path, **kwargs):
    with requests.Session() as client:
        client.trust_env = False
        response = client.request(method, "http://127.0.0.1:8080" + path,
                                  headers={"X-User-Id": "1"}, timeout=(10, 900), **kwargs)
    body = response.json()
    response.raise_for_status()
    if body["code"] != 0:
        raise RuntimeError(str(body))
    return body["data"]


if args.stage == "plan":
    kb = json.loads(Path("output/os-pilot-10/knowledge-base.json").read_text(encoding="utf-8"))["id"]
    if "runId" not in state:
        documents = api("GET", f"/api/knowledge-bases/{kb}/documents")
        lesson = next(d for d in documents if d["title"].startswith("3.1.OS_ProcessManagement"))
        request = {"knowledgeBaseId": kb, "learningGoal": "根据课件学习进程管理与上下文切换，掌握基本机制，为操作系统期末考试复习。",
                   "lessonDocumentIds": [lesson["id"]], "exerciseDocumentIds": []}
        plan = api("POST", "/api/learning/plans", json=request)
        state.update(runId=plan["id"], planningRequest=request)
        write(state_path, state)
    print("Generating current hierarchical outline", state["runId"], flush=True)
    plan = api("POST", f'/api/learning/plans/{state["runId"]}/execute')
    write(root / "outline.json", plan)
    if plan["status"] != "SUCCEEDED":
        raise RuntimeError(str(plan.get("errorMessage")))
    if "sourceSessionId" not in state:
        original = api("POST", f'/api/learning/plans/{state["runId"]}/session')
        state["sourceSessionId"] = original["id"]
        write(root / "source-session.json", original)
        write(state_path, state)
    for mode in ["NONE", "LOCAL"]:
        if mode not in state:
            replica = api("POST", f'/api/eval/learning/sessions/{state["sourceSessionId"]}/replica')
            state[mode] = {"sessionId": replica["id"]}
            write(root / f"{mode}-initial.json", replica)
            write(state_path, state)
        sid = state[mode]["sessionId"]
        configured = api("POST", f"/api/eval/learning/sessions/{sid}/compression", json={"strategy": mode})
        assert configured["compressionStrategy"] == mode
    original = json.loads((root / "source-session.json").read_text(encoding="utf-8"))
    state["selectedTopics"] = [p["topic"] for p in original["plan"][:5]]
    assert len(state["selectedTopics"]) == 5
    write(state_path, state)
    print(json.dumps({"sessions": {m: state[m] for m in ["NONE", "LOCAL"]}, "topics": state["selectedTopics"]}, ensure_ascii=False), flush=True)


def learn(mode):
    sid = state[mode]["sessionId"]
    prefix = f"/api/learning/sessions/{sid}"
    folder = root / mode
    folder.mkdir(exist_ok=True)
    initial = json.loads((root / f"{mode}-initial.json").read_text(encoding="utf-8"))

    def step(key, method, path, payload=None):
        output = folder / f"{key}.json"
        if output.exists():
            saved = json.loads(output.read_text(encoding="utf-8"))
            if saved.get("turn", {}).get("status") == "FAILED":
                raise RuntimeError(f"{mode} {key}: preserved failed turn requires inspection")
            return saved
        print(mode, key, "start", flush=True)
        started = time.time()
        result = api(method, path, **({"json": payload} if payload is not None else {}))
        write(output, result)
        if result.get("turn", {}).get("status") == "FAILED":
            raise RuntimeError(f"{mode} {key}: {result['turn'].get('errorMessage')}")
        print(mode, key, "done", round(time.time()-started, 1), flush=True)
        return result

    def message(key, text):
        return step(key, "POST", prefix + "/messages", {"requestId": f"current-compaction-{mode}-{key}", "message": text})

    for i, point in enumerate(initial["plan"][:5], 1):
        topic = point["topic"]
        message(f"{i}-explain", f"按照大纲继续学习当前知识点：{topic}。请先检索课件并讲解，概念、机制和一个例子即可，约400字；这一步先不要出题。")
        followup = "我容易把并发理解为必须有多核、多个任务真的同时执行。请结合课件纠正我的这个误区；后面复习时请记得这是我容易混淆的地方。" if i == 1 else "请结合刚才的知识点，解释一个容易混淆的地方，并给一个具体的小例子，约200字。先不出题。"
        message(f"{i}-question", followup)
        quiz = message(f"{i}-quiz", "我明白了，继续进入练习阶段。请调用工具生成2道单选题，每题4个选项。")
        view = quiz["session"]
        assert view["activeKnowledgePoint"]["status"] == "QUIZZING", f"{mode} {i}: quiz transition missing"
        count = len(view["currentQuiz"]["questions"])
        assert count == 2, f"{mode} {i}: expected 2 questions, got {count}"
        # Same answer positions in both groups; no answer-key injection into the conversation.
        step(f"{i}-submit", "POST", prefix + "/quiz/submit", {"answers": ["A", "B"]})
        feedback = "请解释我本次做错的题；如果全对，就指出一个解题时容易忽略的条件。约200字，先不要写卡片。"
        if i == 5:
            feedback += "另外请回顾：我在第一个知识点学习时明确说过自己有什么误区，你当时如何纠正？如果记不清请直接说明。"
        message(f"{i}-feedback", feedback)
        cards = message(f"{i}-cards", "现在没有疑问了，继续进入复习卡阶段。请调用工具写2张简洁的复习卡，生成后等我确认。")
        assert len(cards["session"]["cards"]) == 2, f"{mode} {i}: expected 2 cards"
        if i == 2:
            message(f"{i}-rewrite", "请重写第二张卡片，让问题更具体、答案更简短，第一张保持原样，仍保留两张卡。")
        confirmed = step(f"{i}-confirm", "POST", prefix + f'/points/{point["id"]}/cards/confirm')
        assert next(p for p in confirmed["plan"] if p["id"] == point["id"])["status"] == "COMPLETED"
    write(folder / "final-session.json", api("GET", prefix))
    write(folder / "history.json", api("GET", prefix + "/messages"))
    print(mode, "FIVE POINTS COMPLETED", flush=True)


if args.stage == "learn":
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(learn, mode) for mode in ["NONE", "LOCAL"]]
        for future in futures:
            future.result()

if args.stage == "report":
    spec = importlib.util.spec_from_file_location("usage", Path("scripts/report-learning-usage.py"))
    usage = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(usage)
    events = [json.loads(line) for line in Path(".eval/model-calls.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    result = {mode: usage.summarize(events, state[mode]["sessionId"]) for mode in ["NONE", "LOCAL"]}
    for mode in result:
        result[mode]["completedPoints"] = sum(p["status"] == "COMPLETED" for p in api("GET", f'/api/learning/sessions/{state[mode]["sessionId"]}')["plan"])
    if all(result[m]["combined"]["usageComplete"] and result[m]["completedPoints"] == 5 for m in result):
        result["reductionPercent"] = {field: (1-result["LOCAL"]["combined"][field]/result["NONE"]["combined"][field])*100
                                      for field in ["knownInputTokens", "knownTotalTokens"]}
    write(root / "usage.json", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
