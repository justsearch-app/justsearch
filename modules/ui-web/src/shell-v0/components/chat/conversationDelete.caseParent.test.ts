// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { render } from 'lit';
import { confirmAsync } from '../ConfirmDialog.js';
import { confirmConversationTree } from './conversationDelete.js';
import type { DeleteTreeEntry } from '../../state/conversationListStore.js';

vi.mock('../ConfirmDialog.js', () => ({ confirmAsync: vi.fn(async () => false) }));
afterEach(() => { document.body.replaceChildren();vi.clearAllMocks(); });

it('F2 renders every validated tree member when saved parent casing differs', async () => {
  const row = (id: string, depth: number, parentSessionId?: string): DeleteTreeEntry => ({
    id, depth, parentSessionId, branchPointMessageId: 'answer', busy: false, title: id,
    titleSource: 'user', createdAt: 1, lastActiveAt: 1, messageCount: 0, firstUserMessage: '',
    shapeId: 'core.free-chat', storeBacked: true,
  });
  const tree = [row('Root', 0), row('child', 1, 'rOoT'), row('leaf', 2, 'CHILD'), row('peer', 1, 'ROOT')];
  expect(await confirmConversationTree(['child', 'leaf', 'peer'], tree)).toBe(false);
  const options = vi.mocked(confirmAsync).mock.calls[0]![0];
  const host = document.createElement('div');document.body.append(host);render(options.message, host);
  for (const member of tree) expect(host.textContent).toContain(member.id);
  expect(host.textContent).toContain('Branched from rOoT');expect(host.textContent).toContain('Branched from CHILD');
  expect(host.querySelectorAll('ol ol ol')).toHaveLength(1);
  expect(host.querySelectorAll('li')).toHaveLength(4);
  expect(options.confirmLabel).toBe('Delete all (4)');
});
