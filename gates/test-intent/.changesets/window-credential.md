---
schema: test-intent.v1
task: t-20261102-agent-approval
author-role: builder
author-session: builder-window-credential
---

# Test-intent entries

The controller tests are narrower than the packaged-app scenarios: real-app verification belongs
to the orchestrator. S6's behaviour already held on the base (URL provenance was preserved and no
window credential existed), so its check has no fail-before; it fails if a window request that a
link started were classed as a click (shown by mutation in the builder report). S9 has a separate
source-document regression check in `scripts/sandbox/window-credential-docs.test.mjs` (outside
product test-intent scope).

The new test files are isolated new sources (exempt); their entries are kept so their
fail-before evidence is recorded by the script. Only the Rust test module, the register count and
the add-watched-root risk value (catalog test and operation-wire golden) need entries. The golden
is overlaid onto the before-state so its conformance check compares the base catalog with the new
value. N1 for the raised operation already held on the base, so its check has no fail-before. For Rust fail-before, the compatibility patch exposes only new API shapes, keeps the
old session-only stdout filtering and supplies no window authority; the overlaid
`justsearch-bound-exec` helper is the CI-built native fixture the shell's spawn tests need.

The default-deny evidence uses the evidence runner with a temporary adaptation that creates and
removes a shared clone for the before-state, instead of registering a worktree in the source
repository. Its producer script digest records that adaptation; check execution and result parsing
are unchanged. The before-state is `dcce080acd`, with only the new test source overlaid.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAutonomyBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "S1-S5/S8 refuse token-only authority before consuming pending actions or changing grants, roots, settings, agent gates, autonomy or plugin trust. Window actions and preexisting saved entries remain usable. HTTP callers cannot claim trusted gesture transports; a settings write, which has no pending flow, is refused with WINDOW_CREDENTIAL_REQUIRED instead of running or reporting a recovery failure.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S1: Outside caller approves a pending action (capsule issuance and execute:true, with and without allowAlways), its own or another caller's, with the pending id taken from a response or the event stream: refused; nothing approved, consumed, granted or run; the action stays pending and approvable in the app.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S3: Without a grant that covers it, an outside caller invokes a medium-risk operation (e.g. add a folder, ingest) with no transport header, blank, unknown, or forged BUTTON/RAIL/PALETTE/SYSTEM_INTERNAL and any client header: it does not run unprompted; it becomes a pending action shown in the app (or is refused where the path has no pending flow).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S1: Missing or wrong -> 403 with a clear error code; nothing changes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S5: chat start/resume/fork requests with an autonomyLevel above the default are downgraded to the default without it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N6: Upgrade with existing durable-grants.json and plugin-allowlist.json: entries keep working, files not rewritten.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S2: Outside caller creates an operation or family grant, or approves with allowAlways: refused; durable-grants.json unchanged, also after restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S4: Outside caller adds a folder through POST /api/indexing/roots (the direct path): no watched root added, no scan admitted, nothing persisted after restart, unless approved in the app.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S5: Outside caller calls /api/chat/approve for agent and workflow gates (sessionId given or omitted), POST /api/chat/agent/autonomy, starts/resumes/forks a chat with autonomyLevel above the default, or POST /api/plugins/allowlist: refused or downgraded to the default autonomy; no plugin becomes trusted, also after reload and restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S8: With no app window (closed, not yet connected, disconnected), nothing that needs a person runs: pending actions wait and expire as today; no implicit consent.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N9: Approval stays exact and single use: changed arguments refused; a different operation or preparation substituted for the pending one refused; admission refusal happens before the pending is consumed, so a retry after admission frees still works; duplicate/concurrent approval; 410 after use, expiry after 5 min, nothing after restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N1: In the app: button, rail and palette actions behave as today (medium risk runs, high risk asks typed confirm); approve with and without \"allow always\"; grants list in Settings; revoke; Global Hard Stop.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s1ManifestCallerCannotConsumeExecuteOrPersistApproval",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s2OnlyWindowCreatesOperationOrFamilyGrantsAndOldFilesSurvive",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s3MissingUnknownAndForgedTrustedTransportsStayUntrusted",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s3SettingsWriteWithoutPendingFlowIsRefusedClearlyAndWritesNothing",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s4DirectRootAddIsRefusedBeforeIndexing",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s5AgentWorkflowAutonomyAndPluginTrustNeedTheWindow",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s8NoWindowProofLeavesPendingForExpiryAndRestart",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAutonomyBoundaryTest.java#s5ChatDispatchAndRunCreationDowngradeAutoButKeepWatchAndWindowPreferences",
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAutonomyBoundaryTest.java#s5LegacyAgentEntryAlsoDowngradesBeforeRunning"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "S6: a request from the window that a received link started (URL_DEEPLINK or URL_BAR) keeps URL provenance, MEDIUM source tier and the inline prompt for a medium-risk operation even though it carries the window credential. This already held on the base (no credential existed); the check guards the new credential from turning a link into a click.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S6: A justsearch:// link launched by another process (shell argument or single-instance forwarding) that would change something does not count as a click: it keeps URL provenance and prompts.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S6: whatever the webview does on their behalf keeps URL provenance (send the URL transport explicitly); they must not become BUTTON just because the window credential is attached.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-java-s6.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s6DeepLinksFromTheWindowKeepUrlProvenanceAndStillPrompt"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WatchedRootRiskBoundaryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java",
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json"
      ],
      "class": "New",
      "behaviour": "S4 through the generic operation route: core.add-watched-root is declared MEDIUM risk (it was LOW, which the lattice runs without a prompt at every trust level). A token-only caller invoking it via POST /api/operations/core.add-watched-root/invoke, with no, forged trusted or MCP transport, gets 428 with a pending id and the folder is not added; approving that pending in the app window adds it (N2); the window's own click with no, BUTTON, PALETTE or RAIL transport still adds it with no prompt (N1). The catalog test and the operation-wire golden carry the same one value: risk MEDIUM, confirm None, executors UI and AGENT unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S3: Without a grant that covers it, an outside caller invokes a medium-risk operation (e.g. add a folder, ingest) with no transport header, blank, unknown, or forged BUTTON/RAIL/PALETTE/SYSTEM_INTERNAL and any client header: it does not run unprompted; it becomes a pending action shown in the app (or is refused where the path has no pending flow).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S4: Outside caller adds a folder through POST /api/indexing/roots (the direct path): no watched root added, no scan admitted, nothing persisted after restart, unless approved in the app.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N1: In the app: button, rail and palette actions behave as today (medium risk runs, high risk asks typed confirm); approve with and without \"allow always\"; grants list in Settings; revoke; Global Hard Stop.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-java-watched-root.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WatchedRootRiskBoundaryTest.java#s4OutsideCallerAddingAFolderGetsAPendingAndNothingIsAdded",
        "modules/ui/src/test/java/io/justsearch/ui/api/WatchedRootRiskBoundaryTest.java#n2ApprovingTheOutsideRequestInTheAppAddsTheFolder",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java#addWatchedRootHasMediumRiskNoConfirmUiAndAgent",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationViewConformanceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WatchedRootRiskBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "N1 for the raised operation: a click in the app window (no transport, BUTTON, PALETTE or RAIL, with the window credential) adds the folder through the real lattice and executor with no prompt and no pending. This already held on the base (LOW ran automatically everywhere); the check guards that raising the risk adds no prompt to the person's own click.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N1: In the app: button, rail and palette actions behave as today (medium risk runs, high risk asks typed confirm); approve with and without \"allow always\"; grants list in Settings; revoke; Global Hard Stop.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-java-watched-root-n1.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WatchedRootRiskBoundaryTest.java#n1WindowClickAddsTheFolderWithoutAPrompt"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatWindowCredentialTest.java"
      ],
      "class": "New",
      "behaviour": "S7 keeps both Engine credentials inside the Engine even when an inference server echoes all incoming headers into its API response.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S7: The window credential appears in no manifest or manifest history, log (stdout/stderr, shell log), diagnostics bundle, public discovery response or API response, and is not readable from the Engine's stdout by another process after the shell has consumed it (or the residual is named if it cannot be prevented on Linux).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatWindowCredentialTest.java#s7NeitherEngineCredentialCrossesTheInferenceProxy"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/api/httpWindowCredential.test.ts"
      ],
      "class": "New",
      "behaviour": "Attach the in-memory window credential beside the origin-bound session token; keep URL provenance; rotate both once on refusal and discard obsolete in-flight answers. Strip both credentials on other origins and keep redirect rejection. A deep-link operation sent through the invocation handler carries URL_DEEPLINK beside the credential.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S7: ui-web's single request path (http.ts:268-303, 589-605) attaches it as a header next to the token.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S6: whatever the webview does on their behalf keeps URL provenance (send the URL transport explicitly); they must not become BUTTON just because the window credential is attached.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N5: Engine restart, crash recovery, delayed startup, restart during credential resolution, stale manifest, port reuse: the window credential rotates; the app recovers as it does today for the session token (one retry, bounded wait; no promise of no visible error); an old credential is refused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-ui.json",
      "newChecks": [
        "modules/ui-web/src/api/httpWindowCredential.test.ts#sends the window credential only beside its own origin token and preserves URL provenance",
        "modules/ui-web/src/api/httpWindowCredential.test.ts#rotates both credentials once on window refusal and never loops",
        "modules/ui-web/src/api/httpWindowCredential.test.ts#discards an in-flight credential answer across restart",
        "modules/ui-web/src/api/httpWindowCredential.test.ts#a deep-link operation keeps URL_DEEPLINK provenance even with the window credential attached"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/session_credential_tests.rs"
      ],
      "class": "New",
      "behaviour": "The shell waits for its current child's window credential and verified manifest binding together; a predecessor pipe cannot install a credential on the successor; both secret stdout record types are excluded from logging.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S7: The window credential appears in no manifest or manifest history, log (stdout/stderr, shell log), diagnostics bundle, public discovery response or API response, and is not readable from the Engine's stdout by another process after the shell has consumed it (or the residual is named if it cannot be prevented on Linux).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N5: Rotates on every Engine start; the shell picks up the new one the way it does today for restarts.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/session_credential_tests.rs#session_credential_tests::credential_waits_for_both_fields_and_keeps_their_incarnation_together",
        "modules/shell/src-tauri/src/session_credential_tests.rs#session_credential_tests::secret_stdout_lines_are_consumed_before_logging_even_without_a_live_child"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "One register count. packagedRegisterIsTheRepositoryRegister counts 290 register entries instead of 289: the one added entry is justsearch.windowCredential (restart-required), the shell's launch request for the window credential; the byte comparison with the repository register and the explicit scope assertions are unchanged."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "S1-S5 default-deny: the real production LocalApiServer and Head assembly (including ResourceApiModule, MCP and worker-ready RAG routes) enumerate every registered mutation route. Token-only and wrong-window requests to non-allowlisted /api routes return 403 WINDOW_CREDENTIAL_REQUIRED before controllers. Explicit checks cover policy creation and allowlist updates in unmanaged and machine-managed states, install start and repair, pack import and runtime activation. A newly registered handler is closed for POST, PUT, PATCH and DELETE, including accepted path variants; its window requests work and GET/HEAD/OPTIONS keep their existing behavior.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S1-S5 default-deny: new state-changing /api routes require the app window by default; only one explicit, reasoned token-only allowlist survives.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S4/S5: policy creation and pack-manifest allowlist update, AI install start/repair, pack import and runtime activation cannot change state for a token-only caller, including machine-managed and unmanaged policy installs.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-default-deny.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s1ToS5EveryRegisteredMutationDefaultsToWindowAuthority",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s4PolicyCreateUnmanagedIsRefusedBeforeWriting",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s4PolicyAllowlistUnmanagedIsRefusedBeforeWriting",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s4PolicyCreateManagedIsRefusedBeforeReadingPolicy",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s4PolicyAllowlistManagedIsRefusedBeforeReadingPolicy",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s4AiInstallStartIsRefusedBeforeDownloads",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s4AiInstallRepairIsRefusedBeforeDownloads",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s4PackImportIsRefusedBeforeStartingAnImport",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s5RuntimeActivationIsRefusedBeforeActivating",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#s1ToS5NewRoutesAndAllMutationMethodsAreClosedBeforeTheirHandler"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation for S1-S5 default-deny: the app window can still mutate with its credential; headless mode keeps token-as-operator, and development ignores even a builder-supplied window credential. The real production assembly is used in all three modes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S1-S5 default-deny (N1 preservation): app-window actions keep working; headless (no shell flag) and development mode retain their existing mutation access.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-default-deny-modes.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java#n1WindowAndHeadlessAndDevelopmentKeepTheirMutationAccess"
      ]
    }
  ]
}
```

## Acceptance records
