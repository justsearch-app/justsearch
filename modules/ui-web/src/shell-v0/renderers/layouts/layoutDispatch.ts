// SPDX-License-Identifier: Apache-2.0
/**
 * Layout-side dispatch: given a child uischema (Control or nested
 * Layout) + the root schema, instantiate the right child renderer.
 *
 * Layouts iterate `uischema.elements`; for each child, this helper
 * resolves the schema fragment the child points to (via JSON Pointer
 * in `uischema.scope`) and dispatches to the appropriate renderer.
 */

import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { dispatchRenderer } from '../dispatch.js';
import type { RendererUserConfig } from '../userConfig.js';

/**
 * Resolve a JSON Pointer reference into a schema fragment. Supports
 * the common `#/properties/<name>` pattern. Returns the original
 * schema if the pointer is `#` or empty.
 */
export function resolveSchema(
  rootSchema: JsonSchema,
  pointer: string | undefined,
): JsonSchema | null {
  if (!pointer || pointer === '#' || pointer === '') {
    return rootSchema;
  }
  const stripped = pointer.startsWith('#/') ? pointer.slice(2) : pointer;
  const parts = stripped.split('/').filter((p) => p.length > 0);

  let current: unknown = rootSchema;
  for (const part of parts) {
    if (current && typeof current === 'object' && part in current) {
      current = (current as Record<string, unknown>)[part];
    } else {
      return null;
    }
  }
  return current as JsonSchema;
}

/**
 * Construct a child renderer element for a child uischema. Handles
 * both Control children (resolves scope → schema, dispatches to a
 * control renderer) and Layout children (passes the same root
 * schema, dispatches to a layout renderer).
 *
 * Reuse the mounted child for the same declaration and dispatched tag so data updates
 * preserve its controls, focus and in-place confirmation state.
 *
 * Returns null when no renderer matches (caller renders an error
 * fallback).
 */
export function createChildRenderer(
  childUischema: UISchemaElement,
  rootSchema: JsonSchema,
  parentPath: string,
  rootData: unknown,
  enabled: boolean,
  onChange: (value: unknown, path: string) => void,
  userConfig?: RendererUserConfig,
  previous?: JsonFormsRendererBase | null,
): JsonFormsRendererBase | null {
  if ((childUischema as { type?: string }).type === 'Control') {
    const scope = (childUischema as { scope?: string }).scope;
    const resolvedSchema = resolveSchema(rootSchema, scope);
    if (!resolvedSchema) {
      return null;
    }
    const tag = dispatchRenderer(childUischema, resolvedSchema, userConfig);
    if (!tag) {
      return null;
    }

    // Derive the data path from the scope:
    //   '#/properties/email' → 'email'
    //   '#/properties/user/properties/name' → 'user.name'
    const propPath = (scope ?? '')
      .replace(/^#?\/?/, '')
      .split('/')
      .filter((p) => p !== 'properties' && p.length > 0)
      .join('.');
    const childPath = parentPath
      ? propPath
        ? `${parentPath}.${propPath}`
        : parentPath
      : propPath;

    // Walk the data along the same path
    const propValue = walkDataPath(rootData, childPath);

    const child = previous?.localName === tag && previous.uischema === childUischema
      ? previous : document.createElement(tag) as JsonFormsRendererBase;
    child.schema = resolvedSchema;
    child.uischema = childUischema;
    child.path = childPath;
    child.data = propValue;
    child.errors = '';
    child.enabled = enabled;
    child.visible = true;
    child.onChange = onChange;
    child.userConfig = userConfig;
    return child;
  }

  // Layout child — same root schema, recurses
  const tag = dispatchRenderer(childUischema, rootSchema, userConfig);
  if (!tag) {
    return null;
  }
  const child = previous?.localName === tag && previous.uischema === childUischema
    ? previous : document.createElement(tag) as JsonFormsRendererBase;
  child.schema = rootSchema;
  child.uischema = childUischema;
  child.path = parentPath;
  child.data = rootData;
  child.errors = '';
  child.enabled = enabled;
  child.visible = true;
  child.onChange = onChange;
  child.userConfig = userConfig;
  return child;
}

/**
 * Walk a dot-separated data path into a root data structure. Returns
 * `undefined` when any segment is missing.
 *
 * V1 limitation: property names containing literal dots break path
 * decoding (`user.first.name` is parsed as nested `user → first →
 * name`, not flat `user.first.name`). Slice 3a.1's host integration
 * is responsible for either rejecting dot-containing property names
 * or migrating to a JSON-pointer-based path encoding.
 */
function walkDataPath(rootData: unknown, path: string): unknown {
  if (!path) {
    return rootData;
  }
  const parts = path.split('.');
  let current: unknown = rootData;
  for (const part of parts) {
    if (current === null || current === undefined) {
      return undefined;
    }
    if (typeof current === 'object') {
      const idx = Number(part);
      if (Array.isArray(current) && Number.isInteger(idx)) {
        current = current[idx];
      } else {
        current = (current as Record<string, unknown>)[part];
      }
    } else {
      return undefined;
    }
  }
  return current;
}
