import json
import unittest
from collections import Counter
from datetime import date, time
from pathlib import Path
from tempfile import TemporaryDirectory

from replay_short_term import RANKINGS, scans


class CaptureContinuationTest(unittest.TestCase):
    def test_replays_a_scan_spanning_numbered_parts_once(self):
        at = "2026-10-14T00:20:00Z"

        def event(kind, data):
            return json.dumps({"kind": kind, "mode": "BREAKOUT", "code": "pool", "observedAt": at, "data": data}) + "\n"

        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "2026-10-14-breakout.jsonl"
            second = root / "2026-10-14-breakout-part-000002.jsonl"
            third = root / "2026-10-14-breakout-part-000003.jsonl"
            base.write_text(event("source", {ranking: [] for ranking in RANKINGS}), encoding="utf-8")
            second.write_text(event("candidate_selection", {"algorithm": "CONFIRMED_BREAKOUT_V1", "selected": []}),
                              encoding="utf-8")
            third.write_text(event("realtime_snapshot", {"sourceStatus": "REALTIME_EMPTY", "signals": [],
                                                        "scanInputAt": at, "sourcesComplete": True,
                                                        "selectionComplete": True}), encoding="utf-8")

            for paths in ([base], [third, base, second]):
                with self.subTest(paths=paths):
                    counts = Counter()
                    result, = scans(paths, date(2026, 10, 14), time(9), time(15, 20), counts)
                    self.assertEqual(result["status"], "COMPARED_MEASURED_CANDIDATES")
                    self.assertEqual(result["current"]["published"], [])
                    self.assertEqual(counts, {"source": 1, "candidate_selection": 1, "realtime_snapshot": 1})


if __name__ == "__main__":
    unittest.main()
