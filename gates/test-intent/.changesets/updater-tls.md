---
schema: test-intent.v1
task: t-20261020-updater-tls
author-role: builder
author-session: upkeep-g-updater-tls
---

# Test-intent entries

The provider regression pins the no-hang defect; the signature check pins unchanged trust behavior.
Both fresh-process checks require successful child exit, exactly one running test, and a libtest summary reporting one passed and zero failed; an unmatched child filter is a failure.
A temporary wrong-name probe fails with the original helper (the expected panic is absent) and passes with the hardened helper; logs are under tmp/evidence/helper-vacuity-20261020-1436/. The probe is removed after validation.
The fail-before compatibility patch introduces only a no-op initializer interface so the new test can compile on the old implementation.
Baseline signature preservation is also run on the unchanged production sources with the new test module transplanted; logs are under tmp/evidence/.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "Defect pin",
      "behaviour": "In a fresh process, the shared startup initializer installs ring, repeated initialization preserves the installed provider, and both the release-client (20 seconds) and backend-client (HEAD_TIMEOUT) configurations build without panic or error. The child must report running exactly one test and a summary of one passed and zero failed. Tests use the production initializer; the real-shell evidence separately exercises check_release through real IPC.",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no hang",
        "reproduction": "tmp/evidence/real-before/main.shell.log (No provider set); tmp/evidence/real-before/events.jsonl and summary.json (HTTPS checks unresolved, status checking, zero feed requests), produced by tmp/evidence/real-shell.py against the normal debug baseline shell under Xvfb."
      },
      "reproduction": "tmp/evidence/real-before/main.shell.log (No provider set); tmp/evidence/real-before/events.jsonl and summary.json (HTTPS checks unresolved, status checking, zero feed requests), produced by tmp/evidence/real-shell.py against the normal debug baseline shell under Xvfb.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "**The desktop app checks for updates by default**, about 30 seconds after the desktop chrome mounts when a release URL is embedded.",
          "location": "README.md:231-232"
        },
        {
          "kind": "owner-words",
          "quote": "yes",
          "location": "task t-20261019-9d870e user turn 2026-10-21, answer to the question: merge the G6 fix, so in-app update checks start working and contact the release server at launch?"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e request",
          "delegationScope": "the remaining upkeep work, including defects found during it",
          "label": "agent choice",
          "choice": "Install the already enabled ring provider in one shared initializer before either startup path constructs clients; ignore repeated-install errors and check both client configurations in a fresh process. Require the child to exit successfully and report exactly one running test, one passed and zero failed, so an outdated test name cannot pass vacuously."
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-tls-provider.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::startup_provider_builds_release_and_backend_clients"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Preserve signed-metadata validation: an Ed25519 signature is accepted for the original bytes, and rejected for tampered bytes with the existing verification error. The trust-key override is confined to a fresh test subprocess whose output must report exactly one running test, one passed and zero failed. This is a new check of unchanged behavior, not a repaired expectation.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Discovery fetches `release.v1.json`, `release.v1.json.sig` and `latest.json` from GitHub",
          "location": "README.md:233"
        },
        {
          "kind": "owner-words",
          "quote": "yes",
          "location": "task t-20261019-9d870e user turn 2026-10-21, answer to the question: merge the G6 fix, so in-app update checks start working and contact the release server at launch?"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e request",
          "delegationScope": "the remaining upkeep work, including defects found during it",
          "label": "agent choice",
          "choice": "Add a direct preservation check of the existing signature verifier with authentic and tampered metadata; the claimed existing direct signature test was absent. Require the shared child harness to report exactly one running test, one passed and zero failed, so the signature check also rejects an outdated test name."
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-tls-signature.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::metadata_signature_rejects_wrong_signature"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "a14d8eec-a719-404b-b61f-c7416f59788c",
  "verdict": "accept",
  "digest": "sha256:76e078fa3c1368365b6340e7bcbb51d46596d9608e622b24a82fcf0ec45559bb",
  "note": "Sources and fail-before reproduced (no-op initializer: No provider set -> assertion); delegation quote taken from brief; child-name vacuity noted in report"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "95ce653e-7b14-4df7-a4ee-2f112179ce76",
  "verdict": "accept",
  "digest": "sha256:10f3850a986cdfebc2ad71f33307cd89ba8ec0314398a404d44fe545c4905add",
  "note": "Helper now fails an unmatched child name (0 tests) and a no-op TLS install in lib and conformance; a name of another existing test still passes vacuously"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "636bee56-af2a-4ae2-b5c0-150def056b9e",
  "verdict": "accept",
  "digest": "sha256:270574436e7cd169deb6712564d3f97d44ef79d363454ecf126ff19685802132",
  "note": "Post-merge: test block byte-identical, main tests untouched; README 231-233 quotes verbatim at base; evidence fresh (no provider -> assertion, no-op shim); task-record yes not checked"
}
```
