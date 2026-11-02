// SPDX-License-Identifier: Apache-2.0
/** Arrival-only focus hand-off, independent of the Library navigation address. */
export type IndexingRuleControl = 'hours' | 'battery';
type Listener = (control: IndexingRuleControl) => void;
let pending: IndexingRuleControl | undefined;
const listeners = new Set<Listener>();

export function requestIndexingRuleFocus(control: IndexingRuleControl): void {
  if (!listeners.size) pending = control;
  else for (const listener of listeners) listener(control);
}

export function takeIndexingRuleFocus(): IndexingRuleControl | undefined {
  const control = pending;
  pending = undefined;
  return control;
}

export function subscribeIndexingRuleFocus(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
