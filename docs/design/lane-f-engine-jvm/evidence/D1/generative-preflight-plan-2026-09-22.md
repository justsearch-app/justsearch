# D1-5 generative preflight

The current apply path validates files before stopping the incumbent, but checks
candidate VRAM after stopping it and assigning the candidate to the manager.
An insufficient-VRAM refusal therefore reports the prior ONLINE view even though
the incumbent has been stopped. Move the existing VRAM check before cache clear,
server stop, config assignment and crash-counter reset. Keep unknown VRAM's
existing warn-and-proceed behavior. No new owner, state machine or executor is needed.

Use the existing constructor-mocking fixture from
InferenceLifecycleManagerShutdownTest to run the actual manager with deterministic
GPU capability observations and fake server operations. Establish ONLINE with A,
then refuse GPU candidate B: verify the typed reason, unchanged A, restored ONLINE,
and no server stop/start or crash-counter reset. A negative control restores the
old ordering and must fail that regression for the incumbent mutation.

Root owns InferenceLifecycleManager.java and verification; the bounded test worker
owns only InferenceLifecycleManagerApplyConfigTest.java. Run affected tests plus
the existing ExternalServer and Identity suites, then module static/unit checks.

This is the first D1-5 slice, not its completion. Candidate publication after
health, distinguishable rollback outcomes and adoption-hash proof remain required.
Moving the config assignment alone would be incorrect: LlamaServerOps reads the
manager supplier during startup, health, props and retries, and reads global
ResolvedConfig for launch identity/argv. The next design must carry the candidate
through those consumers before changing the publication point. The manager's nine
executor registrations and close order remain unchanged by this slice.

## Explicit failed-restoration target

Discovery also confirms that TransitionRunner always restores the previous mode
and overwrites the rollback view's phase. Thus the existing manager's failed
rollback OFFLINE view cannot take effect. Extend the existing failure outcome
with a closed PREVIOUS/OFFLINE restoration choice. Ordinary failures retain
PREVIOUS; an explicit offline failure completes the existing FSM to OFFLINE,
publishes that same mode in its view/listeners/telemetry, and still throws the
typed failure. This extends the existing outcome rather than adding a state machine.

The runner worker owns TransitionOutcome, TransitionRunner and its tests; root
connects the manager's failed-restoration branches after that source is frozen.
Verify both ordinary rollback and explicit offline from ONLINE, with one failure
notification and matching phase. D1-5's full result-bearing compose and immutable
candidate launch context remain subsequent work.

## Verification

At53a64edb6 plus the five Java files captured in the run source inventories:

- 2425 focused manager apply, TransitionRunner, ExternalServer and Identity suites:
  39 cases across four suites, zero failures/errors/skips, all freshly executed.
- 2426 intentionally restores the old VRAM ordering: the selected regression fails
  because stopLlamaServer was called despite the refusal. 2427 intentionally maps
  explicit OFFLINE back to ordinary rollback: the selected regression fails with
  expected OFFLINE versus actual ONLINE. Each has one intended behavioral failure;
  the script restores exact original source bytes in finally.
- 2428 full app-inference unit tests, Spotless and PMD pass in16s:330 fresh cases,
  31 suites, zero failures/errors/skips. The whole-log failure index is empty.

Accessible logs, XML/counts and five-file source inventories are retained at
`tmp/2425-generative-preflight-focused*`, `tmp/2426-vram-order-negative*`,
`tmp/2427-offline-target-negative*` and `tmp/2428-generative-module*`.
The mutation driver is `tmp/2426-generative-negative-proof.py`.
Independent review and later integrated/live proof are tracked in the handoff;
these module results do not complete D1-5's remaining compose contract.

Independent review found duplicate failure telemetry: the runner emits its supplied
failure sink, then the manager's outer catch emitted it again. Remove that outer
catch so the runner remains the sole publisher inside the transition envelope.
The manager fixture now must verify exactly one typed failure for refusal and
failed rollback, and explicitly verify that refusal does not clear token caches.
The earlier2425/2428 results precede this review correction; a successor check is required.

Successor2430 passes all330 module cases/31 suites plus Spotless/PMD in18s,
zero failures/errors/skips. Its five-file source inventory, logs and XML are
captured at `tmp/2430-generative-reviewed-module*`. Independent bounded rereview
is clear after the correction; it independently checked all three pre-envelope
guards retain their failure events and both failed-restoration paths select OFFLINE.

2431 duplicates the runner's apply-failure sink and fails with
TooManyActualInvocations;2432 clears token caches before preflight and fails with
NeverWantedButInvoked. Each has one intended behavioral failure, and the mutation
driver `tmp/2431-generative-review-negative-proof.py` restores exact source bytes.
Logs/XML/counts are retained under `tmp/2431-duplicate-failure-negative*` and
`tmp/2432-premature-cache-negative*`. Full integrated/live checks follow this checkpoint.

At215af780988f1f455048425ff75a755f535f47d2,2433 full static/unit/stress/installDist
passes in9m18s:11530 cases,1803 suites,zero failures/errors,31 recorded skips.
Of34 test tasks,27 reuse unchanged results; counts identify fresh execution.
Full logs/XML/counts/skips/five-file source inventory are captured under
`tmp/2433-generative-integrated*` before any successor tests.

Installed standard-model2434 reports all four schema2 components READY and passes
runtime-client contract0.4.0 smoke. Real-model jseval's one regression query has
zero errors and exact/intersection/context100%, retrieve1966ms, LLM2953ms,
65 completion tokens. This proves live plumbing, not a broad quality benchmark.
Run31bc93f7-e34c-4a2e-a88f-72b5e1713ca6 uses API57730; official stop confirms
portsClosed:true. Evidence: `tmp/2434-generative-*.json`, `tmp/2434-online-intent.json`,
`tmp/2434-runtime-smoke.txt`, `tmp/2434-model-query.txt` and
`tmp/2434-model-query/tier2-eval.json`.

Hosted CI35671548458 at215af7809 completed successfully, including integration,
Windows-native, app-ui and all other jobs. This closes checkpoint verification;
the successor candidate-context implementation remains separate and unverified.
