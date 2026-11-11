---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-settings-builder-g
---

# Test-intent entries

Candidate G follows the settled SG1–SG10 amendments. SD developer-tools changes are accounted for in s6-devtools.md, including the superseding legacy Developer-link browser check; the former View tier check is no longer selected here.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.chatWidth.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.highContrast.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.overlap.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ui-mode.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.viewerAudience.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/conversationExportWarnings.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only setup coordinates move to the settled section (General, Appearance, Data & reset or Advanced), including selecting Appearance explicitly after the default becomes General. Every prior input, visibility, write, error, confirmation, cancellation, focus, timing, native-capability and audience assertion remains intact. SettingsSurface.dataReset.test.ts RN2 additionally seeds the Reset to defaults operation and views as OPERATOR, the tier its operation policy offers Reset to, because Data & reset no longer lists Reset where it renders nothing; its separate-operation assertion is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2 and SG9 move existing controls without changing their behavior. SG10 retains their checks by pointing test setup at each control’s new section."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG7 More > About opens the About section instead of Desktop; copy-link continues copying the current URL."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"
      ],
      "class": "New",
      "behaviour": "SG7 More > About opens the About section instead of Desktop; copy-link continues copying the current URL.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG7 More > About opens the About section instead of Desktop; copy-link continues copying the current URL."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-adapted.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/SettingsNav.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2 removes group headings. The late-catalog oracle now observes a section label, retaining the before-catalog raw-key and after-catalog localized-label assertions."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/SettingsNav.test.ts"
      ],
      "class": "New",
      "behaviour": "SG2 removes group headings. The late-catalog oracle now observes a section label, retaining the before-catalog raw-key and after-catalog localized-label assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2 removes group headings. The late-catalog oracle now observes a section label, retaining the before-catalog raw-key and after-catalog localized-label assertions."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-adapted.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/SettingsNav.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2 Notifications belongs to General; register membership and search hit coordinates name General. Existing notification choice and write assertions remain."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts"
      ],
      "class": "New",
      "behaviour": "SG2 Notifications belongs to General; register membership and search hit coordinates name General. Existing notification choice and write assertions remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2 Notifications belongs to General; register membership and search hit coordinates name General. Existing notification choice and write assertions remain."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-adapted.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.notifications.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.search.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2/SG6 search selects Advanced and its View tier anchor, and restores focus to Advanced after query clearing; the new section identity replaces Developer. Appearance setup remains explicit."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.search.test.ts"
      ],
      "class": "New",
      "behaviour": "SG2/SG6 search selects Advanced and its View tier anchor, and restores focus to Advanced after query clearing; the new section identity replaces Developer. Appearance setup remains explicit.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2/SG6 search selects Advanced and its View tier anchor, and restores focus to Advanced after query clearing; the new section identity replaces Developer. Appearance setup remains explicit."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-adapted.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.search.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.section-address.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG4 privacy-trust resolves to Security & privacy in the surface and its composed section-change event."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.section-address.test.ts"
      ],
      "class": "New",
      "behaviour": "SG4 privacy-trust resolves to Security & privacy in the surface and its composed section-change event.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG4 privacy-trust resolves to Security & privacy in the surface and its composed section-change event."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-adapted.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.section-address.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2/SG4/SG9 Layout and rail controls remain in Appearance, remembered section uses Advanced, and pending/live Security member requests select Security & privacy. Theme, layout, overrides, drafts and persistence oracles remain; the Layout radiogroup selector is scoped to its moved anchor."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"
      ],
      "class": "New",
      "behaviour": "SG2/SG4/SG9 Layout and rail controls remain in Appearance, remembered section uses Advanced, and pending/live Security member requests select Security & privacy. Theme, layout, overrides, drafts and persistence oracles remain; the Layout radiogroup selector is scoped to its moved anchor.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2/SG4/SG9 Layout and rail controls remain in Appearance, remembered section uses Advanced, and pending/live Security member requests select Security & privacy. Theme, layout, overrides, drafts and persistence oracles remain; the Layout radiogroup selector is scoped to its moved anchor."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-adapted.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2/SG6 the Switcher entry keeps its shipped search words and now belongs to Appearance."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts"
      ],
      "class": "New",
      "behaviour": "SG2/SG6 the Switcher entry keeps its shipped search words and now belongs to Appearance.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2/SG6 the Switcher entry keeps its shipped search words and now belongs to Appearance."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-adapted.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG4 replaces privacy-trust in the emitted address with canonical security-privacy while keeping one-step history close behavior."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"
      ],
      "class": "New",
      "behaviour": "SG1 default Ctrl+comma opens Settings from Search/text inputs and repeats without history changes, excluding IME, repeat and extra modifiers. SG4 legacy section/tab/category plus explicit entry resolves to General > Keyboard; section events emit canonical Security & privacy.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG1 default Ctrl+comma opens Settings from Search/text inputs and repeats without history changes, excluding IME, repeat and extra modifiers. SG4 legacy section/tab/category plus explicit entry resolves to General > Keyboard; section events emit canonical Security & privacy."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-sections.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#SG1 Ctrl+comma opens over Search, repeats in place and honours typing, IME, repeat and modifier guards",
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#SG4 legacy coordinate section=layout&entry=keyboard arrives at General and the Keyboard anchor",
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#SG4 legacy coordinate category=layout&entry=keyboard arrives at General and the Keyboard anchor",
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#SG4 legacy coordinate tab=layout&anchor=keyboard arrives at General and the Keyboard anchor"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG5 recovery now selects Data & reset rather than preserving the previously open section; no View-tier detour, invocation, or audience change is allowed."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "SG5 recovery from Appearance selects Data & reset and focuses the actual Reset button for USER, OPERATOR and DEVELOPER without invoking it. Confirmation/error/success and the existing USER recovery exception retain their checks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG5 recovery from Appearance selects Data & reset and focuses the actual Reset button for USER, OPERATOR and DEVELOPER without invoking it. Confirmation/error/success and the existing USER recovery exception retain their checks."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-sections.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#SG5 R2 selects Data & reset from another section and focuses Reset in every tier without invoking it"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG4 maps a Developer link and reload to Advanced > View tier. SG1/SG3/SG4/SG8/SG9 supply the additional browser contracts below."
        }
      ],
      "conflictSearch": "Decision records searched for Settings category/section placement, Reset header and Developer navigation conflicts: none. ADR-0023 concerns persistence receipts and is unchanged. Settled SG amendments override the draft; developer-tools SD rows remain outside candidate G."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SG1 browser Ctrl+comma preserves composer draft/caret and history, command guard blocks other modals, higher-priority bindings win, and More/status transients dismiss with focus returned to a surviving trigger. SG3/SG4 pending/background projection cannot overwrite an addressed Settings entry; reload and close retain the underlying area, and section changes remove stale anchors. SG8 narrow enlarged-text navigation and local Escape order work. SG9 real Gallery/Editor instances and editor drafts survive sibling apply and leaving/returning.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG1 browser Ctrl+comma preserves composer draft/caret and history, command guard blocks other modals, higher-priority bindings win, and More/status transients dismiss with focus returned to a surviving trigger. SG3/SG4 pending/background projection cannot overwrite an addressed Settings entry; reload and close retain the underlying area, and section changes remove stale anchors. SG8 narrow enlarged-text navigation and local Escape order work. SG9 real Gallery/Editor instances and editor drafts survive sibling apply and leaving/returning."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-browser.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG1 browser Ctrl+comma opens once from a composer and restores the editable control and caret",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG1 command guard blocks the palette and another modal",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG1 user and plugin chords outrank the Settings default, which opens after they are removed",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG1 the Settings command dismisses jf-top-bar .more-trigger and closes onto its surviving trigger",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG1 the Settings command dismisses jf-status-pill .trigger and closes onto its surviving trigger",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG3 SG4 an addressed entry survives underlying background projection, reload and a one-step close; section changes remove its anchor",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG8 at 600x400 and 200 percent text every section remains reachable, and Escape cancels JSON import before closing",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG4 selecting another section removes the old Theme anchor before reload",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG9 real Gallery and Editor keep sibling instances and editor drafts on apply and section return"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "class": "New",
      "behaviour": "SG2 seven ordered sections place every native/member entry exactly once. SG3/SG4 old categories and explicit entries resolve with entry precedence, latest pending/live arrivals alone scroll/focus and no saved-state writes. SG5 recovery selects Data & reset. SG6 live audience changes refresh content, anchors and search results; hidden destinations explain audience restrictions. The later Show developer tools entry, legacy Developer anchor and amended search/navigation/content focus-rescue targets in this shared test file belong to s6-devtools.md. SG7 About names bundle/updater sources and known/unknown/unavailable/observed update states, leaving actions in General. SG8 local theme import/rename Escape is consumed. SG9 member instances/drafts remain independent; missing, loading or unregistered members leave siblings usable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG2 seven ordered sections place every native/member entry exactly once. SG3/SG4 old categories and explicit entries resolve with entry precedence, latest pending/live arrivals alone scroll/focus and no saved-state writes. SG5 recovery selects Data & reset. SG6 live audience changes refresh content, anchors and search results; hidden destinations explain audience restrictions. The later Show developer tools entry, legacy Developer anchor and amended search/navigation/content focus-rescue targets in this shared test file belong to s6-devtools.md. SG7 About names bundle/updater sources and known/unknown/unavailable/observed update states, leaving actions in General. SG8 local theme import/rename Escape is consumed. SG9 member instances/drafts remain independent; missing, loading or unregistered members leave siblings usable."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-sections.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SG3 a background claim of conversation B in the real Search window while Settings is open survives closing Settings by Escape, Back or the close button: the window keeps B, its address names B, history gains no entry, Forward reopens Settings once, and reload shows B. SG4 a member address (security, gallery, editor) at launch and while Settings is open or closed is replaced by the canonical Settings section and anchor without an extra history entry, and focuses the member. SG8 at 600x400 with 200 percent text every enabled control reached by Tab in every section is in view when focused (a control taller than the viewport shows its caret line), no button is cut off sideways, and Settings has exactly one scroll container.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG3 a background claim of conversation B in the real Search window while Settings is open survives closing Settings by Escape, Back or the close button: the window keeps B, its address names B, history gains no entry, Forward reopens Settings once, and reload shows B. SG4 a member address (security, gallery, editor) at launch and while Settings is open or closed is replaced by the canonical Settings section and anchor without an extra history entry, and focuses the member. SG8 at 600x400 with 200 percent text every enabled control reached by Tab in every section is in view when focused (a control taller than the viewport shows its caret line), no button is cut off sideways, and Settings has exactly one scroll container."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG3 dismissal by Escape preserves the real Search window current conversation and reload address",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG3 dismissal by Back preserves the real Search window current conversation and reload address",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG3 dismissal by Close preserves the real Search window current conversation and reload address",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG4 member core.security-surface arrivals replace the address before and after Settings mounts without another history entry",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG4 member core.presentation-gallery-surface arrivals replace the address before and after Settings mounts without another history entry",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG4 member core.presentation-editor-surface arrivals replace the address before and after Settings mounts without another history entry",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG8 at 600x400 and 200 percent text every section and enabled control is in view on keyboard focus, no button is clipped sideways, one scroller when short"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SG8 at 1280x720 with 100 percent text every enabled control reached by Tab in every section is in view when focused and no button is cut off sideways; the wide two-pane layout is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG8 at 1280x720 with 100 percent text every enabled control reached by Tab in every section is in view when focused and no button is cut off sideways; the wide two-pane layout is unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-guards.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG8 at 1280x720 and 100 percent text every section and enabled control is in view on keyboard focus, no button is clipped sideways, one scroller when short"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/SettingsNav.forced-colors-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SG8 forced colours give the selected Data & reset category the system Highlight/HighlightText pair and HighlightText keyboard focus ring, including hover, on light and dark palettes; unselected danger rows keep browser colour adjustment.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG8 forced colours give the selected Data & reset category the system Highlight/HighlightText pair and HighlightText keyboard focus ring, including hover, on light and dark palettes; unselected danger rows keep browser colour adjustment."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-forced-colors.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/SettingsNav.forced-colors-lockdown.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SG4 opening the app cold at a Security & privacy row below the Security panel (old section=agent, plugins with entry durable-grants, desktop with entry feedback-capture) and reloading its canonical address end with that row in view and focused once the Security panel has rendered the Engine's late status and chat-encryption replies, and the row does not move afterwards.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG4 opening the app cold at a Security & privacy row below the Security panel (old section=agent, plugins with entry durable-grants, desktop with entry feedback-capture) and reloading its canonical address end with that row in view and focused once the Security panel has rendered the Engine's late status and chat-encryption replies, and the row does not move afterwards."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-arrival.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG4 a cold section=agent arrival below the Security panel ends in view and focused after the Engine answers late, also on reload",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG4 a cold section=plugins&anchor=durable-grants arrival below the Security panel ends in view and focused after the Engine answers late, also on reload",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SG4 a cold section=desktop&anchor=feedback-capture arrival below the Security panel ends in view and focused after the Engine answers late, also on reload"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "class": "New",
      "behaviour": "SG5 SG6 for a USER outside settings recovery Data & reset neither renders a Reset settings heading nor lists or searches Reset; during settings recovery a USER gets Reset in content, navigation and search, and when recovery ends it leaves all three. SG6 SG7 in the browser General neither renders, lists nor searches the Desktop and App updates rows, About offers no Open Updates link, and an old update link opens General without explaining or focusing a missing row.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG5 SG6 for a USER outside settings recovery Data & reset neither renders a Reset settings heading nor lists or searches Reset; during settings recovery a USER gets Reset in content, navigation and search, and when recovery ends it leaves all three. SG6 SG7 in the browser General neither renders, lists nor searches the Desktop and App updates rows, About offers no Open Updates link, and an old update link opens General without explaining or focusing a missing row."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-gates.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG5 SG6 Reset to defaults is listed, searched and rendered only where its operation policy offers it (USER)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG5 SG6 settings recovery offers Reset to a USER in content, navigation and search; ending it removes Reset everywhere",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG6 SG7 in the browser, rows this platform renders are the rows navigation, search and About offer"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "class": "New",
      "behaviour": "SG5 SG6 OPERATOR and DEVELOPER keep Reset settings with the core.reset-settings operation in Data & reset content, navigation and search. SG6 SG7 on desktop General keeps Desktop and App updates in content, navigation and search, every listed row renders content, About's Open Updates opens General at Check for updates, and an old update link focuses App updates.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG5 SG6 OPERATOR and DEVELOPER keep Reset settings with the core.reset-settings operation in Data & reset content, navigation and search. SG6 SG7 on desktop General keeps Desktop and App updates in content, navigation and search, every listed row renders content, About's Open Updates opens General at Check for updates, and an old update link focuses App updates."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-gates-kept.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG5 SG6 Reset to defaults is listed, searched and rendered only where its operation policy offers it (OPERATOR)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG5 SG6 Reset to defaults is listed, searched and rendered only where its operation policy offers it (DEVELOPER)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG6 SG7 in the desktop, rows this platform renders are the rows navigation, search and About offer"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ContextWindow.home.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Settings links to AI Brain: only setup coordinates move. The former Agent page held both Agent autonomy and the Reading memory link; the check now selects Security & privacy before its Agent autonomy assertion and General before its no-second-control assertion and the link activation. Every assertion, including the navigation detail, is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG9 the AI Brain reading-memory link still arrives; the former Agent page is split by the placement map, so the check reads Agent autonomy in Security & privacy and the link in General."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "class": "New",
      "behaviour": "SG5 SG6 in Session-only mode (backend settings in memory) Data & reset neither renders an empty Reset settings heading nor lists or searches Reset for OPERATOR, DEVELOPER or a USER in settings recovery; an old Reset link and recovery navigation open Data & reset without explaining or focusing the missing row.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG5 SG6 in Session-only mode (backend settings in memory) Data & reset neither renders an empty Reset settings heading nor lists or searches Reset for OPERATOR, DEVELOPER or a USER in settings recovery; an old Reset link and recovery navigation open Data & reset without explaining or focusing the missing row."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-session-only.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG5 SG6 in Session-only mode Reset to defaults is neither rendered empty, listed, searched nor arrived at (OPERATOR)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG5 SG6 in Session-only mode Reset to defaults is neither rendered empty, listed, searched nor arrived at (DEVELOPER)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts#SG5 SG6 in Session-only mode Reset to defaults is neither rendered empty, listed, searched nor arrived at (USER in settings recovery)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG4 SG5 Settings addresses name a section and row: a recovery arrival's address names Data & reset at Reset to defaults instead of the bare Settings address, and a Security member deep-link delivers Security & privacy with the member as its row instead of the member as a Settings page."
        }
      ],
      "conflictSearch": "Decision records searched for Settings address, recovery navigation and member deep-link conflicts: none. Settled SG3/SG4/SG5 rows map old members to section rows and recovery to Data & reset > Reset to defaults."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "SG5 a settings-recovery arrival (in-app boolean or URL string) consumes its focus flag before Back and Forward, and its address names Data & reset at Reset to defaults before Settings mounts; Back closes and Forward reopens without focusing again.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG5 a settings-recovery arrival (in-app boolean or URL string) consumes its focus flag before Back and Forward, and its address names Data & reset at Reset to defaults before Settings mounts; Back closes and Forward reopens without focusing again."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-recovery-address.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#SG5 R2 consumes recovery focus ('in-app boolean') before Back and Forward; the address names Data & reset at Reset",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#SG5 R2 consumes recovery focus ('URL string') before Back and Forward; the address names Data & reset at Reset"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "SG4 a Security member deep-link opens the Settings window, closed or already open, and delivers the Security & privacy section with the Security member as its row, without stacking a history entry.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SG4 a Security member deep-link opens the Settings window, closed or already open, and delivers the Security & privacy section with the Security member as its row, without stacking a history entry."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-settings-member-rows.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#SG4 a member deep-link opens the window AND delivers the member's section and row (window closed)",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#SG4 a member deep-link delivers the member's section and row while the window is ALREADY open"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "f3xlx3bo-verifier",
  "verdict": "reject",
  "digest": "sha256:bc32468e35ec2fb5e283202d74b4ce422dd00f53324c96c644578c373377f710",
  "note": "Current candidate fails the test-intent evidence contract: s6-settings-adapted, s6-settings-sections, s6-settings-browser, s6-settings-repairs and s6-settings-guards cite test file digests changed by this branch (23 evidence-invalid errors). Refresh evidence for final files and reconcile the superseded View tier legacy-address check before renewed acceptance. The reload wait change preserves the assertion but does not refresh these evidence files."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "f3xlx3bo-verifier",
  "verdict": "reject",
  "digest": "sha256:f65082f37a41b14cec666ff5ebf6a468f111c0fffade99231e59dc372dd4202c",
  "note": "Against the specified whole-branch baseline c46bf1a6cc, current evidence has 23 evidence-invalid failures: final test files differ from s6-settings-adapted/sections/browser/repairs/guards runs. Refresh the final-file evidence and superseded legacy Developer View tier check. Runtime rechecks do not repair these committed evidence digests."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c850-d13a-7e82-9414-69c4d92f8a2d",
  "verdict": "accept",
  "digest": "sha256:7ecd0eb2f0b9e414011d0365420e83db72ccfe8e803678035c78f18a471e58c0"
}
```
