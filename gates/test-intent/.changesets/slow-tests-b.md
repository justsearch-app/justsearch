---
schema: test-intent.v1
task: slow-tests-b
author-role: builder
author-session: 01a152dc-c848-7481-9982-88e2a39d11ec
---

# Slow-test batch B: replace waiting with observed completion

The ten suites and independent-interrupt fixture retain their executions and intent, with the changed oracles and limits assessed individually below. Timing and mutation evidence is under `tmp/slow-tests-b/` in the builder checkout. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B1: preserve real HTTP status counter semantics: changing GPU/memory/worker/telemetry measurements must not advance the state counter; a conversation state change must advance it, and a failed action must not advertise a newer counter for measurements alone.",
        "callersAndStateOwners": "StatusRoutes/Javalin call StatusLifecycleHandler; StatusComponentFixture publishes component state, the handler owns the NVML/cache clock, OperatingGpuProjection owns the memory projection cache, and StatusChangeCounter owns publication identity. The fixture supplies mocked producer samples and conversation state; HTTP and response headers are real.",
        "indirectBehaviours": "The handler clock is now shared with GPU-memory projection. The test advances controlled time across both cache windows instead of sleeping for wall-clock expiry and adds exact changed-measurement witnesses on ordinary and fresh HTTP reads. These are changed inputs/observations and stronger assertions, so the whole oracle is not an Adaptation. Field classification and failed-action/header checks remain.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 7/7 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 7/7 passed, 0 failed, 0 skipped; candidate changed inputs 7/7 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. Current assertions require changed NVML bytes, adapter free bytes, indexed count and telemetry values as well as unchanged measurement counters and increased conversation counters. Earlier pinned-base counterexamples B1-system-memory-clock and B1-state-measurement failed those assertions (tmp/slow-tests-b/results/); their patches remain under tmp/slow-tests-b/mutations/. A permanently cached sample cannot satisfy the new witnesses.",
        "unresolvedRisks": "Controlled time does not exercise wall-clock drift/backward jumps or real NVML sampling. Producers and AI service are fixtures: this HTTP test does not drive a real unloaded chat manager, so it does not rule out a status-read-triggered chat reload. Clock/publication assertions cover the chosen samples, not every future producer field."
      }
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RecordedRootPreparationTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B2: removing a watched root must wait for its active producer, and a completed late walk must not restore either membership or its persisted collection binding.",
        "callersAndStateOwners": "RootLifecycleOps add/remove and the real queued scan operate on WatchedRootsState under its lifecycle monitor. WatchedRootsStore owns the JSON persistence, the fixture records submitted walks/watchers, and separate real platform threads execute scan and removal.",
        "indirectBehaviours": "Removal is concurrent instead of synchronously waiting for the scan escape timeout. ThreadMXBean identifies blocking on the exact producer monitor before release. The ten-second escape now fails, and final state is checked in memory and by reloading the persisted file. These timing/precondition and assertion changes strengthen and alter the original oracle.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 2/2 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 2/2 passed, 0 failed, 0 skipped; candidate changed inputs 2/2 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. The duplicate-registration case still requires one walk and watcher. The removal case asserts the producer entered, removal is blocked on its fence, both operations complete after release, and membership/collection entries are absent after persisted reload. Earlier B2-no-removal-fence failed the blocking witness; patch and JUnit failure are in tmp/slow-tests-b/{mutations,results}/.",
        "unresolvedRisks": "The monitor witness uses platform-thread management information; it does not establish the same observation on virtual threads or a JVM lacking that information. Scan entry/release still has the original bounded ten-second escape. Persistence is checked on a normal temporary file, without injected disk/write failures."
      }
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B3: a chat positively holding the real inference lock must cause a vision request to refuse at its real 200-ms deadline, after which the chat must finish successfully and shutdown must complete.",
        "callersAndStateOwners": "OnlineModeOps owns the inference lock/deadline and issues real localhost HTTP requests. A held server handler owns the chat response; the test owns its latch and releases it in finally. The actual lock and timeout implementation run unchanged.",
        "indirectBehaviours": "A five-second fake HTTP handler delay is replaced with a response latch released immediately after vision refusal. The positive lock witness, actual 200-ms timer, refusal and successful chat completion remain, but the stall/fault duration and completion observation changed.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 58/58 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 58/58 passed, 0 failed, 0 skipped; candidate changed inputs 58/58 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. The existing real HTTP/lock test asserts entry/ownership, vision refusal and successful chat result. Earlier B3-no-vision-deadline failed the vision timeout expectation; the counterexample patch and failing JUnit XML remain in tmp/slow-tests-b/{mutations,results}/.",
        "unresolvedRisks": "The shorter held chat no longer gives five seconds of cleanup/stall exposure. A localhost fake response validates lock/deadline ordering, not an actual model inference implementation. Deadline accuracy remains dependent on the real scheduler, with no widened timeout."
      }
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B4: all seven queued inference request variants must carry their original start/deadline pair into worker execution, retain the inference deadline, and preserve real queue-expiry and owner-release behaviour.",
        "callersAndStateOwners": "EngineKnowledgeClient submits through the real inference executor registry/admission controller. CallContext and InferenceRequest carry the budget; held KnowledgeServices expose the captured worker context, and serving/admission owners remain real.",
        "indirectBehaviours": "Seven 600-ms queue-aging sleeps and a loose remaining-time bound are replaced with positive queue occupancy, start-before-release and exact deadline-minus-start/inference-deadline assertions. Those are different observations/assertions, not an unchanged oracle. Separate actual queue-expiry cases retain their timer-based checks.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 38/38 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 38/38 passed, 0 failed, 0 skipped; candidate changed inputs 38/38 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. Each request variant must queue behind a positively entered holder, complete, and expose its original budget identity. B4-restart-deadline and B4-restart-both earlier failed the changed assertions, independently detecting a new worker-entry deadline and a replacement pair; evidence and patches remain under tmp/slow-tests-b/{results,mutations}/.",
        "unresolvedRisks": "The seven timely identity cases no longer age their queue by 600 ms and do not individually prove that amount of elapsed time was deducted. The separate expiry scenarios cover actual expired queued requests, rather than every possible queue age/path. Exact comparisons assume the same monotonic clock used by CallContext."
      }
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ManagedChatSeam.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Restore the original observation: watch for 1.5 s after the last child has ended, exactly as the base test did, and assert that nothing relaunches.",
          "location": "task slow-tests-b user turn 9 (Builder round 6b decision)"
        },
        {
          "kind": "owner-words",
          "quote": "Right after quit, assert the manager's launcher is closed and refuses a start. Reach it the same way the test already reaches the manager's lifecycle lock.",
          "location": "task slow-tests-b user turn 7 (2026-10-19 Builder round 5, B5 quit)"
        },
        {
          "kind": "owner-words",
          "quote": "The test must fail for the auditor's 800 ms mutation and for a 2 s variant, 5 of 5 each.",
          "location": "task slow-tests-b user turn 6 (2026-10-19 Builder round 4, B5 quit)"
        },
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B5: preserve X/A/B/C coalescing, quit during real startup with no post-quit relaunch in the original observation interval, terminal refusal, and lock availability during held health/drain waits.",
        "callersAndStateOwners": "BootstrapInferenceFactory builds the real manager/controller and its default owned DefaultChildControl; SlowStartFakeLlamaServer is a real child. The controller owns queue/drain/health transitions, the manager owns its launcher, and the fixture owns health release, process identity records and cleanup.",
        "indirectBehaviours": "Startup delays become explicit health entry/release signals; accepted caller stack witnesses establish X/A/B/C ordering. Quit additionally checks its actual launcher is closed, rejects the original valid ChildSpec with child control is closed, and retains no child despite refusal. After the last child ends, the original 1.5-second observation is restored while the manager-refusal probe and unchanged launch-count/no-live-child assertions remain. These changed preconditions/observations/assertions are Incidental.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 3/3 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 3/3 passed, 0 failed, 0 skipped; candidate changed inputs 3/3 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. Clean candidate and mutation-restored batches each passed all three B5 cases 5/5 without skips. A5-M1 (a fresh approved launcher 500 ms after quit), quit-leaves-launcher-open and refused-start-still-launches each failed the quit case 5/5, respectively on nothing relaunches after quit, launcher closed, and refused restart retains no child. The other B5 cases were reviewed: coalescing observes all X/A/B/C completions and exact launches; lock probes run during positively held health/drain waits. Neither substitutes a shorter absence interval for the removed delay. Diffs remain under tmp/slow-tests-b/round6b/mutations/; failures and restored passes are in round6c/runs/. Quit-case JUnit median of five was 2.760 s (restored 2.748 s), versus the preceding version's recorded 1.153 s median; the unmodified 087cd7138 original-input single run was 1.555 s.",
        "unresolvedRisks": "A launch first recorded later than 1.5 seconds after the last child ends that bypasses the owned launcher can escape; the base test had the same blind spot. Closing one launcher does not terminate unregistered raw threads or prevent a fresh approved launcher, and the static owner guards do not catch A5-M1. The restored interval covers both raw-thread and fresh-launcher bypasses only within that bound; no manager-wide termination/factory invariant is claimed."
      }
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B6: timely HYBRID/AUTO requests waiting for unavailable models must return keyword evidence with loading degradation and zero encoder calls; expired queued requests must preserve contention/expiry classification, permanent absence and missing-model outcomes.",
        "callersAndStateOwners": "WorkerSearchService drives the real temporary keyword index and SearchBudgetPolicy/SemanticDeadline; the fixture controls the model-ready latch, provider availability, encoders and request CallContext. The service owns the semantic wait budget and fallback trace.",
        "indirectBehaviours": "Only the two timely cases change request budget from 20 seconds to 1250 ms, with an explicit positive allowance beyond lexical reserve/margin. They still really expire while loading, but no longer exhaust the full eight-second CPU cap. Already-aged queue inputs and all loading/contended, keyword and encoder non-execution assertions remain.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 15/16 passed, 1 failed, 0 skipped; candidate production with those unchanged inputs 16/16 passed, 0 failed, 0 skipped; candidate changed inputs 16/16 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. The original sources exercise the old 20-second timely inputs on both production versions through WorkerSearchService.search. The current class asserts keyword hits, exact loading versus contention trace, one logical model wait and zero encoder executions. Earlier B6-loading-as-contention failed the trace assertions; its patch/JUnit failure remain under tmp/slow-tests-b/{mutations,results}/.",
        "unresolvedRisks": "The permanent short timely cases do not by themselves exercise the full eight-second semantic cap or long-wait behaviour formerly reached with a 20-second request. Loaded scheduling can consume the short positive allowance before entry. Backdated queue fixtures check elapsed-budget accounting without reproducing an actual eight-second queue residence. Baseline finding: unmodified 087cd7138 queuedColdPlaceholderVectorKeepsTypedDeadline failed once in the original full run (missing SEMANTIC_BUDGET_EXCEEDED; expected true but was false at line 258). Its cause is not established. Isolated repetitions subsequently had 0/10 base failures and 0/10 candidate failures; the original full-run failure is retained, not erased by those passes. The fresh full candidate old-input/current class runs had 0/16 and 0/16 failures respectively."
      }
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B7/F1: real executor timeout/cancellation must retain issued native/serving owners until completion, release expired queued ownership, preserve text availability/admission/late-failure reporting and interrupt state at serving-view release.",
        "callersAndStateOwners": "EngineKnowledgeClient uses the actual executor registry, admission controller, CallContext expiry and serving leases. Held KnowledgeServices model issued native work; the fixture owns entry/queue/release signals and independent worker interruption. The client owns cancellation, admission and serving-view retention.",
        "indirectBehaviours": "Selected one-second fixture budgets become 250 ms, or 500 ms for seven concurrent entrants, after existing entry/queue witnesses. F1 acknowledges observation of an independent interrupt before releasing the worker. These change timing inputs and a fault-injection ordering precondition; all existing parameter rows, real expiry, late completions and lease-close interrupt assertions remain.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 34/34 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 34/34 passed, 0 failed, 0 skipped; candidate changed inputs 34/34 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. The old sources restore one-second budgets on both production versions through the actual executor calls. Earlier B7-release-issued-owner and B7-retain-expired-queue failed the respective lease/admission assertions. F1-clear-interrupt failed the retained close-time interrupt assertion; forced old release-before-observation failed and the acknowledged fixture passed, with 50 recorded current-fixture runs under tmp/slow-tests-b/results/. Patches and forced-order script remain in tmp/slow-tests-b/.",
        "unresolvedRisks": "Short budgets are more sensitive to host scheduling and do not keep the former one-second stall exposure in each case. Native work is a held service fixture, not every real backend. The original unacknowledged F1 fixture has a known observation/release race; old-input preservation results are not proof that old fixture was reliable. Independent interrupts arriving outside the exercised acknowledged interval are not all enumerated. One shortened deadline case no longer detects a serving view released at expiry, which the old case did; the F1 interrupt case detects it."
      }
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatActivationSeamTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "If waiting is the only honest way, use the shortest window that catches the 300 ms mutation reliably, and say why.",
          "location": "task slow-tests-b user turn 5 (2026-10-19 Builder round 3, B8)"
        },
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B8: all four foreign unload cycles must stay unloaded while chat intent remains enabled, until an explicit spec change forwards the next activation; exact child launches and manager modes remain checked.",
        "callersAndStateOwners": "RuntimeReconciler owns boot/spec forwarding and quiescence, the real InferenceLifecycleManager/controller owns mode changes and child starts, RuntimeIntentTestFixture owns intent, and ManagedChatSeam owns the real fake child and launch records.",
        "indirectBehaviours": "Fake startup delays are removed, unload delivery and activation futures are recorded, and forwarding is allowed to settle. A mode-event queue then watches each unload for 400 ms instead of the old one-second absence interval. This reduces a temporal observation even though all four cycles, the final explicit spec write and exact counts remain.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 1/1 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 1/1 passed, 0 failed, 0 skipped; candidate changed inputs 1/1 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. Original one-second observations run on both production versions. The current suite asserts delivered unload edges, settled forwarding, no unsolicited mode event, exact launch counts and final activation delivery. The exact 300-ms self-reload counterexample failed 5/5 in round3 and the clean candidate passed 5/5; older automatic-reconciliation mutation also failed. JUnit XML and patches remain under tmp/slow-tests-b/round3/ and tmp/slow-tests-b/{results,mutations}/.",
        "unresolvedRisks": "A reload whose first mode event arrives after the 400-ms post-quiescence window can escape, including a later timer or a 300-ms sleeper starved beyond its 100-ms margin. A status-read-triggered reload is not driven as a dedicated full status/API flow while unloaded; mode/view polling, quiescence and a final spec write do not prove that path safe. This class does not establish global future reload quiescence."
      }
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyStreamAcrossReconfigureTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Add the cheapest check that fails on A9-M1: the controller built by the real production wiring has the policy's drain limit.",
          "location": "task slow-tests-b user turn 8 (Builder round 6, B9)"
        },
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B9: a real proxied streaming reply must finish all final bytes/DONE before exactly one target relaunch; the controller built by real production wiring must use the 30-second drain policy limit.",
        "callersAndStateOwners": "BootstrapInferenceFactory constructs and installs the actual ChatServerControllerImpl in the manager. OpenAiCompatController owns the real HTTP proxy/generation lease, GenerationTracker owns admission/draining, the controller owns its configured drainBound, and StreamingFakeLlamaServer owns reply-release and event records.",
        "indirectBehaviours": "The old five-second pending hold is replaced with positive stream-lease/admission-closed witnesses and immediate pending/order checks. The real wired controller is now inspected and compared with both literal 30 seconds and ChatActuationRouter.DRAIN_BOUND. This is a different observation plus a new configuration assertion; the real stream, bytes, DONE, exact relaunch and cleanup remain.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 1/1 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 1/1 passed, 0 failed, 0 skipped; candidate changed inputs 1/1 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. Original sources retain the five-second stream hold on both production versions. Clean and mutation-restored batches passed B9 5/5 each, with final reply bytes/DONE, exactly one relaunch and event ordering unchanged. A9-M1 changes only the production public constructor to two seconds and failed the new production-wiring bound assertion 5/5, without a timed hold. Earlier premature-proxy-lease release failed generation ownership assertions; patches and JUnit XML remain under tmp/slow-tests-b/{round6b/mutations,round6c/runs,results,mutations}/. B9 JUnit median of five was 1.946 s (restored 1.902 s), versus the unmodified 087cd7138 original-input single run of 6.902 s.",
        "unresolvedRisks": "The new bound assertion does not wait for a real 30-second drain expiry and does not prove that runtime deadline arithmetic/enforcement uses the value correctly. Shortening another policy path or proceeding early after the short observation can still escape this class; policy unit tests cover only their explicit bounds. The nine previously named tests did not cover a shortened production-constructor bound: A9-M1 exposed that gap, now covered by the wired-instance assertion. Cleanup/resource probes were executed on Linux, not a native Windows run."
      }
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RichDocumentInProcessTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ],
      "assessment": {
        "purpose": "B10: a real PDF must be extracted, indexed and searchable by its marker, queued indexing must drain, and the actual index owner must close and release its resources for temporary-directory cleanup.",
        "callersAndStateOwners": "EngineRoot composes the actual KnowledgeServer/index/extraction/search path. KnowledgeServer owns index-root locking and closure; the fixture captures that exact server, owns the PDF and temporary directory, and checks real search results plus closure/lock reacquisition/rename access.",
        "indirectBehaviours": "Two stable one-second polls become a queue-empty/document-count observation and successful marker search. A fixed two-second teardown grace becomes server closure, actual index-lock reacquisition and bounded file/directory rename probes. These are different observations and additional resource assertions, even though the real parser/index/search path and original marker/score assertions remain.",
        "evidence": "Round6 original 087cd7138 inputs through the real boundaries: base 1/1 passed, 0 failed, 0 skipped; candidate production with those unchanged inputs 1/1 passed, 0 failed, 0 skipped; candidate changed inputs 1/1 passed, 0 failed, 0 skipped. The original full baseline invocation exited 1 because of the unchanged B6 cold VECTOR assertion; its ten XML reports remain in tmp/slow-tests-b/round6b/runs/original-base-old-inputs/. Round6c isolated cold VECTOR repetitions: base 0/10 failures, candidate 0/10 failures (one invocation each); that baseline failure did not reproduce. Candidate old/current reports, actual Gradle exits and exact commands are in tmp/slow-tests-b/round6c/runs/. Original stable-poll and teardown-grace inputs run on both production versions. Current assertions require searchable extracted content and actual closure/release. Earlier B10-bypass-pdf-job-extraction failed document convergence and B10-no-owner-close failed owner-lock reacquisition. Disabling only one StructuredContentExtractor parser route had survived because another route still extracted the PDF, so the effective counterexample refuses the PDF at the common job extraction entry; patches and failures remain under tmp/slow-tests-b/{mutations,results}/.",
        "unresolvedRisks": "A single queue-depth snapshot plus search does not guarantee that no later indexing work can ever appear; the former stable polls observed an extra interval. Closure checks cannot cover every native external holder, and Linux rename success is not proof of Windows file-sharing behaviour. Both extraction fallback routes and asynchronous startup may vary; the retained bounded failures must not be treated as a silently successful grace period."
      }
    }
  ]
}
```

## Drain-timeout coverage correction

The earlier nine-test coverage statement applied to the policy-level short-bound/proceed-on-expiry mutation only. The second audit showed that all nine use explicit bounds or exercise the policy separately, and therefore miss A9-M1's change to the production public-constructor wiring. B9 now checks the drain limit on the actual controller installed by BootstrapInferenceFactory; its Incidental assessment distinguishes that configuration observation from the removed five-second streaming hold.

## Acceptance records

Pending independent verification; no builder acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-slow-tests-b-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:a000f673b051805293ad37d4fb05d188026b74c6e5965e95cbdc642be2ce8f22",
  "note": "Round 2 at 80b3a61: B8 catches 300 ms, 1 s, reconciler and fourth-unload reloads 5/5 (baseline misses 1 s); round-1 mutations, stability and cross-module checks hold for the unchanged rest."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-slow-tests-b-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:fa8a222fbbcf35960ec60c7bc9fc1023181d7e43cf7874d12cfbb6ffbec02bdb",
  "note": "Round 4 at 63904a7ea: B5 quit fails 5/5 when quit leaves the owned launcher open; raw launcher threads (800 ms, 2 s, on refused restart) pass B5 and are caught 5/5 by EngineSpawnOwnershipArchTest and ProcessLaunchFunnelTest, which cover late launches. B8 catches a reload only within 400 ms after an unload; later ones escape (600 ms escaped) unless they land in the next unload's window."
}
```
