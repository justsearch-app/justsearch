---
title: "949 — Follow-ups from the Claude Code interview analysis: fail-closed MCP validation, honest privacy and publication claims"
type: tempdocs
status: "PR 1 implemented 2026-09-07 (fail-closed validator, README/threat-model/MCP-doc wording); §4 open items routed"
created: 2026-09-07
updated: 2026-09-07
lane: MCP boundary / public claims (outside lane F's owned files)
related:
  - 948-parallel-audits-during-lane-f          # marked "README privacy scoping" done; the two lines were still there
  - 941-0.3.0-sandbox-convergence              # R19-F1 follow-tail: the bug class a still cannot catch
  - 938-release-consolidation-before-architecture-change   # §A: ~half of PRs since v0.2.0 are process-only
  - 935-agent-throughput-week-theorization     # §1: incidents happen where hooks do not fire
  - 930-replace-bounded-areas-with-maintained-oss   # row 4: hooks measured, 0 true positives, retired
  - 655-mcp-conformance-and-capability-policy   # the validation this tempdoc makes fail-closed
---

# 949 — Interview follow-ups

## 0. Source

An external analysis compared a Claude Code team interview (YouTube `2Kch3tWMnw8`) against this
repository at `11f855a83`. A second pass verified its load-bearing claims against the same commit
and added findings of its own. This tempdoc records what survived verification and what it changed.
The transcript and the first analysis live outside the repository.

## 1. Verified claims from the first analysis

| Claim | Verified at | Status |
|---|---|---|
| `validateArgsOrNull` returns `null` (= validated OK) when the validator itself throws | `McpToolSurface.java` catch block | **Fixed in this PR** (§2) |
| README says only the model's answer leaves the machine; `justsearch_answer` returns passages | `README.md:26`, `:93`; `McpEvidenceProjection.answerEvidence` | **Fixed in this PR** (§3) |
| README "no result meets the bar" sits next to a generated block naming an accepted publication | `README.md:97` vs `:167` | **Fixed in this PR** (§3) |
| Agent-utility: all three strata negative, Enron-1k CI excludes zero, adoption 100% | `docs/reference/benchmarks/agent-utility.md:59-113` | true; unchanged |
| `concise` leaves `structuredContent` unchanged (zero reduction over 336 opt-ins) | `mcp-production-server.md:236-240` | true; unchanged |
| Full-document fallback has no passage carrier in `structuredContent` | `McpToolSurface.buildAnswerContent` 650-675 | true; not in this PR |
| PR #709 (delegation verdict) pending | — | **stale**: merged 2026-09-07 16:38Z; `CLAUDE.md` "default is delegate" removed |
| Round 20 skipped for 0.3.0 | `941:299` | true |

Tempdoc 948 listed "README privacy scoping" as already done at the same commit. The two README
lines were still present; either a different item was meant or the sweep missed them.

## 2. Fail-closed boundary validation

**Defect.** `McpToolSurface.validateArgsOrNull` caught any exception from schema/args
serialization or from the validator, logged a warning, and returned `null` — the value the caller
reads as "validated". The unvalidated argument map then reached dispatch and the unchecked casts
the validation exists to guard. The `callOperation` path never had this problem: a throw there
escapes before dispatch.

**Fix.** The catch now returns a typed `INTERNAL_ERROR` tool error ("Argument validation could not
run … the call was not dispatched").

**Test.** `McpProtocolHandlerTest.toolsCall_validatorCannotRun_failsClosedWithoutDispatch`: a
self-referencing argument map makes `MAPPER.writeValueAsString` throw inside the validator's try
from a real `callTool` entry. Asserts `isError`, the validator-unavailable text, the error code, and
`verify(adapter, never()).search(any())` + `verifyNoInteractions(dispatcher)`.

**Fail-before evidence** (fix reverted, `cleanTest --no-build-cache`):
`must be the validator-unavailable error, not a downstream failure: Search failed:
NullPointerException: … "resp" is null` — the call went through to the (mock) adapter. 28 tests,
1 failed. With the fix: 28/28 green, MCP package green.

## 3. Claims corrected

- `README.md` MCP paragraph, tool list, and `## Privacy`: JustSearch itself sends nothing off the
  machine; an external MCP client receives passages and paths as tool results, and a cloud-hosted
  client forwards them to its provider. The built-in assistant keeps the loop on-device.
- `README.md` agent-utility sentence: the accepted publication is adoption-only; no stratum showed
  an accuracy or efficiency improvement.
- `docs/reference/security/threat-model.md`: asset 1 qualified; new "Not" bullet for connected
  MCP clients.
- `docs/reference/mcp-production-server.md` § Data exposure: "nothing leaves the machine" scoped
  to the server, not the client.
- `governance/sandbox-coverage.v1.json` claim row 61 reworded to match.

Left as-is: the in-app first-run line ("your files never leave this device",
`search-ui-behavior.md:375`) — it describes the built-in assistant path, which is true.

Checks run: `check-privacy-claims`, `check-root-readme`, `check-readme-benchmark-numbers`,
`docs-validate`, `llmstxt-generate` + `skills-sync` (no output drift), `build -x test`.

## 4. Findings the first analysis did not draw — routed, not implemented

1. **Hooks were measured and cut (930 row 4: 0 true / 11 false positives, 22 retired); prose rules
   never were.** The always-loaded set is 62.5 KB (~15.6k tokens) per session. The ratchet holds
   each file at its current size; it never forces reduction. Owner decision pending: one bounded
   session applying 930's method to every must/never line in `CLAUDE.md` + `.claude/rules/`
   (30-day transcript incident count per rule; zero → move to `agent-postmortems.md`). Deliverable
   is the deletion list only. Caveat from 935 §1: this would be the sixth agent-waste investigation
   in two weeks.
2. **Guardrails fire where incidents do not.** 935 §1: four of five Claude incidents that week were
   inside subagents, where parent hooks do not fire; 886: 88% of spend is subagent calls. The
   layer that sees every action is native `permissions.deny` / OTel, not per-session hooks. 930
   moved one rule there; the remaining blocking guards are still hook-shaped.
3. **Process-to-product ratio.** 938 §A: of 266 PRs since v0.2.0, 69 CI/governance/hooks + 40
   docs-only + 22 dev-tooling — "about half … agent-process housekeeping with no product effect".
   This is the number to watch when the interview's "build the system that builds the system" is
   proposed again.
4. **Strong-model-plans / cheap-model-executes was tested here and came out flat** (948 §1:
   orchestrator/worker split 84/16 → 22/78, cost per merge +3.5%, within restatement noise; before
   window has zero surviving sessions). Do not re-adopt without a new falsifier.
5. **Interaction recording is the missing UI tier.** 941 R19-F1 was a scroll-position bug; stills at
   fixed steps cannot see it. The interview's "recording of the model using the feature" is the
   cheapest tier that would have caught it. Candidate: a GIF/video capture mode for `ui-shot` on
   presentation-authority PRs.
6. **No loops exist** (zero `schedule:` triggers). Before adding one, note 872: 565 observation
   notes written, none read. A loop needs a named reader first.
7. **"Examples in tool descriptions are mostly negative"** — already satisfied: the MCP tool
   descriptions carry none; the product agent's prompt composer (149 lines) has one incidental hit.
8. **Full-document fallback passage carrier** (row 6 of §1) — still open; owner: the MCP
   delivery lane (tempdoc 725 / 735 W6 successors).
