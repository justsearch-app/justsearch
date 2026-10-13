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
      "whatMoved": "Fixture cleanup records creation instants at process startup and checks them before terminating; the seam accepts an injected child control so the session and candidate share the same real ownership boundary. Existing test inputs and oracles are retained."
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
      "behaviour": "Replacing JDK launch with suspended native launch preserves UTF-8 pipe bytes, Unicode and quoted/trailing-backslash arguments, overridden environment, working directory, separate stderr, and NUL-argument refusal. A start whose registration fails after the suspended chat root exists, and a contained launch whose job assignment fails after creation, leave no process behind, as the JDK launch of the same command leaves none.",
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
        "modules/app-services/src/test/java/io/justsearch/app/api/runtime/child/ChatLaunchCompatibilityTest.java#aFailureBeforeResumeLeavesNoProcessBehind",
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
      "whatMoved": "The stand-in llama-server gains an opt-in --spawn-lingerer behaviour: once serving it starts one more process that records its own PID and creation instant and exits on a signal file or after 90 s, with lookup and release helpers that act only on those recorded identities. No existing behaviour, input or oracle of the fixture changes."
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
