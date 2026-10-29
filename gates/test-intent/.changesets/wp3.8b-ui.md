---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp3.8b-ui-builder-j22dnc4z
---

# WP3.8b UI test intent

Agent-adopted under the design delegation below, never owner-agreed. Execution details and
per-check before failures are in `gates/test-intent/evidence/wp3.8b-ui/README.md` and the runner receipt.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The indexing-rules palette result opens Settings with focusIndexingRule=hours, labelled Change indexing rules, without dispatching core.set-indexing-rules. The raw operation is absent even for an empty palette query; invoking its old command ID cannot dispatch it. This repairs an existing zero-argument invocation defect.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8b UI for the settled revision 3 indexing-hours and pause-on-battery design",
          "choice": "D4 keeps the complete-pair rules write in Settings; reuse the existing input-requiring operation exclusion and navigation-only palette entry, routing through the existing hours focus request.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8b-ui/palette.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts#CommandPaletteProjection (§21.C) WP3.8b opens indexing rules in Settings instead of invoking the operation without arguments"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "Settings edits and refusals, preparation consent/conflict, disabled-hours retention, unknown-power option availability, pending/load/read-only states, confirmed ordered hold explanations, rule destinations and one-arrival focus navigation, manual-only Resume with remaining rules. Each named check passes after and fails by assertion on 1a4ec4a551db33f3dc2cb536b5eb437875ef86dd; no compile/environment failure is claimed as evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8b UI for the settled revision 3 indexing-hours and pause-on-battery design",
          "choice": "D1/D4 complete-pair rule edits, retained refused drafts; D5 ordered policy reasons, rule navigation and residual holds after Resume; D6 unknown-power preference availability; S38.7 native keyboard/time controls, focus and announced errors.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8b-ui/rules.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#Shell \u2014 settings window close semantics (tempdoc 855 \u00a711.1 D3/D4) WP3.8b routes hours to Settings once without replay on Back and Forward",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#Shell \u2014 settings window close semantics (tempdoc 855 \u00a711.1 D3/D4) WP3.8b routes battery to Settings once without replay on Back and Forward",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.1 S38.7 loads labelled days and native locale times and saves both rules retaining disabled hours",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.2 announces equal endpoints and retains the refused draft",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.2 announces missing time and retains the refused draft",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.2 refuses enabled hours without days and accepts the retained empty set when off",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.2 retains the draft and announces Engine 400 without stale replay",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.2 retains the draft and announces Engine 409 without stale replay",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.2 retains the draft and announces Engine 200 without stale replay",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.7 waits for saved rules before editing or applying deferred rule focus",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.7 keeps read-only rules readable without issuing a save",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.7 preserves Save focus while pending and sends one unchanged rule pair",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor D4 preserves both rules through approval and uses the approved preparation key",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor D4 changing battery preserves an absent schedule until the hours are edited",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor D6 keeps battery available with unknown power and explains the rule",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.7 focuses hours in the indexing category",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b rules editor S38.7 focuses battery in the indexing category",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 PAUSING schedule names the rule with an empty queue and offers its Settings action",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 PAUSED schedule names the rule with an empty queue and offers its Settings action",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 PAUSING on_battery names the rule with an empty queue and offers its Settings action",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 PAUSED on_battery names the rule with an empty queue and offers its Settings action",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 orders combined reasons and restricts recovery actions",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 names every folder rule without Waiting for Resume and preserves failure evidence",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions S38.5 Resume clears manual only and announces both remaining rules after confirmed status",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions S38.5 waits for manual-pause removal before announcing it cleared",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 does not invoke a focused Resume when manual pause clears leaving hours",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 jf-library-surface names schedule holds for queued work",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts#WP3.8b policy status and actions D5 jf-task-list names schedule holds for queued work"
      ],
      "repaired": true
    }
  ]
}
```

## Palette regression evidence

Class `New`, repaired behaviour: a new oracle for the existing D4 contract, not a claim that
palette execution was previously allowed. Under `TZ=UTC`, the named check fails by assertion
on 330fe55bc3 because the selected entry emits `jf-invoke-operation`; it passes with this repair.
`palette.json` was written by the evidence runner in a temporary shared clone. That temporary
copy uses shared clones and `npm ci` instead of worktrees and linked/copied dependencies, and
runs Vitest with `--configLoader runner --maxWorkers=2`; its actual script digest is recorded.
The adaptation and raw output are retained in `tmp/verify/wp3.8-palette/`. No repository runner
was changed. The earlier acceptance below has not been renewed by this builder.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a186f1-82fa-76d0-b7e6-b8b0ac622fad",
  "verdict": "accept",
  "digest": "sha256:66a40968ace54faab901e5ed9df5ee84c1123ef9a99063dd698fd32fcc23cd1e",
  "note": "Codex verifier-b2 on e535494115: PASS; classifications and evidence accepted"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1887f-da22-7961-80f7-243ede021e31",
  "verdict": "accept",
  "digest": "sha256:d268213cdc2de9c3d0d1dbfd09d47f3f230b3feea4fb4078b3d9a86941a2e274",
  "note": "Codex verifier-b5 on 7ffff2f266: PASS; changeset accepted as it stands"
}
```
