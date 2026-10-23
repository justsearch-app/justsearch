#!/usr/bin/env python3
"""Compare the fixed D10 workload in B A B A B A order, without discarding runs."""
from pathlib import Path
import ast
import json
import re
import statistics
import xml.etree.ElementTree as ET

EVIDENCE = Path(__file__).resolve().parent
result = {
    "method": "B A B A B A; unchanged CPU scorer fixture; each independent JVM has two warm-up suites and five measured suites; compare medians of the three five-suite medians on each side",
    "settings": "CUDA_VISIBLE_DEVICES=-1; identical Gradle test JVM configuration; --max-workers=1; same private JVM temp directory; private per-side ONNX cache; same read-only models",
    "order": [],
    "before": {"runs": []},
    "after": {"runs": []},
}
reference = None
for repeat in range(1, 4):
    for side in ("before", "after"):
        name = f"uiux-numeric-windows-controlled-performance-{side}-{repeat}.xml"
        root = ET.parse(EVIDENCE / name).getroot()
        assert all(int(root.get(k, 0)) == 0 for k in ("failures", "errors", "skipped")), name
        assert root.get("tests") == "1", name
        output = "\n".join(x.text or "" for x in root.iter("system-out"))
        suites = []
        for index, nanos, scored, matched in re.findall(
            r"NUMERIC_RUN (\d+) suiteNanos=(\d+) scored=(\[[^\]]+\]) matched=(\[[^\]]+\])", output
        ):
            suites.append({"run": int(index), "suiteNanos": int(nanos),
                           "scored": ast.literal_eval(scored), "matched": ast.literal_eval(matched)})
        assert len(suites) == 5 and [r["run"] for r in suites] == list(range(5)), name
        assert all(r["scored"] == [1] * 6 for r in suites), name
        median = int(re.search(r"NUMERIC_MEDIAN suiteNanos=(\d+)", output)[1])
        assert median == statistics.median(r["suiteNanos"] for r in suites), name
        models = re.findall(r"NUMERIC_MODEL (\S+) sha256[= ]([a-f0-9]+)", output)
        fixture = re.search(r"NUMERIC_FIXTURES sha256[= ]([a-f0-9]+)", output)[1]
        identity = (models, fixture, [r["matched"] for r in suites])
        assert len(models) == 3, name
        if reference is None:
            reference = identity
        assert identity == reference, f"Model, fixture or verdict drift: {name}"
        row = {"repeat": repeat, "xml": name, "medianNanos": median,
               "suites": suites, "models": models, "fixtures": fixture,
               "resources": re.findall(r"NUMERIC_RESOURCE .*", output)}
        result[side]["runs"].append(row)
        result["order"].append({"side": side, "repeat": repeat, "medianNanos": median})
        print(f"{side} {repeat}: suitesMs={[round(r['suiteNanos'] / 1e6, 3) for r in suites]} medianMs={median / 1e6:.6f}")
for side in ("before", "after"):
    result[side]["medianNanos"] = statistics.median(r["medianNanos"] for r in result[side]["runs"])
result["ratio"] = result["after"]["medianNanos"] / result["before"]["medianNanos"]
result["withinTenPercent"] = result["ratio"] <= 1.10
(EVIDENCE / "uiux-numeric-windows-controlled-performance-comparison.json").write_text(
    json.dumps(result, indent=2) + "\n"
)
print(f"ALTERNATED_MEDIAN beforeMs={result['before']['medianNanos'] / 1e6:.6f} afterMs={result['after']['medianNanos'] / 1e6:.6f} ratio={result['ratio']:.6f}; all six fixtures scored in all 30 suites")
assert result["withinTenPercent"], "Alternated scorer median exceeds the agreed 10% bound"
