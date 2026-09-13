---
title: Prevent public credential and email exposure
type: how-to
status: stable
description: Signing diagnostics, secret-scan coverage, commit identity checks, and historical cleanup limits.
---

# Prevent public credential and email exposure

## Signing diagnostics

`scripts/ci/sign-windows.ps1` keeps operation context, target paths, native exit
codes, and exception type/line diagnostics. It discards native stdout and stderr
before logging or returning a result. Command templates, native output, exception
messages, and source excerpts can contain credentials and are not diagnostic
data. Do not restore their logging to diagnose a provider failure.

Use the provider's authenticated audit interface for detailed signing failures.
Reproduce integration problems with synthetic credentials in an isolated local
environment. The certificate-free regression is:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/ci/test-sign-windows-diagnostics.ps1
```

The regression covers command-mode stdout/stderr, the workflow's failure-log
surface, launch failures, and terminating errors. It also checks that exit codes
and failed-attempt budget records survive. The existing `test-sign-windows.ps1`
rehearsal covers actual temporary-certificate signing and verification. The
Windows-native CI job runs both.

GitHub masking is an additional layer, not the signing script's protection.
When configuring credentials, store sensitive components individually where the
provider integration permits it, and register generated sensitive values for
masking. Whole-command secrets can stop matching after template substitution.
See [GitHub's secure-use guidance](https://docs.github.com/en/actions/reference/security/secure-use).

## Local and published commit identity

Use the no-reply address shown in your GitHub email settings for local commits:

```powershell
git config --local user.email "YOUR_GITHUB_NOREPLY_ADDRESS"
```

Repository-local configuration applies to linked worktrees unless worktree
configuration or environment variables override it. Check both effective author
and committer, including environment overrides, before committing. Do not paste
their raw values into public diagnostics. GitHub web commits have separate
account settings: enable email privacy and blocking of pushes that expose your
private email at [GitHub email settings](https://github.com/settings/emails).

The publication identity policy in `scripts/ci/repo-history-policy.v1.json`
records the maintainer's opted-in no-reply identity. The local commit/push hooks
check it without applying that personal policy to unrelated contributors.
Activate the checked-out hooks with `git config core.hooksPath .githooks` after
cloning. For linked worktrees with worktree configuration enabled, use
`git config --worktree core.hooksPath .githooks` to select that worktree's hooks.
The commit hook checks effective author and committer; the push hook also checks
co-author trailers in outgoing commits. It excludes history already advertised
by the destination remote and preserves the Git LFS ref-update input.
CI checks the introduced commit range; local preflight checks `origin/main..HEAD`.
Keep the policy's names and approved no-reply identity current when the
maintainer identity changes. A name-based policy is accidental-disclosure
prevention, not authentication or protection against deliberate identity spoofing.

Changing settings does not change old commit objects, signed payloads, or
co-author trailers. Coordinate any historical rewrite separately: existing
clones, forks, caches, and signatures make universal erasure impossible to prove.
Use an existing, monitored project contact alias for public contact information;
never substitute an unprovisioned address or a GitHub no-reply address for support.

## Secret-scan scope

The CI tree scan checks current files. The staged local hook checks the staged
change. Neither establishes that old history or commit identity metadata is
private. Keep Gitleaks exceptions tied to a reviewed rule, path, and specific
non-secret content, rather than excluding first-party directories or test files.
Use `--redact` and retain reports outside the public repository.

For a separate reachable-history investigation, select the intended public ref
explicitly rather than scanning unrelated private worktree branches:

```powershell
gitleaks git . --config .gitleaks.toml --redact --log-opts=origin/main --report-format json --report-path "PRIVATE_REPORT_PATH"
```

An exclusion-free audit needs an external config containing `[extend]` and
`useDefault = true`. Classify its findings before adding exceptions. A detector
match is not proof of a live credential; a clean scan is not proof of absence.
Scanner reports can still contain personal commit metadata despite secret
redaction, so do not publish raw reports.

The local publication preflight retains a reachable-`HEAD` history scan with
`--gitleaks-ignore-path scripts/ci/gitleaks-history.ignore`. That file records
reviewed false positives as exact immutable commit/path/rule/line fingerprints:
generated reproducibility payloads, paging cursors, and a fixed cohort digest.
It does not suppress matches in new commits, even at the same path and line.
The CI tree scan and staged hook do not use this historical exception file.
Omit the ignore option for an independent re-audit. Do not add a historical
entry without reviewing every match at that location and recording its origin.

Run `node scripts/ci/test-gitleaks-config.mjs` with the CI-pinned Gitleaks binary
on PATH (or set `GITLEAKS_PATH`). It tests formerly excluded paths, same-rule
matches beside exempted content, and the historical fingerprint boundary.

## Historical incident closure

Record exposed credential rotation or revocation separately from source fixes.
Provider-side status or explicit owner confirmation is evidence; a repository
secret update timestamp alone is not. Review provider audit records for misuse
without trying a leaked credential against a live service.

Inventory the affected run's logs and artifacts and remove confirmed exposed
copies through authorized account access. A missing log endpoint does not prove
that every copy was deleted. Disabling new build-scan publication also does not
remove historical scans: retain discovered URLs privately and use the provider's
account controls or support process for cleanup. Record inaccessible evidence as
unverified, not clean.

Publisher names in code-signing certificates and intentional project attribution
are public identities. Changing a certificate's publisher requires a legitimate
replacement signing identity; editing a README cannot change the certificate.
