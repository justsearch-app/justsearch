---
title: "ADR-0052: Weekly CodeQL on hosted runners"
type: decision
status: accepted
description: "CodeQL code scanning runs weekly on GitHub-hosted runners and stays dispatchable by hand; it is advisory and never gates a merge."
date: 2026-10-19
amends:
  - "0044-public-hosted-ci-fact-lanes.md"
  - "0026-manual-ci-triggering.md"
probes: "none - scripts/ci/check-workflow-triggers.mjs holds the trigger against workflow-signal-policy.v1.json"
last_reviewed: 2026-10-19
---

# ADR-0052: Weekly CodeQL on hosted runners

## Status

Accepted. Amends [ADR-0044](0044-public-hosted-ci-fact-lanes.md), which keeps specialty workflows
manually dispatched unless a later ADR changes their trigger posture, and
[ADR-0026](0026-manual-ci-triggering.md)'s historical manual-only security rule.

## Context

`codeql.yml` was manual-only. Its last run was in July 2026, so the security scan it configures was
not happening. GitHub's managed default setup is not configured for this repository. The
manual-only rule came from [ADR-0026](0026-manual-ci-triggering.md), whose reason was a self-hosted
runner that is not always online; CodeQL runs on `ubuntu-latest`, so that reason does not apply.

## Decision

`codeql.yml` runs every Monday at 04:17 UTC and keeps `workflow_dispatch`. It stays advisory: it
is not a required check, does not run on pull requests, and its findings
appear under the repository's code-scanning alerts. `scripts/ci/workflow-signal-policy.v1.json`
declares both triggers.

## Consequences

- About 30 hosted-runner minutes a week (two jobs of about 14 minutes), free on a public repository.
- New alerts need someone to look at them; if the first runs are mostly noise, delete the workflow
  rather than leave unread alerts.
- Other specialty workflows stay manual under ADR-0044.
