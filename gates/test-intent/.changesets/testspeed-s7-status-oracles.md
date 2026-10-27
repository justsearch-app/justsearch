---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: 01a17c3e-79d7-7bd1-be46-42bcf06ba7b2
---

# Test-intent entries

S7 is test-only, on unchanged discovery. Adaptation entries cover retained oracles;
New entries cover source-identified oracle weaknesses under the owner's delegation
(agent-adopted scenarios, not owner-authored expectations). No reproduced product
failure or production repair is claimed; every New entry has repaired=false.
Delegation quotes and locations are copied verbatim from testspeed-b2-clock.md.

Supplied independent verification: 144 executions passed before and after, preserving
the 96 family cases and 45 inherited execution identities. Seven publication faults
failed the intended candidate assertions; the baseline accepted four. Three fixture
setup-failure cleanup checks failed before and passed after. These facts do not replace
the generated evidence file, which the orchestrator will write. The four named methods
include all parameterized invocations; the evidence must record execution without skips.

Production and HTTP budgets are unchanged. Fixture quiescence adds a 10-second deadline.
Summed case time rose from 195 to 230 s; peak used heap rose from 200 to 235 MiB with
the unchanged 512 MiB heap. No speed improvement or suite/CI saving is claimed.
No assertion is weakened and no execution is removed; stronger Worker source/value
checks replace the broad root-timestamp-advanced oracle. Shared exceptional async
cleanup remains outside S7 and is not claimed as a passing preservation check.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusFixtureWork.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "Move ad hoc sampler launching and release-gate/thread ownership bookkeeping from the three HTTP fixtures into StatusFixtureWork.startSampler and its owned collections. Route status calls through work.handleStatus to the real handler methods; decorate the real observation map, refresh/refreshProbe producers and sampler entry to capture their actual futures, source threads and publications. Keep the real lane factories, empty-map boot fallback, clocks, admission decisions, coalescing and original publication callbacks; no extra sample or observation-future dependent is introduced. Move GPU first-sample synchronization from an additional projection.sampleFirst call to joining the actual background owner. Record consumed Worker input and real HTTP start/end times without changing requests. Existing inputs outside the New entries, failure/hold positions, status/body/latency assertions, 100 fresh reads, six sampler passes, dependent/release bounds, thread censuses, tags and execution identities stay. Stronger preconditions, value/time assertions, quiescence and cleanup expectations are New below."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Resolve reflected handler fields against StatusLifecycleHandler rather than the instrumentation subclass. In failingConversationIsUnknownAndRecoveryReportsNewState and freshImmediatelyReflectsConversationAction, drain already submitted work before changing the next source input; retain unlocked/unknown/locked recovery and the immediate fresh post-action response. Register registry-lock holder identity and propagate its thread failures while preserving lock entry, the cached/fresh HTTP checks and the 3000 ms exit assertion. No supplier variant, inherited execution or tag is removed; the matrix expectation changes, including replacement of the assumption skip, are New below."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Insert owned-work drains after releasing the long hang and after the explicit recovery sample; no additional sample is scheduled by a drain. Keep the hang loop, real duration, fresh requests, thread bounds, recovered Worker assertion and final 1200 ms thread-clear observation. The new quiescence obligation is separate below; no execution, tag or condition changes."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Register the three additional-producer gates and source threads with the inherited fixture; drain submitted publications before the hold and after release/recovery. Keep actual producer entry, unknown encryption, independent Worker/AI/telemetry freshness and recovery assertions. In olderPendingFailureCannotOverrideQueuedHealthyConversation, retain replacement-before-release ordering, the old failure injection, unknown state, one failed call, all recovery polls and five stability reads; drain only after release. Cleanup records secondary failures without replacing a primary assertion. No parameter, inherited execution, tag or condition is removed."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Drain released old work before the existing explicit recovery sample, then drain that sample before the fresh recovery read. Keep the HEAD revision observations, pure-age versus publication distinction, Worker recovery assertion and existing counter comparisons; a drain schedules no sample. No execution, tag or condition changes."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Require every mandatory supplier/dependency to enter its held phase. Replace the eleven-supplier assumption skip with a failing entry assertion and typed reachable prerequisites, including available NVML for RRD and an unloaded reranker for the GPL-coordinator branch. Require all four lifecycle consumers to enter for lifecycle/bulk-gate holds. Retain all eleven supplier and all 24 blocked-dependency variants.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3: positively prove every mandatory supplier is reached; an assumption skip or an unreachable null fixture is not isolation evidence.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3: positively prove every mandatory supplier is reached; an assumption skip or an unreachable null fixture is not isolation evidence.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "Before holding a source, drain submitted work and require a real typed, nonfailed, non-stale publication at the exact supplied source timestamp. Capture that Sample identity and the known public value before aging; Worker recovery starts from documents=10 and an explicitly fresh Worker stamp. RRD starts from positively available GPU data.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5: establish a known source-identified publication before the hold; finish the publication witness before the age-under-test interval.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5: establish a known source-identified publication before the hold; finish the publication witness before the age-under-test interval.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "Validate supported published busy/idle/cache periods and effective cadence between one and four periods. Age each held publication beyond THREE of its actual effective cadences, retaining the exact old Sample through unrelated sampling. Replace the 1200 ms recovery pause and assumed 31001 ms age. Lock variants make no new sampler pass after aging.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3/S5: age past the actual held lane effective cadence, positively retain the held publication and preserve the separate cached lock-case contract.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3/S5: age past the actual held lane effective cadence, positively retain the held publication and preserve the separate cached lock-case contract.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "Positively assert stale-before-recovery at the real HTTP boundary and in the held lane. Check each surface freshness flag, staleSources entry or explicit unknown/unavailable sentinel while retaining known old values where the wire contract does so. Non-lock isolation requires fresh, successful unrelated Worker/registry publications (and applicable disk/conversation publications), plus READY AI. Lock cases retain cached Worker/AI staleness and UNKNOWN encryption. Recovery clears the relevant freshness indicator.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3: require honest stale reporting before recovery and fresh unrelated owners only in non-lock isolation; UNKNOWN or stale=false cannot fabricate success.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3: require honest stale reporting before recovery and fresh unrelated owners only in non-lock isolation; UNKNOWN or stale=false cannot fabricate success.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "After release, drain old calls and queued successors before changing input. Require a distinct recovery publication with the exact source timestamp, successful typed value, an actual new source invocation and consumption of the changed input. Check the changed public value for every supplier/dependency; observe intentional GPL absence through a consumed unloaded/completed input and READY/not_configured, and registry recovery through the consumed owner subscription transition. Worker recovery must publish documents=11 and a newer Worker epoch.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5: recovery needs changed source content and fresh publication identity after old work ends; a fresh stamp over retained content is insufficient.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5: recovery needs changed source content and fresh publication identity after old work ends; a fresh stamp over retained content is insufficient.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "After Worker11 recovery, supply distinct Worker12 input, advance its source clock once and request fresh status. Bounded existing polling must observe documents=12 together with that exact Worker timestamp, an additional source invocation and the exact consumed input. This replaces the weaker root observed_at inequality; a new HTTP projection alone cannot satisfy the fresh-read assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5: positively witness the fresh request own new Worker value and source timestamp, rather than any changed wrapper observation time.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5: positively witness the fresh request own new Worker value and source timestamp, rather than any changed wrapper observation time.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java"
      ],
      "class": "New",
      "behaviour": "Assert known/held/recovered supplier values and canonical source identities: inference Healthy/Recovering with CHAT; conversation locked/unlocked; telemetry one/two flush failures and disk-space degradation; lifecycle game-mode removal; NVML utilization 37/42; RRD trends 37.5/42.5; VDU circuit_open/missing_mmproj and processing true/false; measured GPU profile P12/P16_PLUS; loaded/unloaded reranker and FAILED/COMPLETED coordinator. Compare direct fields and GPL readiness with the exact captured typed publication, and companion state/reason/source/observedAt and lane age with independently specified expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5/S8: supplier isolation and recovery must retain actual source/value/time provenance, including known recovery to intentional absence, rather than merely clear stale flags.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S5/S8: supplier isolation and recovery must retain actual source/value/time provenance, including known recovery to intentional absence, rather than merely clear stale flags.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "class": "New",
      "behaviour": "For all 24 blocked-dependency variants, assert concrete known/held/recovered public values and typed publication identity. Cover disk OK/WARNING, inference, NVML/RRD values, GPL loaded/not_configured, conversation, telemetry, VDU/vision, measured device plan, game mode, Worker10/11, registry READY/FAILED, agent7/8, saturation, CPU install-contract contents, AC/BATTERY and resume state. GPU-memory preserves the supplied adapter/size/timestamp and age; encryption preserves fixture-volume/HIGH and the independent reading probedAtMs, with ENCRYPTED before hold, UNKNOWN while held and NOT_ENCRYPTED on at-rest recovery. Capture companion source/state/reason/time rather than deriving expectations from production output.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3/S5: every blocked-dependency variant must expose the intended typed source value and provenance across known, held and recovered phases; preserve all 24 HTTP variants and their lock-specific expectations.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/S3/S5: every blocked-dependency variant must expose the intended typed source value and provenance across known, held and recovered phases; preserve all 24 HTTP variants and their lock-specific expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "Separate injected sampling/age time from real projection and transition time. Worker meta/indexing sampledAt retains the source epoch; contact-loss components retain the last successful epoch and bound real-response age plus applicable companion age. Fresh readiness companions retain canonical sources, real projection-window observedAt and their own lane age, including legitimate zero-age stale projections. Chat sampledAt equals the real HTTP projection observed_at; chat since retains the generative owner transition across representations. Indexing since survives unchanged reasons despite fake-clock aging and changes within real assembly time only for a changed lifecycle/power reason. GPU/encryption reading timestamps must not become wrapper time.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S1/S7/S5/S8: distinguish source sampling/age, real projection/transition time and event provenance; reject fabricated source times and preserve owner transition identity.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S1/S7/S5/S8: distinguish source sampling/age, real projection/transition time and event provenance; reject fabricated source times and preserve owner transition identity.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusFixtureWork.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "Require explicit closure of actual submitted work without scheduling another sample: admitted boot/fresh dispatches and requested GPU first sample must enter; join owned samplers/dispatches/source threads and await raw then derived publication futures, excluding deliberately held lanes. Fail unexpected fixture-thread errors and incomplete closure. Add a 10-second quiescence deadline (also used for GPU first-sample entry/join); HTTP/client/production budgets remain unchanged. Clear only this fixture Mockito instrumentation after proven quiescence and resource cleanup.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S5/X4: require explicit owned-work quiescence instead of an arbitrary settle and retain observable failures without touching another owner resources.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S5/X4: require explicit owned-work quiescence instead of an arbitrary settle and retain observable failures without touching another owner resources.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusFixtureWork.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "Protect partial fixture construction as well as normal/failed test teardown: stop fixture ingress, release owned gates, drain work, attempt server/client/GPU-reader/component cleanup and preserve primary failures with cleanup failures suppressed. Await the status monitor test executors on close. For the model contract use an owned temporary home and restore only the installed ConfigStore identity, deleting files only after consumers quiesce. Attempt every admission observer/work-handle close even if a pending response fails. The supplied independent verification found three setup-failure cleanup checks failing before and passing on the candidate; the named existing methods exercise these fixture lifecycles normally, not those injected setup failures.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/X4/S5/S6: strengthen test-fixture setup-failure and teardown cleanup, preserving owned resources and failure evidence; this does not repair or claim preservation of the separate broken shared exceptional async cleanup.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; S7 strengthens status-test preconditions, publication/provenance oracles and owned fixture cleanup on unchanged discovery. Production behavior, public defaults, HTTP budgets, stores and shared exceptional async cleanup are outside this test-only change.",
          "choice": "S7/X4/S5/S6: strengthen test-fixture setup-failure and teardown cleanup, preserving owned resources and failure evidence; this does not repair or claim preservation of the separate broken shared exceptional async cleanup.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-s7-status-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java#recoveryClearsStaleAndFreshEventuallyAdvances",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java#recoveryClearsStaleAndFreshEventuallyAdvances"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17c54-efef-75a0-8a70-e2a36bacdb00",
  "verdict": "accept",
  "digest": "sha256:83e9be2705ce179a5b81d532bdb945029c397c6c38acb859714b87ea766f40e8"
}
```
