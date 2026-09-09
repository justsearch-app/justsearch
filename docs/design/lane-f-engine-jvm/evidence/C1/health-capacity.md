# C1 health-monitor capacity recovery

## Ownership correction

The registry releases a one-shot timer permit before its callback runs
(DefaultEngineExecutorRegistry.java:446-471). A competing owner can reserve that capacity
while the health tick is running. The previous self-rearm then refused and silently lost its
only retry authority. It was incorrect to describe the old tick as retaining its permit during
its callback; the negative control below reproduces the actual inter-owner race.

KnowledgeServerHealthMonitor.java:204 now retains one fixed-delay timer and gates actual samples
with a monotonic due time (:248). Its heartbeat is at most one second; configured sample delays
remain clamped by nextTickDelayMs, with up to one pulse of scheduling quantization. The existing
wall-clock resume detector still uses the configured poll interval. No new executor or registry
callback mechanism is introduced. Timer installation retries typed capacity refusal on the
startup caller for at most five seconds, respecting the registered retry delay. Exhaustion and
CLOSED propagate; a failed start can be retried. HeadlessApp.java:636 starts before publishing
recovery authority, closes the unpublished monitor on failure, and preserves cleanup failures.

Manual recovery (:655) distinguishes CLOSED from capacity. Capacity becomes the existing
ENGINE_LIMIT admission exception, with the typed executor refusal as cause and the registered
retry delay. The existing failed-handoff finally releases the reserved recovery attempt. A later
request can be accepted when capacity is available. The production API exception mapping returns
HTTP429 and Retry-After; because the handler was entered, retrySafe remains false.

## Verification

Windows11, Temurin25.0.2; base95a1a2c52 plus this item.

- Run326 passes the five new real-registry capacity tests and existing health/boot tests. The
  competing-owner case blocks inside an actual health callback, attempts another long timer,
  releases the callback and requires another health tick. Startup tests prove refusal is visible,
  retry after release works, exhaustion is bounded and a later start remains possible. Manual
  recovery tests prove typed delay/cause, slot release and actual attempt after capacity release.
  CLOSED does not report successful startup or accepted manual recovery.
- Run327 fails fixture compilation because EngineResourcePolicy is package-private. The corrected
  HTTP fixture uses the public production registry, captures its real monitor scheduler, fills
  that owner's queue and drives InferenceHandlers.handleRestartWorker through installed filters.
  No production visibility or policy authority was changed to enable the fixture.
- Run328 passes the HTTP mapping and startup transaction tests plus existing restart routing;
  affected PMD passes. Startup tests verify start-before-bind and rollback with suppressed cleanup.
- Run329 did not launch Gradle due to a Windows batch path spelling error. Its captured unchanged
  XML is not proof. Corrected run330 restores the old production monitor byte-for-byte and runs
  the competing-timer and manual-refusal regressions: both fail for their intended behavior
  (no subsequent health tick; no admission exception). The corrected source is restored in finally.
- Restored331 passes51 selected cases across capacity, health, boot recovery, fatal-index recovery,
  startup composition, HTTP admission and restart routing, with no failures/errors/skips. UI test
  output is reused from328 for unchanged compiled behavior; PMD and other selected tests pass.

Final334 adds the actual cadence check: active samples use the one-second supplier delay, then
idle samples wait three seconds despite the one-second pulse. All52 selected cases pass; unaffected
suites reuse331 output. Full build335 passes in4s after this test-only addition.

Full build332 passes in30s with test Error Prone enabled. Canonical index, skill embedding,
link, module graph and runtime configuration checks333 pass. Both skill copies were inspected;
the changed sampling prose is not embedded in either.

Logs: `tmp/c1-health-capacity-326.txt`, `tmp/c1-health-http-327.txt`,
`tmp/c1-health-http-328.txt`, `tmp/c1-health-adverse-329.txt`,
`tmp/c1-health-adverse-330.txt`, `tmp/c1-health-restored-331.txt`,
`tmp/c1-health-build-332.txt`, `tmp/c1-health-docs-333.txt`,
`tmp/c1-health-final-334.txt`, `tmp/c1-health-final-build-335.txt`.
Immutable reports: `tmp/c1-health-results-326/`, `tmp/c1-health-results-327/`,
`tmp/c1-health-results-328/`, `tmp/c1-health-results-329/`,
`tmp/c1-health-results-330/`, `tmp/c1-health-results-331/`, `tmp/c1-health-results-334/`.

Final C1 integrated/live/hosted proof remains required; this evidence closes only this ordered item.

## Independent review and deadline correction

The read-only reviewer verified periodic permit retention, variable cadence, CLOSED handling,
startup publication/rollback and manual refusal projection, but found one P2: a retry caller
paused beyond the deadline could wake to free capacity and install a timer after five seconds.
Root now checks the monotonic deadline before every retry and rethrows the last typed refusal.
No new owner or clock authority is introduced. The regression blocks the caller at its retry
log (after the pre-sleep deadline check), waits beyond the admission window, releases competing
capacity and resumes it. Removing only the new deadline guard in336 makes the regression fail
because startup succeeds; the source is restored byte-for-byte in finally. The test checks that
refused late startup leaves zero timer reservations. A paused caller cannot return while it is
unscheduled; the bound prevents renewed admission after expiry, not operating-system pauses.

Logs: `tmp/c1-health-deadline-adverse-336.txt`, `tmp/c1-health-deadline-restored-337.txt`.
Immutable reports: `tmp/c1-health-results-336/`, `tmp/c1-health-results-337/`.

Restored337 passes53 selected cases in24s, with no failures/errors/skips. Full build338 passes
on the final deadline correction. Root owns this one review fix; no further worker rounds.
Build log: `tmp/c1-health-deadline-build-338.txt`.
