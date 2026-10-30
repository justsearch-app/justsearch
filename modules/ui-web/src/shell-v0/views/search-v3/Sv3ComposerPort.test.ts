// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest';
import { Sv3Composer } from './Sv3Composer.js';
import { Sv3ComposerPort } from './Sv3ComposerPort.js';

afterEach(() => { document.body.replaceChildren(); });

describe('composer element binding', () => {
  it('CP4 a replacement element with equal text and draftVersion makes the old capture stale', async () => {
    const original = new Sv3Composer();
    original.draft = 'A';
    document.body.append(original);
    await original.updateComplete;
    let current: Sv3Composer | null = original;
    const adapter = new Sv3ComposerPort(() => current);
    const capture = adapter.snapshot();

    const replacement = new Sv3Composer();
    replacement.draft = 'A';
    expect(replacement.draftVersion).toBe(original.draftVersion);
    original.replaceWith(replacement);
    current = replacement;
    await replacement.updateComplete;

    expect(capture.port.isCurrent(capture)).toBe(false);
    capture.port.clearIf(capture);
    expect(original.draft).toBe('A');
    expect(replacement.draft).toBe('A');
    const fresh = adapter.snapshot();
    expect(fresh.port).not.toBe(capture.port);
    expect(fresh.port.isCurrent(fresh)).toBe(true);
  });

  it('CP4 reconnecting the same unedited element reuses its port and restores capture validity', async () => {
    const composer = new Sv3Composer();
    composer.draft = 'A';
    document.body.append(composer);
    await composer.updateComplete;
    let current: Sv3Composer | null = composer;
    const adapter = new Sv3ComposerPort(() => current);
    const capture = adapter.snapshot();

    composer.remove();
    current = null;
    expect(capture.port.isCurrent(capture)).toBe(false);
    capture.port.clearIf(capture);
    expect(composer.draft).toBe('A');

    current = composer;
    document.body.append(composer);
    await composer.updateComplete;
    expect(adapter.snapshot().port).toBe(capture.port);
    expect(capture.port.isCurrent(capture)).toBe(true);
    capture.port.clearIf(capture);
    expect(composer.draft).toBe('');
    expect(capture.port.isCurrent(capture)).toBe(false);
  });

  it('CP8 a pre-render capture can clear only as a no-op on the pending seed and becomes stale on first render', async () => {
    let current: Sv3Composer | null = null;
    const adapter = new Sv3ComposerPort(() => current);
    adapter.replace('restored draft');
    const capture = adapter.snapshot();
    expect(capture.port.isCurrent(capture)).toBe(true);
    capture.port.clearIf(capture);
    expect(adapter.read()).toBe('restored draft');

    current = new Sv3Composer();
    document.body.append(current);
    await current.updateComplete;
    adapter.flushSeed();
    expect(current.draft).toBe('restored draft');
    expect(capture.port.isCurrent(capture)).toBe(false);
    capture.port.clearIf(capture);
    expect(current.draft).toBe('restored draft');

    current.draft = 'reader edit';
    adapter.flushSeed();
    expect(adapter.read()).toBe('reader edit');
  });
});
