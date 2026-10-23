# Wrapper lifecycle evidence

The production stop design is unchanged. The cooperative fake captures its wrapper before publishing
health readiness. The two durable K6 checks wait for that fake's own exit (15 seconds: the product's
12-second STOP_WAIT plus a stated 3-second margin), and separately prove the restart barrier while a
key-enforcing descendant ignores parent death. After exit/explicit identity-checked test cleanup,
both checks require a working restart with a fresh key. PID inequality is not assumed.

## Passed candidate checks

- The final sources match the temporary candidate checkouts byte-for-byte. HealthyFakeLlamaServer:
  `sha256:7cd0ed82b95f0a0f8210910fa641c5aea5757f1609302dfeee6471f8883acbb3`;
  ManagedLlamaAuthenticationTest:
  `sha256:700848f88bf66e5c4e1d9967fe976d2846f09a9041ff01ecc740e7aeb0d4507c`.
- `after-final-8-200.xml`: 200 executions, 100 of each replacement method, eight independent
  fixtures concurrently, zero failures. `WrapperParallelLoadTest.java` is its temporary driver.
- `../llama-key-wrapper-load.json` and `official-candidate.xml`: the unchanged official evidence
  producer repeated those 200 executions successfully. `WrapperOfficialLoadTest.java` is its
  temporary driver, restored as modules/app-inference/src/test/java/io/justsearch/app/inference/WrapperParallelLoadTest.java
  in a shared candidate clone. No stress driver is added to the normal test suite.
- The full touched-module suite plus spotlessApply passed (2m 10s); build -x test
  -PskipWebBuild=true passed (4s). The refreshed primary auth evidence has 16 passing candidate
  cases and assertion fail-befores on f003a5c64; the ignored-key evidence has 6 passing candidate
  cases and the four expected assertion failures on 673b54a1b (two preserved success cases pass).

## Old-race reproduction remains unverified

The before-state is review head 950a3a2ed898447a68734eee492095a43791dd62. The original wrapper
method was not changed in the before-state. The diagnostic driver selects that legacy method
there, and both replacements in the candidate; this compares the reviewed assertion with the
explicitly delegated replacement contract, not different production stop implementations.

Early bounded attempts passed 48 cases at four-way concurrency and 200 at eight-way concurrency.
A subsequent eight-way run stopped at 720 cases because of an unrelated fake BindException,
not the requested wrapper-stop assertion. Its raw results are before-8-bounded.xml and
before-8-bounded.log. That failure is not counted as the required race reproduction.

The official diagnostic retained all unrelated errors and searched until the exact old
"stopping the owned wrapper also ends its serving descendant" assertion or 2,000 attempts.
It completed 2,000 attempts without that assertion. Two case directories contain original Java
BindException logs (official-before-bind-1.log and official-before-bind-2.log). Its missing-target
path deliberately throws IllegalStateException, not an assertion, so run-evidence.mjs returned 1
and classified the fail-before as exception. The sealed JSON is not edited and is not claimed
as an assertion fail-before. The independent acceptor's original failure remains in the existing
changeset review record; this builder has not reproduced it within these bounds.

The producer was run from a shared candidate clone under one default slot:

    js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
      --out <source-checkout>/gates/test-intent/evidence/llama-key-wrapper-load.json \
      --before-root <TMPDIR>/wrapper-rework --before 950a3a2ed898447a68734eee492095a43791dd62 \
      --check modules/app-inference/src/test/java/io/justsearch/app/inference/WrapperParallelLoadTest.java

Both candidate sources and the archived driver must first be copied into the shared candidate
clone. The producer overlays only the driver into its old worktree; the old wrapper test and
fake are retained there. JAVA_TOOL_OPTIONS is unset, TMPDIR is the assigned builder folder,
and JAVA_OPTS supplies -Djava.io.tmpdir for the directly started JVM launcher. Worktree cleanup
is checked for both repositories.

## Blocking K8/K6 composition counterexample

IgnoredKeyWrapperProbeTest.java is a temporary diagnostic, not a default-suite test or an accepted
change to the K8 expectation. It composes existing fake modes --wrapper, --ignore-api-key and
--ignore-parent through the real Engine lifecycle, using the current fake and test setup in a
shared clone with unchanged production code at 950a3a2ed.

ignored-key-wrapper-probe.xml records an assertion failure after 14.794 seconds:

    IGNORED_KEY_WRAPPER rejected=true online=false servingPid=322532 unauthenticatedProps=200
    K8 rejection must not leave an unauthenticated serving descendant

The guard correctly rejects startup, but stopping the wrapper leaves the unenforcing descendant
serving protected properties without a key. The ordinary Linux restart barrier does not close
that unauthenticated endpoint. The probe's finally block ends the exact recorded-identity fake;
cleanup.txt confirms no matching serving or wrapper processes remain. The temporary source and
raw Gradle log are retained alongside the XML. This is a real process/HTTP counterexample, not
an accepted residual or a claim that the new K6 checks failed. Resolving it requires a decision
on failed-launch cleanup under K8; no Linux descendant killing was added in this assignment.

All primary D4 JSON files and the additional load JSON were written by the unmodified evidence
producer. Raw XML/logs and this explanatory index are supplemental evidence. No full live key
is printed, and no existing review record is edited.
