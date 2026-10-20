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
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Nothing in the probe may depend on product method names or thread stacks.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "hold the relaunched server's port the same way as the first start's, and probe the lock before releasing it. Keep the health-hold and drain probes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "The audit's mutation and every earlier B5 mutation must fail 5 of 5.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Restore the original observation: watch for 1.5 s after the last child has ended, exactly as the base test did, and assert that nothing relaunches.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Right after quit, assert the manager's launcher is closed and refuses a start. Reach it the same way the test already reaches the manager's lifecycle lock.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "The test must fail for the auditor's 800 ms mutation and for a 2 s variant, 5 of 5 each.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "B5: preserve X/A/B/C coalescing, quit during real startup with terminal refusal and no relaunch in the observed interval, and lifecycle-lock availability during drain and both the first start and relaunch before port binding and during held health responses.",
        "callersAndStateOwners": "BootstrapInferenceFactory builds the real manager/controller and its default owned DefaultChildControl; SlowStartFakeLlamaServer is a real child. The controller owns queued activation/drain/health transitions, the manager owns its launcher, and ManagedChatSeam owns port/health release files, process identity records and cleanup. Other helper callers do not set hold-port and retain their configured startup delays.",
        "indirectBehaviours": "Startup delays become explicit child entry/release signals; accepted caller stack witnesses still establish X/A/B/C ordering only. Both initial activation and the prepared candidate relaunch now trigger the lock check directly from the launched fake PID's port-entered file, written before binding. The hold-port file stays present throughout the real one-second monitor-acquisition assertion, then is released in finally. The product polling-method/thread-stack witness is deleted; retrieving the actual runner lock uses fields, with no product method invocation. Each port release is followed by the separate held-health-response probe, and the drain probe/lease cleanup remain. Quit retains owned-launcher closure, valid-spec refusal and no-retained-child checks, followed by the 2.5-second observation needed for the earlier two-second raw-launch regression. Changed inputs/observations/assertions remain Incidental.",
        "evidence": "Round9 takes the keep branch: full ChatControllerLifecycleSeamTest passed all three original cases 5/5 clean and 5/5 after mutation restoration, no skips; JUnit XML class medians 7.902 s and 7.392 s, both below the agreed 12-second cutoff. The independent verifier's verdict for be829cd2a reported both new outside-health port-wait variants passing that previous candidate 5/5 and failing 087cd7138 5/5. With the direct fake signal, MV-launch-port-wait-outside-health and its relaunch-only variant each fail 5/5 at the lock assertion while the port remains held, without a product-stage witness timeout. All eleven earlier faults also fail their intended assertion 5/5: relaunch-only port wait, audit port wait, drain under lock, no coalescing, lock across health, fresh launcher at500ms, raw800ms/2s launches, manager-refused restart that launches, quit leaves launcher open, and the older owned-launcher refusal side effect. M5b repeats the lock case; every other mutation batch and both clean batches repeat the full three-case class. Commands include per-task --rerun and run sequentially under the shared Gradle flock with the daemon reused. Exact patches, XML and real exits are in tmp/slow-tests-b/round9/{mutations,runs}/. All mutations are reverted; Spotless, compile, PMD and the module build passed. Historical original-base whole-class median22.286s from five round7 executions remains recorded in tmp/slow-tests-b/round7/runs/base087/; no new base timing is claimed here.",
        "unresolvedRisks": "A bypass launch first recorded later than 2.5 seconds after the last child ends can escape, including a nominal two-second timer whose execution/child startup is delayed beyond the observation. The old test also had an unbounded-future-launch blind spot with its shorter 1.5-second interval. Closing the owned launcher does not terminate unregistered raw threads or prevent a fresh approved launcher; no manager-wide termination/factory invariant is claimed. The lock probes target initial activation and the prepared candidate relaunch; the subsequent abort restores the incumbent without a separate held-port/health lock probe, as on the base test."
      }
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Where the test already has an entry witness, restore 1000 ms.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "B7/F1: real executor timeout/cancellation must retain issued native/serving owners until completion, release expired queued ownership, preserve text availability/admission/late-failure reporting and interrupt state at serving-view release.",
        "callersAndStateOwners": "EngineKnowledgeClient uses the actual executor registry, admission controller, CallContext expiry and serving leases. Held KnowledgeServices model issued native work; the fixture owns entry/queue/release signals and independent worker interruption. The client owns cancellation, admission and serving-view retention.",
        "indirectBehaviours": "All five constructor deadlines previously shortened to 250 ms or 500 ms are restored to 1000 ms where the test positively witnesses worker entry. Actual timer expiry still happens while the native/worker body is held; issued/queued ownership, text availability, admission counts, late failures and all parameter rows remain. F1 retains its independent-interrupt acknowledgement before release and its interrupt-at-serving-view-close assertion. This retains the existing Incidental assessment rather than claiming that restoring fixture budgets removes every changed observation.",
        "evidence": "Round10 serial five-run JUnit class medians at unchanged 59b7aea5e: 18.447 s; updated candidate: 32.417 s; mutation-restored candidate: 32.318 s. Each class passed all original cases 5/5 clean and 5/5 after restoration, without skips; a shared before clone under tmp/slow-tests-b/round10/tmpdir/ preserves the unchanged source. Each repetition is --rerun and each Gradle invocation uses flock /tmp/js-linux-port/gradle.lock ./gradlew with the existing daemon. F1-release-serving-view-at-expiry closes the issued serving view from budget expiry while the worker is still held; F1 rejects it 5/5 at its close-time interrupt assertion. F1-clear-interrupt also fails that assertion 5/5. The separate deadlineRetainsExactServingViewUntilWorkerActuallyExits case retains its explicit non-closure check and successor-view execution. Historical forced release-before-interrupt observation and acknowledged-fixture evidence remain under tmp/slow-tests-b/{results,force-interrupt.py}; this round claims five current clean/fault repetitions, not a fresh fifty-run proof. Exact patches, XML and commands are under tmp/slow-tests-b/round10/{mutations,runs}/.",
        "unresolvedRisks": "One-second fixture deadlines restore the original entry margin but still depend on host scheduling. Native stalls remain held service fixtures rather than every real backend. A point-in-time non-closure assertion alone is not an interval proof; F1 separately detects the release-at-expiry counterexample after the deadline restoration. Independent interrupts outside the acknowledged worker interval are not all enumerated; the old unacknowledged F1 fixture remains historically racy."
      }
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatActivationSeamTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Restore a window of at least 1000 ms after each foreign unload, using the poll, not a sleep.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "If waiting is the only honest way, use the shortest window that catches the 300 ms mutation reliably, and say why.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "B8: all four foreign unload cycles must stay unloaded while chat intent remains enabled, until an explicit spec change forwards the next activation; exact child launches and manager modes remain checked.",
        "callersAndStateOwners": "RuntimeReconciler owns boot/spec forwarding and quiescence, the real InferenceLifecycleManager/controller owns mode changes and child starts, RuntimeIntentTestFixture owns intent, and ManagedChatSeam owns the real fake child and launch records.",
        "indirectBehaviours": "Fake startup delays remain removed and unload/activation delivery remains acknowledged. After each of all four foreign unloads and reconciler quiescence, the mode-event queue is now polled for 1100 ms, preserving at least the base one-second absence interval. The extra 100 ms gives the earlier one-second timer fault a margin. Early mode events fail immediately; exact activation/launch counts and the final explicit spec change remain. This restores the temporal guard rather than claiming that delivery quiescence proves future inactivity.",
        "evidence": "Round10 serial five-run JUnit class medians at unchanged 59b7aea5e: 5.708 s; updated candidate: 8.206 s; mutation-restored candidate: 8.443 s. Each class passed all original cases 5/5 clean and 5/5 after restoration, without skips; a shared before clone under tmp/slow-tests-b/round10/tmpdir/ preserves the unchanged source. Each repetition is --rerun and each Gradle invocation uses flock /tmp/js-linux-port/gradle.lock ./gradlew with the existing daemon. Self-reload timers at 300, 600, 900 and 1000 ms each fail the restored foreign-unload observation 5/5. A reload restricted to the fourth foreign unload and the earlier automatic-reconciliation mutation also fail 5/5. All four real manager/child unload cycles and the final spec-triggered activation remain in every clean execution. Exact patches and JUnit failures are under tmp/slow-tests-b/round10/{mutations,runs}/; Spotless/compile/PMD/build pass.",
        "unresolvedRisks": "A reload first observed after the 1100-ms post-quiescence window can still escape, including a nominal one-second timer starved beyond its 100-ms margin. A dedicated status-read-triggered reload flow is still not exercised while unloaded. The window is at least the base one-second observation, not a proof of global future manager quiescence."
      }
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyStreamAcrossReconfigureTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "After `draining` is observed, keep asserting for 1 s that the reconfigure is not done and nothing relaunched.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Add the cheapest check that fails on A9-M1: the controller built by the real production wiring has the policy's drain limit.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "B9: a real proxied streaming reply must finish all final bytes/DONE before exactly one target relaunch; the controller built by real production wiring must use the 30-second drain policy limit.",
        "callersAndStateOwners": "BootstrapInferenceFactory constructs and installs the actual ChatServerControllerImpl in the manager. OpenAiCompatController owns the real HTTP proxy/generation lease, GenerationTracker owns admission/draining, the controller owns its configured drainBound, and StreamingFakeLlamaServer owns reply-release and event records.",
        "indirectBehaviours": "The original five-second stream hold is now replaced by positive stream-lease/admission-closed witnesses followed by a full one-second interval that repeatedly asserts the generation lease remains held, reconfiguration remains incomplete and only the original server is launched. Final reply bytes/DONE, exactly one target relaunch and event ordering remain. The real production-wired controller drainBound is still compared with literal 30 seconds and ChatActuationRouter.DRAIN_BOUND; a policy-bound assertion additionally detects the historical 1500-ms policy cut without restoring a long hold.",
        "evidence": "Round10 serial five-run JUnit class medians at unchanged 59b7aea5e: 2.271 s; updated candidate: 2.941 s; mutation-restored candidate: 2.970 s. Each class passed all original cases 5/5 clean and 5/5 after restoration, without skips; a shared before clone under tmp/slow-tests-b/round10/tmpdir/ preserves the unchanged source. Each repetition is --rerun and each Gradle invocation uses flock /tmp/js-linux-port/gradle.lock ./gradlew with the existing daemon. B9-runtime-proceeds-500ms changes GenerationTracker to proceed after 500 ms while the controller field and factory policy still report 30 seconds; it fails during the one-second held-stream observation 5/5. Earlier premature proxy-lease release, 1500-ms factory-policy cut and 2000-ms production-wiring cut each fail their respective lease/policy/wiring assertions 5/5. The policy check is distinct from the temporal observation and is not represented as evidence of actual thirty-second deadline enforcement. All real HTTP stream completions and relaunch cleanup pass on clean/restored runs; exact patches and XML are under tmp/slow-tests-b/round10/{mutations,runs}/. Spotless/compile/PMD/build pass.",
        "unresolvedRisks": "The dynamic observation is one second, compared with the base five seconds. A later early-proceed defect that leaves wiring/factory values unchanged may escape this class. The configured/policy-bound assertions do not wait for a real thirty-second expiry or prove all runtime deadline arithmetic. The historical 1500-ms factory mutation is caught by configuration evidence; the new 500-ms runtime mutation is caught through the actual stream boundary. Resource cleanup was exercised on Linux, not a native Windows host."
      }
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RichDocumentInProcessTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Once the first move has succeeded, retry only the move back.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "B10: a real PDF must be extracted, indexed and searchable by its marker, queued indexing must drain, and the actual index owner must close and release its resources for temporary-directory cleanup.",
        "callersAndStateOwners": "EngineRoot composes the actual KnowledgeServer/index/extraction/search path. KnowledgeServer owns index-root locking and closure; the fixture captures that exact server, owns the PDF and temporary directory, and checks real search results plus closure/lock reacquisition/rename access.",
        "indirectBehaviours": "Queue/document convergence and real extracted-marker search remain. Teardown still observes actual KnowledgeServer closure and reacquires the index-owner lock, then probes child files/directories only, excluding the TempDir root. Forward and reverse moves now retry separately against the original shared two-second deadline. After forward success only the reverse move is retried; interruption or a reverse timeout attempts original-name restoration before propagating the primary failure. The real IOException remains the cause, and any failed final restoration is suppressed with both paths rather than masking it with a subsequent forward NoSuchFileException.",
        "evidence": "Round10 serial five-run JUnit class medians at unchanged 59b7aea5e: 9.508 s; updated candidate: 8.078 s; mutation-restored candidate: 8.222 s. Each class passed all original cases 5/5 clean and 5/5 after restoration, without skips; a shared before clone under tmp/slow-tests-b/round10/tmpdir/ preserves the unchanged source. Each repetition is --rerun and each Gradle invocation uses flock /tmp/js-linux-port/gradle.lock ./gradlew with the existing daemon. Four temporary boundary checks use real file moves with controlled failure injection: do not rename the TempDir root; retry only the reverse move after transient reverse failures; restore the original name on interruption; restore it before reporting a delayed reverse failure past the deadline. Every check fails on the unchanged helper 5/5 for its intended root/source/name assertion and passes on the repair 5/5; the real PDF ingestion/index/search test is unchanged and passes separately. The probes also verify marker contents, absence of the probe name and preservation of the actual I/O error. Probe source/init script, raw failures and passes are in tmp/slow-tests-b/round10/{probe-src,runs}/. Historical PDF extraction/owner-closure counterexamples remain in tmp/slow-tests-b/{mutations,results}/. Spotless/compile/PMD/build pass.",
        "unresolvedRisks": "A queue-depth snapshot plus successful search is not a guarantee that no later work can ever appear. Linux moves do not prove Windows sharing semantics. Recoverable reverse errors and interruption restore the original name in the fault checks; if the filesystem permanently rejects even the final restoration attempt, the helper cannot force a rename and reports both the primary and restoration errors with the two paths. No unbounded wait or widened release deadline is introduced."
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-slow-tests-b-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:4cc059ec998b20b5637d285f07c8bcb008df7b10c99aa5e59026cef322f39c3e",
  "note": "Round 6 at 175b78ff1 (changeset text only since round 5): B7 gap sentence is accurate but broader than seen; the shortened deadline case missed a view released at expiry after the caller was failed and still caught one released before (single runs). B5 quit fails on a fresh launcher 500 ms after quit; late raw launches are caught only by the static spawn guards. B8 catches a reload only within 400 ms after an unload; later ones escape unless they land in the next window."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-slow-tests-b-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:0e63f1dcf5c5fbaeafdb82fdb345bfc23d2395151a459cf100dec11be244d4e3",
  "note": "Round 9 at 5e2a44782 (B5 test and changeset only since round 8): clean B5 5/5, class median 7.21 s vs 21.63 s base. All 12 B5 mutations, including both outside-health port-wait variants, fail 5/5 at their intended assertion. Each pre-bind lock probe samples one 1-second window from the fake child's pre-bind signal: a lock first taken 1.5 s later while the port is still unbound escapes, as it does on the base test (5/5 each). B8 catches a reload only within 400 ms after an unload; later ones escape unless they land in the next window."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "auditor-slow-tests-b-3-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:0e63f1dcf5c5fbaeafdb82fdb345bfc23d2395151a459cf100dec11be244d4e3",
  "note": "Audit 4 at fbf212bbf (B5 only changed since audit 3): the port-wait mutation that escaped audit 3, plus relaunch-only port wait and a lock held for the first 1.2 s of each health wait, now fail B5 3/3 on the candidate as on base 087cd7138; lock held only during back-off sleeps escapes both, so it is not a loss. Clean B5 3/3, 10-class batch 161/161."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-slow-tests-b-r10-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:e39f23ecaf13f44c796177b9c99f806f5cd25cae4e63277ea685f06c8b4511ca",
  "note": "Round 10 at a6fbcee54: the four restored oracles fail 5/5 on the candidate for M1 reloads 600/900 ms after a foreign unload (base also 5/5), M2 a reconfigure drain ending after 500 ms (base 5/5), M3 an expiry that interrupts the worker (base 5/5) and M4 a leaked index root lock (candidate only; base sleeps), the item-4 helper passes six real-filesystem fault scenarios 5/5, B5 and F1 samples still fail 5/5, clean classes pass 5/5 with medians B8 8.67 s vs 10.22 s base, B9 3.01 vs 6.89, executor 32.61 vs 32.39, rich document 8.93 vs 10.87, and all 22 relabelled sources are delegated choices within the quoted grant."
}
```
