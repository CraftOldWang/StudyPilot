"""Accounting regressions; authored events, no provider or network requests."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("usage", Path(__file__).with_name("report-learning-usage.py"))
usage = importlib.util.module_from_spec(spec); spec.loader.exec_module(usage)


def start(call, operation): return {"callId": call, "status": "STARTED", "operation": operation}
def end(call, status="SUCCEEDED", known=True):
    return {"callId": call, "status": status, "usageAvailable": known,
            "inputTokens": 100 if known else None, "outputTokens": 20 if known else None,
            "totalTokens": 120 if known else None, "cachedInputTokens": 30 if known else None}


class AccountingTest(unittest.TestCase):
    def test_async_point_summary_belongs_to_session(self):
        events = [start("a", "LEARNING/12/5"), end("a"),
                  start("b", "COMPACTION/12/POINT/20"), end("b"),
                  start("c", "COMPACTION/123/POINT/21"), end("c")]
        result = usage.summarize(events, "12")
        self.assertEqual(result["combined"]["knownTotalTokens"], 240)
        self.assertEqual(result["compaction"]["attempts"], 1)

    def test_retries_summaries_and_isolation(self):
        events = [start("a", "LEARNING/12/5"), end("a", "FAILED", False),
                  start("b", "LEARNING/12/5"), end("b"), start("c", "COMPACTION/5/POINT"), end("c"),
                  start("d", "LEARNING/123/9"), end("d"), start("e", "COMPACTION/9/POINT"), end("e"),
                  start("f", "PLANNING/12"), end("f")]
        result = usage.summarize(events, "12")
        self.assertEqual(result["combined"]["attempts"], 3)
        self.assertEqual(result["combined"]["knownInputTokens"], 200)
        self.assertEqual(result["combined"]["knownCachedInputTokens"], 60)
        self.assertEqual(result["combined"]["knownTotalTokens"], 240)
        self.assertEqual(result["combined"]["attemptsWithoutUsage"], 1)
        self.assertFalse(result["combined"]["usageComplete"])
        self.assertEqual(result["compaction"]["attempts"], 1)

    def test_inflight_and_summary_only_recovery(self):
        result = usage.summarize([start("a", "COMPACTION/7/WHOLE")], "12", ["7"])
        self.assertEqual(result["combined"]["statuses"], {"NO_TERMINAL": 1})
        self.assertFalse(result["combined"]["usageComplete"])

    def test_invalid_ledger_is_not_silently_counted(self):
        for events in [[end("a")], [start("a", "LEARNING/1/2")] * 2,
                       [start("a", "LEARNING/1/2"), end("a"), end("a")]]:
            with self.assertRaises(ValueError): usage.summarize(events, "1")


if __name__ == "__main__": unittest.main()
