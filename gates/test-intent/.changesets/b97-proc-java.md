---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b97-proc-java
---

# Named limits

Accepted without change (round 4):

- A failed self-test with a survivor takes about 12-14 s, because it waits out the stop timeout before ending what the self-test server left running.
- At a clean quit, leftovers of an already-exited chat root are ended by the Engine's job without a per-member record.

# Test-intent entries

Signoff repair of entry 6: launch/registration preservation and the new job-assignment fault
injection are declared separately below. The preservation run uses a164833f5 plus an add-only
createChat declaration needed solely to compile the unselected job-assignment test method.
The two selected checks never call it; it throws if reached and cannot decide their outcomes.
The job-assignment check stays candidate-only for the concrete API reason in its entry.
The initial unpatched parent replay and native PID/creation-time logs are retained in
F:/phaseb-fix3-b6d536-20261015.

Before-state replay repair: the native ProcessTerminator.open(ProcessHandle) cleanup in the
rejected overlays was already present at d393938e8, but neither a164833f5 nor d7bbe9600 has that
overload. The post-acceptance change to SlowStartFakeLlamaServer only shortened enum references;
HealthyFakeLlamaServer and ProcessTerminator were unchanged. Restore the PID/creation-instant
checked JDK cleanup from the original evidence trees (7de00fd963 for SlowStart and 8d423e994f
for Healthy; also present at d7bbe9600 and f40ba7da8 respectively), retaining later fixture
inputs and serving behavior. This needs no
before-patch and leaves every test assertion intact. Regenerated evidence records the actual
before assertion failures and candidate results; acceptance remains the verifier's decision.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SynchronousGpuReleaseTest.java"
      ],
      "class": "New",
      "behaviour": "A synchronous exit continuation sees a running GPU release producer, with recovered release recorded before replacement spawn. A release poll still pending when the bounded wait ends (held at its ledger record, since adapter readings are bounded themselves) refuses spawning with a reason, then permits retry after release.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Retain observed descendant identities after a self-test wrapper exits; refuse chat while a survivor or unreadable enumeration remains; start GPU release polling before synchronous exit callbacks, and refuse a GPU start if its bounded wait ends while the poll is still pending. Completed timeout/unmeasured polls keep their existing recorded outcomes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SynchronousGpuReleaseTest.java#synchronousExitContinuationStartsReplacementAfterGpuRelease",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SynchronousGpuReleaseTest.java#pendingGpuReleaseTimeoutRefusesSpawnAndAllowsRetryAfterRelease"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "Unreadable descendant enumeration throws instead of certifying an empty snapshot.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Retain observed descendant identities after a self-test wrapper exits; refuse chat while a survivor or unreadable enumeration remains; start GPU release polling before synchronous exit callbacks, and refuse a GPU start if its bounded wait ends while the poll is still pending. Completed timeout/unmeasured polls keep their existing recorded outcomes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-selftest-enumeration.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java#unreadableDescendantsAreNotAnEmptySuccessfulSnapshot"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlHarness.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ManagedChatSeam.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "signature"
      ],
      "whatMoved": "Fixture cleanup records creation instants at process startup and checks them before terminating; the seam accepts an injected child control so the session and candidate share the same real ownership boundary. Existing test inputs and oracles are retained. Before-state replay restores SlowStartFakeLlamaServer's original PID/creation-instant checked JDK cleanup from d7bbe9600, avoiding the later ProcessTerminator.open(ProcessHandle) API absent at a164833f5. Later serving behavior, inputs and every assertion are retained."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java"
      ],
      "class": "New",
      "behaviour": "The real Windows control with an idle chat-kind child adds no more than 100 ms of JVM CPU over ten seconds compared with a non-chat child. A later admission retries a transient descendant-listing failure and clears the refusal once the descendants are verified gone, without restarting the control. A wrapper that exits at once after spawning its server leaves that actual serving descendant in the chat scope in all twenty trials. Self-test cleanup rechecks the retained scope after the earlier snapshot drains and rejects a later live member.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-2 process accounting repairs.",
          "choice": "Use the existing Windows Engine FFM layer to assign a suspended chat root to its own nested job before any instruction can spawn descendants. Query membership only on demand; idle chat adds at most one percent of a core. Retry transient verification failures on later admission or cleanup, retain unresolved ownership, and distinguish confirmed root exit with unverified descendants from actual survival. Recheck the retained scope after an earlier descendant snapshot drains, before declaring self-test success. Preserve launch pipes, arguments, environment, working directory, and rejection of NUL arguments.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round2.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#idleChatControlAddsLessThanOnePercentOfACore",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#laterAdmissionRetriesAListingFailureAndClearsTheBlock",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#immediateWrapperExitRetainsTheActualSurvivorEveryTime",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#selfTestChecksMembershipAfterObservedDescendantsDrain"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaStopVerificationTest.java"
      ],
      "class": "New",
      "behaviour": "Confirmed root exit with unreadable descendants reports exited, descendants unverified, retains the child for retry, and clears ownership after a later stop verifies the descendants gone.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-2 process accounting repairs.",
          "choice": "Use the existing Windows Engine FFM layer to assign a suspended chat root to its own nested job before any instruction can spawn descendants. Query membership only on demand; idle chat adds at most one percent of a core. Retry transient verification failures on later admission or cleanup, retain unresolved ownership, and distinguish confirmed root exit with unverified descendants from actual survival. Recheck the retained scope after an earlier descendant snapshot drains, before declaring self-test success. Preserve launch pipes, arguments, environment, working directory, and rejection of NUL arguments.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round2.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaStopVerificationTest.java#exitedRootWithUnreadableDescendantsIsUnverifiedAndLaterStopRecovers"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java"
      ],
      "class": "New",
      "behaviour": "Replacing JDK launch with suspended native launch preserves UTF-8 pipe bytes, Unicode and quoted/trailing-backslash arguments, overridden environment, working directory, separate stderr, and NUL-argument refusal. aFailureBeforeResumeLeavesNoProcessBehind injects a registry failure after process creation for EXTRACTION and LLAMA_SERVER, asserts start throws IOException and registration saw a process, and checks that exact PID and creation instant is gone within five seconds. These are preservation checks on the a164833f5 JDK path and on the candidate suspended-chat path; the before-state has no suspended-chat path.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-2 process accounting repairs.",
          "choice": "Use the existing Windows Engine FFM layer to assign a suspended chat root to its own nested job before any instruction can spawn descendants. Query membership only on demand; idle chat adds at most one percent of a core. Retry transient verification failures on later admission or cleanup, retain unresolved ownership, and distinguish confirmed root exit with unverified descendants from actual survival. Recheck the retained scope after an earlier descendant snapshot drains, before declaring self-test success. Preserve launch pipes, arguments, environment, working directory, and rejection of NUL arguments.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java#chatLaunchPreservesPipesArgumentsEnvironmentAndWorkingDirectory",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java#aFailureBeforeResumeLeavesNoProcessBehind"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java"
      ],
      "class": "New",
      "behaviour": "aFailureJoiningTheJobLeavesNoProcessBehind calls WindowsEngineJob.createChat directly, injects IOException at AssignProcessToJobObject after CreateProcessW, checks the injected failure is returned, confirms the created PID and creation instant were captured, and checks that exact suspended process is gone within five seconds. Candidate-only. before-state not reproducible: a164833f5 WindowsEngineJob has neither createChat nor the nested Kernel used to inject the post-creation job-assignment failure; it only joins the current Engine to its own job. DefaultChildControl starts chat via ProcessBuilder.start, so no suspended chat process joins a per-chat job. Adding that launch/assignment path or a reached replacement would determine the check outcome.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-2 process accounting repairs.",
          "choice": "Use the existing Windows Engine FFM layer to assign a suspended chat root to its own nested job before any instruction can spawn descendants. Query membership only on demand; idle chat adds at most one percent of a core. Retry transient verification failures on later admission or cleanup, retain unresolved ownership, and distinguish confirmed root exit with unverified descendants from actual survival. Recheck the retained scope after an earlier descendant snapshot drains, before declaring self-test success. Preserve launch pipes, arguments, environment, working directory, and rejection of NUL arguments.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-job-assignment-candidate.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java#aFailureJoiningTheJobLeavesNoProcessBehind"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "A Windows chat launch now starts through WindowsEngineJob.createChat (suspended, inside its own job) instead of ProcessBuilder.start(). oneShotRecoveryHealthFailureCannotRelaunchFallbackLadders hands back the same pre-started sleeping child at that seam as well: a static stub returns a ChatProcess mock that forwards every Process method to that child. The ProcessBuilder construction stub and its one-construction assertion, the inputs, the killing registry and every expected outcome are unchanged; on a platform or revision without the contained launch the stub is never reached."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/GpuSwitchBarrierTest.java"
      ],
      "class": "New",
      "behaviour": "Twenty model switches (stop, then start at once) on an adapter whose memory never shows as released, with real sleeps: no start is refused, and each start is spawned only after the previous server's gpu-release record.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-3 repairs of the per-server job containment.",
          "choice": "A GPU start waits for every pending release poll to finish, bounded by the poll maximum plus one sample interval and one second, and is refused only when a poll is still running after that. What an exited chat root left running in its own Windows job is ended through that job (kill-on-close set on the nested job, then the only handle to it closed), after a failed self-test, after an intended stop and before a crash-recovery or any later chat start; each ended member is recorded first in the exit ledger as intended_cleanup:<reason>:job_member:<pid>@<start>. Off Windows nothing can be ended and the next chat start keeps refusing beside it. A contained chat launch builds the command line by java.lang.ProcessImpl's Windows rules and names no application, so a .cmd or .bat runs through cmd.exe exactly as under the JDK. A membership read retries a lagging job count for up to 2 s.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/GpuSwitchBarrierTest.java#aSwitchStartWaitsForTheReleasePollEveryTime"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "A self-test whose wrapper exits while its serving descendant lives still fails with \"still running\". On Windows that survivor, in the candidate's own job, is then ended and recorded as intended_cleanup:SELF_TEST:job_member:<pid>@<start>, and after the failed self-test the main server loads without an Engine restart. Off Windows the survivor keeps running and the main start is refused beside it, with the reason in status, until it exits.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-3 repairs of the per-server job containment.",
          "choice": "A GPU start waits for every pending release poll to finish, bounded by the poll maximum plus one sample interval and one second, and is refused only when a poll is still running after that. What an exited chat root left running in its own Windows job is ended through that job (kill-on-close set on the nested job, then the only handle to it closed), after a failed self-test, after an intended stop and before a crash-recovery or any later chat start; each ended member is recorded first in the exit ledger as intended_cleanup:<reason>:job_member:<pid>@<start>. Off Windows nothing can be ended and the next chat start keeps refusing beside it. A contained chat launch builds the command line by java.lang.ProcessImpl's Windows rules and names no application, so a .cmd or .bat runs through cmd.exe exactly as under the JDK. A membership read retries a lagging job count for up to 2 s.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java#exitedSelfTestWrapperWithLiveDescendantCannotPass",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestProcessOwnershipTest.java#selfTestDescendantSurvivalBlocksMainReload"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashLeftoverTest.java"
      ],
      "class": "New",
      "behaviour": "After ten minutes of READY, a crashed llama-server that left a process in its own job is relaunched within 8 s (not after the 12 s stop wait, and not refused); the leftover process is ended and recorded once as an intended cleanup.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-3 repairs of the per-server job containment.",
          "choice": "A GPU start waits for every pending release poll to finish, bounded by the poll maximum plus one sample interval and one second, and is refused only when a poll is still running after that. What an exited chat root left running in its own Windows job is ended through that job (kill-on-close set on the nested job, then the only handle to it closed), after a failed self-test, after an intended stop and before a crash-recovery or any later chat start; each ended member is recorded first in the exit ledger as intended_cleanup:<reason>:job_member:<pid>@<start>. Off Windows nothing can be ended and the next chat start keeps refusing beside it. A contained chat launch builds the command line by java.lang.ProcessImpl's Windows rules and names no application, so a .cmd or .bat runs through cmd.exe exactly as under the JDK. A membership read retries a lagging job count for up to 2 s.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round3.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashLeftoverTest.java#aCrashRestartEndsWhatTheCrashedServerLeftInItsJob"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java"
      ],
      "class": "New",
      "behaviour": "A chat root that leaves a short-lived process in its job, read while the job count lags each exit by a second, settles to an empty membership instead of failing as a changed membership.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-3 repairs of the per-server job containment.",
          "choice": "A GPU start waits for every pending release poll to finish, bounded by the poll maximum plus one sample interval and one second, and is refused only when a poll is still running after that. What an exited chat root left running in its own Windows job is ended through that job (kill-on-close set on the nested job, then the only handle to it closed), after a failed self-test, after an intended stop and before a crash-recovery or any later chat start; each ended member is recorded first in the exit ledger as intended_cleanup:<reason>:job_member:<pid>@<start>. Off Windows nothing can be ended and the next chat start keeps refusing beside it. A contained chat launch builds the command line by java.lang.ProcessImpl's Windows rules and names no application, so a .cmd or .bat runs through cmd.exe exactly as under the JDK. A membership read retries a lagging job count for up to 2 s.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobAccountingTest.java#membershipSettlesWhileTheJobCountLagsAnExit"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java"
      ],
      "class": "New",
      "behaviour": "A .cmd launcher in a directory with a space sees the same %1 and %* under the contained chat launch as under the JDK launch, for empty, spaced, quoted, embedded-quote, trailing-backslash, percent and separator arguments; an executable receives the same argv for the same arguments.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-3 repairs of the per-server job containment.",
          "choice": "A GPU start waits for every pending release poll to finish, bounded by the poll maximum plus one sample interval and one second, and is refused only when a poll is still running after that. What an exited chat root left running in its own Windows job is ended through that job (kill-on-close set on the nested job, then the only handle to it closed), after a failed self-test, after an intended stop and before a crash-recovery or any later chat start; each ended member is recorded first in the exit ledger as intended_cleanup:<reason>:job_member:<pid>@<start>. Off Windows nothing can be ended and the next chat start keeps refusing beside it. A contained chat launch builds the command line by java.lang.ProcessImpl's Windows rules and names no application, so a .cmd or .bat runs through cmd.exe exactly as under the JDK. A membership read retries a lagging job count for up to 2 s.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java#aCmdLauncherSeesTheSameArgumentsAsUnderTheJdk",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java#embeddedQuotesReachAnExecutableAsUnderTheJdk"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The stand-in llama-server gains an opt-in --spawn-lingerer behaviour: once serving it starts one more process that records its own PID and creation instant and exits on a signal file or after 90 s, with lookup and release helpers that act only on those recorded identities. Before-state replay restores the original PID/creation-instant checked JDK cleanup and crash injection from f40ba7da8, avoiding the later ProcessTerminator.open(ProcessHandle) API absent at d7bbe9600. Later single-slot streaming behavior and every existing fixture input and test assertion are retained."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/GpuReadBoundTest.java"
      ],
      "class": "New",
      "behaviour": "An adapter memory reading that never returns ends the release poll as unmeasured within the GPU start bound, recorded, and the GPU start proceeds; while it still hangs the next switch neither waits on it nor queues behind it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-4 repairs of the per-server job containment.",
          "choice": "Each adapter memory reading is bounded at 2 s: a reading that does not answer ends its release poll as unmeasured (recorded as gpu-release result unmeasured, with a child-note gpu_read_unanswered), and while it still hangs later readings are empty at once, so GPU starts proceed; the GPU start wait grows by the read bound to 8.25 s. When a chat root's leftovers cannot be ended, each member's outcome is recorded after its intent (cleanup_ended:<reason>:job_member:<pid>@<start>, or cleanup_not_ended:...:<cause>), a member not ended keeps its scope held and refusing chat starts until it exits, its own exit is recorded once (leftover_exited:job_member:<pid>@<start>), and the start is refused with \"chat start blocked: could not end leftover chat processes (<native error>)\". A contained chat launch that cannot run fails with the JDK's text, Cannot run program \"<path>\"[ (in directory \"<dir>\")]: CreateProcess error=<n>, <system text>, an argument refused in strict mode with the JDK's text, and a NUL character with the JDK's \"invalid null character in command\"; jdk.lang.Process.allowAmbiguousCommands is read through io.justsearch.configuration.SystemAccess with the JDK's default and strict-mode rules. Named limits, accepted without change: a failed self-test with a survivor takes about 12-14 s because it waits out the stop timeout before ending what it left; at a clean quit, leftovers of an already-exited chat root are ended by the Engine's job without a per-member record.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round4.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/GpuReadBoundTest.java#aReadThatNeverReturnsEndsThePollUnmeasuredAndGpuStartsProceed"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobEndFailureTest.java"
      ],
      "class": "New",
      "behaviour": "When a chat root's job cannot be ended, the refusal reads \"chat start blocked: could not end leftover chat processes (<native error>)\", the ledger records the not-ended outcome after the intent, the leftover stays counted in its scope, and its own later exit is recorded once; a chat start then proceeds.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-4 repairs of the per-server job containment.",
          "choice": "Each adapter memory reading is bounded at 2 s: a reading that does not answer ends its release poll as unmeasured (recorded as gpu-release result unmeasured, with a child-note gpu_read_unanswered), and while it still hangs later readings are empty at once, so GPU starts proceed; the GPU start wait grows by the read bound to 8.25 s. When a chat root's leftovers cannot be ended, each member's outcome is recorded after its intent (cleanup_ended:<reason>:job_member:<pid>@<start>, or cleanup_not_ended:...:<cause>), a member not ended keeps its scope held and refusing chat starts until it exits, its own exit is recorded once (leftover_exited:job_member:<pid>@<start>), and the start is refused with \"chat start blocked: could not end leftover chat processes (<native error>)\". A contained chat launch that cannot run fails with the JDK's text, Cannot run program \"<path>\"[ (in directory \"<dir>\")]: CreateProcess error=<n>, <system text>, an argument refused in strict mode with the JDK's text, and a NUL character with the JDK's \"invalid null character in command\"; jdk.lang.Process.allowAmbiguousCommands is read through io.justsearch.configuration.SystemAccess with the JDK's default and strict-mode rules. Named limits, accepted without change: a failed self-test with a survivor takes about 12-14 s because it waits out the stop timeout before ending what it left; at a clean quit, leftovers of an already-exited chat root are ended by the Engine's job without a per-member record.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round4.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatJobEndFailureTest.java#aLeftoverThatCannotBeEndedIsRecordedCountedAndItsExitRecorded"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchFailureTextTest.java"
      ],
      "class": "New",
      "behaviour": "A contained chat launch of a missing executable, with and without a working directory, and one with a missing working directory, fails with exactly the JDK's text for the same command. With jdk.lang.Process.allowAmbiguousCommands=false a .cmd launcher sees what it sees under the JDK and an argument the JDK refuses is refused with the JDK's text.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting), including round-4 repairs of the per-server job containment.",
          "choice": "Each adapter memory reading is bounded at 2 s: a reading that does not answer ends its release poll as unmeasured (recorded as gpu-release result unmeasured, with a child-note gpu_read_unanswered), and while it still hangs later readings are empty at once, so GPU starts proceed; the GPU start wait grows by the read bound to 8.25 s. When a chat root's leftovers cannot be ended, each member's outcome is recorded after its intent (cleanup_ended:<reason>:job_member:<pid>@<start>, or cleanup_not_ended:...:<cause>), a member not ended keeps its scope held and refusing chat starts until it exits, its own exit is recorded once (leftover_exited:job_member:<pid>@<start>), and the start is refused with \"chat start blocked: could not end leftover chat processes (<native error>)\". A contained chat launch that cannot run fails with the JDK's text, Cannot run program \"<path>\"[ (in directory \"<dir>\")]: CreateProcess error=<n>, <system text>, an argument refused in strict mode with the JDK's text, and a NUL character with the JDK's \"invalid null character in command\"; jdk.lang.Process.allowAmbiguousCommands is read through io.justsearch.configuration.SystemAccess with the JDK's default and strict-mode rules. Named limits, accepted without change: a failed self-test with a survivor takes about 12-14 s because it waits out the stop timeout before ending what it left; at a clean quit, leftovers of an already-exited chat root are ended by the Engine's job without a per-member record.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b97-proc-java-round4.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchFailureTextTest.java#aMissingExecutableFailsWithTheJdksText",
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchFailureTextTest.java#strictModeLaunchesAndRefusesAsTheJdkDoes"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-procjava3-20261013",
  "verdict": "accept",
  "digest": "sha256:337eb77280de9b292e65b75aab0789d8c8223912a164ff3e02ad6782be96781a",
  "note": "VC-procjava3 (independent Claude verifier) over two rounds on 1eb01f51e vs a164833f5 (rejected f40ba7da8 for SystemAccessFunnelTest): funnel test and all 39 ArchUnit classes pass; app-api 383, app-inference 487, app-services 3701 pass, PMD clean; new checks fail on f40ba7da8 for their reason (GPU release pending 6250 ms, raw native message, CreateProcessW text); adapted SynchronousGpuReleaseTest still checks a pending poll refuses then retry succeeds (fails on a164833f5); real processes: self-test with surviving server records intended_cleanup then cleanup_ended and the main start succeeds (12.4 s early variant), crash leftover ended, job listing failure refuses starts with chat start blocked and recovers, Engine death ends all helpers in 5 s incl. an unregistered suspended child, 0 ms idle CPU over 35 s, launch parity 34/34 incl. strict mode, job end failure keeps the member counted then records leftover_exited, launch failure text identical to the JDK in 5 cases, a never-returning VRAM read ends the poll unmeasured after 2.25 s. Named limits: failed self-test with survivor 12-14 s; no per-member records at clean quit; a VRAM read that never returns leaves later polls unmeasured for the process lifetime. Unverified: live self-test, model switch and GPU release on the real model (stack held by another session). Digest bound with --base a164833f5, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "reject",
  "digest": "sha256:94d4ad78ce85207887f377dea6312d593105c08025251e228fe8ee202d643122",
  "note": "Entry 6 has candidate-only preservation evidence with no stated before-state reason for aFailureBeforeResumeLeavesNoProcessBehind and aFailureJoiningTheJobLeavesNoProcessBehind. The archived before XML proves only the original pipe/argument case; it does not cover these two later checks."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3b-20261015",
  "verdict": "accept",
  "digest": "sha256:be756e5344de0b5f085061734838985b2135a746b11c843bb2db0820c9820752",
  "note": "All three corrected checks pass; both preservation checks also pass at a164833f5 with the declared add-only createChat stub, which their paths cannot reach. The job-assignment candidate-only reason is true, declarations match assertions, and remaining entries/items/evidence are unchanged since 9f1f6257b; bound to main."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5c-20261016",
  "verdict": "reject",
  "digest": "sha256:c1150c75251662777b20800c412e659bea4b0bffe306994bc9fcef42f62ebba6",
  "note": "Re-bound to origin/main 298354254; the declared a164833f5 self-test enumeration before replay with its three overlays and no patch fails compilation at SlowStartFakeLlamaServer.java:71 (ProcessHandle cannot be converted to Process), so unreadableDescendantsAreNotAnEmptySuccessfulSnapshot executes zero cases instead of the declared assertion failure. The candidate checks pass and both synchronous GPU before failures match, but this rerun mismatch requires reject."
}
```
