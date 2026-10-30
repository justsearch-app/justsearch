// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { ComposerPort } from './composerPort.js';

function fixture() {
  let text = 'A', edit = 0;
  let current: ComposerPort;
  const assign = (next: string) => { text = next; edit += 1; };
  const port: ComposerPort = new ComposerPort({
    read: () => ({ text, edit }), isCurrent: () => current === port,
    replace: assign, clear: () => assign(''),
  });
  current = port;
  return { port, assign, compose: () => { edit += 1; },
    replaceComposer: () => { current = new ComposerPort({
      read: () => ({ text, edit }), isCurrent: () => true, replace: assign, clear: () => assign(''),
    }); return current; } };
}

describe('composer port', () => {
  it('CP2 every assignment invalidates a capture, including identical text and A-B-A', () => {
    const { port, assign } = fixture();
    const initial = port.snapshot(); assign('A');
    expect(port.clearIf(initial)).toBe(false);
    const next = port.snapshot(); assign('B'); assign('A');
    expect(port.clearIf(next)).toBe(false);
    expect(port.snapshot().text).toBe('A');
  });

  it('CP3 composition start invalidates without publishing a committed edit', () => {
    const { port, compose } = fixture();
    const capture = port.snapshot(); compose();
    expect(port.clearIf(capture)).toBe(false);
    expect(port.snapshot().text).toBe('A');
  });

  it('CP4 a replacement composer refuses the old incarnation even with the same edit and text', () => {
    const { port, replaceComposer } = fixture();
    const capture = port.snapshot(), replacement = replaceComposer();
    expect(port.clearIf(capture)).toBe(false);
    expect(replacement.clearIf(capture)).toBe(false);
    expect(replacement.snapshot().text).toBe('A');
  });

  it('clears only a current capture and invalidates it on success', () => {
    const { port } = fixture();
    const initial = port.snapshot();
    expect(port.clearIf(initial)).toBe(true);
    expect(port.snapshot().text).toBe('');
    expect(port.isCurrent(initial)).toBe(false);
    expect(port.clearIf(initial)).toBe(false);
  });

  it('clears synchronously before returning, including reentrant edits', () => {
    let edit = 0, text = 'A';
    const events: string[] = [];
    const port = new ComposerPort({
      read: () => ({ text, edit }), isCurrent: () => true,
      replace: next => { text = next; edit += 1; },
      clear: () => { text = ''; edit += 1; events.push(text); text = 'listener edit'; edit += 1; },
    });
    const initial = port.snapshot();
    expect(port.clearIf(initial)).toBe(true);
    expect(events).toEqual(['']);
    expect(port.snapshot().text).toBe('listener edit');
    expect(port.isCurrent(initial)).toBe(false);
  });
});
