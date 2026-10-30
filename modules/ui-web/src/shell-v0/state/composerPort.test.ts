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
    expect(port.clearIf(initial)).toEqual({ status: 'stale' });
    expect(port.replaceIf(initial, 'lost')).toEqual({ status: 'stale' });
    const next = port.snapshot(); assign('B'); assign('A');
    expect(port.clearIf(next)).toEqual({ status: 'stale' });
    expect(port.snapshot().text).toBe('A');
  });

  it('CP3 composition start invalidates without publishing a committed edit', () => {
    const { port, compose } = fixture();
    const capture = port.snapshot(); compose();
    expect(port.clearIf(capture)).toEqual({ status: 'stale' });
    expect(port.replaceIf(capture, 'lost')).toEqual({ status: 'stale' });
    expect(port.snapshot().text).toBe('A');
  });

  it('CP4 a replacement composer refuses the old incarnation even with the same edit and text', () => {
    const { port, replaceComposer } = fixture();
    const capture = port.snapshot(), replacement = replaceComposer();
    expect(port.clearIf(capture)).toEqual({ status: 'stale' });
    expect(replacement.replaceIf(capture, 'lost')).toEqual({ status: 'stale' });
    expect(replacement.snapshot().text).toBe('A');
  });

  it('returns a successor after a conditional change and never reuses the previous edit', () => {
    const { port } = fixture();
    const initial = port.snapshot();
    const replaced = port.replaceIf(initial, 'B');
    expect(replaced.status).toBe('applied');
    expect(port.isCurrent(initial)).toBe(false);
    if (replaced.status !== 'applied') throw Error('replacement was stale');
    expect(replaced.capture.text).toBe('B');
    const cleared = port.clearIf(replaced.capture);
    expect(cleared.status).toBe('applied');
    expect(port.snapshot().text).toBe('');
    expect(port.isCurrent(replaced.capture)).toBe(false);
  });

  it('applies assignment synchronously before capturing the successor, including reentrant edits', () => {
    let edit = 0, text = 'A';
    const events: string[] = [];
    const port = new ComposerPort({
      read: () => ({ text, edit }), isCurrent: () => true,
      replace: next => { text = next; edit += 1; events.push(text); text = 'listener edit'; edit += 1; },
      clear: () => { text = ''; edit += 1; },
    });
    const changed = port.replaceIf(port.snapshot(), 'B');
    expect(events).toEqual(['B']); expect(changed.status).toBe('applied');
    if (changed.status !== 'applied') throw Error('replacement was stale');
    expect(changed.capture.text).toBe('listener edit'); expect(port.isCurrent(changed.capture)).toBe(true);
  });
});
