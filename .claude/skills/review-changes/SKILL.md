---
description: "Critically review implemented changes against the tempdoc/spec, report verification claims with evidence pointers, and get an independent refute-first subagent check before fixing issues."
---

Review all your implemented changes, since the last critical analysis/review, critically against the relevant spec, tempdoc, and intended design. Identify only substantive issues: missing requirements, logic errors, broken behavior, failed validation, important edge cases, integration problems, or meaningful design mismatches. Ignore minor style issues, harmless refactors, and speculative improvements unless they materially affect correctness or the tempdoc's goals. For each real issue, explain why it matters and what the correct fix should be. If you find anything security- or privacy-sensitive (an exploitable issue, a credential/secret, a privacy leak), stop and flag it to me directly instead of writing the details into a pushed branch or PR — this repo is public and PRs are visible immediately.

If no major issue is found, say so. Regardless of findings, include at least one check that is anchored outside the tempdoc itself — a live behavior, a real measurement, or a user-visible outcome — so the review doesn't only validate the implementation against the plan this same session wrote. Report your verification as a list of concrete claims, each with its evidence pointer — the exact test name and result, the command and its output, a screenshot path, or an evidence bundle run-id (capture one with `node modules/ui-web/scripts/capture-evidence-bundle.mjs` if you verified against the live stack); a verification claim without a pointer counts as unverified. Before planning fixes, spawn one independent subagent with a refute-first brief over that claims list — its default stance should be that each claim is wrong until the evidence holds — and carry its surviving objections into the fix plan. Then enter plan mode to investigate and plan the implementation of the needed fixes. Afterwards proceed with the plan's implementation.

Reconcile every acceptance item with the actual result, tested revision, required
environment, and accessible evidence. Challenge a completion claim with a missing
hosted/platform proof: CI wiring, skipped tests, and advisory jobs do not prove
required checks passed. Consolidate findings into coherent rounds; after two
substantive correction rounds the parent reassesses scope/design/ownership,
without waiving defects. See `docs/reference/contributing/agent-workflow.md`.
