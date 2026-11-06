// SPDX-License-Identifier: Apache-2.0
/**
 * Projection over UserStateDocument's `detailsByConversation` slice: what each conversation's details
 * pane last showed, so returning to a conversation (or reloading it) puts you back where you were.
 * Bounded: the least recently written conversations are forgotten first.
 */
import { getDocument, mutateDocument, type DetailsMemory } from './UserStateDocument.js';

export const DETAILS_MEMORY_CAP = 50;

export function getDetailsMemory(conversationId: string): DetailsMemory | null {
  return getDocument().detailsByConversation?.[conversationId] ?? null;
}

export function rememberDetails(conversationId: string, memory: DetailsMemory): void {
  mutateDocument((doc) => {
    const { [conversationId]: _replaced, ...others } = doc.detailsByConversation ?? {};
    const kept = Object.entries(others).slice(-(DETAILS_MEMORY_CAP - 1));
    return { ...doc, detailsByConversation: { ...Object.fromEntries(kept), [conversationId]: memory } };
  });
}
