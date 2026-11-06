// SPDX-License-Identifier: Apache-2.0
// Tempdoc 565 §12.3.E — the cross-highlight selection: which grounding source the user last focused.
// A tiny module store shared by the answer's inline [n] marks (MarkdownBlock),
// the citations panel, and the details pane's source tabs — so clicking a
// citation, a rail card, or a chip highlights the SAME source across all three. The id is a stable
// composite of the source's local-passage identity (parentDocId + startLine), the key both the inline
// `MarkdownCitation.detail` and the rail's `AgentSource` carry — so the three surfaces resolve the same id.

let _selected: string | null = null;
const _subs = new Set<() => void>();

function notify(): void {
  for (const s of _subs) {
    try {
      s();
    } catch {
      /* swallow */
    }
  }
}

/** The stable cross-surface identity of a grounding source (its exact local passage). */
export function sourceKey(parentDocId: string, startLine: number): string {
  return `${parentDocId}:${startLine}`;
}

export function getSelectedSource(): string | null {
  return _selected;
}

export function setSelectedSource(id: string | null): void {
  if (_selected === id) return;
  _selected = id;
  notify();
}

export function subscribeSelectedSource(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}

/** Test-only reset. */
export function __resetSelectedSource(): void {
  _selected = null;
  _subs.clear();
}
