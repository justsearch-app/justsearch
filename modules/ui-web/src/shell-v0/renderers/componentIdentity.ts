// SPDX-License-Identifier: Apache-2.0
/** Resolve retained authoring vocabulary at construction; saved declarations keep their tokens. */
export function canonicalComponentTag(tag: string): string {
  return tag === 'jf-unified-chat-view' ? 'jf-sv3-window' : tag;
}
