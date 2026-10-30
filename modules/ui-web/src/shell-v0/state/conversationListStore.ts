// SPDX-License-Identifier: Apache-2.0
/**
 * conversationListStore — lightweight conversation management (tempdoc 510 Design D).
 *
 * Lists, resumes, titles, and exports conversations. Separate from AiStateStore
 * because conversations are a content domain, not an AI state concern.
 */

import { subscribeProfileSwitch } from './UserStateDocument.js';
import { subscribeProfiles, getActiveProfileId as getWorkspaceProfileId } from '../substrates/profiles/index.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { startUserActivity } from '../utils/userActivity.js';
import { subscribeStatus } from '../utils/statusPoll.js';

/** Tempdoc 838 — who named a conversation. `null` when it has no name. */
export type ConversationTitleSource = 'user' | 'auto';

export interface Conversation {
  id: string;
  title: string | null;
  /**
   * Tempdoc 838 — provenance for `title`, straight off the wire. It is what lets a surface refuse to
   * auto-title a conversation the reader already named ACROSS A RELOAD: before this field the
   * provenance lived only in the tab's memory, so the next ask after a reload replaced the reader's
   * name with the model's.
   */
  titleSource: ConversationTitleSource | null;
  createdAt: number;
  lastActiveAt: number;
  /**
   * Tempdoc 859 slice C PR-2 — OPTIONAL, because the endpoint now lists conversations from two
   * records and only one of them can count messages cheaply. A run-backed (delegate) conversation
   * omits the field: deriving a count for it means projecting its whole event stream per row per
   * request. `undefined` is "not told" and renders as no count — never as zero, which would be a
   * claim that the conversation is empty.
   */
  messageCount?: number;
  firstUserMessage: string;
  shapeId: string;
  /**
   * Tempdoc 859 slice C PR-2 — does a `ConversationStore` session back this row? Absent on the wire
   * means yes (the conditional-key idiom `title` / `parentSessionId` already use), so every row that
   * predates the two-store join keeps its meaning. `false` marks a conversation that exists only as
   * agent runs: its id is real and its transcript is served by `GET /api/thread/{id}`, but every
   * per-row action that writes to a store session — rename, branch, context-floor, compact, exclude —
   * has nothing to write to. Delete DOES work: the endpoint deletes the conversation's runs.
   */
  storeBacked: boolean;
  // Slice 513 — branching: when this conversation was forked from another,
  // parentSessionId points at the source and branchPointMessageId records
  // the last message included from the parent. Both omitted on root sessions.
  parentSessionId?: string;
  branchPointMessageId?: string;
}

interface ConversationListState {
  conversations: Conversation[];
  /** Confirmed by successful DELETE or the Engine list; omission from its recent window is insufficient. */
  deletedIds: string[];
  activeId: string | null;
  loading: boolean;
}

let state: ConversationListState = {
  conversations: [],
  deletedIds: [],
  activeId: null,
  loading: false,
};

/**
 * Why the store emitted (tempdoc 864 PR C). The conversation a window is reading is URL-projected
 * now, and the URL projector has to tell two look-alike writes apart:
 *
 *  - `claim` — somebody OPENED a different conversation. That is a navigation, and it earns a
 *    history entry, which is what makes an accidental swap undoable with Back (§3.3(a)).
 *  - `restore` — the same write replaying state that already exists somewhere else: the URL on a
 *    deep link or a popstate, or this tab's own reload pointer. Pushing an entry for one of those
 *    would either duplicate the entry the browser just moved to or strand the URL a Back landed on.
 *  - `list` — the roster changed. Usually the claim did not, but DELETING the open conversation
 *    drops it under this reason too, and that is deliberate rather than an oversight: a deleted
 *    conversation is not somewhere the reader can go back to (the address would restore a phantom
 *    row and a 404 record), so the URL is corrected in place instead of earning an entry. The
 *    projector keys on the VALUE changing, so the correction still happens — as a replace.
 */
export type ConversationListChange = 'claim' | 'restore' | 'list';

const listeners = new Set<
  (s: ConversationListState, change: ConversationListChange) => void
>();
const TITLES_KEY = 'jf-conversation-titles';
const RECENT_SESSIONS_KEY = 'jf-chat-recent-sessions';
const MAX_RECENT = 10;
let apiBase = '';

export interface RecentSession {
  sessionId: string;
  timestamp: number;
}

// Tempdoc 562: the recent-session pointer NEVER carries message content. The previous shape cached a
// plaintext `firstMessage` slice in localStorage, which the resume card rendered on the start screen even
// while the chat store was encrypted + locked — a real content leak outside the encryption boundary. The
// resume preview is now derived from the lock-safe backend conversation list instead (`firstUserMessage`,
// which `listSessions` returns as "" while locked). This reader also purges any legacy plaintext at rest.
export function getRecentSessions(): RecentSession[] {
  try {
    const raw = localStorage.getItem(RECENT_SESSIONS_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as Array<Record<string, unknown>>;
    const clean: RecentSession[] = parsed
      .filter((s) => typeof s.sessionId === 'string')
      .map((s) => ({ sessionId: s.sessionId as string, timestamp: Number(s.timestamp) || 0 }));
    const cleanRaw = JSON.stringify(clean);
    if (cleanRaw !== raw) {
      // Legacy entries carried a `firstMessage` — rewrite the sanitized list to purge the cached plaintext.
      try { localStorage.setItem(RECENT_SESSIONS_KEY, cleanRaw); } catch { /* */ }
    }
    return clean;
  } catch { return []; }
}

export function recordRecentSession(sessionId: string): void {
  try {
    const sessions = getRecentSessions().filter((s) => s.sessionId !== sessionId);
    sessions.unshift({ sessionId, timestamp: Date.now() });
    if (sessions.length > MAX_RECENT) sessions.length = MAX_RECENT;
    localStorage.setItem(RECENT_SESSIONS_KEY, JSON.stringify(sessions));
  } catch { /* */ }
}

export function forgetRecentSession(sessionId: string): void {
  try {
    const sessions = getRecentSessions().filter((s) => s.sessionId !== sessionId);
    localStorage.setItem(RECENT_SESSIONS_KEY, JSON.stringify(sessions));
  } catch { /* */ }
}

function emit(change: ConversationListChange = 'list'): void {
  for (const l of listeners) l(state, change);
}

/**
 * Tempdoc 838 — the LEGACY title map, now read-only and self-erasing. It was the only authority for a
 * conversation's name until the backend grew one (`POST .../title`), and it is a tempdoc-562-class
 * defect in its own right: a plaintext name for a conversation whose every other content field is
 * sealed. {@link adoptLegacyTitles} writes each surviving entry through to the backend and deletes it
 * on a confirmed 2xx; when the map empties the key is removed and this reader returns `{}` forever.
 */
function loadTitles(): Record<string, string> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(TITLES_KEY) ?? '{}');
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const clean: Record<string, string> = {};
    for (const [id, title] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof title === 'string' && title !== '') clean[id] = title;
    }
    return clean;
  } catch { return {}; }
}

/** Drop one adopted entry, and the whole key once the last one is gone — migration is COMPLETE. */
function forgetLegacyTitle(id: string): void {
  releaseAdoption(id);
  try {
    const titles = loadTitles();
    if (!(id in titles)) return;
    delete titles[id];
    if (Object.keys(titles).length === 0) localStorage.removeItem(TITLES_KEY);
    else localStorage.setItem(TITLES_KEY, JSON.stringify(titles));
  } catch { /* */ }
}

/**
 * Legacy entries one of this browser's windows has sent to the server, keyed by conversation id,
 * with the time it was sent. Every window shares the legacy map, and the map keeps an entry until a
 * list answer reflects the saved name; without this claim a second window acting meanwhile would
 * send the legacy name again, over a rename made in between. Only ids and times are kept here, no
 * names. A claim older than {@link ADOPTION_CLAIM_MS} is treated as abandoned (its window closed
 * before the answer); adopting again is then safe because the server stores a legacy name only
 * while the conversation has none.
 */
const ADOPTING_KEY = 'jf-conversation-titles-adopting';
const ADOPTION_CLAIM_MS = 120_000;

function loadAdoptionClaims(): Record<string, number> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(ADOPTING_KEY) ?? '{}');
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const clean: Record<string, number> = {};
    for (const [id, at] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof at === 'number' && Number.isFinite(at)) clean[id] = at;
    }
    return clean;
  } catch { return {}; }
}

function storeAdoptionClaims(claims: Record<string, number>): void {
  try {
    if (Object.keys(claims).length === 0) localStorage.removeItem(ADOPTING_KEY);
    else localStorage.setItem(ADOPTING_KEY, JSON.stringify(claims));
  } catch { /* */ }
}

function claimAdoption(id: string): void {
  storeAdoptionClaims({ ...loadAdoptionClaims(), [id]: Date.now() });
}

function releaseAdoption(id: string): void {
  const claims = loadAdoptionClaims();
  if (!(id in claims)) return;
  delete claims[id];
  storeAdoptionClaims(claims);
}

function adoptionClaimed(id: string): boolean {
  const at = loadAdoptionClaims()[id];
  return at !== undefined && Date.now() - at < ADOPTION_CLAIM_MS;
}

/**
 * Names the backend has not accepted yet, keyed by conversation id (tempdoc 838).
 *
 * A rename can land in the sub-second window between a conversation being OPENED and its first
 * message being appended — before that append there is no `meta.json`, and the endpoint answers 404
 * rather than minting a zero-message conversation that would list as a row naming nothing. The name
 * is held here and re-sent once from the next successful {@link loadConversations}, which runs at
 * every completed ask.
 */
const pendingTitles = new Map<string, { title: string; source: ConversationTitleSource }>();

/**
 * Sessions whose legacy-title adopt hit a locked store (423). Retrying up to twenty of those on every
 * list refresh would be a per-poll burst that cannot succeed; the next reload tries again.
 */
const adoptBlockedIds = new Set<string>();

/** List requests in the order this tab made them; an answer older than the one on screen is dropped. */
let listRequests = 0;
let listShown = 0;

/**
 * Names this tab wrote (adopted, renamed or auto-titled) that no list answer has confirmed yet. A list
 * request made before the write completed can be answered from the server's state before the write;
 * for those answers the written name stays on screen, whatever older name the answer carries. A
 * `conditional` write (a legacy or automatic name, stored only while the conversation has none) stands
 * in only for an answer with no name: a name the server already holds wins over it. `confirmAfter` is
 * the last list request made before the write completed (`Infinity` while it is in flight): an answer
 * to any later request reflects the write, or something newer that supersedes it.
 */
const writtenTitles = new Map<
  string,
  { title: string; source: ConversationTitleSource; confirmAfter: number; conditional: boolean }
>();

/**
 * What the Head answered to a title write. A conditional write that found a name already stored is
 * refused with 412, and `title`/`titleSource` then carry the name the conversation keeps.
 */
interface TitleWriteOutcome {
  readonly ok: boolean;
  readonly status: number;
  readonly title?: string | null;
  readonly titleSource?: ConversationTitleSource | null;
  /** A later write of this tab to the same conversation replaced this one before it completed. */
  readonly superseded?: boolean;
}

/** Title writes in flight per conversation, so a later write always reaches the Head after an earlier one. */
const titleWrites = new Map<string, Promise<TitleWriteOutcome>>();

export function setConversationApiBase(base: string): void {
  apiBase = base;
}

export function subscribeConversationList(
  listener: (s: ConversationListState, change: ConversationListChange) => void,
): () => void {
  listeners.add(listener);
  // The catch-up call is a `restore`: it reports state that already exists rather than a claim
  // somebody just made, so a subscriber taken while a conversation is open must not read it as a
  // fresh navigation (tempdoc 864 PR C).
  listener(state, 'restore');
  return () => listeners.delete(listener);
}

export function getConversationListState(): ConversationListState {
  return state;
}

/** Stops listening for another window's changes to the legacy map; null until the first list load. */
let stopLegacyWatch: (() => void) | null = null;

/**
 * Another window of this browser that adopts or drops a legacy name (because the server already
 * holds one, a person's rename say) removes it from the shared map; this window may still show it.
 * The browser tells every other window through a `storage` event, and the list is read again (an
 * observation), so the name the server keeps replaces the retained one here as well.
 */
function watchOtherWindowsLegacyTitles(): void {
  if (stopLegacyWatch !== null || typeof window === 'undefined') return;
  const onStorage = (e: StorageEvent): void => {
    if (e.key === TITLES_KEY || e.key === null) void loadConversations();
  };
  window.addEventListener('storage', onStorage);
  stopLegacyWatch = () => window.removeEventListener('storage', onStorage);
}

// Identities observed in this window's server rows or cached server content. Never enumerate the
// Engine's full history: only these held identities can need a missing-row confirmation.
const serverConversations = new Set<string>();

// Confirmations live only in this window and this Engine run. More than 100 deletions between
// list answers can hide a missed delete, starting a new generation even in the same run.
let existenceGeneration: { instanceId: string; deletionCount: number } | null = null;
const confirmedExisting = new Set<string>();
const existenceReads = new Map<string, Promise<boolean | null>>();

function observeDeletionWindow(instanceId: unknown, deletionCount: unknown): void {
  const valid = typeof instanceId === 'string' && instanceId.trim() !== ''
    && typeof deletionCount === 'number' && Number.isSafeInteger(deletionCount) && deletionCount >= 0;
  if (!valid || !existenceGeneration || existenceGeneration.instanceId !== instanceId
    || deletionCount < existenceGeneration.deletionCount
    || deletionCount - existenceGeneration.deletionCount > 100) {
    confirmedExisting.clear();
    existenceReads.clear();
    existenceGeneration = valid ? { instanceId, deletionCount } : null;
  } else {
    existenceGeneration.deletionCount = deletionCount;
  }
}

/** Concurrent refreshes share one exact read; only an explicit boolean is an answer. */
function readConversationExists(id: string, request: number): Promise<boolean | null> {
  const pending = existenceReads.get(id);
  // A shared positive is reusable, but an absence read before this refresh may predate a restore.
  if (pending) return pending.then(exists => exists === false && request >= listShown
    ? readConversationExists(id, request) : exists);
  const read = (async () => {
    try {
      const answer = await authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(id)}/history?existsOnly=true`);
      if (!answer.ok) return null;
      const exists: unknown = (await answer.json()).exists;
      return typeof exists === 'boolean' ? exists : null;
    } catch { return null; }
  })();
  existenceReads.set(id, read);
  void read.finally(() => {
    if (existenceReads.get(id) === read) existenceReads.delete(id);
  });
  return read;
}

export function rememberServerConversation(id: string): void {
  if (serverConversations.has(id)) return;
  if (!state.deletedIds.includes(id)) serverConversations.add(id);
}

/** Complete, lock-safe versions of one fork; never changes the recent/sidebar roster. */
export async function loadForkConversations(
  parentSessionId: string,
  branchPointMessageId: string,
  signal: AbortSignal,
): Promise<Conversation[] | null> {
  try {
    const query = new URLSearchParams({ parentSessionId, branchPointMessageId });
    const response = await authorizedFetch(`${apiBase}/api/chat/conversations?${query}`, { signal });
    if (!response.ok) return null;
    const data = await response.json();
    // An older Engine ignores unknown filters. Its recent window is not a complete fork answer.
    if (data.fork?.parentSessionId !== parentSessionId
      || data.fork?.branchPointMessageId !== branchPointMessageId || !Array.isArray(data.sessions)) return null;
    return data.sessions.map((row: Record<string, unknown>) => toConversation(row, listRequests, {}));
  } catch { return null; }
}

let listProfileEpoch = 0;
let stopListProfileWatch: (() => void) | null = null;

export async function loadConversations(): Promise<boolean> {
  if (!stopListProfileWatch) {
    const invalidate = (): void => { listProfileEpoch += 1; };
    const stopUserProfile = subscribeProfileSwitch(invalidate);
    let workspaceProfile = getWorkspaceProfileId();
    const stopWorkspaceProfile = subscribeProfiles(() => {
      const next = getWorkspaceProfileId();
      if (next === workspaceProfile) return;
      workspaceProfile = next;
      invalidate();
    });
    stopListProfileWatch = () => { stopUserProfile(); stopWorkspaceProfile(); };
  }
  watchOtherWindowsLegacyTitles();
  const request = ++listRequests;
  const profileEpoch = listProfileEpoch;
  state = { ...state, loading: true };
  emit();
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/conversations?limit=20`);
    if (profileEpoch !== listProfileEpoch) return false;
    if (!res.ok) { state = { ...state, loading: false }; emit(); return false; }
    const data = await res.json();
    if (profileEpoch !== listProfileEpoch) return false;
    // A newer answer is already on screen; this one was built from older server state.
    if (request < listShown) return false;
    listShown = request;
    const rows: Array<Record<string, unknown>> = data.sessions ?? [];
    // The backend is the authority for a name now. A name this tab wrote that the answer may predate
    // is shown next, and the legacy browser map only for rows that have neither; each legacy entry is
    // written through when a person acts and erased once a list answer confirms the write.
    const legacy = loadTitles();
    const conversations = rows.map((row) => toConversation(row, request, legacy));
    const listed = new Set(conversations.map(c => c.id));
    const markers: string[] = Array.isArray(data.deletedIds)
      ? data.deletedIds.filter((id: unknown): id is string => typeof id === 'string')
      : [];
    observeDeletionWindow(data.instanceId, data.deletionCount);
    const generation = existenceGeneration;
    const missing = [...serverConversations].filter(id => !listed.has(id) && !markers.includes(id)
      && !confirmedExisting.has(id));
    const deletedIds = [...new Set([...state.deletedIds.filter(id => !listed.has(id)), ...markers])];
    for (const id of listed) serverConversations.add(id);
    for (const id of deletedIds) {
      serverConversations.delete(id);
      confirmedExisting.delete(id);
    }
    for (const id of deletedIds) {
      forgetRecentSession(id);
      forgetLegacyTitle(id);
    }
    state = { ...state, conversations: conversations.filter(c => !deletedIds.includes(c.id)), deletedIds,
      activeId: state.activeId !== null && deletedIds.includes(state.activeId) ? null : state.activeId,
      loading: false };
    emit();
    // Both write-throughs the list makes possible, fired AFTER the list has rendered so neither one
    // delays it: the names the backend refused with a 404 while the conversation was still being
    // created, and the legacy browser-local names this migration is retiring. A list load runs on
    // mount with nobody at the keyboard, and a title write counts as a person acting (it would
    // interrupt background enrichment), so legacy adoption waits for the person's next real input.
    void flushPendingTitles(conversations);
    confirmWrittenTitles(rows, request);
    forgetOutgrownLegacyTitles(rows, legacy);
    if (conversations.some((c) => needsAdoption(c, legacy))) adoptLegacyTitlesWhenAPersonActs();
    resumeAutoTitles(state.conversations);
    // Apply each exact answer independently: one unavailable read must not delay the list or
    // another confirmed absence. A limited list, failed read or old-server reply proves nothing.
    await Promise.all(missing.map(async id => {
      const exists = await readConversationExists(id, request);
      if (profileEpoch !== listProfileEpoch || generation !== existenceGeneration) return false;
      if (exists === true && generation !== null && serverConversations.has(id)) confirmedExisting.add(id);
      if (exists !== false || request < listShown) return false;
      serverConversations.delete(id);
      confirmedExisting.delete(id);
      forgetRecentSession(id);
      forgetLegacyTitle(id);
      state = { ...state, conversations: state.conversations.filter(c => c.id !== id),
        deletedIds: [...new Set([...state.deletedIds, id])],
        activeId: state.activeId === id ? null : state.activeId };
      emit();
    }));
    return true;
  } catch {
    if (profileEpoch !== listProfileEpoch) return false;
    state = { ...state, loading: false };
    emit();
    return false;
  }
}

/** One wire row, with the name this tab wrote standing in for a title the answer may predate. */
function toConversation(
  s: Record<string, unknown>,
  request: number,
  legacy: Record<string, string>,
): Conversation {
  const id = s.sessionId as string;
  const backendTitle = typeof s.title === 'string' && s.title !== '' ? s.title : null;
  const written = writtenTitles.get(id);
  // An answer to a request made before this tab's write completed may predate it, whatever name it
  // carries (the legacy name an adoption wrote just before a rename, say): the written name stays.
  const unconfirmed = written !== undefined && request <= written.confirmAfter
    && (backendTitle === null || !written.conditional)
    ? written
    : undefined;
  return {
    id,
    title: unconfirmed?.title ?? backendTitle ?? legacy[id] ?? null,
    titleSource: unconfirmed?.source ?? readTitleSource(s),
    createdAt: s.createdAtMs as number,
    lastActiveAt: s.lastActiveAtMs as number,
    messageCount: typeof s.messageCount === 'number' ? s.messageCount : undefined,
    firstUserMessage: s.firstUserMessage as string,
    shapeId: s.shapeId as string,
    // Absent means store-backed: only a row the endpoint SYNTHESISED from the agent-run record
    // carries the key, and it carries it as `false`.
    storeBacked: s.storeBacked !== false,
    parentSessionId: typeof s.parentSessionId === 'string'
      ? (s.parentSessionId as string)
      : undefined,
    branchPointMessageId: typeof s.branchPointMessageId === 'string'
      ? (s.branchPointMessageId as string)
      : undefined,
    // The row keeps its earlier shape: absent optional fields are present as undefined.
  } as Conversation;
}

/** The wire's `titleSource`, narrowed — an unknown value is no provenance rather than a wrong one. */
function readTitleSource(row: Record<string, unknown>): ConversationTitleSource | null {
  const raw = row.titleSource;
  return raw === 'user' || raw === 'auto' ? raw : null;
}

/**
 * Re-send the names held by the §1d create-race, ONCE each (tempdoc 838). The conversation exists
 * server-side from its first appended message, so by the time a list refresh runs the 404 is gone;
 * the entry is dropped either way, because a name that fails twice is a name the row is no longer
 * showing — this refresh already replaced it with whatever the backend holds.
 */
async function flushPendingTitles(conversations: readonly Conversation[]): Promise<void> {
  if (pendingTitles.size === 0) return;
  const listed = new Set(conversations.map((c) => c.id));
  for (const [id, held] of [...pendingTitles]) {
    if (!listed.has(id)) continue;
    pendingTitles.delete(id);
    const outcome = await writeTitle(id, held.title, held.source, { keepOn404: false });
    if (outcome.ok) applyTitle(id, held.title, held.source);
  }
}

/**
 * An answer to a list request made after a write completed reflects that write, or something newer
 * that supersedes it: the written name needs no stand-in any more, and a legacy browser copy of that
 * conversation's name is obsolete (the server holds a name, or a newer change replaced it).
 */
function confirmWrittenTitles(rows: ReadonlyArray<Record<string, unknown>>, request: number): void {
  for (const row of rows) {
    const id = row.sessionId as string;
    const written = writtenTitles.get(id);
    if (written === undefined || request <= written.confirmAfter) continue;
    writtenTitles.delete(id);
    forgetLegacyTitle(id);
  }
}

/** A backend title always wins: its presence means the local entry is stale rather than unadopted. */
function forgetOutgrownLegacyTitles(
  rows: ReadonlyArray<Record<string, unknown>>,
  legacy: Record<string, string>,
): void {
  for (const row of rows) {
    const id = row.sessionId as string;
    if (legacy[id] !== undefined && readTitleSource(row) !== null) forgetLegacyTitle(id);
  }
}

function needsAdoption(c: Conversation, legacy: Record<string, string>): boolean {
  return legacy[c.id] !== undefined && c.titleSource === null && !adoptBlockedIds.has(c.id)
    && !writtenTitles.has(c.id) && !adoptionClaimed(c.id);
}

/** Stops the one armed input watcher; null while nothing waits for a person. */
let stopAdoptionWatch: (() => void) | null = null;

/**
 * The adoption write is the reader's own title, so it is sent only as part of a person acting: the
 * first trusted app input (the same definition the shell reports as user activity) after a list
 * showed a row to adopt. Until then the legacy name is shown from the browser map, unchanged.
 */
function adoptLegacyTitlesWhenAPersonActs(): void {
  if (stopAdoptionWatch !== null || typeof document === 'undefined') return;
  stopAdoptionWatch = startUserActivity(document, async () => {
    stopAdoptionWatch?.();
    stopAdoptionWatch = null;
    await adoptLegacyTitles(loadTitles());
  });
}

/**
 * Tempdoc 838 migration — adopt once a person acts, once, and erase only what the server confirmed.
 *
 * For every listed row the backend has no name for while the legacy map does: the reader keeps
 * seeing the name they know (it was already merged in on list, and stays shown while the write is
 * in flight and while list answers built before it arrive), and the backend is told on the
 * person's next input. The local entry is deleted only once a list answer requested after a 2xx
 * reflects the write ({@link confirmWrittenTitles}); this function asks for that answer. A locked
 * store answers 423, the entry survives, and the next reload re-arms adoption — so the migration
 * cannot lose a name to a lock, and it cannot leave one behind either.
 *
 * A name a person gives the conversation always wins, from any window and at any moment. Before the
 * write, the row is skipped. While it is in flight in this window, the person's write is ordered
 * after it and stays on screen. Another window of this browser sees this window's claim and does not
 * send the legacy name again. And the write is conditional (`If-None-Match: *`): the server stores the
 * legacy name only while the conversation has none and otherwise answers 412 with the name it keeps,
 * so a name given from anywhere before the write arrives is kept, and shown. It is complete when
 * `jf-conversation-titles` is gone.
 */
async function adoptLegacyTitles(legacy: Record<string, string>): Promise<void> {
  if (Object.keys(legacy).length === 0) return;
  let adopted = false;
  for (const id of state.conversations.map((c) => c.id)) {
    // The row as it is now: a person may have renamed it while an earlier adoption was in flight.
    const c = state.conversations.find((row) => row.id === id);
    if (c === undefined || !needsAdoption(c, legacy)) continue;
    claimAdoption(id);
    // Adopted as the READER's: an existing name is one somebody chose to keep, and treating it as
    // auto-generated would license the next ask to overwrite it.
    const outcome = await writeTitle(id, legacy[id]!, 'user', { keepOn404: false, ifUntitled: true });
    if (outcome.ok) {
      adopted = true;
      if (!outcome.superseded && state.conversations.find((row) => row.id === id)?.titleSource === null) {
        applyTitle(id, legacy[id]!, 'user');
      }
    } else if (outcome.status === 412) {
      // The server already holds a newer name (given from another window, say), which stays; the
      // retained copy is obsolete.
      adopted = true;
      forgetLegacyTitle(id);
      const row = state.conversations.find((r) => r.id === id);
      if (!outcome.superseded && !writtenTitles.has(id) && row?.titleSource === null && outcome.title) {
        applyTitle(id, outcome.title, outcome.titleSource ?? null);
      }
    } else {
      releaseAdoption(id);
      if (outcome.status === 423) adoptBlockedIds.add(id);
    }
  }
  // An observation read, so the list confirms what the server now holds and the local copy can go.
  if (adopted) void loadConversations();
}

export function setActiveConversation(id: string | null): void {
  state = { ...state, activeId: id !== null && state.deletedIds.includes(id) ? null : id };
  emit('claim');
}

/**
 * The same write as {@link setActiveConversation}, declared as a REHYDRATION rather than a claim
 * (tempdoc 864 PR C). Callers: the router's store adapter, replaying the conversation the URL
 * carries on a deep link or a Back, and a window restoring the per-tab pointer it was reading
 * before a reload. Both are re-establishing a position the reader already had, so neither may add
 * a history entry — see {@link ConversationListChange}.
 */
export function restoreActiveConversation(id: string | null): void {
  state = { ...state, activeId: id !== null && state.deletedIds.includes(id) ? null : id };
  emit('restore');
}

/**
 * Tempdoc 610 Phase B — the ordered "version" set at a fork point. A fork is
 * identified by a base conversation `baseId` and the `branchKey` (the branch
 * point: the parent message id the branches diverge after, or the empty-prefix
 * sentinel). Version 1 is always the base (the original continuation); the
 * branches that share `(parentSessionId === baseId, branchPointMessageId ===
 * branchKey)` follow, ordered by creation time. Returns `[baseId]` alone when
 * there are no branches (callers render the pager only when length > 1). This
 * is a pure projection over the supplied rows: a complete fork read in Search v3,
 * or the loaded recent list in the legacy window.
 */
export function siblingSessionsAt(
  conversations: Conversation[],
  baseId: string,
  branchKey: string,
): string[] {
  const branches = conversations
    .filter((c) => c.parentSessionId === baseId && c.branchPointMessageId === branchKey)
    .slice()
    .sort((a, b) => a.createdAt - b.createdAt)
    .map((c) => c.id);
  return [baseId, ...branches];
}

/** The in-memory half of a rename: the list re-renders NOW, whatever the network is about to do. */
function applyTitle(
  id: string,
  title: string | null,
  titleSource: ConversationTitleSource | null,
): void {
  state = {
    ...state,
    conversations: state.conversations.map((c) =>
      c.id === id ? { ...c, title, titleSource } : c,
    ),
  };
  emit();
}

/**
 * The outcome of a title write (tempdoc 838). `status` is the HTTP status the Head answered, or `0`
 * when the request never reached it; `423` is the store refusing because its data key is locked.
 */
export interface ConversationTitleWrite {
  readonly ok: boolean;
  readonly status: number;
}

/**
 * The one write. `status` is 0 when the request never reached the Head. Writes to one conversation
 * are sent one after another, so the Head applies them in the order they were made.
 */
function postTitle(
  id: string,
  title: string,
  source: ConversationTitleSource,
  ifUntitled: boolean,
): Promise<TitleWriteOutcome> {
  const earlier = titleWrites.get(id);
  const write = earlier === undefined
    ? sendTitle(id, title, source, ifUntitled)
    : earlier.then(() => sendTitle(id, title, source, ifUntitled));
  titleWrites.set(id, write);
  void write.then(() => { if (titleWrites.get(id) === write) titleWrites.delete(id); });
  return write;
}

/**
 * A title write that keeps its name on screen until a list answer confirms it. `keepOn404` holds a
 * name the conversation was too young for, which {@link flushPendingTitles} re-sends. `ifUntitled`
 * asks the server to store the name only while the conversation has none.
 */
async function writeTitle(
  id: string,
  title: string,
  source: ConversationTitleSource,
  { keepOn404, ifUntitled = false }: { keepOn404: boolean; ifUntitled?: boolean },
): Promise<TitleWriteOutcome> {
  const earlier = writtenTitles.get(id);
  const entry = { title, source, confirmAfter: Infinity, conditional: ifUntitled };
  writtenTitles.set(id, entry);
  const outcome = await postTitle(id, title, source, ifUntitled);
  if (writtenTitles.get(id) !== entry) return { ...outcome, superseded: true };
  if (outcome.ok) entry.confirmAfter = listRequests;
  else if (!(keepOn404 && outcome.status === 404)) {
    if (earlier !== undefined && earlier.confirmAfter !== Infinity) writtenTitles.set(id, earlier);
    else writtenTitles.delete(id);
  }
  return outcome;
}

async function sendTitle(
  id: string,
  title: string,
  source: ConversationTitleSource,
  ifUntitled: boolean,
): Promise<TitleWriteOutcome> {
  try {
    // `If-None-Match: *` is HTTP's "only if there is none yet": the Head stores the name only while
    // the conversation has none, and answers 412 with the name it keeps otherwise.
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (ifUntitled) headers['If-None-Match'] = '*';
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(id)}/title`,
      { method: 'POST', headers, body: JSON.stringify({ title, source }) },
    );
    if (res.ok) rememberServerConversation(id);
    if (res.status !== 412) return { ok: res.ok, status: res.status };
    const body = await readJson(res);
    return {
      ok: false,
      status: 412,
      title: typeof body?.title === 'string' && body.title !== '' ? body.title : null,
      titleSource: body === null ? null : readTitleSource(body),
    };
  } catch {
    return { ok: false, status: 0 };
  }
}

/** A JSON object body, or null when there is none to read. */
async function readJson(res: Response): Promise<Record<string, unknown> | null> {
  try {
    if (typeof res.json !== 'function') return null;
    const parsed: unknown = await res.json();
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * Name a conversation, durably (tempdoc 838). Optimistic, then authoritative:
 *
 *  1. The list renames SYNCHRONOUSLY, because a name that flickered while a request was in flight
 *     would be worse than one that can fail.
 *  2. The backend is told. It is the only writer of record — there is deliberately NO localStorage
 *     mirror any more: the title is a sealed meta field now, so a plaintext browser copy of it would
 *     show a locked conversation's subject while every other content field of that conversation
 *     refuses to render (the tempdoc 562 defect, in a second key). And there is no offline case to
 *     serve: every row in this list came from the Head, so if the Head is unreachable there is
 *     nothing on screen to rename.
 *  3. A failure REVERTS the row and returns false, so the caller can say so. The one exception is
 *     the 404 of a conversation whose first message has not landed yet ({@link pendingTitles}):
 *     that name is kept on screen and re-sent from the next list refresh, because it is a race with
 *     creation rather than a refusal.
 *
 * `status` rides along with the outcome rather than being reported as a bare `false`, because the
 * caller has to word a LOCKED store (`423`) differently from any other failure — a boolean cannot
 * carry the difference, and re-deriving it with a second request would be a second authority.
 */
export async function setConversationTitle(
  id: string,
  title: string,
  source: ConversationTitleSource = 'user',
): Promise<ConversationTitleWrite> {
  const before = state.conversations.find((c) => c.id === id);
  applyTitle(id, title, source);
  const outcome = await writeTitle(id, title, source, { keepOn404: true });
  if (outcome.ok) {
    pendingTitles.delete(id);
    return { ok: true, status: outcome.status };
  }
  if (outcome.status === 404) {
    pendingTitles.set(id, { title, source });
    return { ok: true, status: outcome.status };
  }
  if (before !== undefined) applyTitle(id, before.title, before.titleSource);
  return { ok: false, status: outcome.status };
}

export function createConversationId(): string {
  return `uc-${crypto.randomUUID()}`;
}

/** The first wait before asking again for an automatic title; it doubles up to the longest wait. */
const AUTO_TITLE_FIRST_RETRY_MS = 5_000;
/** The longest wait between two requests for an automatic title. */
const AUTO_TITLE_MAX_RETRY_MS = 60_000;
/** Statuses that mean "not now": the model is busy, loading or briefly unreachable. */
const AUTO_TITLE_RETRY_STATUSES = new Set([0, 429, 502, 503, 504]);
/**
 * Conversations whose automatic name was asked for and not yet settled, with the time of the last
 * request, shared by this browser's windows (ids and times only). A window that closes while the
 * model is busy leaves its entry; the next list load in any window asks again, so the name is not
 * lost. An entry asked for within {@link AUTO_TITLE_RESUME_AFTER_MS} belongs to a window still asking.
 */
const AUTO_TITLE_PENDING_KEY = 'jf-conversation-autotitle-pending';
const AUTO_TITLE_RESUME_AFTER_MS = 2 * AUTO_TITLE_MAX_RETRY_MS;
/** Conversations this window is asking an automatic name for now. */
const autoTitlesAsking = new Set<string>();
/** Bumped by the test reset, which ends every retry loop a previous test left waiting. */
let autoTitleEpoch = 0;

function loadAutoTitlesPending(): Record<string, number> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(AUTO_TITLE_PENDING_KEY) ?? '{}');
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const clean: Record<string, number> = {};
    for (const [id, at] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof at === 'number' && Number.isFinite(at)) clean[id] = at;
    }
    return clean;
  } catch { return {}; }
}

function storeAutoTitlesPending(pending: Record<string, number>): void {
  try {
    if (Object.keys(pending).length === 0) localStorage.removeItem(AUTO_TITLE_PENDING_KEY);
    else localStorage.setItem(AUTO_TITLE_PENDING_KEY, JSON.stringify(pending));
  } catch { /* */ }
}

function markAutoTitlePending(id: string): void {
  storeAutoTitlesPending({ ...loadAutoTitlesPending(), [id]: Date.now() });
}

function settleAutoTitle(id: string): void {
  const pending = loadAutoTitlesPending();
  if (!(id in pending)) return;
  delete pending[id];
  storeAutoTitlesPending(pending);
}

/**
 * Ask again for the automatic names a closed window left unsettled (see {@link AUTO_TITLE_PENDING_KEY}),
 * and forget those the list shows already named. Called after a list load; the requests are
 * observations, so an untouched window asking again does not count as a person acting.
 */
function resumeAutoTitles(conversations: readonly Conversation[]): void {
  const pending = loadAutoTitlesPending();
  for (const [id, at] of Object.entries(pending)) {
    const row = conversations.find((c) => c.id === id);
    if (row === undefined) continue;
    if (row.titleSource !== null) { settleAutoTitle(id); continue; }
    if (autoTitlesAsking.has(id)) continue;
    if (Date.now() - at < AUTO_TITLE_RESUME_AFTER_MS) { resumeAutoTitleIfAbandoned(id, at); continue; }
    void askAutoTitle(id);
  }
}

/** Entries this window will look at again once their asker has had time to ask again. */
const autoTitleResumeChecks = new Map<string, ReturnType<typeof setTimeout>>();

/**
 * An entry asked for recently belongs to the window asking, which renews it on every request (at
 * most {@link AUTO_TITLE_MAX_RETRY_MS} apart). That window may be the one a reload just replaced, so
 * look again once the entry would be stale: still unsettled and not renewed means nobody is asking,
 * and this window asks, an observation, without waiting for another list load. Each window looks
 * at its own random moment within the first retry interval, so when several windows wait for the
 * same entry the first to ask renews it and the others find it owned again.
 */
function resumeAutoTitleIfAbandoned(id: string, at: number): void {
  if (autoTitleResumeChecks.has(id)) return;
  const epoch = autoTitleEpoch;
  autoTitleResumeChecks.set(id, setTimeout(() => {
    autoTitleResumeChecks.delete(id);
    if (epoch !== autoTitleEpoch || autoTitlesAsking.has(id)) return;
    const renewed = loadAutoTitlesPending()[id];
    if (renewed === undefined) return;
    const row = state.conversations.find((c) => c.id === id);
    if (row === undefined) return;
    if (row.titleSource !== null) { settleAutoTitle(id); return; }
    if (Date.now() - renewed < AUTO_TITLE_RESUME_AFTER_MS) { resumeAutoTitleIfAbandoned(id, renewed); return; }
    void askAutoTitle(id);
  }, Math.max(0, at + AUTO_TITLE_RESUME_AFTER_MS - Date.now())
    + Math.floor(Math.random() * AUTO_TITLE_FIRST_RETRY_MS)));
}

/**
 * Ask the model to name a conversation once its first question has been answered. Returns the name
 * the model gave it, or null when the conversation kept another name or none could be made.
 *
 * This is work the window starts on its own, after the person's own action has finished, so it uses
 * the one route declared for it (`POST .../auto-title`, an observation): it never counts as a person
 * acting, and it could not carry a person's deliberate work, because its only input is the
 * conversation id. The server builds the prompt from the stored transcript and stores the name only
 * while the conversation has none, so a rename always wins.
 *
 * It is background inference in the chat lane: while the model is busy (picture reading, a reply,
 * a self-test) or not loaded, the server refuses with a typed reason and a `retryAfterMs`, and this
 * asks again after that time, backing off to a minute apart, so the name still arrives once the
 * model is free. Should the window close first, the next list load in any window of this browser
 * asks again ({@link resumeAutoTitles}). A refusal that cannot change by waiting (no such
 * conversation, nothing answered yet, a locked store, chat off) settles it. Best-effort — never throws.
 */
export async function generateConversationTitle(
  sessionId: string,
  userMsg: string,
  aiMsg: string,
): Promise<string | null> {
  if (!userMsg || !aiMsg) return null;
  return askAutoTitle(sessionId);
}

async function askAutoTitle(sessionId: string): Promise<string | null> {
  if (autoTitlesAsking.has(sessionId)) return null;
  autoTitlesAsking.add(sessionId);
  const epoch = autoTitleEpoch;
  try {
    let backoff = AUTO_TITLE_FIRST_RETRY_MS;
    for (;;) {
      markAutoTitlePending(sessionId);
      let status = 0;
      let body: Record<string, unknown> | null = null;
      try {
        const res = await authorizedFetch(
          `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/auto-title`,
          { method: 'POST' },
        );
        status = res.status;
        body = await readJson(res);
        if (res.ok) {
          settleAutoTitle(sessionId);
          return autoTitleAnswered(sessionId, body);
        }
      } catch { /* unreachable: asked again below */ }
      if (!AUTO_TITLE_RETRY_STATUSES.has(status)) {
        settleAutoTitle(sessionId);
        return null;
      }
      const asked = typeof body?.retryAfterMs === 'number' ? body.retryAfterMs : 0;
      await new Promise((resume) => setTimeout(resume, Math.min(Math.max(asked, backoff), AUTO_TITLE_MAX_RETRY_MS)));
      if (epoch !== autoTitleEpoch) return null;
      backoff = Math.min(backoff * 2, AUTO_TITLE_MAX_RETRY_MS);
    }
  } finally {
    if (epoch === autoTitleEpoch) autoTitlesAsking.delete(sessionId);
  }
}

/** The server's answer to an automatic title: shown at once, unless this window has a name in flight. */
function autoTitleAnswered(sessionId: string, body: Record<string, unknown> | null): string | null {
  const title = typeof body?.title === 'string' && body.title !== '' ? body.title : null;
  if (title === null) return null;
  const source = readTitleSource(body!) ?? 'auto';
  const row = state.conversations.find((c) => c.id === sessionId);
  if (!writtenTitles.has(sessionId) && (row === undefined || row.titleSource === null)) {
    // A list answer requested before the server stored it would show no name; this stands in.
    writtenTitles.set(sessionId, { title, source, confirmAfter: listRequests, conditional: true });
    if (row !== undefined) applyTitle(sessionId, title, source);
  }
  return body?.generated === true ? title : null;
}

export interface ResumedMessage {
  role: 'user' | 'assistant';
  content: string;
  // Slice 513 — stable message id from the backend (real UUID for post-513
  // writes, synthetic idx-N for legacy data). Used as the branch-point id
  // when the user clicks "Branch here" on an assistant turn.
  id?: string;
  /**
   * Tempdoc 941 — the shape THIS turn was dispatched by, per message.
   *
   * `FileConversationStore.enrichMessage` stamps `shapeId` on every appended message (863 §4.A.5
   * F1) precisely because the conversation-level `shapeId` cannot answer it: that one is
   * first-wins for the whole conversation (A-10.1), so in a MIXED conversation every turn reports
   * the shape that OPENED it. The live path already reads the per-turn fact
   * (`UnifiedChatView.recordShapeId`, off `attributes.shapeId` in the unified thread); the resume
   * path threw it away and stamped one conversation-level shape across the whole transcript —
   * which is how a `core.rag-ask` answer over five sources came back framed as "Model answer —
   * this mode does not search your documents". Same evidence, different claim about it.
   *
   * Absent for a row written before 863 (there is deliberately no backfill), in which case the
   * caller falls back to the conversation-level shape, as it always did.
   */
  shapeId?: string;
  /**
   * Tempdoc 941 — the decontextualized question this turn's retrieval actually ran on, when the
   * record carries it (`QueryRewriteInjector.ATTR_STANDALONE_QUESTION` = `rag.standaloneQuestion`).
   *
   * The live path pins it onto the committed turn so the "Interpreted as: …" transparency line
   * survives the stream (603 C2); carrying it here is what lets a RELOADED turn say the same
   * thing. `ConversationEngine` persists it as a top-level `standaloneQuestion` on the turn's
   * outcome row (its answer, or its error) when the follow-up was rewritten; it is still read
   * defensively (nested `attributes`, both spellings), and an older record without it reads as
   * absent.
   */
  standaloneQuestion?: string;
}

export interface ResumedConversation {
  sessionId: string;
  shapeId: string;
  messages: Array<ResumedMessage>;
  // Slice 513 — when this is a branch, parentSessionId is the source and
  // branchPointMessageId is the last inherited message id. Messages up to
  // and including that id were prepended from the parent.
  parentSessionId?: string;
  branchPointMessageId?: string;
  // Slice 515 FIX-8 — parent's first user message preview, surfaced by the
  // backend so the branch banner can name the parent without a second
  // roundtrip. Falls back to undefined when the parent is missing or empty.
  parentFirstUserMessage?: string;
  // Tempdoc 610 Phase C — the effective-context floor message id (undefined =
  // no floor). The transcript still displays the full history; messages above
  // this id are out-of-context (not sent to the next LLM turn).
  contextFloor?: string;
  // Tempdoc 610 Phase D — the compaction summary attached to the floor
  // (undefined for a plain rewind). Shown via the divider's "Show summary".
  contextFloorSummary?: string;
  // Tempdoc 610 §E.3 — message ids the user excluded from the effective context
  // (still displayed in the transcript, dropped from the next prompt).
  excludedMessageIds?: string[];
  // Tempdoc 610 §J.3 — retrieved-source ids the user hid from retrieval (unit-separator-joined
  // parentDocId+chunkIndex); the Worker drops these chunks on subsequent turns.
  excludedSourceIds?: string[];
  // Tempdoc 629 (LAYER) — true when the history load returned 423 (the conversation store is
  // encrypted + locked). The transcript must render a "locked → Unlock" notice, NOT an empty
  // thread (an empty thread reads as deleted; §L4: locked must never look deleted).
  locked?: boolean;
  // True when the read itself FAILED (a server error or no answer) — as opposed to a 404, which is
  // the backend saying the conversation has no record. Every other field is then "not told", and a
  // caller that keeps state from an earlier read must not take the empty result for "nothing is
  // hidden, no floor" (search-v3 keeps what it had).
  failed?: boolean;
}

/**
 * Reading a conversation's history CLAIMS it as the app-wide active conversation by default, because
 * the shipped window's only caller is its open path. A caller that reads the history WITHOUT the
 * reader navigating — search-v3's companion load (tempdoc 852 S1), which does its own claiming at
 * open — passes `claim: false`: a read that moved the product's idea of where the reader is, while
 * they were somewhere else entirely, is a side effect no read should have.
 */
export interface ResumeOptions {
  readonly claim?: boolean;
}

/**
 * The first of `values` that is a non-blank string, else `undefined`. "Absent ⇒ no key" — an empty
 * string is not a fact, and writing one onto a resumed turn would claim a shape/rewrite that was
 * never recorded (tempdoc 941; same `putIfPresent` discipline the Java writer uses).
 */
function firstString(...values: readonly unknown[]): string | undefined {
  for (const v of values) {
    // 941 review — return the TRIMMED value, not the raw one. The blankness test already trims, so
    // returning `v` meant `" core.rag-ask "` passed the guard and then failed the exact-match
    // narrowing in `asKnownShape` — accepted here, silently dropped there.
    if (typeof v === 'string' && v.trim().length > 0) return v.trim();
  }
  return undefined;
}

export async function resumeConversation(
  sessionId: string,
  shapeId: string,
  { claim = true }: ResumeOptions = {},
): Promise<ResumedConversation> {
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/history`);
    // Tempdoc 629 (LAYER): 423 = the conversation store is encrypted + locked. Surface it as a typed
    // locked state so the chat surface renders an unlock affordance instead of an empty (deleted-looking)
    // transcript. Other non-OK responses remain a benign empty resume.
    if (res.status === 423) return { sessionId, shapeId, messages: [], locked: true };
    if (!res.ok) {
      return res.status === 404
        ? { sessionId, shapeId, messages: [] }
        : { sessionId, shapeId, messages: [], failed: true };
    }
    const data = (await res.json()) as {
      // Tempdoc 941 — the endpoint returns the STORE'S OWN message maps verbatim
      // (`ChatController.handleLoadHistory` → `conversationStore.loadHistory`), so the per-message
      // facts the store stamps are already on the wire; only the reader was narrow.
      messages?: Array<{
        role?: string;
        content?: string;
        id?: string;
        shapeId?: unknown;
        standaloneQuestion?: unknown;
        attributes?: Record<string, unknown>;
      }>;
      parentSessionId?: string;
      branchPointMessageId?: string;
      parentFirstUserMessage?: string;
      contextFloor?: string;
      contextFloorSummary?: string;
      excludedMessageIds?: string[];
      excludedSourceIds?: string[];
    };
    const messages = (data.messages ?? [])
      .filter((m) => (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string')
      .map((m): ResumedMessage => {
        // Tempdoc 941 — the store stamps `shapeId` at the message's top level
        // (`FileConversationStore.enrichMessage`); `attributes.shapeId` is the same fact under the
        // unified-thread spelling (`InteractionThreadController.chatTurn` lifts it there, and
        // `UnifiedChatView.recordShapeId` reads it). Accept either so the two transports of one
        // fact cannot disagree about whether this reader sees it.
        const shapeId = firstString(m.shapeId, m.attributes?.['shapeId']);
        const standaloneQuestion = firstString(
          m.attributes?.['rag.standaloneQuestion'],
          m.attributes?.['standaloneQuestion'],
          m.standaloneQuestion,
        );
        return {
          role: m.role as 'user' | 'assistant',
          content: m.content as string,
          id: typeof m.id === 'string' ? m.id : undefined,
          ...(shapeId !== undefined ? { shapeId } : {}),
          ...(standaloneQuestion !== undefined ? { standaloneQuestion } : {}),
        };
      });
    if (state.deletedIds.includes(sessionId)) return { sessionId, shapeId, messages: [] };
    if (messages.length > 0) rememberServerConversation(sessionId);
    if (claim) setActiveConversation(sessionId);
    return {
      sessionId,
      shapeId,
      messages,
      parentSessionId: typeof data.parentSessionId === 'string'
        ? data.parentSessionId
        : undefined,
      branchPointMessageId: typeof data.branchPointMessageId === 'string'
        ? data.branchPointMessageId
        : undefined,
      parentFirstUserMessage: typeof data.parentFirstUserMessage === 'string'
        ? data.parentFirstUserMessage
        : undefined,
      contextFloor: typeof data.contextFloor === 'string' ? data.contextFloor : undefined,
      contextFloorSummary:
        typeof data.contextFloorSummary === 'string' ? data.contextFloorSummary : undefined,
      excludedMessageIds: Array.isArray(data.excludedMessageIds)
        ? data.excludedMessageIds.filter((x): x is string => typeof x === 'string')
        : undefined,
      excludedSourceIds: Array.isArray(data.excludedSourceIds)
        ? data.excludedSourceIds.filter((x): x is string => typeof x === 'string')
        : undefined,
    };
  } catch {
    return { sessionId, shapeId, messages: [], failed: true };
  }
}

/**
 * Tempdoc 610 Phase C — set the session's effective-context floor (the next
 * LLM turn's prompt starts at this message; the transcript still shows
 * everything above it). Returns true on success.
 */
export async function setContextFloor(
  sessionId: string,
  floorMessageId: string,
): Promise<boolean> {
  try {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/context-floor`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ floorMessageId }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/** Tempdoc 610 Phase C — clear the floor (restore full context). */
export async function clearContextFloor(sessionId: string): Promise<boolean> {
  try {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/context-floor`,
      { method: 'DELETE' },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Tempdoc 610 Phase D — compact: the backend summarizes the messages above the
 * floor (one-shot LLM) and attaches the summary to the floor. Returns the
 * generated summary, or null on failure (LLM unavailable, nothing to compact).
 */
export async function compactContext(
  sessionId: string,
  floorMessageId: string,
): Promise<string | null> {
  try {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/compact`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ floorMessageId }),
      },
    );
    if (!res.ok) return null;
    const data = (await res.json()) as { contextFloorSummary?: string };
    return typeof data.contextFloorSummary === 'string' ? data.contextFloorSummary : null;
  } catch {
    return null;
  }
}

/**
 * Tempdoc 610 §E.2 — edit the compaction summary in place. The floor is unchanged; only the stored
 * summary text is replaced (the backend reuses compactContext with the current floor message id), so
 * the corrected summary stands in for the dropped turns on the next prompt. Returns true on success.
 */
export async function editContextFloorSummary(
  sessionId: string,
  summaryText: string,
): Promise<boolean> {
  try {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/context-floor/summary`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ summaryText }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Tempdoc 610 §E.3 — toggle whether a single message is excluded from the effective context. The
 * transcript still displays it; only the next prompt drops it. Returns true on success.
 */
export async function setMessageExcluded(
  sessionId: string,
  messageId: string,
  excluded: boolean,
): Promise<boolean> {
  try {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(
        sessionId,
      )}/messages/${encodeURIComponent(messageId)}/exclude`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ excluded }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Tempdoc 610 §J.3 — toggle whether a retrieved source (a unit-separator-joined parentDocId+chunkIndex
 * id) is hidden from this conversation's retrieval. The Worker drops the matching chunk on subsequent
 * turns; past transcript turns are unaffected. The sourceId rides in the body (it has slashes/colons).
 */
export async function setSourceExcluded(
  sessionId: string,
  sourceId: string,
  excluded: boolean,
): Promise<boolean> {
  try {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/sources/exclude`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sourceId, excluded }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Slice 513 — refresh the stable message ids for a conversation that was
 * just extended in-memory by a streaming send. The store-side ids are
 * authoritative; this fetches the latest history and returns it so the
 * caller can splice ids into its own thread without rebuilding state.
 *
 * Returns null on transport failure so callers can no-op safely.
 */
export async function fetchMessageIds(
  sessionId: string,
): Promise<Array<{ role: string; content: string; id?: string }> | null> {
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/history`);
    if (!res.ok) return null;
    const data = (await res.json()) as {
      messages?: Array<{ role?: string; content?: string; id?: string }>;
    };
    if ((data.messages ?? []).some(m => (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string')) {
      rememberServerConversation(sessionId);
    }
    return (data.messages ?? []).map((m) => ({
      role: typeof m.role === 'string' ? m.role : '',
      content: typeof m.content === 'string' ? m.content : '',
      id: typeof m.id === 'string' ? m.id : undefined,
    }));
  } catch {
    return null;
  }
}

/**
 * Slice 513 — branch a conversation from a specific assistant message. The
 * backend creates a new sessionId whose loadHistory will lazily resolve the
 * parent prefix up to and including {@code fromMsgId}. The FE records the
 * new sessionId as a recent session and returns it so the caller can navigate
 * the chat surface into the branch.
 */
export async function branchConversation(
  parentSessionId: string,
  fromMsgId: string,
  firstMessagePreview?: string,
): Promise<string | null> {
  try {
    const url = `${apiBase}/api/chat/conversations/${encodeURIComponent(parentSessionId)}/branch`
      + `?fromMsgId=${encodeURIComponent(fromMsgId)}`;
    const res = await authorizedFetch(url, { method: 'POST' });
    if (!res.ok) return null;
    const data = (await res.json()) as { sessionId?: string };
    const newSessionId = data.sessionId;
    if (!newSessionId) return null;
    if (typeof newSessionId === 'string') rememberServerConversation(newSessionId);
    // Record as recent so the resume prompt + history dropdown surface it
    // without waiting for the next listSessions roundtrip.
    // Tempdoc 562: record the session POINTER only (no cached content); the preview is derived from the
    // lock-safe backend list. Still gated on a non-empty preview so only meaningful branches are surfaced.
    if (firstMessagePreview) recordRecentSession(newSessionId);
    return newSessionId;
  } catch {
    return null;
  }
}

export async function deleteConversation(sessionId: string): Promise<boolean> {
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
    });
    if (!res.ok) return false;
    forgetRecentSession(sessionId);
    forgetLegacyTitle(sessionId);
    // An older in-flight list cannot undo this successful deletion.
    listShown = ++listRequests;
    state = {
      ...state,
      conversations: state.conversations.filter((c) => c.id !== sessionId),
      deletedIds: [...new Set([...state.deletedIds, sessionId])],
      loading: false,
      activeId: state.activeId === sessionId ? null : state.activeId,
    };
    emit();
    return true;
  } catch {
    return false;
  }
}

/** One confirmed row, in preorder, with structural facts available even while content is locked. */
export interface DeleteTreeEntry extends Conversation {
  depth: number;
  busy: boolean;
}

export interface DeleteCascadeResult {
  ok: boolean;
  childIds?: string[];
  error?: string;
  errorCode?: string;
  deletedIds?: string[];
  remainingIds?: string[];
  unconfirmedIds?: string[];
  failedId?: string;
  cancelled?: boolean;
}

/** Java's equalsIgnoreCase uses single-code-point casing, including dotted I and final sigma. */
function sameDeleteParentId(left: string, right: string): boolean {
  const fold = (id: string): string => Array.from(id, point => {
    const upper = point.toUpperCase();
    // Full JS casing can expand a character (ß -> SS); the store does not expand characters.
    const simple = Array.from(upper).length === 1 ? upper : point;
    return String.fromCodePoint(simple.toLowerCase().codePointAt(0)!);
  }).join('');
  return left.length === right.length && fold(left) === fold(right);
}

/** Client walk over complete direct-child reads; no recent-list cap and no recursive server delete. */
async function readDeleteTree(root: string): Promise<{ tree: DeleteTreeEntry[]; locked: boolean }> {
  const tree: DeleteTreeEntry[] = [];
  const pending = [{ id: root, depth: 0 }];
  const seen = new Set<string>();
  let locked = false;
  while (pending.length > 0) {
    const next = pending.pop()!;
    if (seen.has(next.id)) throw new Error('The conversation tree changed. Review it again before deleting.');
    seen.add(next.id);
    const query = new URLSearchParams({ childrenOf: next.id });
    const response = await authorizedFetch(`${apiBase}/api/chat/conversations?${query}`);
    if (!response.ok) {
      let reason = 'Could not read the complete conversation tree. Nothing further was deleted.';
      try { const body = await response.json(); if (typeof body.error === 'string') reason = body.error; } catch { /* No readable reason. */ }
      throw new Error(reason);
    }
    const data = await response.json();
    if (data.childrenOf !== next.id || !Array.isArray(data.sessions) || typeof data.locked !== 'boolean') {
      throw new Error('The Engine could not confirm the complete conversation tree.');
    }
    locked ||= data.locked;
    const rows = data.sessions as Record<string, unknown>[];
    const row = rows.find(r => r.sessionId === next.id);
    if (!row || typeof row.busy !== 'boolean') throw new Error('The conversation changed or could not be read. Review it again before deleting.');
    tree.push({ ...toConversation(row, listRequests, {}), depth: next.depth, busy: row.busy });
    const children = rows.filter(r => r.sessionId !== next.id);
    for (const child of children.reverse()) {
      if (typeof child.sessionId !== 'string' || typeof child.parentSessionId !== 'string'
          || !sameDeleteParentId(child.parentSessionId, next.id)) {
        throw new Error('The Engine could not confirm the conversation tree.');
      }
      pending.push({ id: child.sessionId, depth: next.depth + 1 });
    }
  }
  return { tree, locked };
}

function rememberDeletedConversation(id: string): void {
  forgetRecentSession(id);
  forgetLegacyTitle(id);
  listShown = ++listRequests;
  state = { ...state, conversations: state.conversations.filter(c => c.id !== id),
    deletedIds: [...new Set([...state.deletedIds, id])], loading: false,
    activeId: state.activeId === id ? null : state.activeId };
  emit();
}

/** Refresh the roster, then ask the two-store existence boundary about EACH named identity. */
async function reconcileDelete(tree: readonly { id: string }[], confirmed: Set<string>, result: DeleteCascadeResult): Promise<DeleteCascadeResult> {
  await loadConversations();
  const deletedIds: string[] = [];
  const remainingIds: string[] = [];
  const unconfirmedIds: string[] = [];
  for (const row of tree) {
    if (confirmed.has(row.id)) { deletedIds.push(row.id); continue; }
    const exists = await readConversationExists(row.id, listRequests);
    if (exists === true) remainingIds.push(row.id);
    else if (exists === false) { deletedIds.push(row.id); rememberDeletedConversation(row.id); }
    else unconfirmedIds.push(row.id);
  }
  return { ...result, deletedIds, remainingIds, unconfirmedIds };
}

/** Only unresolved observations survive a cascade. Reconnection never retains a delete action. */
const pendingDeleteReads = new Set<() => Promise<void>>();
let stopDeleteStatus: (() => void) | null = null;

function watchUnconfirmedDelete(
  tree: readonly { id: string }[], initial: DeleteCascadeResult,
  onReconciled?: (result: DeleteCascadeResult) => void,
): DeleteCascadeResult {
  if (!initial.unconfirmedIds?.length || typeof window === 'undefined') return initial;
  let result = initial;
  let reading = false;
  const read = async () => {
    if (reading) return;
    reading = true;
    try {
      const next = await reconcileDelete(tree, new Set(result.deletedIds), result);
      if (!pendingDeleteReads.has(read)) return;
      const changed = JSON.stringify([next.deletedIds, next.remainingIds, next.unconfirmedIds])
        !== JSON.stringify([result.deletedIds, result.remainingIds, result.unconfirmedIds]);
      result = next;
      if (!next.unconfirmedIds?.length) {
        pendingDeleteReads.delete(read);
        if (pendingDeleteReads.size === 0) { stopDeleteStatus?.(); stopDeleteStatus = null; }
      }
      if (changed) onReconciled?.(next);
    } finally { reading = false; }
  };
  pendingDeleteReads.add(read);
  // The shell already shares this poller. A successful status is an opportunity to settle an
  // unavailable/locked read; with no unresolved outcomes there is no subscription or extra read.
  stopDeleteStatus ??= subscribeStatus(snapshot => {
    if (snapshot) for (const retry of pendingDeleteReads) void retry();
  });
  return initial;
}

/** Without a callback, retain the single-delete boundary. With one, confirm the full tree ONCE. */
export async function deleteConversationWithCascade(
  sessionId: string,
  onTreeFound?: (childIds: string[], tree: DeleteTreeEntry[]) => Promise<boolean>,
  onReconciled?: (result: DeleteCascadeResult) => void,
): Promise<DeleteCascadeResult> {
  if (onTreeFound) {
    let tree: DeleteTreeEntry[] = [];
    const confirmed = new Set<string>();
    try {
      const preview = await readDeleteTree(sessionId);
      tree = preview.tree;
      const busy = tree.find(row => row.busy);
      if (busy) return { ok: false, errorCode: 'CONVERSATION_BUSY', failedId: busy.id,
        error: 'Stop the running answer or agent, including any pending decision, before deleting this tree.' };
      if (!await onTreeFound(tree.slice(1).map(row => row.id), tree)) return { ok: false, cancelled: true };
      const current = await readDeleteTree(sessionId);
      if (current.locked) return { ok: false, errorCode: 'STORE_LOCKED', error: 'Unlock chat history before deleting this conversation.' };
      if (current.tree.some(row => row.busy)) return { ok: false, errorCode: 'CONVERSATION_BUSY',
        error: 'Stop the running answer or agent, including any pending decision, before deleting this tree.' };
      const named = new Map(tree.map(row => [row.id, row.parentSessionId]));
      if (current.tree.length !== tree.length || current.tree.some(row => !named.has(row.id) || named.get(row.id) !== row.parentSessionId)) {
        return watchUnconfirmedDelete(tree, await reconcileDelete(tree, confirmed, { ok: false, error: 'The conversation tree changed. Review it again before deleting.' }), onReconciled);
      }
      // Every child precedes its parent, at any depth; stable sort preserves sibling order.
      for (const row of [...tree].sort((a, b) => b.depth - a.depth)) {
        const result = await deleteConversationWithCascade(row.id);
        if (!result.ok) return watchUnconfirmedDelete(tree, await reconcileDelete(tree, confirmed, { ...result, failedId: row.id }), onReconciled);
        confirmed.add(row.id);
      }
      return { ok: true, deletedIds: tree.map(row => row.id), remainingIds: [], unconfirmedIds: [] };
    } catch (failure) {
      const result = { ok: false, error: failure instanceof Error ? failure.message : 'Could not confirm deletion.' };
      const affected = tree.length > 0 ? tree : [{ id: sessionId }];
      const outcome = tree.length > 0 ? await reconcileDelete(tree, confirmed, result) : { ...result, unconfirmedIds: [sessionId] };
      return watchUnconfirmedDelete(affected, outcome, onReconciled);
    }
  }
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
    if (res.ok) { rememberDeletedConversation(sessionId); return { ok: true }; }
    let body: { error?: unknown; errorCode?: string; childSessionIds?: unknown } = {};
    try { body = await res.json() as typeof body; } catch { /* A failed response may have no JSON. */ }
    const childIds = Array.isArray(body.childSessionIds) ? body.childSessionIds.filter((id): id is string => typeof id === 'string') : undefined;
    return { ok: false, ...(typeof body.error === 'string' ? { error: body.error } : {}),
      ...(body.errorCode ? { errorCode: body.errorCode } : {}), ...(childIds ? { childIds } : {}) };
  } catch { return { ok: false, error: 'The delete response was not received.' }; }
}

export function __resetConversationListForTest(): void {
  stopListProfileWatch?.();
  stopListProfileWatch = null;
  listProfileEpoch += 1;
  pendingDeleteReads.clear();
  stopDeleteStatus?.();
  stopDeleteStatus = null;
  state = { conversations: [], deletedIds: [], activeId: null, loading: false };
  listeners.clear();
  serverConversations.clear();
  existenceGeneration = null;
  confirmedExisting.clear();
  existenceReads.clear();
  apiBase = '';
  pendingTitles.clear();
  adoptBlockedIds.clear();
  writtenTitles.clear();
  titleWrites.clear();
  listRequests = 0;
  listShown = 0;
  autoTitleEpoch += 1;
  autoTitlesAsking.clear();
  for (const check of autoTitleResumeChecks.values()) clearTimeout(check);
  autoTitleResumeChecks.clear();
  stopAdoptionWatch?.();
  stopAdoptionWatch = null;
  stopLegacyWatch?.();
  stopLegacyWatch = null;
}
