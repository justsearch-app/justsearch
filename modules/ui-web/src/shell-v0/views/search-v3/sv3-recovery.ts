// SPDX-License-Identifier: Apache-2.0
/** RR15 storage whitelist. Live questions, selections, history and force authority never enter it. */
export type RecoveryShape = 'core.rag-ask' | 'core.free-chat' | 'core.extract' | 'core.summarize';
export interface Sv3RecoveryTask {
  id: string;
  place: string | null;
  target: string | null;
  shape: RecoveryShape;
  raw: string;
  schema: string | null;
  scope: string[];
  record: string | null;
  needsReselection: boolean;
  effort: 'quick' | 'standard' | 'thorough';
  delivery: 'unaccepted' | 'unknown' | 'review';
}
interface Sv3RecoveryDraft { place: string | null; text: string }
export interface Sv3Recovery {
  task: Sv3RecoveryTask | null;
  drafts: Sv3RecoveryDraft[];
}
const bytes = (value: string) => new TextEncoder().encode(value).length;
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
const keys = (v: Record<string, unknown>, names: string) => Object.keys(v).sort().join(',') === names.split(',').sort().join(',');
const str = (v: unknown, cap: number): v is string => typeof v === 'string' && bytes(v) <= cap;
const id = (v: unknown) => v === null || str(v, 4096);
export function isSv3Recovery(v: unknown): v is Sv3Recovery {
  if (!object(v) || !keys(v, 'task,drafts') || !Array.isArray(v.drafts) || v.drafts.length > 2) return false;
  const snapshots = new Set<string>();
  for (const draft of v.drafts) {
    if (!object(draft) || !keys(draft, 'place,text') || !id(draft.place) || !str(draft.text, 65536) || snapshots.has(JSON.stringify([draft.place, draft.text]))) return false;
    snapshots.add(JSON.stringify([draft.place, draft.text]));
  }
  const t = v.task;
  if (t === null) return true;
  return object(t) && keys(t, 'id,place,target,shape,raw,schema,scope,record,needsReselection,effort,delivery')
    && str(t.id, 4096) && id(t.place) && id(t.target) && id(t.record) && str(t.raw, 65536)
    && (t.schema === null || str(t.schema, 16384)) && typeof t.needsReselection === 'boolean'
    && typeof t.shape === 'string' && ['core.rag-ask', 'core.free-chat', 'core.extract', 'core.summarize'].includes(t.shape)
    && typeof t.effort === 'string' && ['quick', 'standard', 'thorough'].includes(t.effort)
    && typeof t.delivery === 'string' && ['unaccepted', 'unknown', 'review'].includes(t.delivery)
    && Array.isArray(t.scope) && t.scope.length <= 128 && t.scope.every(s => str(s, 4096));
}
