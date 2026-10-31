// SPDX-License-Identifier: Apache-2.0
/** RR15 storage whitelist. Live questions, selections, history and force authority never enter it. */
export type RecoveryShape = 'core.rag-ask' | 'core.free-chat' | 'core.extract' | 'core.summarize';
export interface Sv3RecoveryTask {
  id: string;
  dispatchTurnId?: string;
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
/** Draft settings are a proposal, never restored approval or submission authority. */
export type Sv3Preparation =
  | { kind: 'search' }
  | { kind: 'delegate' }
  | { kind: 'model' }
  | { kind: 'ask'; scope: { kind: 'all' } | PreparationFiles }
  | { kind: 'summarize'; scope: PreparationFiles }
  | { kind: 'structured'; schema: string; scope: { kind: 'typed' } | PreparationFiles };
type PreparationFiles = { kind: 'files' | 'reselect'; paths: string[] };
export interface Sv3RecoveryDraft { place: string | null; text: string; preparation?: Sv3Preparation | null }
export interface Sv3Recovery {
  /** Absent is the strict original text-only payload. The envelope remains version1. */
  version?: 2 | 3;
  task: Sv3RecoveryTask | null;
  drafts: Sv3RecoveryDraft[];
}
const bytes = (value: string) => new TextEncoder().encode(value).length;
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
const keys = (v: Record<string, unknown>, names: string) => Object.keys(v).sort().join(',') === names.split(',').sort().join(',');
const str = (v: unknown, cap: number): v is string => typeof v === 'string' && bytes(v) <= cap;
const id = (v: unknown) => v === null || str(v, 4096);
export function isSv3Preparation(v: unknown): v is Sv3Preparation {
  if (!object(v) || typeof v.kind !== 'string') return false;
  if (['search', 'delegate', 'model'].includes(v.kind)) return keys(v, 'kind');
  if (!['ask', 'summarize', 'structured'].includes(v.kind)
    || !keys(v, v.kind === 'structured' ? 'kind,schema,scope' : 'kind,scope')
    || v.kind === 'structured' && !str(v.schema, 16384) || !object(v.scope) || typeof v.scope.kind !== 'string') return false;
  const scope = v.scope;
  if (scope.kind === 'all') return v.kind === 'ask' && keys(scope, 'kind');
  if (scope.kind === 'typed') return v.kind === 'structured' && keys(scope, 'kind');
  return keys(scope, 'kind,paths') && (scope.kind === 'files' || scope.kind === 'reselect')
    && Array.isArray(scope.paths) && scope.paths.length <= 128
    && (scope.kind === 'reselect' || scope.paths.length > 0)
    && scope.paths.every(p => str(p, 4096) && p.trim() !== '')
    && new Set(scope.paths).size === scope.paths.length;
}
export function isSv3Recovery(v: unknown): v is Sv3Recovery {
  if (!object(v) || !(keys(v, 'task,drafts') || keys(v, 'version,task,drafts') && (v.version === 2 || v.version === 3))
    || !Array.isArray(v.drafts) || v.drafts.length > 2) return false;
  const snapshots = new Set<string>();
  for (const draft of v.drafts) {
    if (!object(draft) || !keys(draft, v.version !== undefined ? 'place,text,preparation' : 'place,text')
      || !id(draft.place) || !str(draft.text, 65536)
      || v.version !== undefined && draft.preparation !== null && !isSv3Preparation(draft.preparation)) return false;
    const snapshot = JSON.stringify([draft.place, draft.text, draft.preparation ?? null]);
    if (snapshots.has(snapshot)) return false;
    snapshots.add(snapshot);
  }
  const t = v.task;
  if (t === null) return true;
  return object(t) && keys(t, 'id,place,target,shape,raw,schema,scope,record,needsReselection,effort,delivery'
    + (v.version === 3 && Object.hasOwn(t, 'dispatchTurnId') ? ',dispatchTurnId' : ''))
    && (t.dispatchTurnId === undefined || str(t.dispatchTurnId, 4096) && t.dispatchTurnId.trim() !== '')
    && str(t.id, 4096) && id(t.place) && id(t.target) && id(t.record) && str(t.raw, 65536)
    && (t.schema === null || str(t.schema, 16384)) && typeof t.needsReselection === 'boolean'
    && typeof t.shape === 'string' && ['core.rag-ask', 'core.free-chat', 'core.extract', 'core.summarize'].includes(t.shape)
    && typeof t.effort === 'string' && ['quick', 'standard', 'thorough'].includes(t.effort)
    && typeof t.delivery === 'string' && ['unaccepted', 'unknown', 'review'].includes(t.delivery)
    && Array.isArray(t.scope) && t.scope.length <= 128 && t.scope.every(s => str(s, 4096));
}
