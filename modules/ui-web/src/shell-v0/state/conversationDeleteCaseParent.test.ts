// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { __resetConversationListForTest, deleteConversationWithCascade } from './conversationListStore.js';

afterEach(() => { __resetConversationListForTest();vi.unstubAllGlobals(); });
for (const [root, savedParent] of [['Root', 'rOoT'], ['id-i', 'ID-İ'], ['id-σ', 'ID-ς'], ['id-ß', 'ID-ẞ']] as const) {
  it(`F2 names and deletes a child of ${root} saved under ${savedParent}`, async () => {
    __resetConversationListForTest();const deletes: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => {
      const u = new URL(String(url), 'http://test');const id = decodeURIComponent(u.pathname.split('/')[4] ?? '');
      if (init?.method === 'DELETE') { deletes.push(id);return Response.json({ ok: true, agentRunsDeleted: 0 }); }
      const childrenOf = u.searchParams.get('childrenOf');
      if (childrenOf) return Response.json({ childrenOf, locked: false, sessions: [
        { sessionId: childrenOf, busy: false, title: childrenOf, ...(childrenOf === 'child' ? { parentSessionId: savedParent } : {}) },
        ...(childrenOf === root ? [{ sessionId: 'child', parentSessionId: savedParent, title: 'Blocking child', busy: false }] : []),
      ] });
      return Response.json({ sessions: [] });
    }));
    const consent = vi.fn(async (_ids: string[], tree: { id: string; depth: number }[]) => {
      expect(deletes).toEqual([]);expect(tree.map(r => [r.id, r.depth])).toEqual([[root, 0], ['child', 1]]);return true;
    });
    const result = await deleteConversationWithCascade(root, consent);
    expect(consent).toHaveBeenCalledOnce();expect(deletes).toEqual(['child', root]);expect(result.ok).toBe(true);
  });
}
