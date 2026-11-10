"""Run selected RAG cases against the retrieval API and append JSONL results."""

import argparse
import json
import re
import time
import unicodedata
from pathlib import Path

import requests


def normalize(text):
    return " ".join(unicodedata.normalize("NFC", text).split())


def literal_fragments(quote):
    marked = re.findall(r'“([^”]+)”|"([^"]+)"|「([^」]+)」', quote)
    return [normalize(next(part for part in match if part)) for match in marked] or [normalize(quote)]


def source_pages(provenance):
    location = provenance.get("sourceLocation")
    if isinstance(location, str):
        try:
            location = json.loads(location)
        except json.JSONDecodeError:
            return set()
    if not isinstance(location, dict):
        return set()
    pages = set()
    for item in [location, *(location.get("blocks") or [])]:
        # ParsedDocument.Block currently serializes its zero-based page as pageIndex.
        index = item.get("pageIndex0", item.get("pageIndex"))
        if type(index) is int and index >= 0:
            pages.add(index + 1)
    return pages


def match_evidence(case, hits, sources):
    contexts = [(hit["chunkId"], str(hit["provenance"]["documentId"]),
                 source_pages(hit["provenance"]), normalize(hit["content"])) for hit in hits]
    evidence = []
    for entry in case["evidence"]:
        source = entry["sourceId"]
        expected = set(entry["samplePages"])
        scoped = [(chunk, pages, text) for chunk, document, pages, text in contexts
                  if document == str(sources[source]["documentId"])]
        found = set().union(*(pages for _, pages, _ in scoped))
        literals = [
            {"quote": quote, "chunkIds": [chunk for chunk, _, text in scoped if quote in text]}
            for quote in literal_fragments(entry["quote"])
        ]
        evidence.append({"sourceId": source, "samplePages": sorted(expected),
                         "observedPages": sorted(found), "matchedPages": sorted(expected & found),
                         "pageMetadataAvailable": bool(found), "literals": literals})
    return evidence


def score(case, data, sources):
    if not case["answerable"]:
        return {"manualReview": True}
    evidence = match_evidence(case, data["hits"], sources)
    literals = [literal for entry in evidence for literal in entry["literals"]]
    matched = sum(bool(literal["chunkIds"]) for literal in literals)
    ranked = data["rankedChildren"][:5]
    ranked_evidence = match_evidence(case, ranked, sources)
    matching_children = {chunk for entry in ranked_evidence for literal in entry["literals"]
                         for chunk in literal["chunkIds"]}
    reciprocal_rank = next((1 / rank for rank, hit in enumerate(ranked, start=1)
                            if hit["chunkId"] in matching_children), 0.0)
    return {
        "pageHit": any(entry["matchedPages"] for entry in evidence),
        "literalEvidenceHit": matched > 0,
        "literalEvidenceMatched": matched,
        "literalEvidenceTotal": len(literals),
        "literalEvidenceCoverage": matched / len(literals),
        "pageHitAt5": any(entry["matchedPages"] for entry in ranked_evidence),
        "literalEvidenceHitAt5": bool(matching_children),
        "literalEvidenceMrrAt5": reciprocal_rank,
        "evidence": evidence,
    }


def request_for(case, args, sources):
    if case["answerable"] and not case["evidence"]:
        raise ValueError(f"{case['id']} is answerable but has no labeled evidence")
    source_ids = {entry["sourceId"] for entry in [*case["evidence"], *case.get("checkedScope", [])]}
    if case.get("sourceId"):
        source_ids.add(case["sourceId"])
    kb_ids = set()
    for source in source_ids:
        mapped = sources[source]
        kb, document = str(mapped["knowledgeBaseId"]), str(mapped["documentId"])
        if not kb.isdigit() or not document.isdigit():
            raise ValueError(f"{source} needs a knowledge base ID and a physical document ID")
        kb_ids.add(kb)
    if len(kb_ids) != 1:
        raise ValueError(f"{case['id']} requires all labeled sources in one knowledge base")
    kb = kb_ids.pop()
    for entry in case["evidence"]:
        if not entry["quote"].strip() or not entry["samplePages"] or min(entry["samplePages"]) < 1:
            raise ValueError(f"{case['id']} needs a literal quote and one-based sample pages")
    payload = {
        "query": case["question"], "mode": args.mode, "topK": args.top_k,
        "rerank": args.rerank, "contextMode": args.context_mode,
    }
    if args.rerank_candidates is not None:
        payload["rerankCandidates"] = args.rerank_candidates
    return {
        "url": f"{args.base_url.rstrip('/')}/api/knowledge-bases/{kb}/search",
        "userId": args.user_id,
        "body": payload,
        "documentIdsBySource": {source: str(mapped["documentId"]) for source, mapped in sources.items()
                                if str(mapped["knowledgeBaseId"]) == kb},
        "configuration": args.configuration,
    }


def request_key(case_id, request):
    return case_id, json.dumps(request, sort_keys=True)


def previous_successes(path):
    if not path.exists():
        return {}
    rows = (json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    return {request_key(row["caseId"], row["request"]): row for row in rows if row["success"]}


def run_case(http, case, request, timeout):
    row = {"caseId": case["id"], "split": case["split"], "request": request, "success": False}
    start = time.perf_counter()
    try:
        response = http.post(request["url"], json=request["body"],
                             headers={"X-User-Id": request["userId"]}, timeout=timeout)
        row.update(httpStatus=response.status_code, traceId=response.headers.get("X-Trace-Id"))
        try:
            envelope = response.json()
        except ValueError:
            row.update(response=response.text, error="Expected a JSON API response")
            return row
        row["response"] = envelope
        if not response.ok or not isinstance(envelope, dict) or envelope.get("code") != 0:
            row["error"] = f"Search failed (HTTP {response.status_code})"
        elif not isinstance(envelope.get("data"), dict) or not all(
                isinstance(envelope["data"].get(field), list) for field in ("hits", "rankedChildren")):
            row["error"] = "Search response must contain hits and rankedChildren lists"
        else:
            row["success"] = True
    except requests.RequestException as ex:
        row["error"] = str(ex)
    finally:
        row["elapsedMs"] = round((time.perf_counter() - start) * 1000)
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=Path("eval/rag/improvement-v2-cases.json"))
    parser.add_argument("--source-map", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="Append results to this JSONL file")
    parser.add_argument("--split", choices=["dev", "test"], required=True)
    parser.add_argument("--case", action="append", help="Restrict to this case ID, repeat as needed")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", default="1")
    parser.add_argument("--mode", choices=["BM25", "VECTOR", "RRF"], default="BM25")
    parser.add_argument("--top-k", type=int, choices=range(1, 21), default=5)
    parser.add_argument("--context-mode", choices=["CHILD", "PARENT", "NEIGHBOR"], default="CHILD")
    parser.add_argument("--rerank", action="store_true")
    parser.add_argument("--rerank-candidates", type=int)
    parser.add_argument("--configuration", help="Optional label for the indexed chunker version and settings")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if args.rerank_candidates is not None and (
            not args.rerank or not args.top_k <= args.rerank_candidates <= 50):
        parser.error("--rerank-candidates requires --rerank and a value between top K and 50")
    try:
        corpus = json.loads(args.cases.read_text(encoding="utf-8-sig"))
        sources = json.loads(args.source_map.read_text(encoding="utf-8-sig"))
        if corpus["numberingBase"] != 1:
            raise ValueError("Expected one-based samplePages")
        cases = [case for case in corpus["questions"] if case["split"] == args.split
                 and (not args.case or case["id"] in args.case)]
        if not cases or (args.case and set(args.case) != {case["id"] for case in cases}):
            raise ValueError("No matching case(s) for the requested split")
        jobs = [(case, request_for(case, args, sources)) for case in cases]
        previous = previous_successes(args.out) if args.resume else {}
    except (KeyError, ValueError, OSError) as ex:
        parser.error(f"Cannot prepare evaluation: {ex}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    successes, failures = [], []
    with requests.Session() as http, args.out.open("a", encoding="utf-8") as log:
        for case, request in jobs:
            cached = previous.get(request_key(case["id"], request))
            row = cached or run_case(http, case, request, args.timeout)
            if row["success"]:
                try:
                    row["metrics"] = score(case, row["response"]["data"], sources)
                except (KeyError, TypeError, ValueError) as ex:
                    row.update(success=False, error=f"Cannot score response: {ex}")
            if row["success"]:
                successes.append(row["metrics"])
            else:
                failures.append(case["id"])
            if cached is None or not row["success"]:
                log.write(json.dumps(row, ensure_ascii=False) + "\n")
                log.flush()
            label = ("cached" if cached else "ok") if row["success"] else row["error"]
            print(f"{case['id']} {label}", flush=True)
    measured = [entry for entry in successes if not entry.get("manualReview")]
    literal_count = sum(entry["literalEvidenceTotal"] for entry in measured)
    print(json.dumps({
        "successful": len(successes), "failed": failures, "scored": len(measured),
        "manualReview": len(successes) - len(measured),
        "pageHits": sum(entry["pageHit"] for entry in measured),
        "literalEvidenceHits": sum(entry["literalEvidenceHit"] for entry in measured),
        "pageHitAt5": sum(entry["pageHitAt5"] for entry in measured) / len(measured) if measured else None,
        "literalEvidenceHitAt5": (
            sum(entry["literalEvidenceHitAt5"] for entry in measured) / len(measured) if measured else None
        ),
        "literalEvidenceMrrAt5": (
            sum(entry["literalEvidenceMrrAt5"] for entry in measured) / len(measured) if measured else None
        ),
        "literalEvidenceCoverage": (
            sum(entry["literalEvidenceMatched"] for entry in measured) / literal_count if literal_count else None
        ),
    }))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
