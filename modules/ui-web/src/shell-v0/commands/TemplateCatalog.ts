// SPDX-License-Identifier: Apache-2.0
/**
 * TemplateCatalog — Tempdoc 508 §11.7 / §13.7 — parametric search /
 * action templates with Raycast-style named slots and ambient
 * bindings.
 *
 * A template is an invocable command with optional slots that get
 * bound at invocation time from one of three sources:
 *   - "prompt": user-typed at invocation (one-shot prompt UI per
 *     slot, in declaration order).
 *   - "clipboard": navigator.clipboard.readText().
 *   - "selection" / "primarySelection": current selectionState
 *     (denied for UNTRUSTED templates at registration time).
 *   - "date" / "time" / "datetime" / "day" / "uuid":
 *     ambient computed values.
 *   - "currentSurface" / "activeProfile": ShellContext values.
 *
 * Template syntax (Raycast precedent):
 *   - {argument name="topic" default="recent"}  — named user arg
 *   - {clipboard} {selection} {date} ...        — bare ambient
 *   - {{ }} {{{}} ...                           — literal `{` / `}`
 *
 * Slot cap: soft 5 (warns), hard 8 (throws at register time).
 *
 * Projection to CommandRegistry: each registered template appears
 * as a Command with source='plugin' (TRUSTED+) or source='shell'
 * (core templates). Invoking the command resolves the slots, runs
 * the action (search / navigate / arbitrary handler), and records
 * the invocation in recent commands.
 */

import { registerAction, unregisterAction } from '../substrates/actions/index.js';
import { getSelection, type SelectionItem } from '../state/selectionState.js';
import { getShellContext } from '../state/shellContextState.js';
import { evaluateSafeMath, formatSafeMathResult, SafeMathError } from './safeMath.js';
import type { Provenance } from '../primitives/provenance.js';

/** Allowed ambient binding sources. */
export const AMBIENT_BINDINGS = [
  'clipboard',
  'selection',
  'primarySelection',
  'date',
  'time',
  'datetime',
  'day',
  'uuid',
  'currentSurface',
  'activeProfile',
  // Tempdoc 521 §16.2 — `calculator` prompts the user for a single
  // arithmetic expression and resolves to its evaluated result (safeMath,
  // no eval()). TRUST_RESTRICTED below: UNTRUSTED templates cannot bind
  // it because the user-supplied expression is hostile-input territory.
  'calculator',
] as const;
export type AmbientBinding = (typeof AMBIENT_BINDINGS)[number];

/** Soft warning threshold; hard cap is enforced. */
export const TEMPLATE_SLOT_SOFT_CAP = 5;
export const TEMPLATE_SLOT_HARD_CAP = 8;

/** Trust-attenuated binding sources — UNTRUSTED templates cannot use these. */
const TRUST_RESTRICTED: ReadonlySet<AmbientBinding> = new Set([
  'selection',
  'primarySelection',
  'clipboard',
  'calculator',
]);

export interface NamedArgumentSlot {
  readonly kind: 'argument';
  readonly name: string;
  readonly default?: string;
}

export interface AmbientSlot {
  readonly kind: 'ambient';
  readonly binding: AmbientBinding;
}

/**
 * Tempdoc 521 §16.2 deeper (Raycast inter-slot form) — a compute slot
 * carries an arithmetic expression whose tokens may include
 * `{argument-slot-name}` references. At expansion time, references are
 * substituted with the resolved argument-slot values (Pass 1), then the
 * resulting expression is evaluated through safeMath (Pass 2). Compute
 * slots are TRUST_RESTRICTED: UNTRUSTED templates cannot register them
 * because the substituted expression is user-supplied input flowing
 * into an evaluator.
 *
 * Syntax: `{compute:<expression>}` — the expression body may freely
 * use `{name}` to reference a sibling `argument` slot's resolved value.
 * Compute → compute references are rejected at registration to keep
 * the expansion order deterministic.
 */
export interface ComputeSlot {
  readonly kind: 'compute';
  readonly expression: string;
}

export type TemplateSlot = NamedArgumentSlot | AmbientSlot | ComputeSlot;

export interface TemplateRegistration {
  readonly id: string;
  readonly label: string;
  readonly category?: string;
  readonly icon?: string;
  readonly source: 'core' | 'plugin';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /** Plugin trust tier for binding-source attenuation. */
  readonly trustTier?: 'CORE' | 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN';
  /** Visibility predicate (§11.1). */
  readonly when?: string;
  /** Template expression with {slot} placeholders. */
  readonly template: string;
  /**
   * Handler receives the expanded string after all slots resolve.
   * Common pattern: navigate to a search URL or run an operation.
   */
  readonly onInvoke: (expanded: string) => void | Promise<void>;
}

interface ParsedTemplate {
  readonly raw: string;
  readonly slots: ReadonlyArray<TemplateSlot>;
  /** Expansion plan: each segment is either literal text or a slot to fill. */
  readonly segments: ReadonlyArray<{ kind: 'text'; value: string } | { kind: 'slot'; index: number }>;
}

// Tempdoc 543 §28.W7 — shared registry primitive.
import { createRegistry } from '../primitives/registry.js';

const _registry = createRegistry<TemplateRegistration & { parsed: ParsedTemplate }>();
const templates = _registry._map;

/**
 * Tempdoc 508-followup §δ1 — global slot prompt provider. The
 * CommandPalette registers itself here on connectedCallback so
 * template invocations route through the inline wizard instead of
 * window.prompt. One provider at a time; absent → fall back.
 */
type SlotPromptProvider = (slot: NamedArgumentSlot, templateId: string) => Promise<string | null>;
let slotPromptProvider: SlotPromptProvider | null = null;
let slotPromptLifecycle: ((phase: 'start' | 'end') => void) | undefined;

export function setSlotPromptProvider(fn: SlotPromptProvider | null, lifecycle?: (phase: 'start' | 'end') => void): void {
  slotPromptProvider = fn;
  slotPromptLifecycle = lifecycle;
}

export class TemplateValidationError extends Error {}

/**
 * Parse a template expression. Returns segments + slots in
 * declaration order. Throws TemplateValidationError on:
 *   - malformed slot syntax
 *   - unknown ambient binding name
 *   - slot count > TEMPLATE_SLOT_HARD_CAP
 *   - {argument} missing name attribute
 */
export function parseTemplate(raw: string): ParsedTemplate {
  const segments: Array<{ kind: 'text'; value: string } | { kind: 'slot'; index: number }> = [];
  const slots: TemplateSlot[] = [];
  let i = 0;
  let buffer = '';

  while (i < raw.length) {
    const ch = raw[i]!;
    if (ch === '{' && raw[i + 1] === '{') {
      // Escape: {{ → literal {
      buffer += '{';
      i += 2;
      continue;
    }
    if (ch === '}' && raw[i + 1] === '}') {
      // Escape: }} → literal }
      buffer += '}';
      i += 2;
      continue;
    }
    if (ch === '{') {
      // Slot start. Find the matching unescaped '}'.
      const end = findSlotEnd(raw, i + 1);
      if (end === -1) {
        throw new TemplateValidationError(`unterminated slot starting at position ${i}`);
      }
      const inner = raw.slice(i + 1, end).trim();
      const slot = parseSlot(inner);
      if (buffer.length > 0) {
        segments.push({ kind: 'text', value: buffer });
        buffer = '';
      }
      segments.push({ kind: 'slot', index: slots.length });
      slots.push(slot);
      i = end + 1;
      continue;
    }
    buffer += ch;
    i++;
  }
  if (buffer.length > 0) {
    segments.push({ kind: 'text', value: buffer });
  }
  if (slots.length > TEMPLATE_SLOT_HARD_CAP) {
    throw new TemplateValidationError(
      `template has ${slots.length} slots; hard cap is ${TEMPLATE_SLOT_HARD_CAP}`,
    );
  }
  return { raw, slots, segments };
}

function findSlotEnd(raw: string, from: number): number {
  // Tempdoc 521 §16.2 deeper — depth-aware close. The compute slot's
  // expression body may legitimately contain `{name}` references to
  // sibling slots (e.g., `{compute:{n} * 7}`), so simple "next `}`
  // wins" doesn't hold. Inside a slot body the `{{` / `}}` template-
  // level escapes are NOT in scope (those are consumed by the outer
  // parseTemplate loop BEFORE we enter a slot); every `{` and `}`
  // here is grammatical. Any inner `{` increases depth; the outer
  // close is the `}` that brings depth back to 0.
  //
  // §22 Phase B regression fix: an earlier version of this function
  // treated `}}` as an escape inside the slot body, which broke
  // valid `{compute:{name}}` templates (the inner `}` consumed the
  // outer `}` as part of a "doubled close"). Escapes are template-
  // level only.
  let depth = 1;
  let i = from;
  while (i < raw.length) {
    const ch = raw[i]!;
    if (ch === '{') {
      depth++;
      i++;
      continue;
    }
    if (ch === '}') {
      depth--;
      if (depth === 0) return i;
      i++;
      continue;
    }
    i++;
  }
  return -1;
}

function parseSlot(inner: string): TemplateSlot {
  // Tempdoc 521 §16.2 deeper — `{compute:<expression>}` slot kind.
  // The expression body is preserved verbatim (including embedded
  // `{name}` references) for two-pass expansion later.
  if (inner.startsWith('compute:')) {
    const expression = inner.slice('compute:'.length).trim();
    if (expression.length === 0) {
      throw new TemplateValidationError('compute slot requires a non-empty expression');
    }
    return { kind: 'compute', expression };
  }
  if (inner.startsWith('argument')) {
    // {argument name="X" default="Y"}
    const nameMatch = /name=("([^"]*)"|'([^']*)')/.exec(inner);
    if (!nameMatch) {
      throw new TemplateValidationError('argument slot requires name="..."');
    }
    const name = nameMatch[2] ?? nameMatch[3]!;
    if (name.length === 0) {
      throw new TemplateValidationError('argument slot name cannot be empty');
    }
    const defaultMatch = /default=("([^"]*)"|'([^']*)')/.exec(inner);
    const def = defaultMatch ? (defaultMatch[2] ?? defaultMatch[3]) : undefined;
    return def !== undefined
      ? { kind: 'argument', name, default: def }
      : { kind: 'argument', name };
  }
  // Ambient binding — single bareword.
  const bareMatch = /^[A-Za-z][\w]*$/.exec(inner);
  if (!bareMatch) {
    throw new TemplateValidationError(`unrecognized slot syntax: {${inner}}`);
  }
  const binding = inner as AmbientBinding;
  if (!AMBIENT_BINDINGS.includes(binding)) {
    throw new TemplateValidationError(`unknown ambient binding: {${inner}}`);
  }
  return { kind: 'ambient', binding };
}

/**
 * Register a template. Validates trust attenuation: UNTRUSTED tier
 * cannot bind `selection`, `primarySelection`, or `clipboard`.
 */
export function registerTemplate(reg: TemplateRegistration): void {
  const parsed = parseTemplate(reg.template);

  // Tempdoc 521 §16.2 deeper — compute slot validation. References must
  // point at argument slots that exist in this template; compute → compute
  // references are rejected (V1) to keep expansion order deterministic;
  // V1 cap of one compute slot per template (lift later if multi-compute
  // demand surfaces with a real DAG resolver).
  const computeSlots = parsed.slots.filter(
    (s): s is ComputeSlot => s.kind === 'compute',
  );
  if (computeSlots.length > 1) {
    throw new TemplateValidationError(
      `template '${reg.id}' has ${computeSlots.length} compute slots; V1 cap is 1.`,
    );
  }
  // Tempdoc 521 §22 Phase B — compute slots reference any *resolvable*
  // sibling: argument slots by name OR ambient bindings declared in the
  // same template. The latter widens the §16.2-deeper substrate from
  // "argument refs only" to "every Pass-1-resolvable slot," matching
  // §11.7's slot-composition design.
  const resolvableNames = new Set<string>([
    ...parsed.slots
      .filter((s): s is NamedArgumentSlot => s.kind === 'argument')
      .map((s) => s.name),
    ...parsed.slots
      .filter((s): s is AmbientSlot => s.kind === 'ambient')
      .map((s) => s.binding),
  ]);
  for (const c of computeSlots) {
    const refs = extractSlotReferences(c.expression);
    for (const ref of refs) {
      if (!resolvableNames.has(ref)) {
        throw new TemplateValidationError(
          `compute slot in '${reg.id}' references {${ref}}, but no argument ` +
            `or ambient slot with that name exists in the template.`,
        );
      }
    }
  }

  if (reg.trustTier === 'UNTRUSTED_PLUGIN') {
    for (const slot of parsed.slots) {
      if (slot.kind === 'ambient' && TRUST_RESTRICTED.has(slot.binding)) {
        throw new TemplateValidationError(
          `UNTRUSTED templates cannot bind {${slot.binding}} (data-exfil risk).` +
            ` Use {argument name="..."} for user-supplied input instead.`,
        );
      }
      if (slot.kind === 'compute') {
        throw new TemplateValidationError(
          `UNTRUSTED templates cannot use {compute:...} slots (evaluator risk).` +
            ` Use a plain {argument name="..."} slot instead.`,
        );
      }
    }
  }
  if (parsed.slots.length > TEMPLATE_SLOT_SOFT_CAP) {
    try {
      console.warn(
        `[TemplateCatalog] template '${reg.id}' has ${parsed.slots.length} slots; ` +
          `soft cap is ${TEMPLATE_SLOT_SOFT_CAP}. Above this, multi-prompt UX is awkward — ` +
          `consider a form surface instead.`,
      );
    } catch { /* swallow */ }
  }
  _registry.register({ ...reg, parsed });
  // §28.W10 — project into the Action substrate (was: Command). Templates
  // surface in the palette as `core.action.template.<id>`; CommandPalette
  // already lists Actions via the §21.C projection layer. Action handler
  // returns Effect.noop since template expansion's user callback
  // (`reg.onInvoke(expanded)`) is opaque to the substrate.
  registerAction({
    id: `core.action.template.${reg.id}`,
    title: reg.label,
    ...(reg.category !== undefined ? { category: reg.category } : {}),
    ...(reg.icon !== undefined ? { icon: reg.icon } : {}),
    provenance: reg.provenance,
    ...(reg.when !== undefined ? { when: reg.when } : {}),
    handler: async () => {
      // Tempdoc 508-followup §δ1 — use the registered slot prompt
      // provider (the CommandPalette wizard) when one is attached;
      // fall back to window.prompt for headless / pre-boot scenarios.
      const provider = slotPromptProvider;
      const lifecycle = slotPromptLifecycle;
      lifecycle?.('start');
      try {
        const expanded = await expandTemplate(parsed, reg.id, provider ?? defaultPromptFn);
        if (expanded === null) return { kind: 'noop' as const };
        await reg.onInvoke(expanded);
        return { kind: 'noop' as const };
      } finally { lifecycle?.('end'); }
    },
  });
}

export function unregisterTemplate(id: string): boolean {
  const removed = _registry.unregister(id);
  if (removed) {
    try {
      unregisterAction(`core.action.template.${id}`);
    } catch {
      /* swallow */
    }
  }
  return removed;
}

export function listTemplates(): ReadonlyArray<TemplateRegistration & { parsed: ParsedTemplate }> {
  return Array.from(_registry.list());
}

export const getTemplate = _registry.get;
export const onTemplateChange = _registry.subscribe;

/**
 * Resolve a parsed template against the current ambient state and
 * any user-supplied argument prompts. Returns null when the user
 * cancels a prompt; the caller should abort.
 *
 * Custom `promptFn` enables tests + alternative prompt UIs (e.g.,
 * an inline palette wizard). Default uses window.prompt().
 */
export async function expandTemplate(
  parsed: ParsedTemplate,
  templateId: string,
  promptFn: (slot: NamedArgumentSlot, templateId: string) => Promise<string | null> = defaultPromptFn,
): Promise<string | null> {
  // Tempdoc 521 §16.2 deeper — two-pass expansion. Pass 1 resolves all
  // argument and ambient slots, recording values by name so Pass 2 can
  // substitute `{name}` references inside compute slots. Compute slots
  // are evaluated last; cycles are impossible because compute slots
  // cannot reference other compute slots (validated at registration).
  //
  // §22 Phase B — `resolvedSlotValues` covers BOTH argument names and
  // ambient bindings, so a compute expression like `{n} + {hours}` (with
  // ambient `{hours}` resolving from clipboard / a numeric ambient)
  // works the same as `{n} + {m}` (two arguments).
  const values: string[] = new Array(parsed.slots.length).fill('');
  const resolvedSlotValues: Record<string, string> = {};

  // Pass 1: argument prompts + ambient resolution.
  for (let i = 0; i < parsed.slots.length; i++) {
    const slot = parsed.slots[i]!;
    if (slot.kind === 'argument') {
      const value = await promptFn(slot, templateId);
      if (value === null) return null;
      values[i] = value;
      resolvedSlotValues[slot.name] = value;
    } else if (slot.kind === 'ambient') {
      const value = await resolveAmbient(slot.binding);
      values[i] = value;
      resolvedSlotValues[slot.binding] = value;
    }
    // compute slots resolved in Pass 2.
  }

  // Pass 2: compute slot evaluation. Substitute `{name}` references
  // with their resolved argument values, then evaluate through safeMath.
  for (let i = 0; i < parsed.slots.length; i++) {
    const slot = parsed.slots[i]!;
    if (slot.kind === 'compute') {
      const substituted = substituteSlotReferences(slot.expression, resolvedSlotValues);
      try {
        values[i] = formatSafeMathResult(evaluateSafeMath(substituted));
      } catch (err) {
        if (err instanceof SafeMathError) {
          try { window.alert?.(`Compute: ${err.message}`); } catch { /* swallow */ }
        }
        values[i] = '';
      }
    }
  }

  let out = '';
  for (const seg of parsed.segments) {
    if (seg.kind === 'text') {
      out += seg.value;
    } else {
      out += values[seg.index] ?? '';
    }
  }
  return out;
}

/**
 * Tempdoc 521 §16.2 deeper — find every `{name}` reference in a compute
 * expression. The names returned are validated against the template's
 * argument-slot names at registration; substitution happens at expand
 * time. Doubled-brace template-level escapes (`{{` / `}}`) are skipped
 * so a literal `{` inside an expression is intentional.
 */
function extractSlotReferences(expression: string): string[] {
  const refs: string[] = [];
  let i = 0;
  while (i < expression.length) {
    if (expression[i] === '{' && expression[i + 1] === '{') {
      i += 2;
      continue;
    }
    if (expression[i] === '{') {
      const end = expression.indexOf('}', i + 1);
      if (end === -1) break;
      const name = expression.slice(i + 1, end).trim();
      if (/^[A-Za-z][\w-]*$/.test(name)) {
        refs.push(name);
      }
      i = end + 1;
      continue;
    }
    i++;
  }
  return refs;
}

function substituteSlotReferences(
  expression: string,
  resolvedSlotValues: Record<string, string>,
): string {
  let out = '';
  let i = 0;
  while (i < expression.length) {
    if (expression[i] === '{' && expression[i + 1] === '{') {
      out += '{';
      i += 2;
      continue;
    }
    if (expression[i] === '}' && expression[i + 1] === '}') {
      out += '}';
      i += 2;
      continue;
    }
    if (expression[i] === '{') {
      const end = expression.indexOf('}', i + 1);
      if (end === -1) {
        out += expression.slice(i);
        break;
      }
      const name = expression.slice(i + 1, end).trim();
      out += resolvedSlotValues[name] ?? '';
      i = end + 1;
      continue;
    }
    out += expression[i];
    i++;
  }
  return out;
}

async function defaultPromptFn(slot: NamedArgumentSlot): Promise<string | null> {
  if (typeof window === 'undefined' || typeof window.prompt !== 'function') {
    return slot.default ?? '';
  }
  const answer = window.prompt(slot.name, slot.default ?? '');
  return answer;
}

async function resolveAmbient(binding: AmbientBinding): Promise<string> {
  switch (binding) {
    case 'clipboard':
      try {
        return (await navigator.clipboard?.readText()) ?? '';
      } catch {
        return '';
      }
    case 'selection': {
      const sel = getSelection();
      return sel.items.map((i) => selectionItemLabel(i)).join(', ');
    }
    case 'primarySelection': {
      const sel = getSelection();
      const item = sel.items[sel.primaryIndex];
      return item ? selectionItemLabel(item) : '';
    }
    case 'date':
      return new Date().toISOString().slice(0, 10);
    case 'time':
      return new Date().toISOString().slice(11, 19);
    case 'datetime':
      return new Date().toISOString();
    case 'day':
      return ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][
        new Date().getDay()
      ]!;
    case 'uuid':
      return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
    case 'currentSurface':
      return getShellContext().activeSurface ?? '';
    case 'activeProfile':
      return getShellContext().activeProfile;
    case 'calculator': {
      // Tempdoc 521 §16.2 — simple-form calculator binding. Prompt the
      // user for an arithmetic expression, evaluate it via the safe-math
      // grammar (no eval/Function), and return the formatted result. On
      // any error (parse, divide-by-zero, blank/cancelled prompt), return
      // an empty string so the surrounding template expansion still
      // produces a sensible literal — the caller's `onInvoke` can decide
      // how to treat the empty.
      if (typeof window === 'undefined' || typeof window.prompt !== 'function') {
        return '';
      }
      const expr = window.prompt('Calculator (e.g., 2 * (3 + 4)):', '');
      if (expr === null || expr.trim() === '') return '';
      try {
        return formatSafeMathResult(evaluateSafeMath(expr));
      } catch (err) {
        if (err instanceof SafeMathError) {
          window.alert?.(`Calculator: ${err.message}`);
        }
        return '';
      }
    }
  }
}

function selectionItemLabel(item: SelectionItem): string {
  switch (item.kind) {
    case 'search-hit':
      return item.title;
    case 'browse-node':
      return item.path;
    case 'plugin-item':
      return item.label;
    case 'text-range':
      // Tempdoc 526 §12.4 — show the selected text (truncated) as the label.
      return item.selectionText.length > 60
        ? item.selectionText.slice(0, 60) + '…'
        : item.selectionText;
    case 'citation':
      // Tempdoc 526 §4.1 — citation excerpt as label.
      return item.citation.excerpt.length > 60
        ? item.citation.excerpt.slice(0, 60) + '…'
        : item.citation.excerpt || `Citation (${item.citation.parentDocId})`;
    case 'result-set':
      return item.query
        ? `Result set: ${item.query}`
        : `${item.items.length} result${item.items.length === 1 ? '' : 's'}`;
    case 'health-condition':
      return item.summary || item.conditionId;
  }
}

export function __resetTemplateCatalogForTest(): void {
  for (const id of templates.keys()) {
    try {
      unregisterAction(`core.action.template.${id}`);
    } catch {
      /* swallow */
    }
  }
  _registry.__resetForTest();
}
