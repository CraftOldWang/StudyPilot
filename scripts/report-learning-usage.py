"""Summarize actual learning and compaction attempts from the production ledger.

Includes failed/retried attempts, excludes planning and other sessions. Missing
usage remains unknown; this file alone does not prove quality or token savings.
"""
import argparse
from collections import Counter
import json
from pathlib import Path


def summarize(events, session_id, turn_ids=()):
    calls = {}
    for event in events:
        entry = calls.setdefault(event["callId"], {})
        key = "start" if event["status"] == "STARTED" else "end"
        if key in entry: raise ValueError("Duplicate ledger event for one attempt")
        entry[key] = event
    if any("start" not in c for c in calls.values()): raise ValueError("Terminal event without a start")
    prefix = f"LEARNING/{session_id}/"
    ids = {str(t) for t in turn_ids}
    for call in calls.values():
        operation = call["start"].get("operation", "")
        if operation.startswith(prefix): ids.add(operation[len(prefix):].split("/")[0])
    selected = []
    for call in calls.values():
        operation = call["start"].get("operation", "")
        parts = operation.split("/")
        if (operation.startswith(prefix) or operation.startswith(f"COMPACTION/{session_id}/POINT/")
                or (len(parts) >= 3 and parts[0] == "COMPACTION" and parts[1] in ids)):
            selected.append(call)

    def totals(group):
        known = [c["end"] for c in group if c.get("end", {}).get("usageAvailable")]
        return {"attempts": len(group), "statuses": dict(Counter(c.get("end", {}).get("status", "NO_TERMINAL") for c in group)),
                "attemptsWithoutUsage": len(group) - len(known), "usageComplete": len(group) == len(known),
                **{"known" + field[0].upper() + field[1:]: sum(int(e[field]) for e in known)
                   for field in ["inputTokens", "outputTokens", "totalTokens", "cachedInputTokens"]}}

    return {"sessionId": str(session_id), "turnIds": sorted(ids), "combined": totals(selected),
            "learning": totals([c for c in selected if c["start"]["operation"].startswith(prefix)]),
            "compaction": totals([c for c in selected if c["start"]["operation"].startswith("COMPACTION/")]),
            "callIds": [c["start"]["callId"] for c in selected],
            "limits": ["Known sums are lower bounds if any usage is missing.",
                       "Cached input is already part of input, not an additional token charge.",
                       "Zero selected attempts do not establish a completed experiment.",
                       "No quality or compression-reduction claim is inferred from usage alone."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ledger", type=Path, default=Path(".eval/model-calls.jsonl"))
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--turn-id", action="append", default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    events = [json.loads(line) for line in args.ledger.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    result = summarize(events, args.session_id, args.turn_id)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["combined"]))
