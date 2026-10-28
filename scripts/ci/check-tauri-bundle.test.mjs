// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { bareTauriSpecifiers } from '../../modules/ui-web/scripts/check-tauri-bundle.mjs';

test('rejects literal and runtime-joined Tauri specifiers in shipped JavaScript', () => {
  assert.deepEqual(bareTauriSpecifiers(`import('@tauri-apps/api/core')`), ['@tauri-apps/api/core']);
  assert.deepEqual(bareTauriSpecifiers(`const s=['@tauri-apps','api','core'].join('/');import(s)`), ['@tauri-apps/api/core']);
  assert.deepEqual(bareTauriSpecifiers(`const s=['@tauri-apps','plugin-dialog'].join('/');import(s)`), ['@tauri-apps/plugin-dialog']);
});

test('allows only the optional plugin-fs exception and bundled relative imports', () => {
  assert.deepEqual(bareTauriSpecifiers(`const s=['@tauri-apps','plugin-fs'].join('/');import(s);import('./core-123.js')`), []);
  assert.deepEqual(bareTauriSpecifiers(`import('@tauri-apps/plugin-fs-extra')`), ['@tauri-apps/plugin-fs-extra']);
  assert.deepEqual(bareTauriSpecifiers(`// @tauri-apps/api/core\nconst message='ordinary text';`), []);
});
