// SPDX-License-Identifier: Apache-2.0
/**
 * stateValidator — slice 489 §7 stage 2 resolver-stage validation.
 *
 * Lightweight JSON-Schema-driven validation for surface state values arriving
 * from URL parsing. URL args arrive as strings (URLSearchParams); the
 * surface's declared {@code SurfaceStateSchema.schema} provides the typed
 * contract; this module validates that each value PARSES as the declared
 * type (so `?modifiedFromMs=banana` fails fast on a number-typed field), but
 * returns the validated state as wire-string values unchanged. Downstream
 * store-adapter `restore()` methods (e.g. {@code restoreSearchFilters})
 * already perform their own `Number(s)` coercion; the validator's role is
 * "reject malformed before the store sees it," not "transform the carrier
 * type."
 *
 * Why lightweight instead of ajv: surface state today is shallow (the
 * search-surface schema has 3 string-typed fields per the wire shape — the
 * declared types in the schema name `number` for the filter bounds, but the
 * URL carries them as strings). If surface schemas grow nested or
 * polymorphic, use a full JSON Schema validator.
 *
 * Per slice 489 §7: the validator is pure. Caller decides what to do with
 * the result — NavigationHandler (slice 492) logs + skips state restore on
 * failure, leaving stores at their defaults (surface still activates).
 */

import type { StateSnapshot } from './types.js';

/** Result of a validation pass. */
export type ValidationResult =
  | { ok: true; value: StateSnapshot }
  | { ok: false; errors: string[] };

/**
 * Minimal subset of JSON Schema this validator understands. Mirrors what
 * surfaces declare in {@code SurfaceStateSchema.schema} today.
 */
interface SimpleObjectSchema {
  type?: 'object';
  properties?: Record<string, SimplePropertySchema>;
  required?: string[];
}

interface SimplePropertySchema {
  type?: 'string' | 'number' | 'integer' | 'boolean';
}

/**
 * Validate a parsed state snapshot against a JSON Schema source.
 *
 * Behaviour:
 *   - For each key in `schema.properties`: check that the value parses as
 *     the declared type. Number / integer: `Number(s)` + `isFinite`
 *     (integer adds `Number.isInteger`). Boolean: matches
 *     `/^(true|false|1|0|yes|no)$/i` case-insensitively. String: any string
 *     accepted.
 *   - Keys not in `schema.properties` are silently dropped (URL params can
 *     legitimately include client-side state the schema doesn't address;
 *     dropping is safer than passing through unknown keys to the store).
 *   - Keys in `schema.required` (if declared) that are absent produce errors.
 *   - Array values (URL repeated-key form) are validated element-wise against
 *     the property's declared type.
 *   - Returned values are kept as wire-strings (or arrays of strings) when
 *     input is wire-shaped; downstream stores perform their own type
 *     coercion. The validator's job is rejection, not transformation.
 *
 * Always returns `{ok: true, value}` when the schema is itself malformed
 * (the schema-source well-formedness check is the controller-side gate, not
 * this module's responsibility).
 */
export function coerceAndValidate(
  state: StateSnapshot,
  schemaSource: string,
): ValidationResult {
  let schema: SimpleObjectSchema | null;
  try {
    schema = JSON.parse(schemaSource) as SimpleObjectSchema;
  } catch {
    // Malformed schema — return state as-is. The schema-source well-formedness
    // check (F6 in RegistryController) is the authoritative gate.
    return { ok: true, value: state };
  }
  if (!schema || typeof schema !== 'object' || !schema.properties) {
    return { ok: true, value: state };
  }
  const properties = schema.properties;
  const required = Array.isArray(schema.required) ? schema.required : [];
  const errors: string[] = [];
  const value: StateSnapshot = {};

  for (const [key, propSchema] of Object.entries(properties)) {
    const raw = state[key];
    if (raw === undefined) {
      if (required.includes(key)) {
        errors.push(`required key "${key}" absent from URL state`);
      }
      continue;
    }
    const declaredType = propSchema.type ?? 'string';
    const result = validateValue(raw, declaredType, key);
    if (result.ok) {
      value[key] = raw; // pass-through; downstream store handles type coercion
    } else {
      errors.push(result.error);
    }
  }

  if (errors.length > 0) {
    return { ok: false, errors };
  }
  return { ok: true, value };
}

type ValidateResult = { ok: true } | { ok: false; error: string };

function validateValue(
  raw: string | number | boolean | string[],
  declaredType: 'string' | 'number' | 'integer' | 'boolean',
  key: string,
): ValidateResult {
  if (Array.isArray(raw)) {
    for (const elem of raw) {
      const single = validateScalar(elem, declaredType, key);
      if (!single.ok) return single;
    }
    return { ok: true };
  }
  return validateScalar(raw, declaredType, key);
}

function validateScalar(
  raw: string | number | boolean,
  declaredType: 'string' | 'number' | 'integer' | 'boolean',
  key: string,
): ValidateResult {
  if (declaredType === 'string') {
    return { ok: true }; // any string is a valid string
  }
  if (declaredType === 'number' || declaredType === 'integer') {
    const n = typeof raw === 'number' ? raw : Number(String(raw));
    if (!Number.isFinite(n)) {
      return {
        ok: false,
        error: `key "${key}" expected ${declaredType}, got non-finite value "${raw}"`,
      };
    }
    if (declaredType === 'integer' && !Number.isInteger(n)) {
      return {
        ok: false,
        error: `key "${key}" expected integer, got non-integer "${raw}"`,
      };
    }
    return { ok: true };
  }
  if (declaredType === 'boolean') {
    const s = String(raw).toLowerCase();
    if (
      s === 'true' ||
      s === '1' ||
      s === 'yes' ||
      s === 'false' ||
      s === '0' ||
      s === 'no'
    ) {
      return { ok: true };
    }
    return {
      ok: false,
      error: `key "${key}" expected boolean, got "${raw}"`,
    };
  }
  // Unknown declared type — accept as opaque string.
  return { ok: true };
}
