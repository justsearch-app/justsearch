---
schema: test-intent.v1
task: keystore-http-timeout
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Keystore HTTP rotation/retry timing on Windows

The task request delegates timeout sizing only after establishing slowness. The budget and
instrumentation below are agent choices. No assertion, credential, fault injection or HTTP
operation changes.

## Windows observations

The Windows-native contracts-2 jobs and their uploaded JUnit reports show:

| PR | Run / job | Rotation/retry | Independent-manager round trip |
| --- | --- | --- | --- |
| #877 | 39656014182 / 119416509145 | pass, 27.431 s | pass, 10.599 s |
| #880 | 39656038128 / 119417351334 | timeout after 30 s (30.109 s including unwind); retry passes, 11.064 s | pass, 10.089 s |
| #881 | 39656031315 / 119416585098 | pass, 26.226 s | pass, 8.911 s |

Source jobs: [#877](https://github.com/justsearch-app/justsearch/actions/runs/39656014182/job/119416509145),
[#880](https://github.com/justsearch-app/justsearch/actions/runs/39656038128/job/119417351334),
[#881](https://github.com/justsearch-app/justsearch/actions/runs/39656031315/job/119416585098).
The other Windows shard failures are the sibling process-kill tests fixed by #882.
All three PR head revisions contain this test byte-for-byte identical to baseline 2c7501faf.
The rotation/retry method performs fourteen real KDF derivations (source setup, two rotation
attempts, two target setups and four credential imports), versus six in the other method;
no KDF or persistence work is stubbed or reduced by this change.

The #880 timeout stack is in HttpClient.send, called by roundTrip line 84 and the test line 55:
the import in the SECOND round trip, after the failed rotation, first round trip and successful
rotation retry. This is evidence of progress, rather than a test stuck at its first operation.
The passing executions near the default and the successful retry support cumulative slowness.
The reports contain no per-request timings or server stack, so they cannot establish the exact
cost of that interrupted request or exclude every possible transient stall. There is no observed
deadlock or consistently stuck step. New diagnostics distinguish preparation, each HTTP request
(including credential TYPE, never its value), rotation phase and server cleanup.

The default is `junit.jupiter.execution.timeout.default=30s` in
`build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt`. The chosen 90 s is
3.28 times the slowest observed passing rotation/retry run (27.431 s), about 63 s headroom.
The independent-manager method stays on the default because its longest observed pass is 10.599 s.
The whole-test limit remains finite; unlike #882, this test has no child JVM or child deadline.

Raw job logs and downloaded artifacts are in `tmp/keystore-http-timeout/` (not committed).
Retrieved with the requested gh CLI, `gh pr checks <PR> --repo justsearch-app/justsearch`,
`gh api repos/justsearch-app/justsearch/actions/jobs/<job>/logs` and the run artifacts API.
Artifact IDs for the three contracts-2 reports: 12371079290, 12375647471, 12374011422.
Each ZIP's result is `modules/ui/build/test-results/test/TEST-io.justsearch.ui.api.KeystoreBackupDurabilityHttpTest.xml`.
Its SHA-256, in PR order:

- #877: `0142cef67ee4935a0f2df00ea51a9c039d287b12b3e5475c747e585c820e8410`
- #880: `88f5885cbafa89ce9563716c0d784cd542ef54470b80aea517c521b066e8c00b`
- #881: `da5122b48a9813c06c81600a65186ffff0a3d1e67f4b92d785c010ad0928fc64`

## Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreBackupDurabilityHttpTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "If slow: give the affected methods an explicit @Timeout sized from measured durations, with headroom like #882's, plus diagnostics like #882's if they help a future failure show where the time went. Don't change what the test asserts.",
          "delegationLocation": "task keystore-http-timeout request (session cc6d91b2-5314-42fd-867b-dbc2d8a68699)",
          "delegationScope": "Distinguish slow Windows execution from a hang in KeystoreBackupDurabilityHttpTest using the failing and passing CI runs, then size only affected methods and improve diagnostics without changing assertions.",
          "choice": "Give only failedRotationAndRetryKeepHttpExportsDecryptable a 90-second JUnit timeout, about 3.28 times its slowest measured Windows pass. Add rotation phase markers, target-keystore preparation and round-trip/cleanup elapsed timings, and per-request start/elapsed diagnostics that identify the path and body key names without printing credentials or encrypted data. Retain the independent-manager method's 30-second default.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Keep the existing real-HTTP failure/rotation/retry durability proof executable on a slow Windows runner, with a finite method deadline and diagnostics showing the phase interrupted by a future failure.",
        "callersAndStateOwners": "JUnit owns an isolated TempDir and a local ephemeral-port Javalin server plus HttpClient per round trip. DataKeyManager and EncryptionKeystore own the temporary keystore and replica files; the mocked authored-store descriptors supply and receive the same conversation entry. No production code, saved-state format, persistence ownership, test tag, selection or default timeout changes.",
        "indirectBehaviours": "Both independent managers remain; target setup, actual HTTP export, both passphrase and recovery imports, HTTP 200 checks, restored count and exact entry equality stay unchanged. The nonempty replica directory still forces UncheckedIOException, the old credentials still round-trip after failure, and the new passphrase plus original recovery key still round-trip after retry. HttpClient close and server stop retain their original order and finally block. Diagnostics print only fixed phase names, paths, body key names and monotonic elapsed milliseconds.",
        "evidence": "Windows job links, artifact IDs, exact report path and hashes, timings and the timeout stack location are retained above. The historical before-state has a real 30-second JUnit timeout in the second round trip and passes at 26.226-27.431 seconds in adjacent runs. Linux execution and lint are unverified: the corrected focused invocation never acquired a shared Gradle slot and was cancelled after 1734.72 seconds in the queue. The initial invocation failed in the temporary classpath-capture init script before test execution; that script was corrected. The CI lint command was not started. Exact commands and output locations are recorded below; no Linux test pass is claimed.",
        "unresolvedRisks": "The candidate has not executed its Linux test or CI lint, and has not run on hosted Windows. Historical reports do not expose individual request durations or server thread stacks, so cumulative slowness is an inference from near-limit passes, a late-stage timeout and a passing retry. The explicit 90-second method limit bounds a future stalled request; no per-request timeout existed before or is introduced. Independent acceptance is deliberately left to the verifier."
      }
    }
  ]
}
```

## Local checks

- `js-gradle-slot node scripts/dev/prepare-worktree.cjs`: exit 0; worktree dependencies and
  `:modules:ui:installDist` prepared, Gradle reports `BUILD SUCCESSFUL in 38s`.
- Focused command: `js-gradle-slot ./gradlew :modules:ui:test --tests
  "io.justsearch.ui.api.KeystoreBackupDurabilityHttpTest" --init-script
  tmp/keystore-http-timeout/capture.init.gradle`. The temporary script only captures runtime
  classpath and Java executable for baseline checks. First attempt: exit 1 before any test,
  because the initial script also queried the included build for the UI project. Corrected
  script: the second attempt waited 1734.72 seconds without acquiring a shared Gradle slot.
  At 21:37:16 UTC it was cancelled by SIGTERM to its recorded owned wrapper PID 424272;
  the command session returned 143. No test executed, so its Linux runtime is unmeasured.
  The GNU time file reports the signal separately; its `%x` field reads zero for this signal
  termination and is not a successful test result.
- `js-gradle-slot ./gradlew compileTestJava compileIntegrationTestJava pmdAll --continue
  -PskipErrorProneTests=false -PskipWebBuild=true`: NOT RUN. The focused invocation remained
  queued until the builder's 35-minute estimate upper end; the brief requires returning when
  the work runs well past the assignment. Verification needs a shared Gradle slot scheduled
  by the orchestrator. There is no implementation failure to fix on this evidence.
- The untouched before-state is a shared detached clone of 2c7501faf in `$TMPDIR/before`;
  no baseline build or test executed. Historical Windows results above are the before evidence.
- Raw outputs: `tmp/keystore-http-timeout/prepare-worktree.log`,
  `focused-capture-error.log`, `focused-capture-error-time.txt`, `focused.log`,
  `focused-time.txt`, `focused-process.json`, and `test-intent.log`.
- Shared resources touched: normal npm/Gradle caches during preparation and the shared Gradle
  slot queue. No owner's app, dev stack, GPU or other checkout's dependency tree was used.
- `git diff --check`: exit 0.
- Test-intent gate: exit 1, with only `test-intent/acceptance-missing`; independent acceptance
  remains the verifier's responsibility. The historical reconsider-rate warning (1/9 audited
  PRs, 11.1%) is nonblocking. The final gate output is in `test-intent.log`.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "claude-verifier-ks-http-2426ca209",
  "verdict": "accept",
  "digest": "sha256:3bbbb2d2eb03978b9c0858068bd71df148a2b5a17fc93b34a134b3c0e63a27fe",
  "note": "Incidental fits: oracle unchanged, 90s on rotation/retry only (3.3x slowest Windows pass 27.4s), print-only diagnostics; candidate passed hosted Windows 24.8s, Linux 2.9s"
}
```
