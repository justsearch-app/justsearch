---
schema: test-intent.v1
task: t-20261019-5ec1a1
author-role: builder
author-session: 01a15776-d065-7d12-8b21-f9e5b2e63fdb
---

# Test-intent entries

P1–P9 were adopted by the owner on 2026-10-20: "i agree, proceed accordingly"
(task t-20261019-5ec1a1 agreement). Scenario quotes below reproduce the first builder brief exactly.
The Windows distinct-account scenario remains unverified without a Windows host. The delegated
residual leaves explicit ACLs on copied-in files unchanged. No acceptance record is authored here.

The earlier authentication warrants are pending clarification. Entries mark that gap explicitly;
changed policy documents in this branch are not cited as circular sources.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "behaviour": "Owner-only creation and upgrade repair preserve old bytes and parent modes; keystore setup, passphrase/recovery replacement, interruption and same-owner restart remain usable. Permission failures warn once without deletion. Engine symlinks and junctions warn once and continue without repairing through the link. The before-parent run specifically pins the formerly fatal link/refusal change; the initial folder-permission repair has separate before-4975ceedf evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P1",
          "quote": "Fresh Linux install under umask 002: `<data>/runtime` and `<data>/encryption` are 0700; another account cannot list, read, create, rename or delete inside them. Fails today (0775).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P2",
          "quote": "Upgrade, Linux, data folder outside the home folder: runtime 0775 with history 0664 holding recognizable old tokens and a readable keystore, written by the old version; also a data folder copied or restored with broader permissions (cp/rsync/tar without preserving modes). After the first start of the new version, another account cannot read history, manifest or keystore; history readers (instance API, stop-exit census) and keystore unlock still work. Fails today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P3",
          "quote": "The keystore stays owner-only after setup, passphrase change and recovery-key regeneration (atomic replacement), and still unlocks after restart; a replacement interrupted midway (kill, or denied write) leaves the last usable keystore and its recovery material intact.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P6",
          "quote": "Permissions cannot be set (unsupported filesystem or denied): one warning, the Engine starts, nothing is deleted; unit test with a failing fake. The alternative (refuse to create new secrets there) is the user's call.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P7",
          "quote": "Repair never follows a symlink or junction out of the data folder and never changes a parent folder.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P8",
          "quote": "Same-owner readers keep working: desktop shell discovery, MCP discovery, dev runner, jseval, diagnostics, shutdown recovery, on Linux (and Windows, unverified).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Internal choices are settled by the orchestrator",
          "delegationLocation": "task t-20261019-5ec1a1 user turn (owner delegation, supplied in the test-intent assignment)",
          "delegationScope": "Engine response to a linked credential folder, which P7 constrains to no traversal during repair but does not specify startup failure versus continuation.",
          "choice": "A symlinked runtime or encryption folder warns once and continues; permissions on the link target and its parent are left unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-link-continuation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "behaviour": "Engine-first exit-ledger append and folder recreation protect runtime at 0700 while keeping records readable to the owner and preserving the data-folder mode.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P1",
          "quote": "Fresh Linux install under umask 002: `<data>/runtime` and `<data>/encryption` are 0700; another account cannot list, read, create, rename or delete inside them. Fails today (0775).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P5",
          "quote": "Exit ledger and other runtime writers: shell-first and Engine-first startup, ledger appends, rotation, `.pending` and temporary files and recreated folders are inside an owner-only folder from creation, not only after the Engine's repair (the shell creates the folder owner-only too).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P7",
          "quote": "Repair never follows a symlink or junction out of the data folder and never changes a parent folder.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P8",
          "quote": "Same-owner readers keep working: desktop shell discovery, MCP discovery, dev runner, jseval, diagnostics, shutdown recovery, on Linux (and Windows, unverified).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-initial-repair.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "class": "New",
      "behaviour": "Protected owner+SYSTEM inheritable full-control DACL on create and repair; repair changes neither owner/group nor SACL. The before-state compatibility patch exposes the legacy NIO policy in the new descriptor/flag vocabulary; the old runtime still uses its unchanged NIO code. These are portable policy-construction comparisons, not distinct-account Windows access evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P6",
          "quote": "Permissions cannot be set (unsupported filesystem or denied): one warning, the Engine starts, nothing is deleted; unit test with a failing fake. The alternative (refuse to create new secrets there) is the user's call.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Internal choices are settled by the orchestrator",
          "delegationLocation": "task t-20261019-5ec1a1 user turn (owner delegation, supplied in the test-intent assignment)",
          "delegationScope": "Residual Windows ACL policy for files copied in with explicit grants; folder DACL protection itself follows P9.",
          "choice": "Protect only runtime and encryption folder DACLs; copied-in files with explicit grants retain those grants.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-windows-policy.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java#creationDescriptorIsProtectedAndGrantsOnlyOwnerAndSystemInheritableFullControl",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java#repairSetsDaclAndProtectsItWithoutChangingOwnerGroupOrSacl"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Shell-first ledger append, repair and recreation protect runtime/ledger folders and preserve the data-parent mode. A linked runtime is refused without touching the target. The before patch adds only test-visible constant names required by the candidate test module; the legacy writer never uses them.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P5",
          "quote": "Exit ledger and other runtime writers: shell-first and Engine-first startup, ledger appends, rotation, `.pending` and temporary files and recreated folders are inside an owner-only folder from creation, not only after the Engine's repair (the shell creates the folder owner-only too).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P7",
          "quote": "Repair never follows a symlink or junction out of the data folder and never changes a parent folder.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P8",
          "quote": "Same-owner readers keep working: desktop shell discovery, MCP discovery, dev runner, jseval, diagnostics, shutdown recovery, on Linux (and Windows, unverified).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-shell.json",
      "newChecks": [
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_first_ledger_and_recreation_are_private",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_refuses_linked_runtime_without_touching_target"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/activity/idleSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/components/ResourceView.history-reconnect.test.ts",
        "modules/ui-web/src/shell-v0/components/ResourceView.render.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryStore.test.ts",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionStreamOwnership.test.ts",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthLitView.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Replace EventSource-only fakes with controlled HTTP SSE byte streams consumed by the product fetch transport. Keep fixture payloads, event order, reduction/rendering/lifecycle outcomes, tags and conditions. Await asynchronous token resolution/parser work before the original observations. The assertion comparison accounts for all 182 original expect calls: only stream collection/listener observation renames and two equivalent closed-state observations move; the additional pending-fetch wait establishes the original reattach precondition. aiInstallPoll only drains time-zero token work before its unchanged call-count/cancellation assertions. See gates/test-intent/evidence/private-folders-adapted-assertions.json for every before/after assertion and the explicit observation mappings.",
      "assertionEvidence": "gates/test-intent/evidence/private-folders-adapted-assertions.json",
      "oracleReview": "The idle-surfaces connect-burst loop now selects connections that are still open. The old EventSource fake retained callbacks even after close and the old loop visited those closed fakes too. The asserted scenario/comment is about every stream the shell holds open; this transport-validity difference is explicit for the acceptor to review, rather than being hidden by the unchanged-assertion comparison."
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Add getJson(path, headers) overload and delegate the original getJson(path) to it with an empty map. For every existing no-header caller, method, URI, timeout, response decoding and assertions are unchanged; explicit headers are forwarded only by callers that request them. No test assertion is added or removed in this abstract fixture."
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "class": "New",
      "behaviour": "Invoking the new Windows-only native backend on Linux is unsupported: one warning, no deletion and no mode/content change. This is a new backend-boundary check; the Engine permission-denial repair is independently pinned against 4975ceedf.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P6",
          "quote": "Permissions cannot be set (unsupported filesystem or denied): one warning, the Engine starts, nothing is deleted; unit test with a failing fake. The alternative (refuse to create new secrets there) is the user's call.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Internal choices are settled by the orchestrator",
          "delegationLocation": "task t-20261019-5ec1a1 user turn (owner delegation, supplied in the test-intent assignment)",
          "delegationScope": "Residual Windows ACL policy for files copied in with explicit grants; folder DACL protection itself follows P9.",
          "choice": "Protect only runtime and encryption folder DACLs; copied-in files with explicit grants retain those grants.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-policy-preservation.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The shell Windows descriptor retains its already protected owner+SYSTEM inheritable full-control DACL and repair flags. This portable construction guard does not claim Windows account-level verification.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-policy-preservation.json",
      "newChecks": [
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::windows_directory_descriptor_is_protected_and_only_owner_and_system_inherit_full_control"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Encode the same authorized settings/activation observations with the current session header on the two GET requests, preserving fixtures, operations, persisted-value assertions and activation outcomes. The added polling status assertion enforces the existing successful-response precondition before reading activation JSON. Four consumer tests execute in private-folders-policy-preservation.json.",
      "executionEvidence": "gates/test-intent/evidence/private-folders-policy-preservation.json"
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Encode the existing authenticated-owner production GET with the session header so the CORS observation remains reachable after non-public reads require authentication. Status 200, exact response body and foreign-versus-shell CORS outcomes remain unchanged; only the assertion message names authentication. Eleven tests execute in private-folders-policy-preservation.json.",
      "executionEvidence": "gates/test-intent/evidence/private-folders-policy-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.test.ts"
      ],
      "class": "Structural rule kept",
      "rule": "Without a usable default stream transport, headless bridge startup is a no-op: no tasks and no pooled stream.",
      "whySurvives": "The missing-capability fixture follows the default transport rename from EventSource to fetch. The original zero-tasks assertion and stop call stay; the new zero-pool assertion is a stricter observation of the same no-op rule. All 23 tests execute in private-folders-policy-preservation.json.",
      "executionEvidence": "gates/test-intent/evidence/private-folders-policy-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/api/http.test.ts"
      ],
      "class": "New",
      "behaviour": "Non-public desktop GET attaches the owner session token; a 401 resolves the new boot token and retries exactly once. Browser/dev reads without a desktop token remain token-free. Tagged success/failure outcomes preserve the successful-result oracle while making the old rejected request fail an assertion.",
      "sources": [],
      "evidence": "gates/test-intent/evidence/private-folders-http-retry.json",
      "newChecks": [
        "modules/ui-web/src/api/http.test.ts"
      ],
      "repaired": true,
      "pendingWarrant": "Owner words or adopted scenarios for the earlier authentication work requested asynchronously; P1\u2013P9 do not establish this complete expectation."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java"
      ],
      "class": "New",
      "behaviour": "The removed credential bootstrap has no route for any Origin. Existing MCP Origin/Host checks keep their outcomes while their fixture/callers supply a production token. The new absence check uses a fixture without that endpoint; real removal is pinned by ProductionRouteTokenBoundaryTest.",
      "sources": [],
      "evidence": "gates/test-intent/evidence/private-folders-policy-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java"
      ],
      "repaired": false,
      "pendingWarrant": "Owner words or adopted scenarios for the earlier authentication work requested asynchronously; P1\u2013P9 do not establish this complete expectation."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestCredentialBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "Every canonical manifest publish replaces readable manifest and leftover temporary files with owner-only files before writing credentials; newly written historical manifests/logs omit session credentials. P4 warrants the file/temp guarantee; the earlier new-history credential-omission warrant is still missing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P4",
          "quote": "The manifest keeps its owner-only file and temporary-file guarantee from item 2; a kill between temp write and move leaves nothing readable to others and the next start is normal.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-auth-repairs.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestCredentialBoundaryTest.java"
      ],
      "repaired": true,
      "pendingWarrant": "Owner words or adopted scenarios for the earlier authentication work requested asynchronously; P1\u2013P9 do not establish this complete expectation.",
      "expectationSources": {
        "everyPublishReplacesReadableManifestAndLeftoverTempWithOwnerOnlyFile": [
          "P4"
        ],
        "newInstanceHistoryContainsNoCredentialAcrossPublishes": []
      }
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/ProductionRouteTokenWalkTest.java",
        "modules/ui-web/src/api/registry/AuthenticatedCatalogs.test.ts",
        "modules/ui-web/src/shell-v0/api/authorizedFetch.test.ts",
        "modules/ui-web/src/shell-v0/streaming/AuthenticatedStream.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessLauncherProductionModeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionRouteTokenBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "Earlier authentication changes: shipped headless launchers enable production and preserve the selected mode; stdout never prints the token; production non-public routes (including reads/SSE/MCP and future routes) require the per-boot owner token, with only the six public read paths and OPTIONS exempt; the HTTP bootstrap is removed; dev remains token-free; default desktop catalog/canonical-schema reads attach the token while external schemas do not; streams use a header, parse chunked SSE, resume after EOF, refresh stale tokens and retry cold-start discovery without credentials in URLs. Each named class has script-produced candidate and assertion fail-before evidence.",
      "sources": [],
      "evidence": "gates/test-intent/evidence/private-folders-auth-repairs.json",
      "newChecks": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/ProductionRouteTokenWalkTest.java",
        "modules/ui-web/src/api/registry/AuthenticatedCatalogs.test.ts",
        "modules/ui-web/src/shell-v0/api/authorizedFetch.test.ts",
        "modules/ui-web/src/shell-v0/streaming/AuthenticatedStream.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessLauncherProductionModeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessTokenOutputTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProductionRouteTokenBoundaryTest.java"
      ],
      "repaired": true,
      "pendingWarrant": "The first builder brief supplies folder scenarios only. The changed ADR-0046 and threat-model document cannot serve as sources because they change in this branch. Awaiting the earlier owner words/adopted scenarios, rather than inventing a delegation scope or citing the implementation."
    }
  ]
}
```

The D4 assertion-only fail-before requirement required four durable test adaptations:
`OwnerOnlyDirectoryTest` wraps the prior bare `OwnerOnlyDirectory.ensure(...)` link call;
`EncryptionKeystorePermissionsTest` wraps the prior bare `restart()` calls;
`RuntimeDirectoryPermissionsTest` wraps the prior bare publisher constructors and the prior
`Mockito.verify(warning, Mockito.times(1)).warn(...)` call in `assertDoesNotThrow`.
The old HTTP assertion was
`await expect(request('http://127.0.0.1:33221', '/api/preview')).resolves.toEqual({ recovered: true });`.
It now compares tagged success/failure outcomes against `{ value: { recovered: true } }`;
a rejection still fails, but the runner can classify the failure as an assertion.
No original expected permission, content, count, status, recovery result or lifecycle outcome was removed.

The two compatibility patches are evidence scaffolding only. The Rust patch supplies unused
test-visible constant names; it leaves the legacy ledger writer unchanged. The Windows patch
exposes the known legacy unprotected NIO ACL policy through the candidate's descriptor/flag
vocabulary; it does not replace or repair the old Engine's runtime path. Neither patch establishes
real Windows account-level access. Their exact bytes/digests are recorded by the evidence runner.

## Acceptance records
