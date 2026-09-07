// SPDX-License-Identifier: Apache-2.0
/**
 * conversationListStore — lightweight conversation management (tempdoc 510 Design D).
 *
 * Lists, resumes, titles, and exports conversations. Separate from AiStateStore
 * because conversations are a content domain, not an AI state concern.
 */

import { authorizedFetch } from '../api/authorizedFetch.js';

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
  activeId: string | null;
  loading: boolean;
}

let state: ConversationListState = {
  conversations: [],
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
  try {
    const titles = loadTitles();
    if (!(id in titles)) return;
    delete titles[id];
    if (Object.keys(titles).length === 0) localStorage.removeItem(TITLES_KEY);
    else localStorage.setItem(TITLES_KEY, JSON.stringify(titles));
  } catch { /* */ }
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

export async function loadConversations(): Promise<void> {
  state = { ...state, loading: true };
  emit();
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/conversations?limit=20`);
    if (!res.ok) { state = { ...state, loading: false }; emit(); return; }
    const data = await res.json();
    // The backend is the authority for a name now; the legacy browser map is only consulted for rows
    // the backend has no title for, and each such entry is written through and erased below.
    const legacy = loadTitles();
    const conversations: Conversation[] = (data.sessions ?? []).map(
      (s: Record<string, unknown>) => ({
        id: s.sessionId as string,
        title:
          typeof s.title === 'string' && s.title !== ''
            ? (s.title as string)
            : (legacy[s.sessionId as string] ?? null),
        titleSource: readTitleSource(s),
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
      }),
    );
    state = { ...state, conversations, loading: false };
    emit();
    // Both write-throughs the list makes possible, fired AFTER the list has rendered so neither one
    // delays it: the names the backend refused with a 404 while the conversation was still being
    // created, and the legacy browser-local names this migration is retiring.
    void flushPendingTitles(conversations);
    void adoptLegacyTitles(conversations, legacy);
  } catch {
    state = { ...state, loading: false };
    emit();
  }
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
    const outcome = await postTitle(id, held.title, held.source);
    if (outcome.ok) applyTitle(id, held.title, held.source);
  }
}

/**
 * Tempdoc 838 migration — adopt on list, once, and only on a confirmed write.
 *
 * For every listed row the backend has no name for while the legacy map does: the reader keeps
 * seeing the name they know (it was already merged in above), the backend is told, and the local
 * entry is deleted ONLY on a 2xx. A locked store answers 423, the entry survives, and the next
 * unlocked list adopts it — so the migration cannot lose a name to a lock, and it cannot leave one
 * behind either. It is complete when `jf-conversation-titles` is gone.
 */
async function adoptLegacyTitles(
  conversations: readonly Conversation[],
  legacy: Record<string, string>,
): Promise<void> {
  if (Object.keys(legacy).length === 0) return;
  for (const c of conversations) {
    const inherited = legacy[c.id];
    if (inherited === undefined || adoptBlockedIds.has(c.id)) continue;
    // Only rows the BACKEND cannot name: a backend title always wins, and its presence means this
    // entry is stale rather than unadopted.
    if (c.titleSource !== null) {
      forgetLegacyTitle(c.id);
      continue;
    }
    // Adopted as the READER's: an existing name is one somebody chose to keep, and treating it as
    // auto-generated would license the next ask to overwrite it.
    const outcome = await postTitle(c.id, inherited, 'user');
    if (outcome.ok) forgetLegacyTitle(c.id);
    else if (outcome.status === 423) adoptBlockedIds.add(c.id);
  }
}

export function setActiveConversation(id: string | null): void {
  state = { ...state, activeId: id };
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
  state = { ...state, activeId: id };
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
 * is a pure read-side projection over the already-loaded conversation list —
 * no new endpoint (the siblings are discoverable from listSessions' parent
 * pointers).
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

/** The one write. `status` is 0 when the request never reached the Head. */
async function postTitle(
  id: string,
  title: string,
  source: ConversationTitleSource,
): Promise<{ ok: boolean; status: number }> {
  try {
    const res = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(id)}/title`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, source }),
      },
    );
    return { ok: res.ok, status: res.status };
  } catch {
    return { ok: false, status: 0 };
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
  const outcome = await postTitle(id, title, source);
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

/**
 * Generate and persist an auto-title for the given conversation. Dispatches a
 * one-off FreeChat with a throwaway sessionId, reads the streamed reply, and
 * then deletes the throwaway session to avoid polluting persisted state.
 * Returns the generated title on success or null on failure. Best-effort —
 * never throws.
 */
export async function generateConversationTitle(
  sessionId: string,
  userMsg: string,
  aiMsg: string,
): Promise<string | null> {
  if (!userMsg || !aiMsg) return null;
  // Slice 516 FIX-T4 — prefix MUST match the Java constant
  // ConversationStore.THROWAWAY_SESSION_PREFIX
  // (modules/app-agent-api/.../conversation/ConversationStore.java).
  // FileConversationStore.deleteSession bypasses the branches-prevent-
  // deletion check for sessions starting with this prefix.
  // 804 B3: the dispatch POST goes through authorizedFetch (token-enforced in the
  // packaged app). The hand-rolled reader loop stays: consumeShapeStream would
  // change this best-effort path's failure mode (it throws) and would book a
  // throwaway title generation against the live-channel budget (662).
  const throwawayId = `_title_${crypto.randomUUID()}`;
  const prompt = `Generate a concise 3-5 word title for this conversation. Reply with only the title, nothing else.\n\nUser: ${userMsg.slice(0, 200)}\nAssistant: ${aiMsg.slice(0, 200)}`;
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/dispatch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // 835 §10f: title generation is plumbing, not an answer. The server-wide reasoning budget is
      // on by default, so without this a 3-5 word title costs a full reasoning pass.
      body: JSON.stringify({
        shapeId: 'core.free-chat',
        prompt,
        sessionId: throwawayId,
        enableThinking: false,
      }),
    });
    if (!res.ok || !res.body) {
      void deleteThrowaway(throwawayId);
      return null;
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let title = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = decoder.decode(value, { stream: true });
      for (const line of chunk.split('\n')) {
        if (line.startsWith('data: ')) {
          try {
            const data = JSON.parse(line.slice(6));
            if (typeof data.text === 'string') title += data.text;
          } catch { /* */ }
        }
      }
    }
    void deleteThrowaway(throwawayId);
    title = title.trim().replace(/^["']|["']$/g, '');
    if (title.length === 0 || title.length >= 80) return null;
    // Tempdoc 838 — awaited, and reported honestly: a name the store refused is not a name, and the
    // caller decides what to do about that. `auto` is the provenance, which is what keeps the next
    // reader rename from being treated as something the model may overwrite later.
    const written = await setConversationTitle(sessionId, title, 'auto');
    return written.ok ? title : null;
  } catch {
    void deleteThrowaway(throwawayId);
    return null;
  }
}

async function deleteThrowaway(sessionId: string): Promise<void> {
  try {
    await authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
    });
  } catch { /* best-effort cleanup */ }
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
   * thing. Honest limit: the injector writes it to the dispatch context, and no producer persists
   * it onto the store record today — so this reads a field that is currently always absent
   * rather than inventing one. It is read defensively (nested `attributes`, both spellings) so
   * the transparency line appears the moment a producer does start carrying it, instead of the
   * record and the reader having to be changed together.
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
    if (typeof v === 'string' && v.trim().length > 0) return v;
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
    if (!res.ok) return { sessionId, shapeId, messages: [] };
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
    return { sessionId, shapeId, messages: [] };
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
    state = {
      ...state,
      conversations: state.conversations.filter((c) => c.id !== sessionId),
      activeId: state.activeId === sessionId ? null : state.activeId,
    };
    emit();
    return true;
  } catch {
    return false;
  }
}

/**
 * Slice 517 FIX-U1 — delete with cascade support for branched conversations.
 *
 * If the backend rejects with HTTP 409 + BRANCHES_PREVENT_DELETION (the
 * slice 515 + 516 guarantee that orphaned branches can't be silently
 * created), this function:
 *  1. extracts the {@code childSessionIds} from the response body,
 *  2. invokes {@code onChildsFound(childIds)} so the caller can prompt the
 *     user "delete the children too?" (returns a {@code Promise<boolean>}),
 *  3. if the user confirms, deletes the children sequentially then retries
 *     the parent.
 *
 * Sequential (not Promise.all) — simpler error semantics, cascade depth is
 * typically 1 today, cost is bounded by N branches.
 *
 * The original {@link deleteConversation} signature is preserved for
 * backward compatibility; callers that don't need cascade UX keep using it.
 */
export interface DeleteCascadeResult {
  ok: boolean;
  childIds?: string[];
}

export async function deleteConversationWithCascade(
  sessionId: string,
  onChildsFound?: (childIds: string[]) => Promise<boolean>,
): Promise<DeleteCascadeResult> {
  try {
    const res = await authorizedFetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
    });
    if (res.ok) {
      forgetRecentSession(sessionId);
      state = {
        ...state,
        conversations: state.conversations.filter((c) => c.id !== sessionId),
        activeId: state.activeId === sessionId ? null : state.activeId,
      };
      emit();
      return { ok: true };
    }
    if (res.status === 409) {
      let childIds: string[] = [];
      try {
        const body = (await res.json()) as { childSessionIds?: string[] };
        if (Array.isArray(body.childSessionIds)) childIds = body.childSessionIds;
      } catch {
        // best-effort body parse; if it fails we still surface the 409
      }
      if (!onChildsFound) return { ok: false, childIds };
      const userConfirmed = await onChildsFound(childIds);
      if (!userConfirmed) return { ok: false, childIds };
      // Sequential cascade delete: children first, then parent. If any
      // child delete fails, abort the cascade and leave the parent intact.
      for (const childId of childIds) {
        const childResult = await deleteConversationWithCascade(childId);
        if (!childResult.ok) return { ok: false, childIds };
      }
      // Retry parent now that branches are gone.
      return deleteConversationWithCascade(sessionId);
    }
    return { ok: false };
  } catch {
    return { ok: false };
  }
}

export function exportConversationMarkdown(
  thread: Array<{ role: string; content: string }>,
  title?: string | null,
): string {
  const lines: string[] = [];
  if (title) lines.push(`# ${title}`, '');
  for (const m of thread) {
    lines.push(`**${m.role === 'user' ? 'User' : 'Assistant'}**: ${m.content}`, '');
  }
  return lines.join('\n');
}

export function __resetConversationListForTest(): void {
  state = { conversations: [], activeId: null, loading: false };
  listeners.clear();
  apiBase = '';
  pendingTitles.clear();
  adoptBlockedIds.clear();
}
