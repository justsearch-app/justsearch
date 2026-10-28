---
classification: unused-export-shrink
tempdoc: 852
---

SA15/WP1.5b retires the old host and its private closure. Knip was regenerated on the combined tree. No pin increases or dummy imports. The separate D-T40 declaration records only the explicitly retained public run-step capability. Surviving canonical projection/evidence/client helpers are module-private where no external caller remains. D-T40 retains the public run-step capability with an explicit declared-growth changeset; its Knip count stays at the existing pin of 1.

- `src/shell-v0/shell/Shell.ts`: 4 → 2 (one unused export plus the pre-existing unlisted transitive import, both counted by the ratchet).
- `src/shell-v0/state/aiStateStore.ts`: 8 → 7.
- `src/api/http.ts`: 4 → 3.
- `src/api/domains/settings.ts`: 5 → 4.
- `src/shell-v0/renderers/registry.ts`: 5 → 1.
- `src/shell-v0/state/askChatState.ts`: 4 → 3.
- `src/api/domains/packs.ts`: 13 → 12.
- `src/shell-v0/views/search-v3/Sv3Sidebar.ts`: 6 → 5.
- `src/shell-v0/plugin-api/plugin-types.ts`: 30 → 24.
- `src/shell-v0/handshake/capabilities-types.ts`: 5 → 1.
- `src/shell-v0/components/SelectionActionsMenu.ts`: 1 → 0.
- `src/shell-v0/utils/selectionAnchor.ts`: 3 → 0.
- `src/shell-v0/views/unifiedThreadProjection.ts`: 1 → 0.
- `src/shell-v0/components/chat/recordEvidence.ts`: 1 → 0.
