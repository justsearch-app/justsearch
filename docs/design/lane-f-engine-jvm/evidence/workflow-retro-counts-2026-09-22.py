"""Reproduce descriptive root-transcript counts; never emit transcript content.

Usage: python -X utf8 workflow-retro-counts-2026-09-22.py PATH_TO_ROOT_JSONL
The fixed user-message boundary excludes the later handoff and retrospective.
Counts describe recorded calls/results, not wasted tokens, cost, or child work.
"""

import collections
import hashlib
import json
import sys


STOP = "your work was interrupted due to usage limits being hit."
SPLIT = "resume work and consider adding agent system changes as well"
calls = collections.Counter()
targets = collections.Counter()
phases = [collections.Counter(), collections.Counter()]
prefix = hashlib.sha256()
compactions = 0
phase = 0
boundary_found = False
split_found = False
last_line = 0

with open(sys.argv[1], "rb") as source:
    for line_number, raw in enumerate(source, 1):
        event = json.loads(raw)
        payload = event.get("payload", {})
        kind = payload.get("type")
        is_response = event.get("type") == "response_item"
        if is_response and kind == "message" and payload.get("role") == "user":
            message = " ".join(item.get("text", "") for item in payload.get("content", []))
            if message.startswith(STOP):
                boundary_found = True
                break
            if message.startswith(SPLIT):
                phase = 1
                split_found = True
        prefix.update(raw)
        last_line = line_number
        if event.get("type") == "compacted":
            compactions += 1
        if not is_response:
            continue
        if kind in ("function_call", "custom_tool_call"):
            name = payload.get("name", "unknown")
            calls[name] += 1
            if name in ("send_message", "followup_task"):
                arguments = json.loads(payload["arguments"])
                targets[arguments.get("target", "unknown").split("/")[-1]] += 1
        if kind in ("function_call_output", "custom_tool_call_output"):
            phases[phase]["tool_results"] += 1
            output = payload.get("output", "")
            if not isinstance(output, str):
                output = "\n".join(item.get("text", "") for item in output)
            if "Warning: truncated output" in output:
                phases[phase]["results_containing_truncation_warning"] += 1

if not boundary_found or not split_found:
    raise SystemExit("Expected handoff and self-audit boundaries were not both found; no comparable result.")

print(json.dumps({
    "schema": 1,
    "scope": "root response_item records before usage-limit handoff request",
    "last_included_line": last_line,
    "included_prefix_sha256": prefix.hexdigest(),
    "compaction_records": compactions,
    "direct_tool_calls": dict(calls),
    "message_targets_normalized": dict(targets.most_common()),
    "before_self_audit_followup": dict(phases[0]),
    "after_self_audit_followup": dict(phases[1]),
    "limits": [
        "No child transcripts or nested exec calls counted.",
        "Event mirrors excluded; direct call records are counted, not model responses.",
        "Truncation markers may be embedded historical output; no claim every result lost needed evidence.",
        "Message counts do not identify substantive correction rounds or wasted work.",
        "No token, cost, critical-path, or causal before/after improvement estimate.",
    ],
}, indent=2))
