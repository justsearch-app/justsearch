// SPDX-License-Identifier: Apache-2.0
/**
 * KeybindingRegistry — Tempdoc 508 §3.4 — keyboard shortcut registry.
 *
 * Stores keybinding entries with platform-aware modifier mapping
 * (Cmd on Mac, Ctrl on Windows/Linux). Most-specific binding wins
 * on conflict (user > plugin > default). User overrides persist in
 * UserStateDocument.
 */

import { getDocument, mutateDocument } from '../state/UserStateDocument.js';
import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';
import { isTypingTarget, shouldIgnoreKeyEvent } from '../utils/keyboardHandler.js';
import { modalOwnsFocus } from '../primitives/modality.js';
import { makeCoreProvenance } from '../primitives/provenance.js';
import type { Provenance } from '../primitives/provenance.js';

export interface KeybindingEntry {
  /** Key combo like 'ctrl+k', 'cmd+shift+p', 'mod+/'. `mod` maps to ctrl on Win/Linux and cmd on Mac. */
  readonly key: string;
  /** Command id resolved against CommandRegistry. */
  readonly commandId: string;
  /**
   * Tempdoc 508 §11.1 / §13.1 — when-expression evaluated against
   * the live ShellContext at keydown time. Bindings with `when` only
   * fire when the expression evaluates true. Absent = always-fire
   * (preserves V1 behavior for the existing default bindings).
   */
  readonly when?: string;
  /** Where this binding came from. */
  readonly source: 'default' | 'user' | 'plugin';

  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
}

/**
 * C8 — commands that are also a text field's own (Undo, Redo), under whatever key they are bound:
 * inside an editable control, or while a modal owns focus, the key keeps its native meaning.
 */
const TEXT_EDIT_COMMANDS: ReadonlySet<string> = new Set(['shell.undo', 'shell.redo', 'core.action.shell.undo', 'core.action.shell.redo']);

interface ParsedKey {
  key: string;
  mod: boolean;
  ctrl: boolean;
  meta: boolean;
  alt: boolean;
  shift: boolean;
}

function parseKey(combo: string): ParsedKey {
  const parts = combo.toLowerCase().split('+').map((p) => p.trim());
  const out: ParsedKey = {
    key: '',
    mod: false,
    ctrl: false,
    meta: false,
    alt: false,
    shift: false,
  };
  for (const p of parts) {
    if (p === 'mod') out.mod = true;
    else if (p === 'ctrl' || p === 'control') out.ctrl = true;
    else if (p === 'cmd' || p === 'meta' || p === 'command') out.meta = true;
    else if (p === 'alt' || p === 'option') out.alt = true;
    else if (p === 'shift') out.shift = true;
    else out.key = p;
  }
  return out;
}

export function isMac(): boolean {
  if (typeof navigator === 'undefined') return false;
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform);
}

function matchesEvent(parsed: ParsedKey, e: KeyboardEvent): boolean {
  if (e.key.toLowerCase() !== parsed.key) return false;
  const ctrl = parsed.ctrl || (parsed.mod && !isMac());
  const meta = parsed.meta || (parsed.mod && isMac());
  if (e.ctrlKey !== ctrl || e.metaKey !== meta || e.altKey !== parsed.alt || e.shiftKey !== parsed.shift) return false;
  if (e.getModifierState?.('AltGraph')) return false;
  return true;
}

const bindings = new Map<string, KeybindingEntry>();
const listeners = new Set<() => void>();
let invokeHandler: ((commandId: string) => void) | null = null;
let attached = false;
let keydownHandler: ((e: KeyboardEvent) => void) | null = null;
let nativeSearchShortcutActive = false;

/** Native registration wins over an overlapping binding without rewriting the saved binding. */
export function setNativeSearchShortcutActive(active: boolean): void {
  nativeSearchShortcutActive = active;
}

function bindingKey(entry: KeybindingEntry): string {
  return `${entry.key}::${entry.commandId}`;
}

function notify(): void {
  for (const l of listeners) {
    try { l(); } catch { /* swallow */ }
  }
}

function loadUserOverrides(): KeybindingEntry[] {
  const doc = getDocument() as unknown as { keybindingOverrides?: KeybindingEntry[] };
  return doc.keybindingOverrides ?? [];
}

function updateUserOverrides(update: (overrides: KeybindingEntry[]) => KeybindingEntry[]): void {
  mutateDocument((doc) => ({
    ...doc,
    keybindingOverrides: update((doc as unknown as { keybindingOverrides?: KeybindingEntry[] }).keybindingOverrides ?? []),
  } as unknown as ReturnType<typeof getDocument>));
}

export function registerKeybinding(entry: KeybindingEntry): void {
  bindings.set(bindingKey(entry), entry);
  if (entry.source === 'user') {
    updateUserOverrides((overrides) => [...overrides.filter((b) => b.key !== entry.key), entry]);
  }
  notify();
}

export function unregisterKeybinding(key: string, commandId: string): boolean {
  const removed = bindings.delete(`${key}::${commandId}`);
  if (removed) notify();
  return removed;
}

export function listKeybindings(): KeybindingEntry[] {
  return Array.from(bindings.values());
}

export function onKeybindingChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Attach the global keydown listener that dispatches to commands.
 * Calls invokeHandler(commandId) on match. `mod` is platform-aware.
 */
export function attachKeybindingDispatcher(invoke: (commandId: string) => void): () => void {
  invokeHandler = invoke;
  if (attached) return () => detachKeybindingDispatcher();
  attached = true;
  keydownHandler = (e: KeyboardEvent) => {
    if (!invokeHandler) return;
    if (nativeSearchShortcutActive && e.altKey && e.shiftKey && !e.ctrlKey && !e.metaKey
      && (e.code === 'Space' || e.key === ' ')) {
      e.preventDefault();
      e.stopImmediatePropagation();
      return;
    }
    // Tempdoc 864 Layer 2(b) — IME composition and auto-repeat, by the app's ONE definition, shared
    // verbatim with the two raw window/document listeners that are NOT registered bindings.
    if (shouldIgnoreKeyEvent(e)) return;
    // Priority order: user > plugin > default. Iterate in reverse so user wins.
    const entries = Array.from(bindings.values()).sort((a, b) => {
      const order = { user: 0, plugin: 1, default: 2 };
      return order[a.source] - order[b.source];
    });
    // §11.1 / §13.1 — evaluate `when` against the live ShellContext.
    // A binding without `when` always fires (V1 behavior preserved).
    const ctx = getShellContext() as unknown as Record<string, unknown>;
    // Search Thread S2 — modifier-less bindings (e.g. '/') must not hijack typing:
    // when the event originates in an editable control, only modified chords fire.
    // (The listener is capture-phase on window, so without this guard a plain-key
    // binding would steal characters from every input in the app.)
    // Tempdoc 857 PR-A — the SUBJECT stays this registry's own (`composedPath()[0]`, where the event
    // came FROM, which is not the question "where is focus now?" that the shadow-root descent
    // answers). Only the PREDICATE is shared: what counts as an editable is one definition for the
    // whole app, and this file held the third copy of it.
    const path = e.composedPath();
    const origin = (path.length > 0 ? path[0] : e.target) as HTMLElement | null;
    const inEditable = isTypingTarget(origin);
    // Tempdoc 864 Layer 2(d) — while a modal is open, a modifier-less printable belongs to the
    // modal's own content, not to a global command. Chorded bindings are deliberately NOT blocked:
    // `mod+k` is how the palette itself is toggled, and blocking it would trap the reader inside.
    const modalOpen = modalOwnsFocus();
    for (const entry of entries) {
      const parsed = parseKey(entry.key);
      if (!matchesEvent(parsed, e)) continue;
      const modifierless = !parsed.mod && !parsed.ctrl && !parsed.meta && !parsed.alt;
      if (inEditable && modifierless) continue;
      if (modalOpen && modifierless) continue;
      if (TEXT_EDIT_COMMANDS.has(entry.commandId) && (inEditable || modalOpen)) continue;
      if (!evaluateWhen(entry.when, ctx)) continue;
      e.preventDefault();
      invokeHandler(entry.commandId);
      return;
    }
  };
  window.addEventListener('keydown', keydownHandler, true);
  return () => detachKeybindingDispatcher();
}

export function detachKeybindingDispatcher(): void {
  if (keydownHandler) {
    window.removeEventListener('keydown', keydownHandler, true);
    keydownHandler = null;
  }
  attached = false;
  invokeHandler = null;
}

/**
 * Load user overrides from UserStateDocument and register them.
 * Called at boot after UserStateDocument initialization.
 */
export function loadPersistedKeybindings(): void {
  const overrides = loadUserOverrides();
  for (const raw of overrides) {
    // F18: the persisted shape (Profile.keybindingOverrides) carries only
    // {key, commandId, source} — `provenance` (required since tempdoc 543)
    // and `when` are stripped by parseKeybindingOverrides. The cast in
    // loadUserOverrides hides that, so reconstruct here: stamp a CORE-tier
    // provenance fallback (the convention user-source bindings use) so the
    // required-provenance invariant holds for reloaded user bindings.
    const r = raw as {
      key: string;
      commandId: string;
      when?: string;
      provenance?: Provenance;
    };
    const entry: KeybindingEntry = {
      key: r.key,
      commandId: r.commandId,
      source: 'user',
      ...(typeof r.when === 'string' ? { when: r.when } : {}),
      provenance: r.provenance ?? makeCoreProvenance(),
    };
    bindings.set(bindingKey(entry), entry);
  }
}

/**
 * Tempdoc 508-followup §β4 — rebind user-source entries on profile
 * switch. Drops every entry tagged source='user' (the previous
 * profile's overrides), then re-loads from UserStateDocument so the
 * new active profile's overrides take effect. Default + plugin
 * bindings are untouched — those don't belong to any profile.
 */
export function rebindUserKeybindings(): void {
  for (const key of Array.from(bindings.keys())) {
    const entry = bindings.get(key);
    if (entry?.source === 'user') {
      bindings.delete(key);
    }
  }
  loadPersistedKeybindings();
  notify();
}

export function __resetForTest(): void {
  bindings.clear();
  listeners.clear();
  detachKeybindingDispatcher();
}

/** Read the same priority/when/modifier resolution as dispatch, without rewriting overrides. */
export function effectiveShortcut(commandId: string): string | undefined {
  const ctx = getShellContext() as unknown as Record<string, unknown>;
  const entries = [...bindings.values()].sort((a, b) =>
    ({ user: 0, plugin: 1, default: 2 }[a.source] - { user: 0, plugin: 1, default: 2 }[b.source]));
  for (const entry of entries) {
    if (entry.commandId !== commandId || !evaluateWhen(entry.when, ctx)) continue;
    const key = parseKey(entry.key);
    const event = new KeyboardEvent('keydown', {
      key: key.key, ctrlKey: key.ctrl || (key.mod && !isMac()), metaKey: key.meta || (key.mod && isMac()),
      altKey: key.alt, shiftKey: key.shift,
    });
    const winner = entries.find(candidate => matchesEvent(parseKey(candidate.key), event) && evaluateWhen(candidate.when, ctx));
    if (winner !== entry) continue;
    return [event.ctrlKey ? 'Ctrl' : '', event.metaKey ? 'Cmd' : '', event.altKey ? 'Alt' : '',
      event.shiftKey ? 'Shift' : '', key.key.length === 1 ? key.key.toUpperCase() : key.key]
      .filter(Boolean).join(' ');
  }
  return undefined;
}
