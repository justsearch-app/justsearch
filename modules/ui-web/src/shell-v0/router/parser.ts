// SPDX-License-Identifier: Apache-2.0
/**
 * URL parser — slice 489 §7 stage 1.
 *
 * Structural parser for shell-address URLs. The Java shell-address model
 * and URL emitters share this grammar; canonicalization differences are noted below.
 *
 * Grammar:
 *   justsearch://surface/<surfaceId>[?key=value&...]    → Navigation
 *   justsearch://op/<opId>[?argName=value&...]          → Invocation
 *   justsearch://query?q=<text>[&key=value&...]        → Query
 *   justsearch://answer?q=<prompt>[&shape=<id>]        → Answer
 *
 * Rules:
 *   - scheme must be `justsearch:`
 *   - host must be `op`, `surface`, `query`, or `answer`
 *   - for `op` and `surface`, pathname is /<id>; strip surrounding slashes; require single segment
 *   - op/surface id must match /^(core|vendor\.[a-z][a-z0-9-]*)\.[a-z][a-z0-9-]*$/
 *   - args use URLSearchParams; repeated keys → array
 *
 * Returns `null` for malformed input — pure function, no exceptions.
 */

import type {
  ShellAddress,
  ShellAddressAnswer,
  ShellAddressInvocation,
  ShellAddressNavigation,
  ShellAddressQuery,
  StateSnapshot,
} from './types.js';

export const URL_SCHEME = 'justsearch:';
export const URL_HOST_SURFACE = 'surface';
export const URL_HOST_OP = 'op';
/** 548 S4-A: host for the search verb — `justsearch://query?q=<text>`. */
export const URL_HOST_QUERY = 'query';
/** 548 §4.5: host for the answer verb — `justsearch://answer?q=<prompt>[&shape=<id>]`. */
export const URL_HOST_ANSWER = 'answer';
/** Default AI shape an `answer` resolves to when no `shape` param is given. */
export const DEFAULT_ANSWER_SHAPE = 'core.rag-ask';

// Slice 487 post-impl C1 (2026-05-13): tightened to match the platform's
// `NamespacedId.NAMESPACE_PATTERN` (Java side: `^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$`).
// The pre-Commit-6 regex `^[A-Za-z0-9_.\-]+$` was permissive — it accepted
// uppercase letters and underscore — which is syntactically richer than what
// the platform's id catalog actually permits. The Java side parser
// (MarkdownUrlExtractor) catches `SurfaceRef`/`OperationRef` construction
// failures from the stricter regex; tightening the TS parser eliminates the
// cross-port divergence that was previously logged as a `_excluded` fixture
// in v1.json. Both ports now reject uppercase / underscore / non-namespaced ids
// uniformly at the syntactic stage; semantic id-in-catalog resolution still
// happens at the resolver.
const ID_REGEX = /^(core|vendor\.[a-z][a-z0-9-]*)\.[a-z][a-z0-9-]*$/;
// Strip trailing prose punctuation from extracted URLs.
const TRAILING_PUNCT_REGEX = /[.,;:!?”'"]+$/;

/**
 * Extract every justsearch:// URL from a free-text string.
 *
 * Returns matches in document order, each with `{ raw, url, span }`.
 * Used by slice 491's URLExtractor for LLM
 * responses; the chrome's intent sources call {@link parseUrl} directly with
 * a single URL string and don't need extraction.
 */
export function extractUrls(text: string): Array<{
  raw: string;
  url: string;
  span: [number, number];
}> {
  if (typeof text !== 'string' || text.length === 0) return [];
  const re = /justsearch:\/\/[^\s)\]<>"'`]+/g;
  const out: Array<{ raw: string; url: string; span: [number, number] }> = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    let url = m[0];
    const stripped = url.replace(TRAILING_PUNCT_REGEX, '');
    if (stripped.length > 0) url = stripped;
    out.push({ raw: url, url, span: [m.index, m.index + m[0].length] });
  }
  return out;
}

/**
 * Parse a single `justsearch://` URL into a {@link ShellAddress}.
 *
 * Returns null on malformed input. Argument coercion (strings → typed values
 * via the surface's state schema) happens downstream at the resolver stage;
 * this function returns args as raw strings (per URLSearchParams).
 */
export function parseUrl(raw: string): ShellAddress | null {
  if (typeof raw !== 'string') return null;
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return null;
  }
  if (parsed.protocol !== URL_SCHEME) return null;
  const kind = parsed.host;
  if (
    kind !== URL_HOST_OP &&
    kind !== URL_HOST_SURFACE &&
    kind !== URL_HOST_QUERY &&
    kind !== URL_HOST_ANSWER
  ) {
    return null;
  }

  if (kind === URL_HOST_ANSWER) {
    // 548 §4.5: free-form prompt in `q`, optional `shape` (default core.rag-ask); path empty.
    const answerPath = parsed.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
    if (answerPath.length !== 0) return null;
    const prompt = parsed.searchParams.get('q');
    if (prompt === null || prompt.trim().length === 0) return null;
    const rest = new URLSearchParams(parsed.search);
    rest.delete('q');
    const shape = rest.get('shape') ?? DEFAULT_ANSWER_SHAPE;
    rest.delete('shape');
    const state = parseQueryParamsAsState(rest);
    return { kind: 'answer', prompt, shape, state } satisfies ShellAddressAnswer;
  }

  if (kind === URL_HOST_QUERY) {
    // 548 S4-A: query text is free-form (not a catalog id), carried in `q`; path must be empty.
    const queryPath = parsed.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
    if (queryPath.length !== 0) return null;
    const q = parsed.searchParams.get('q');
    if (q === null || q.trim().length === 0) return null;
    const rest = new URLSearchParams(parsed.search);
    rest.delete('q');
    const state = parseQueryParamsAsState(rest);
    return { kind: 'query', query: q, state } satisfies ShellAddressQuery;
  }

  // pathname is /<id>; strip leading slash; require single segment
  const path = parsed.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
  if (path.length === 0 || path.includes('/')) return null;
  if (!ID_REGEX.test(path)) return null;

  if (kind === URL_HOST_SURFACE) {
    const state = parseQueryParamsAsState(parsed.searchParams);
    return { kind: 'navigate', target: path, state } satisfies ShellAddressNavigation;
  } else {
    const args = parseQueryParamsAsArgs(parsed.searchParams);
    return { kind: 'invoke', target: path, args } satisfies ShellAddressInvocation;
  }
}

/**
 * Build a canonical URL string from a {@link ShellAddress}.
 *
 * Round-trip-stable with {@link parseUrl}: `parseUrl(canonicalize(addr))`
 * yields the same address (modulo argument-coercion type differences for
 * Invocation, which are an args-parsing concern). For Navigation, the round
 * trip is exact.
 *
 * Like `URLSurfaceEmitter`, keys are sorted alphabetically and array values
 * produce repeated keys in array order. Value encoding differs: this uses
 * `encodeURIComponent` (space → %20, apostrophe unchanged); Java uses
 * `URLEncoder` (space → +, apostrophe → %27).
 */
export function canonicalize(addr: ShellAddress): string {
  if (addr.kind === 'answer') {
    // 548 §4.5: prompt in `q`, shape only when non-default; keys sorted by encodeFlatBag.
    const bag: StateSnapshot = { ...addr.state, q: addr.prompt };
    if (addr.shape && addr.shape !== DEFAULT_ANSWER_SHAPE) bag.shape = addr.shape;
    return `justsearch://answer?${encodeFlatBag(bag)}`;
  }
  if (addr.kind === 'query') {
    // 548 S4-A: `q` is reserved for the query text. Spread state first, then set `q` last so
    // the query text always wins over any refinement key literally named `q` (which parseUrl
    // never produces — it extracts `q` before building state — so this only guards
    // hand-constructed addresses). Keys are sorted by encodeFlatBag; round-trip-stable since
    // parseUrl reads `q` regardless of order.
    const bag: StateSnapshot = { ...addr.state, q: addr.query };
    return `justsearch://query?${encodeFlatBag(bag)}`;
  }
  if (addr.kind === 'navigate') {
    const query = encodeFlatBag(addr.state);
    return query.length > 0
      ? `justsearch://surface/${addr.target}?${query}`
      : `justsearch://surface/${addr.target}`;
  } else {
    // Operation invocation: stringify each arg value (numbers/booleans/etc.
    // serialize as their `.toString()`; arrays produce repeated keys).
    const flat: StateSnapshot = {};
    for (const [k, v] of Object.entries(addr.args)) {
      if (v === null || v === undefined) continue;
      if (Array.isArray(v)) {
        flat[k] = v.map(String);
      } else {
        flat[k] = String(v);
      }
    }
    const query = encodeFlatBag(flat);
    return query.length > 0
      ? `justsearch://op/${addr.target}?${query}`
      : `justsearch://op/${addr.target}`;
  }
}

// ----- helpers -----

function parseQueryParamsAsState(params: URLSearchParams): StateSnapshot {
  const out: StateSnapshot = {};
  for (const [key, value] of params.entries()) {
    if (Object.prototype.hasOwnProperty.call(out, key)) {
      const existing = out[key];
      if (Array.isArray(existing)) {
        existing.push(value);
      } else {
        out[key] = [existing as string, value];
      }
    } else {
      out[key] = value;
    }
  }
  return out;
}

function parseQueryParamsAsArgs(params: URLSearchParams): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of params.entries()) {
    if (Object.prototype.hasOwnProperty.call(out, key)) {
      const existing = out[key];
      if (Array.isArray(existing)) {
        existing.push(value);
      } else {
        out[key] = [existing as string, value];
      }
    } else {
      out[key] = value;
    }
  }
  return out;
}

function encodeFlatBag(bag: StateSnapshot): string {
  const keys = Object.keys(bag).sort();
  const parts: string[] = [];
  for (const key of keys) {
    const value = bag[key];
    if (value === null || value === undefined) continue;
    const encodedKey = encodeURIComponent(key);
    if (Array.isArray(value)) {
      for (const v of value) {
        parts.push(`${encodedKey}=${encodeURIComponent(String(v))}`);
      }
    } else {
      parts.push(`${encodedKey}=${encodeURIComponent(String(value))}`);
    }
  }
  return parts.join('&');
}
