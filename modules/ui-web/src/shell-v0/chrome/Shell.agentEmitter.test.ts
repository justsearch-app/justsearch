// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import './Shell.js';
import type { Shell } from './Shell.js';
import { getAction, unregisterAction } from '../substrates/actions/index.js';
import { isComponentTag } from '../renderers/component-vocabulary.generated.js';

const ACTION = 'core.action.shell.show-agent-emitter';

afterEach(() => {
  document.querySelectorAll('jf-shell').forEach(el => el.remove());
  unregisterAction(ACTION);
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('Shell — synthetic agent emitter is development-only', () => {
  it.each([false, true])('DEV=%s controls the action and mounted demo', async dev => {
    vi.stubEnv('DEV', dev);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }))));
    unregisterAction(ACTION);
    const shell = document.createElement('jf-shell') as Shell;
    document.body.append(shell);
    await shell.updateComplete;
    expect(Boolean(getAction(ACTION))).toBe(dev);
    expect(Boolean(shell.shadowRoot!.querySelector('jf-agent-emitter-demo'))).toBe(dev);
    expect(customElements.get('jf-agent-emitter-demo')).toBeDefined();
    expect(isComponentTag('jf-agent-emitter-demo')).toBe(true);
  });
});
