// SPDX-License-Identifier: Apache-2.0
/**
 * Router bootstrap — slice 489 §6 / §7.
 *
 * One-time setup the chrome calls during boot:
 *   1. Register the concrete FE stores as `StoreAdapter`s (the abstract
 *      storeId → store mapping the schemas reference).
 *   2. Fetch `GET /api/registry/surfaces` and register each surface's
 *      `stateSchema` (if present) in the local schema cache.
 *
 * After bootstrap completes:
 *   - URLSource (slice 492) can parse `?...` query params from the hash
 *     and dispatch Navigation Intents that the NavigationHandler realizes
 *     against the registered surfaces' schemas.
 *   - URLProjector can subscribe to the relevant stores on activation.
 *
 * The two-phase split (register stores first, then fetch schemas) means
 * schemas can reference stores added later (forward-compat) and the local
 * fallback in `surfaceSchemas.resolveSurfaceStateSchema` (drop bindings to
 * unknown stores) handles gaps gracefully.
 */

import {
  serializeSearch,
  restoreSearch,
  subscribeSearch,
} from '../state/searchState.js';
import {
  serializeSearchFilters,
  restoreSearchFilters,
  subscribeFilters,
} from '../state/searchFiltersState.js';
import {
  serializeAskChat,
  restoreAskChat,
  subscribeAskChat,
} from '../state/askChatState.js';
import {
  serializeAgentChat,
  restoreAgentChat,
  subscribeAgentChat,
} from '../state/agentChatState.js';
import {
  getConversationListState,
  restoreActiveConversation,
  subscribeConversationList,
} from '../state/conversationListStore.js';
import { projectAsNavigation } from './URLProjector.js';
import { registerStore, type StoreAdapter } from './storeRegistry.js';
import {
  registerSurfaceStateSchema,
  type RawSurfaceStateSchema,
} from './surfaceSchemas.js';
import type { StateSnapshot } from './types.js';
import { authorizedFetch } from '../api/authorizedFetch.js';

interface SurfaceCatalogEnvelope {
  entries?: Array<{
    id?: string;
    stateSchema?: {
      schema?: string;
      bindings?: Array<{
        schemaPath?: string;
        storeId?: string;
        storeKey?: string;
      }>;
    };
  }>;
}

let storesRegistered = false;
let schemasFetched = false;

/** Register the v1 set of concrete stores. Idempotent. */
export function registerCoreStores(): void {
  if (storesRegistered) return;
  storesRegistered = true;
  registerStore(buildSearchAdapter());
  registerStore(buildSearchFiltersAdapter());
  registerStore(buildAskChatAdapter());
  registerStore(buildAgentChatAdapter());
  registerStore(buildSv3ConversationAdapter());
  registerFrontendSurfaceSchemas();
}

/**
 * Conversation projection fallback when the Engine catalog is unavailable. Registered before the
 * fetch, whose authoritative Search schema replaces it. WP1.5a keeps the same sv3.conversation
 * binding in Java: hand-off state is consumed once by NavigationHandler and never projected.
 */
export function registerFrontendSurfaceSchemas(): void {
  registerSurfaceStateSchema(SV3_SURFACE_ID, {
    schema: SV3_STATE_SCHEMA,
    bindings: [
      {
        schemaPath: '/conversationId',
        storeId: SV3_CONVERSATION_STORE_ID,
        storeKey: 'conversationId',
      },
    ],
  });
}

/** The canonical Search window, also declared in CoreSurfaceCatalog. */
const SV3_SURFACE_ID = 'core.search-v3-surface';

/** Abstract storeId the sv3 schema binds — resolved through {@link registerStore} like any other. */
const SV3_CONVERSATION_STORE_ID = 'sv3.conversation';

/**
 * The hash format, and the whole of it (tempdoc 864 PR C):
 *
 *   `#justsearch://surface/core.search-v3-surface?conversationId=<id>`
 *
 * with the argument ABSENT — not empty, not a sentinel — when no conversation is claimed (the hero).
 * `collectState` drops null/undefined, so "no conversation" is the bare surface address, and an
 * absent id restores nothing rather than restoring a conversation named "".
 */
const SV3_STATE_SCHEMA =
  '{"type":"object","properties":{"conversationId":{"type":"string"}}}';

/**
 * Fetch the surface catalog and register every entry that declares a
 * `stateSchema`. Surfaces without a schema are skipped (per §17.2 optional).
 */
export async function fetchAndRegisterSurfaceSchemas(apiBase: string): Promise<void> {
  if (schemasFetched) return;
  const url = (apiBase || '') + '/api/registry/surfaces';
  let envelope: SurfaceCatalogEnvelope;
  try {
    const res = await authorizedFetch(url);
    if (!res.ok) {
      console.warn(
        `[router/bootstrap] GET ${url} returned ${res.status}; surface schemas unavailable`,
      );
      return;
    }
    envelope = (await res.json()) as SurfaceCatalogEnvelope;
  } catch (err) {
    console.warn(`[router/bootstrap] GET ${url} failed:`, err);
    return;
  }
  schemasFetched = true;
  const entries = envelope.entries ?? [];
  for (const entry of entries) {
    if (!entry.id || !entry.stateSchema) continue;
    const raw = normalizeRawSchema(entry.stateSchema);
    if (raw) registerSurfaceStateSchema(entry.id, raw);
  }
}

/** Test-only: reset both stages so vitest can re-run bootstrap cleanly. */
export function __resetBootstrapForTest(): void {
  storesRegistered = false;
  schemasFetched = false;
}

// ----- adapters -----

function buildSearchAdapter(): StoreAdapter {
  return {
    storeId: 'search',
    serialize(): StateSnapshot {
      const slice = serializeSearch();
      const out: StateSnapshot = {};
      if (slice.query !== undefined) out.query = slice.query;
      return out;
    },
    restore(snapshot: StateSnapshot): void {
      restoreSearch({ query: snapshot.query as string | string[] | undefined });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeSearch((s) => {
        listener(s.query ? { query: s.query } : {});
      });
    },
  };
}

function buildSearchFiltersAdapter(): StoreAdapter {
  return {
    storeId: 'search.filters',
    serialize(): StateSnapshot {
      const slice = serializeSearchFilters();
      const out: StateSnapshot = {};
      if (slice.modifiedFromMs !== undefined) out.modifiedFromMs = slice.modifiedFromMs;
      if (slice.modifiedToMs !== undefined) out.modifiedToMs = slice.modifiedToMs;
      return out;
    },
    restore(snapshot: StateSnapshot): void {
      restoreSearchFilters({
        modifiedFromMs: snapshot.modifiedFromMs as string | string[] | undefined,
        modifiedToMs: snapshot.modifiedToMs as string | string[] | undefined,
      });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeFilters((s) => {
        const out: StateSnapshot = {};
        if (typeof s.modifiedFromMs === 'number') {
          out.modifiedFromMs = String(s.modifiedFromMs);
        }
        if (typeof s.modifiedToMs === 'number') {
          out.modifiedToMs = String(s.modifiedToMs);
        }
        listener(out);
      });
    },
  };
}

function buildAgentChatAdapter(): StoreAdapter {
  return {
    storeId: 'agent',
    serialize(): StateSnapshot {
      return serializeAgentChat();
    },
    restore(snapshot: StateSnapshot): void {
      restoreAgentChat({
        initialMessage: snapshot.initialMessage as string | string[] | undefined,
        sessionId: snapshot.sessionId as string | string[] | undefined,
      });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeAgentChat((s) => {
        const out: StateSnapshot = {};
        if (s.initialMessage) out.initialMessage = s.initialMessage;
        if (s.sessionId) out.sessionId = s.sessionId;
        listener(out);
      });
    },
  };
}

function buildAskChatAdapter(): StoreAdapter {
  return {
    storeId: 'ask',
    serialize(): StateSnapshot {
      const s = serializeAskChat();
      const out: StateSnapshot = {};
      if (s.query) out.query = s.query;
      if (s.docIds && s.docIds.length > 0) out.docIds = s.docIds;
      return out;
    },
    restore(snapshot: StateSnapshot): void {
      restoreAskChat({
        query: snapshot.query as string | string[] | undefined,
        docIds: snapshot.docIds as string | string[] | undefined,
      });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeAskChat((s) => {
        const out: StateSnapshot = {};
        if (s.query) out.query = s.query;
        if (s.docIds.length > 0) out.docIds = s.docIds;
        listener(out);
      });
    },
  };
}

/**
 * Search v3's conversation identity, as a URL-projected store (tempdoc 864 Layer 3(a)).
 *
 * THE CLAIM IS THE ADDRESS. `conversationListStore.activeId` is what the window is reading, and
 * before this it lived only in module state plus a per-tab pointer — invisible to the router, so a
 * swap moved no hash, left no history entry, and was recoverable only by reload (§2.7a). Binding it
 * here makes the reader's position part of the address: it survives a reload, it can be linked, and
 * `restore` is what a Back press runs.
 *
 * Two details are load-bearing rather than defensive:
 *
 *  - **The activeId dedup.** The store emits on every list refresh (a completed ask re-lists), and
 *    an undeduped adapter would report those as state changes. Harmless under replaceState; under
 *    the navigation projection below it would stack a history entry per re-list.
 *  - **`claim` vs `restore`.** Only a claim is a navigation. A restore is the URL, a popstate or the
 *    reload pointer replaying a position the reader already has — see {@link ConversationListChange}.
 *
 * HONEST LIMIT: `activeId` is APP-WIDE, and this projects whoever writes it while sv3 is the active
 * surface. Only the surface on screen can claim in practice (the other window that claims the same
 * pointer is a different surface, and two surfaces are not active at once), so the projection and
 * what sv3 shows agree — but a future second claimer active at the same time would move the URL
 * without moving the window. The fix then is a window-scoped slice, not a special case here.
 */
function buildSv3ConversationAdapter(): StoreAdapter {
  return {
    storeId: SV3_CONVERSATION_STORE_ID,
    // The hero is the bare surface address, so an absent id on a Back/Forward means "nothing is
    // claimed" and has to be applied as such.
    clearsOnTraversal: true,
    serialize(): StateSnapshot {
      const id = getConversationListState().activeId;
      return id === null ? {} : { conversationId: id };
    },
    restore(snapshot: StateSnapshot): void {
      const raw = snapshot.conversationId;
      const id = Array.isArray(raw) ? raw[0] : raw;
      restoreActiveConversation(typeof id === 'string' && id !== '' ? id : null);
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      let last = getConversationListState().activeId;
      return subscribeConversationList((s, change) => {
        if (s.activeId === last) return;
        last = s.activeId;
        const bag: StateSnapshot = s.activeId === null ? {} : { conversationId: s.activeId };
        if (change === 'claim') {
          projectAsNavigation(() => listener(bag));
          return;
        }
        listener(bag);
      });
    },
  };
}

function normalizeRawSchema(
  s: {
    schema?: string;
    bindings?: Array<{ schemaPath?: string; storeId?: string; storeKey?: string }>;
  },
): RawSurfaceStateSchema | null {
  if (!s.schema) return null;
  const bindings: RawSurfaceStateSchema['bindings'] = [];
  for (const b of s.bindings ?? []) {
    if (typeof b.schemaPath === 'string' && typeof b.storeId === 'string') {
      bindings.push({
        schemaPath: b.schemaPath,
        storeId: b.storeId,
        storeKey: b.storeKey ?? '',
      });
    }
  }
  return { schema: s.schema, bindings };
}
