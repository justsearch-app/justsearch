---
title: "Simple and Detailed UI modes"
type: reference
status: stable
description: "The app-wide disclosure-level contract, its Lit state authority, projections, and stable wire values."
---

# Simple and Detailed UI modes

JustSearch has one app-wide disclosure level with two user-facing choices:

| Mode | Purpose |
|---|---|
| **Simple** (default) | Keeps routine actions and plain-language status visible while withholding optional technical detail. |
| **Detailed** | Reveals diagnostics, raw identifiers, full paths, runtime configuration, and other power-user detail. |

**Detailed** is the only user-facing name for the second mode. The persisted API value remains
`advanced` for compatibility; code may therefore use the `UiMode` value `advanced` and the predicate
`isAdvancedMode()`, but rendered labels and user instructions say **Detailed**.

## Authority and projections

`modules/ui-web/src/shell-v0/state/uiModeState.ts` is the live frontend authority. It normalizes the
persisted `ui.mode` setting, publishes changes, lets Lit surfaces subscribe, and serializes mode writes
from every projection. The mode controls in the top bar, Settings, and AI Brain are projections of that
same value; changing any one updates the others immediately and enqueues the compatible value for
`/api/settings/v2`. The shared queue preserves user-intent order across controls, not only within one
surface. Each queued request receives an abort signal and is bounded to 10 seconds, so a lost response
cannot wedge later choices. Each request also carries a monotonic intent token. Its client ID and
sequence are held in origin storage, and sequence allocation uses the Web Locks API, so reloads and
additional shell windows stay in one ordering domain. The backend ignores an older token's mode field
if an aborted handler reaches persistence after a newer choice. The
backend serializes each partial settings request's whole-document
load/merge/save transaction, preventing a concurrent theme, LLM, or library patch from restoring an
older mode. Persistence feedback follows the initiating control's existing lifecycle: the top bar is
best-effort, Settings reports its save state, and Brain rolls back its latest still-current choice when
that save is rejected or times out.

Consumers must read or subscribe through `uiModeState` rather than maintaining a second local mode.
The active projections include:

- `chrome/Shell.ts` for the top-bar control and rail visibility;
- `views/SettingsSurface.ts` for the Interface setting;
- `views/BrainSurface.ts` for AI setup versus runtime detail;
- search, chat, result, and document components that choose plain-language or technical detail.

## Lit usage

Use the shared store directly in Lit components:

```typescript
import {
  enqueueUiModePersistence,
  getUiMode,
  getUiModeRevision,
  isAdvancedMode,
  setUiMode,
  subscribeUiMode,
} from '../state/uiModeState.js';
```

- Use `isAdvancedMode()` for an immediate disclosure decision.
- Use `subscribeUiMode()` when a mounted component must rerender after another control changes the mode.
- Use `setUiMode()` when publishing a successfully loaded or intentionally changed value.
- Use `enqueueUiModePersistence()` for every `ui.mode` write so different controls cannot persist out
  of order; pass its supplied abort signal and `X-JustSearch-UI-Mode-Intent` value to the request.
- Compare `getUiModeRevision()` around a surface-local asynchronous read before adopting its mode.
- Keep the wire values `simple` and `advanced`; map them to **Simple** and **Detailed** at presentation sites.

The store defaults to Simple until the boot appearance restore loads persisted settings. A projecting
surface must not overwrite a newer shared choice with a slower local settings response.

## Verification

`uiModeState.test.ts` pins normalization, subscription, queued-write, timeout, and reload-persistence
behavior. Surface tests pin the visible
Simple/Detailed labels and mode-specific disclosure. Changes to a projecting surface also require the
affected `jsharness ui-shot` measurement and the shell-v0 UI coverage check.
