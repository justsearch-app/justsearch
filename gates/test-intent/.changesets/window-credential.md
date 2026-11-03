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
fail-before evidence is recorded by the script. The Rust test module, register count and raised operation risk values (catalog test and
operation-wire golden) need entries. The golden
is overlaid onto the before-state so its conformance check compares the base catalog with the new
value. N1 for the raised operation already held on the base, so its check has no fail-before. For Rust fail-before, the compatibility patch exposes only new API shapes, keeps the
old session-only stdout filtering and supplies no window authority; the overlaid
`justsearch-bound-exec` helper is compiled from `native/bound-exec/bound-exec.c` for the shell's spawn tests.

Execution evidence is produced with the unmodified `run-evidence.mjs`, run from an isolated
shared clone. The review repairs use fff9c9f9d0 for attach/settings fail-before; the refreshed
watched-root checks retain their original 57bf6cfe5a baseline. Its temporary before-state worktrees and build folders stay inside the session
temporary directory. The default-deny before-state is `dcce080acd`, with only the new test source
overlaid. The register-count entry is a new expectation for the deliberate S7 launch setting, not
a repair, so its evidence has no fail-before.

Review repairs after fff9c9f9d0 add S7 attach hardening and S3/S5 operation-route settings gating.
The catalog audit covers CoreOperationCatalog and AgentToolsOperationCatalog:

- Chat enable/disable, indexing rules, pause and resume persist settings through RuntimeSpecStore
  and SettingsCommitCoordinator; all four now use MEDIUM risk with confirmation None.
- `core.remember` persists assistant memory and remains LOW by the orchestrator's explicit
  continuation decision. It is a separate finding: raising it here would prompt on every in-app
  assistant remember call.
- Reindex, reconcile, retry and offline processing keep their existing LOW policy by that same
  decision: they process existing roots without widening them. Cancel marks an existing job done
  and settle-index collapses already-hidden tombstones; both narrow existing state and stay LOW.
- Export-diagnostics creates a local redacted diagnostic artifact without changing settings or
  authority and stays LOW. The other LOW operations (ping, path resolution, diagnostic summary,
  preview-excludes, pack preflight, search, document read, folder browse and navigation) do not
  widen saved state or persist settings. No additional settings writer was found.

The attach audit found no runtime attach dependency in diagnostics export, SlowRequestDumper,
telemetry or the Rust supervisor. Dumps use Thread.getAllStackTraces and management beans;
diagnostics read files and existing service state; supervision uses child handles and HTTP.
JDI/attach utilities found under scripts/dev, scripts/supervisor-conformance and jseval, and jcmd
in worker/soak tests, are development or qualification tools. The shell command also serves a debug
shell when it launches a bundled Engine, so that launch gets the flag too. External development
and directly launched headless JVM commands are unchanged.

The GPU journey mock correction exercises window_api on success and rejection. Original GPU tests
fail at fff9c9f9d0 with Mock serialization errors and pass at 81f1b00072; repaired tests pass here.

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
      "class": "New",
      "behaviour": "S7: The configuration apply-scope register contains 290 entries, including the one new restart-required justsearch.windowCredential launch setting used by the shell to request the window credential. The packaged register must remain byte-identical to the repository register; the existing explicit scope assertions remain unchanged. This is a deliberate new configuration expectation, not a repair of the prior 289-entry register.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S7: The shell requests its per-Engine-start window credential through justsearch.windowCredential, a restart-required configuration entry. Register that one launch setting alongside the 289 existing entries, so the packaged and repository configuration registers contain 290 entries and stay byte-identical.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-config-register.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java#packagedRegisterIsTheRepositoryRegister"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionWindowRouteBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "S1-S5 default-deny: the real production LocalApiServer and Head assembly (including ResourceApiModule, MCP and worker-ready RAG routes) enumerate every registered mutation route. Token-only and wrong-window requests to non-allowlisted /api routes return 403 WINDOW_CREDENTIAL_REQUIRED before controllers. Explicit checks cover policy creation and allowlist updates in unmanaged and machine-managed states, install start and repair, pack import and runtime activation. A newly registered handler is closed for POST, PUT, PATCH and DELETE, including accepted path variants; its window requests work and GET/HEAD/OPTIONS keep their existing behavior. Path checks explicitly pin case and escape rejection (404) and trailing-slash routing (204), and explicit token-only statuses (404 for rejected routes, 403 for the accepted route) for every variant; method-override headers plus HEAD/OPTIONS never invoke the mutation probe.",
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
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SettingsOperationRiskBoundaryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java",
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json"
      ],
      "class": "New",
      "behaviour": "S3/S5: Settings writes via core.set-chat-enabled, core.set-indexing-rules, core.indexing-pause and core.indexing-resume must require pending approval for outside callers without a covering grant, including forged click transports; settings bytes and the accepted revision stay unchanged. Trusted app-window clicks stay automatic.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S3/S5: Settings writes via core.set-chat-enabled, core.set-indexing-rules, core.indexing-pause and core.indexing-resume must require pending approval for outside callers without a covering grant, including forged click transports; settings bytes and the accepted revision stay unchanged. Trusted app-window clicks stay automatic.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-settings-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SettingsOperationRiskBoundaryTest.java#s3s5OutsideSettingsWriteStaysPendingWithoutPersistence",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java#setChatEnabledIsMediumRiskNoConfirmNoPreconditionsAndSupersedesSwitchMode",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationViewConformanceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SettingsOperationRiskBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "N1 preservation: the same four settings operations run without a prompt for a credentialed app-window click with no transport, BUTTON, RAIL or PALETTE. Real handlers commit settings through the existing owner; a fresh store reload observes chat enabled, indexing resumed and pauseOnBattery enabled.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S3/S5 (N1 preservation): raising settings operations to MEDIUM must not add a prompt to a click in the app window.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-settings-clicks.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SettingsOperationRiskBoundaryTest.java#n1WindowSettingsClickPersistsWithoutPrompt"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/session_credential_tests.rs"
      ],
      "class": "New",
      "behaviour": "S7: the shell command that starts the credential-holding Engine JVM disables the standard JVM attach mechanism, closing same-user attach heap dumping. The existing per-launch credential flag remains on that command.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "S7: Start the production Engine JVM with -XX:+DisableAttachMechanism so standard JVM attach tooling cannot dump the window credential; diagnostics, supervision and in-process thread dumps must continue without attach.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-attach-repair.json",
      "newChecks": [
        "modules/shell/src-tauri/src/session_credential_tests.rs#session_credential_tests::production_engine_spawn_disables_jvm_attach"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/shell_connection_tests.rs",
        "modules/shell/src-tauri/src/engine_conn_test_support.rs",
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs"
      ],
      "class": "New",
      "behaviour": "N5/N11: Real owned socket fixtures require the matching per-process window credential as well as the token. Preparation and reconciliation succeed; quit uses HTTP without a request-file fallback. Every lifecycle/upgrade route uses the successor credentials after same/new-port restart. Existing reconciliation readiness, refusal and identity oracles remain unchanged. The explicit installed Engine check now enables window-credential mode, consumes the secret stdout record, reconciles a real durable intent, and retains ordered quit, prepare/cancel/commit and released-port checks. Shell and updater readiness wait for the same-incarnation window credential even when port/token arrive first; a predecessor credential does not release the wait. A startup readiness timeout keeps the durable intent reconciling and reports not-ready rather than requiring repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N5/N11: Every shell lifecycle and upgrade request carries the current Engine incarnation’s window credential next to the session token over the proven connection. On same-port and new-port restarts the successor supplies both credentials. Update preparation, cancellation, commit, reconciliation and quit remain usable under default-deny, without persisting the credential or broadening the token-only list. The installed Windows API-port proof cleans up through the existing window request helper.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decision: when the Engine runs in window-credential mode, the shell's Engine readiness (for updater reconciliation and any other shell->Engine call gated on readiness) also requires the window credential for the current incarnation to have arrived; it waits for it within the existing bounded readiness wait, and if it does not arrive in time it reports the same transient/not-ready state it reports today for a missing token, not repair-required.",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "Finish the readiness race repair, keep the existing shell lifecycle transport repair, and rerun its checks without committing.",
          "choice": "N5/N11: Require the current incarnation's port, token and window credential before shell readiness. Delayed stdout stays not-ready within the existing wait; expiration leaves the durable update reconciling. Existing fake-Engine launch fixtures supply their synthetic incarnation credential without changing their readiness or restart oracles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-shell-lifecycle.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::window_credential_authorizes_update_preparation",
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::window_credential_authorizes_reconciliation",
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::window_credential_authorizes_quit_without_request_file",
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::supervisor_successors_use_the_new_pid_and_coherent_binding_on_same_and_new_ports",
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::delayed_window_credential_waits_before_reconciliation",
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::shell_readiness_waits_for_current_window_credential"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_conn_tests.rs",
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "N5/N11: Pass a fixture window credential to post_connected’s new required parameter. Preserve all response decoding, connect failure, send-hook, partial-write and receipt expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task record and proceed accordingly with the remainign work",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "start the fix for the self-approval defect (any same-user program holding the session token can approve its own pending actions or create grants without the person seeing a prompt) as its own task, answering the orchestrator's question 'Should I start that fix as its own task?'",
          "choice": "N5/N11: Every shell lifecycle and upgrade request carries the current Engine incarnation’s window credential next to the session token over the proven connection. On same-port and new-port restarts the successor supplies both credentials. Update preparation, cancellation, commit, reconciliation and quit remain usable under default-deny, without persisting the credential or broadening the token-only list. The installed Windows API-port proof cleans up through the existing window request helper.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "N5/N11: Supply the fake Engine incarnation's synthetic window credential alongside its manifest credentials. Preserve the failed-generation, manual-restart, running-state, reset-budget, binding and reaping assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decision: when the Engine runs in window-credential mode, the shell's Engine readiness (for updater reconciliation and any other shell->Engine call gated on readiness) also requires the window credential for the current incarnation to have arrived; it waits for it within the existing bounded readiness wait, and if it does not arrive in time it reports the same transient/not-ready state it reports today for a missing token, not repair-required.",
          "delegationLocation": "task t-20261102-agent-approval request",
          "delegationScope": "Finish the readiness race repair, keep the existing shell lifecycle transport repair, and rerun its checks without committing.",
          "choice": "N5/N11: Require the current incarnation's port, token and window credential before shell readiness. Delayed stdout stays not-ready within the existing wait; expiration leaves the durable update reconciling. Existing fake-Engine launch fixtures supply their synthetic incarnation credential without changing their readiness or restart oracles.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
