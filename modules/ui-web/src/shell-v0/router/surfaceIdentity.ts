// SPDX-License-Identifier: Apache-2.0
import { canonicalize, parseUrl } from './parser.js';

/** Persisted identities of the retired main windows all name the same Search window. */
export function canonicalSurfaceId(id: string): string {
  return id === 'core.unified-chat-surface' || id === 'core.search-surface' || id === 'core.agent-surface'
    ? 'core.search-v3-surface'
    : id;
}

/** Preserve the place (including a re-runnable query), while comparing aliases and URL encodings. */
export function canonicalSurfaceUrl(url: string): string {
  const address = parseUrl(url);
  if (address?.kind !== 'navigate') return url;
  const target = canonicalSurfaceId(address.target);
  const state = target === 'core.search-v3-surface'
    ? Object.fromEntries(Object.entries(address.state).filter(([key]) =>
      ['conversationId', 'query', 'docIds', 'mode', 'affordance', 'forceShapeId', 'selection'].includes(key)))
    : address.state;
  return canonicalize({ ...address, target, state });
}
