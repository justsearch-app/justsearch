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

The four new test files are isolated new sources (exempt); their entries are kept so their
fail-before evidence is recorded by the script. Only the Rust test module and the register count
need entries. For Rust fail-before, the compatibility patch exposes only new API shapes, keeps the
old session-only stdout filtering and supplies no window authority; the overlaid
`justsearch-bound-exec` helper is the CI-built native fixture the shell's spawn tests need.

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
          "kind": "owner-words",
          "quote": "Outside caller approves a pending action (capsule issuance and execute:true, with and without allowAlways), its own or another caller's, with the pending id taken from a response or the event stream: refused; nothing approved, consumed, granted or run; the action stays pending and approvable in the app.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Without a grant that covers it, an outside caller invokes a medium-risk operation (e.g. add a folder, ingest) with no transport header, blank, unknown, or forged BUTTON/RAIL/PALETTE/SYSTEM_INTERNAL and any client header: it does not run unprompted; it becomes a pending action shown in the app (or is refused where the path has no pending flow).",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Missing or wrong -> 403 with a clear error code; nothing changes.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "chat start/resume/fork requests with an autonomyLevel above the default are downgraded to the default without it.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Upgrade with existing durable-grants.json and plugin-allowlist.json: entries keep working, files not rewritten.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Outside caller creates an operation or family grant, or approves with allowAlways: refused; durable-grants.json unchanged, also after restart.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Outside caller adds a folder through POST /api/indexing/roots (the direct path): no watched root added, no scan admitted, nothing persisted after restart, unless approved in the app.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Outside caller calls /api/chat/approve for agent and workflow gates (sessionId given or omitted), POST /api/chat/agent/autonomy, starts/resumes/forks a chat with autonomyLevel above the default, or POST /api/plugins/allowlist: refused or downgraded to the default autonomy; no plugin becomes trusted, also after reload and restart.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "With no app window (closed, not yet connected, disconnected), nothing that needs a person runs: pending actions wait and expire as today; no implicit consent.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Approval stays exact and single use: changed arguments refused; a different operation or preparation substituted for the pending one refused; admission refusal happens before the pending is consumed, so a retry after admission frees still works; duplicate/concurrent approval; 410 after use, expiry after 5 min, nothing after restart.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261102-agent-approval request",
          "quote": "In the app: button, rail and palette actions behave as today (medium risk runs, high risk asks typed confirm); approve with and without \"allow always\"; grants list in Settings; revoke; Global Hard Stop."
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
          "kind": "owner-words",
          "quote": "A justsearch:// link launched by another process (shell argument or single-instance forwarding) that would change something does not count as a click: it keeps URL provenance and prompts.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "whatever the webview does on their behalf keeps URL provenance (send the URL transport explicitly); they must not become BUTTON just because the window credential is attached.",
          "location": "task t-20261102-agent-approval request"
        }
      ],
      "evidence": "gates/test-intent/evidence/window-credential-java-s6.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WindowAuthorityBoundaryTest.java#s6DeepLinksFromTheWindowKeepUrlProvenanceAndStillPrompt"
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
          "kind": "owner-words",
          "location": "task t-20261102-agent-approval request",
          "quote": "The window credential appears in no manifest or manifest history, log (stdout/stderr, shell log), diagnostics bundle, public discovery response or API response, and is not readable from the Engine's stdout by another process after the shell has consumed it (or the residual is named if it cannot be prevented on Linux)."
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
          "kind": "owner-words",
          "quote": "ui-web's single request path (http.ts:268-303, 589-605) attaches it as a header next to the token.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "whatever the webview does on their behalf keeps URL provenance (send the URL transport explicitly); they must not become BUTTON just because the window credential is attached.",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Engine restart, crash recovery, delayed startup, restart during credential resolution, stale manifest, port reuse: the window credential rotates; the app recovers as it does today for the session token (one retry, bounded wait; no promise of no visible error); an old credential is refused.",
          "location": "task t-20261102-agent-approval request"
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
          "kind": "owner-words",
          "quote": "The window credential appears in no manifest or manifest history, log (stdout/stderr, shell log), diagnostics bundle, public discovery response or API response, and is not readable from the Engine's stdout by another process after the shell has consumed it (or the residual is named if it cannot be prevented on Linux).",
          "location": "task t-20261102-agent-approval request"
        },
        {
          "kind": "owner-words",
          "quote": "Rotates on every Engine start; the shell picks up the new one the way it does today for restarts.",
          "location": "task t-20261102-agent-approval request"
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
    }
  ]
}
```

## Acceptance records
