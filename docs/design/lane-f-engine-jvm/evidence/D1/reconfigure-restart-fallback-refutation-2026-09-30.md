# D1 reconfigure: restart-fallback refutation (2026-09-30)

Refute-first review by Sol (gpt-6.1-sol) at f80fd1f69 of a requested-restart fallback for in-place query reconfigure. Verbatim output.

**Verdict: SAFE-BUT-VIOLATES-DESIGN**, assuming the routing safeguards below. B avoids overlapping GPU allocations, but cannot satisfy the merge’s current acceptance clauses. Static review of `f80fd1f` against `origin/main` `ac1c93b`; no edits, tests, Gradle, or backend.

1. **Main did not live-reload rerankers.** On `origin/main`, ordinary settings save persisted and rebuilt Head config (`SettingsController.java:137–138`); its `SettingsV2.java:11` exposed no query-model paths. Install AI wrote reranker/citation paths, saved settings, then restarted the **Worker** (`AiInstallService.java:1883`, `:1922`, `:1139`, `:2125`). Worker restart stopped A before spawning B, while Head/API stayed alive (`WorkerSpawner.java:264–286`). Manual recovery also restarted Worker (`InferenceHandlers.java:666`). `/api/inference/reload` applied **LLM** model/context/GPU-layer overrides, not ONNX encoders (`InferenceHandlers.java:558–564`).

   B matches main’s avoidance of simultaneous A/B allocations, **but is worse for API continuity**: it restarts the entire Engine.

2. **The restart machinery exists; encoder routing does not.** Current [SettingsCommitCoordinator.java:707](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java:707) derives restart from `changedKeys.restartRequired()`; API port is the only permitted restart-required settings key. Encoder changes instead require prepared physical composition and selection at `:741–754`. `:389` is the installer-generation branch; ordinary settings add `restartScheduled` at `:759`.

   Smallest credible implementation:
   
   - KnowledgeServer decides memory mode using **query-only** candidate footprint and actual releasable query GPU ownership; preserve `REFUSED`.
   - Return a typed deferred-restart preparation through EngineRoot/composer, carrying B’s exact model/tokenizer selection.
   - Coordinator persists B’s desired settings/selection, retains A’s serving query config and applied observation, then returns `COMPLETE + restartScheduled`.
   - Reuse terminal scheduling at `:909–928`.

   Estimate: **150–250 production lines**, plus regressions; no supervisor rewrite. The webview currently parses but does not specifically display `restartScheduled` ([settingsAttempt.ts:143](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/ui-web/src/api/settingsAttempt.ts:143)); desktop restart recovery invalidates the token and reloads the window ([backendRestart.ts:35](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/ui-web/src/api/backendRestart.ts:35), `desktopRecovery.ts:31`).

3. **B explicitly violates acceptance—not merely mechanism preference.** B loses API/SSE/MCP connections through shutdown and successor startup. Requested exit is uncounted, without cooldown ramp ([engine-supervisor.cjs:154](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/scripts/dev/lib/engine-supervisor.cjs:154)). A preserves API/text search while query capability reloads.

   Binding clauses:
   
   - **D1-4:** “All fallible preparation and composition precedes the settings-file commitment point.” [D1.md:372](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/stages/D1.md:372)
   - **D1-14 acceptance:** “in place chosen when it does not, `RELOADING` reported and a text search answering”; rejection must recompose A. [D1.md:2524](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/stages/D1.md:2524)
   - **D1-16:** requires “reconfigure beside and in place under the ceiling key.” [D1.md:3150](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/stages/D1.md:3150)
   - **§7.4:** “Only success persists”; failed composition keeps A applied so no boot inherits an uncomposable version. [design.md:1376](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/design.md:1376)
   - **§16:** requires “zero API connection drops”. [design.md:2312](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/design.md:2312)

   D1-12’s acceptance alone mainly tests generation ownership and leases; it is not the decisive prohibition.

4. **Failure/recovery limits.**
   
   - **No inherent free restart loop:** receipt replay does not reschedule (`SettingsCommitCoordinatorTest.java:1015–1019`, inspected, not run). Successor faults use counted/exhausting supervision, not requested exit (`engine-supervisor.cjs:169–193`).
   - **B still failing:** CUDA initialization failure can use CPU fallback with B’s selected model ([NativeSessionHandle.java:1017](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java:1017)). Broader reranker composition failure leaves the role unavailable (`InferenceCompositionRoot.java:1018–1022`); boot does **not restore A**.
   - **Witness trap:** skipping composition while retaining the old selection can boot **A despite B’s settings**. Persist B’s exact selection; boot consumes it (`KnowledgeServer.java:4097–4118`). Never publish B as physically applied while A serves.
   - **Crash after commit:** the exact revision/key witness reconciles interrupted ordinary reconfigure to `COMPLETE` (`SettingsCommitCoordinator.java:1009–1031`); successor boot reads committed selection. That proves commitment, **not successful B composition**.

5. **Required addition:** typed deferred preparation, exact B selection, truthful A-serving/B-desired publication, and bounded-failure regressions make the restart fallback operationally safe. They do **not** satisfy the locked design. Preserving its failed-B→A guarantee additionally requires durable rollback/recovery; merging B requires an explicit design and acceptance amendment.