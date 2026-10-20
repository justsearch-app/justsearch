---
schema: test-intent.v1
task: t-20261019-9d870e
author-role: builder
author-session: upkeep-g-updatecheck-builder
---

# Test-intent entries

The delegation location above is rendered in the gate's `task <id> user turn ...` form;
the original supplied location is "user turn to session justsearch-8b, 2026-10-19,
task t-20261019-9d870e". Its scope and quote are unchanged.

The privacy invariant predates this change: "No telemetry, analytics, or phone-home.
The product does not report usage anywhere." (NON-GOALS.md:8 at f003a5c64, also quoted
by the orchestrator in follow-up 1). NON-GOALS.md is edited in this change, so the gate
forbids it as a repository source. Formal warrants below use the existing enterprise
promise and the supplied delegation for the specific off-switch mechanism.

Java's isolated new AppUpdatePolicyTest is exempt from the mechanical entry requirement;
its checks and shared-fixture evidence are additionally recorded in
`gates/test-intent/evidence/app-update-java.json`.

The versioned `app-update-java-before.patch` and `app-update-rust-before.patch`
beside the evidence only expose a path-injection seam / compatibility constructor and an
always-allow Rust authorization shim, reflecting the old unrestricted behavior. They
contain no policy parsing, environment suppression or fail-closed repair.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/pack/PackAllowlistServiceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/policy/UserPolicyWriterTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Add the permissive app-update argument to EffectivePolicy construction; retain every pre-existing input, policy flag, assertion and scenario."
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)",
        "modules/app-services/src/test/resources/policy/app-update-policy-cases.json"
      ],
      "class": "New",
      "behaviour": "Both policy readers agree on all shared v1 fixture permissions: defaults, null, unknown fields, known-field scalar coercions and Unicode/BOM file encodings, restrictive machine/user merge, AI independence, broken machine paths fail closed and broken user fallback. Rust suppresses only automatic checks for the exact env value false, policy wins, and policy edits take effect on the next operation without deleting durable updater files.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fail closed on mismatches (hash/policy/pack allowlist).",
          "location": "docs/explanation/15-enterprise-policy.md:24"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "Use JUSTSEARCH_AUTO_UPDATE_CHECK=false only for scheduled checks and optional v1 appUpdatesEnabled policy for all updater operations; preserve state and show administrator refusals, including recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/app-update-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::app_update_shared_policy_fixtures",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::app_update_env_suppresses_only_automatic_checks",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::app_update_policy_beats_env_and_reloads_without_changing_state"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/appUpdateState.test.ts",
        "modules/ui-web/src/boot/desktopRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "Only the delayed startup check sends automatic:true; a suppressed check retains host state without reporting checking or up to date. Manual invocation stays explicit. Both Check and Install display an administrator-policy refusal while the Engine is down. Existing startup timing and recovery assertions remain intact.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "Use JUSTSEARCH_AUTO_UPDATE_CHECK=false only for scheduled checks and optional v1 appUpdatesEnabled policy for all updater operations; preserve state and show administrator refusals, including recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/app-update-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/appUpdateState.test.ts#keeps a suppressed scheduled check idle, with manual checks still explicit",
        "modules/ui-web/src/boot/desktopRecovery.test.ts#shows administrator refusal for Check for updates with the Engine down",
        "modules/ui-web/src/boot/desktopRecovery.test.ts#shows administrator refusal for Install update with the Engine down"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PolicyControllerTest.java"
      ],
      "class": "New",
      "behaviour": "GET /api/policy/effective exposes a false app-update permission loaded from the real policy service. POST of an allowlist digest preserves that saved flag and effective restriction. Existing validation inputs and expectations stay intact with the new permissive constructor argument.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "It must **never edit machine policy**, and must preserve all other user policy fields (only update `allowlists.packManifestSha256` plus `updatedAt`).",
          "location": "docs/explanation/15-enterprise-policy.md:86"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining upkeep work, including the sweep follow-ups the orchestrator decides",
          "choice": "Use JUSTSEARCH_AUTO_UPDATE_CHECK=false only for scheduled checks and optional v1 appUpdatesEnabled policy for all updater operations; preserve state and show administrator refusals, including recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/app-update-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PolicyControllerTest.java#effectiveEndpointAndAllowlistAppendKeepAppUpdatesDisabled"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
