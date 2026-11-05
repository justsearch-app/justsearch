// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE2/§8 words — a host confirmation dialog asks a question and answers with a verb (never
 * "Confirm"), and only a destructive request is drawn as a danger dialog: repairing an install,
 * for one, is a dialog about downloads and terms, not a danger dialog.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { createUiApi } from './ui.js';
import type { ConfirmDialog } from '../../components/ConfirmDialog.js';

const dialog = () => document.querySelector('jf-confirm-dialog') as ConfirmDialog | null;
afterEach(() => { document.body.replaceChildren(); });

describe('host.ui.showConfirmDialog (DE2)', () => {
  it('without options it asks "Continue?" and answers "Continue", not as danger', async () => {
    void createUiApi('TRUSTED_PLUGIN', {}).showConfirmDialog('This runs the repair.');
    const shown = dialog()!;
    await shown.updateComplete;
    expect(shown.title).toBe('Continue?');
    expect(shown.confirmLabel).toBe('Continue');
    expect(shown.variant).toBe('info');
  });

  it('a titled, non-destructive request keeps its question and verb and is not a danger dialog', async () => {
    void createUiApi('CORE', {}).showConfirmDialog('Download: 1.2 GB.', { title: 'Repair AI installation?', confirmLabel: 'Repair' });
    const shown = dialog()!;
    expect(shown.title).toBe('Repair AI installation?');
    expect(shown.confirmLabel).toBe('Repair');
    expect(shown.variant).toBe('info');
  });

  it('a destructive request is drawn as danger and keeps its typed phrase', async () => {
    void createUiApi('CORE', {}).showConfirmDialog('Apply the saved patterns?', { confirmLabel: 'Apply', destructive: true, typedConfirmWord: 'core.apply-excludes' });
    const shown = dialog()!;
    expect(shown.variant).toBe('danger');
    expect(shown.typedConfirmWord).toBe('core.apply-excludes');
  });
});
