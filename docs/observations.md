---
title: Observations
type: observations
status: retired
---

# Observations

**Retired — tempdoc 872, 2026-08-26.**

This file was the agent-written "conditions store": an inbox of out-of-scope findings that a
periodic triage pass was meant to route onward (tempdoc 680). That pass never ran. At retirement the
store held 565 conditions — 517 with their kind never confirmed, one with a probe, 73 % seen once —
and grew ~14 a day. Its top "recurrence" (`seen: 25`) was 23 distinct findings sharing a file
anchor. Tempdoc 680 pre-registered exactly this failure condition and its fallback; 872 executes it
and goes one step further: no pile at all.

**Where findings go now** — `docs/reference/contributing/agent-workflow.md` `rule:log-pre-existing-issues`
("Route findings at discovery"): a wrong doc/comment with a one-line fix is fixed in place; a red or flaky command
on `main` is fixed, or the flaky test is quarantined in its own runner with a tracked fix (tempdoc
930 retired the expected-state pin mechanism, which had made red-on-main cheaper to live with than
to fix); a platform lesson becomes a hook or an `agent-lessons.md` line; a product defect goes
to the owning tempdoc's open-items section or its domain register.

**The retired content** is in git history — the last full store is at commit `7b85a5a6`
(`git show 7b85a5a6:docs/observations.md`). Nothing in it was being read; nothing in it is lost.
`docs/observations.d/` shards written by sessions that predate 872 are likewise history only.
