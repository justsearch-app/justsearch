// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 449 phase 5 — FE client for `/api/registry/surfaces`.
 *
 * Mirrors {@link ./ResourceCatalogClient.ts} and
 * {@link ./DiagnosticChannelCatalogClient.ts} structurally: localStorage-cached
 * body + ETag, conditional GET on subsequent boots, defensive fallback on
 * missing data. Per slice 449 §B.B.G decision (deferred — same rationale as
 * slice 448 §B.B.G): a parallel implementation rather than a generic
 * `CatalogClient<T>` extraction. Three near-identical catalog clients now
 * exist; extraction lands when a fourth or meaningful divergence forces it.
 *
 * Lookup contract:
 *  - `getSurface(id)`: synchronous lookup; `undefined` when the id isn't in
 *    the catalog.
 *  - `listSurfaces()`: snapshot of all entries.
 *  - `listSurfacesByPlacement(placement)`: filtered by chrome zone.
 *  - `onSurfaceCatalogChange(listener)`: invoked after a fresh fetch updates
 *    the in-memory catalog; ALSO the V1.5 hot-reload teardown hook per
 *    slice 449 §6.2 (when 8e Phase 3 ships, surface mutations flow through
 *    this listener).
 */

import type {
  Altitude,
  Audience,
  Placement,
  Surface,
  SurfaceCatalog,
  SurfaceFactory,
  SurfaceRef,
} from '../types/surface.js';
import { surfaceCatalogSchema } from '../types/surface.js';
import type { Provenance } from '../types/registry.js';
import { parseWireContract } from '../schemas.js';

/**
 * Tempdoc 571 — the host-assigned contributor id for the first-party CORE plugin. Provenance is
 * minted by the PluginRegistry at install (548 §4.3); a third-party plugin gets its own id and cannot
 * claim this one, so it is a trustworthy first-party signal even though the FE represents core surfaces
 * as tier TRUSTED_PLUGIN (not literal CORE) while the Java catalog marks them Provenance.core (CORE).
 */
export const CORE_CONTRIBUTOR_ID = 'core';

/**
 * Tempdoc 571 — is this provenance first-party (host/core), as opposed to a third-party plugin? Drives
 * the `TRUST ⟹ CORE` foreclosure at the merge boundary (a plugin cannot forge a trust surface).
 */
export function isFirstPartyProvenance(p: Provenance): boolean {
  return p.tier === 'CORE' || p.contributorId === CORE_CONTRIBUTOR_ID;
}

/**
 * 478 §4.A — module-private brand symbol (TYPE-LEVEL branding).
 * Stamped on every minted factory so the type system can
 * distinguish a SurfaceFactory from `{mount: () => el}`. The
 * symbol value is publicly readable via `surface.factory!.__dispatchBrand`,
 * so it CANNOT be the runtime verification primitive — a consumer
 * could steal it from one factory and forge another. See
 * VALID_FACTORIES below for the runtime-verification primitive.
 */
const DISPATCH_BRAND = Symbol('justsearch.surface-factory.dispatch');

/**
 * 478 §4.A defense-in-depth (post-reviewer-pass): module-private
 * WeakSet of catalog-minted factories. The set is closure-captured
 * inside this module; consumers with only a factory reference
 * cannot reach the set, so they cannot insert a forged factory
 * into the valid-set. `verifyFactoryBrand` checks membership here
 * — stealing `__dispatchBrand` from a real factory and forging a
 * fake factory with the same Symbol fails the WeakSet check
 * because the fake was never added to VALID_FACTORIES.
 *
 * Stolen-brand attack the original Symbol-only check missed:
 *
 *   const stolen = realSurface.factory!.__dispatchBrand;
 *   const forged = { __dispatchBrand: stolen, mount: () => evilEl };
 *   // Old check: passes (Symbol identity matches)
 *   // New check: rejects (forged not in VALID_FACTORIES)
 */
const VALID_FACTORIES = new WeakSet<SurfaceFactory>();

/**
 * 478 §4.A — mint a SurfaceFactory for a tag. Captures the tag
 * in a closure; mount() looks up the registered class via
 * customElements.get and constructs an instance. The HTML spec's
 * custom-element-name validation (PCEN regex) is enforced by
 * customElements.get itself — only names accepted by
 * customElements.define can return a class.
 *
 * Encapsulating the customElements.get + new klass() pattern
 * inside the factory eliminates the consumer's exposure to the
 * tag string and the unsafeStatic dispatch primitive.
 */
function mintFactory(entry: Surface): SurfaceFactory {
  const tag = entry.mountTag;
  const surfaceId = entry.id;
  // Slice 491 F2: surfaces hosting a chat shape (mountTag === 'jf-chat-shape-mount')
  // declare their consumed shape via consumes.conversationShapes. The factory closes
  // over the first shape id (V1: single shape per surface) and stamps it as a
  // `shape-id` attribute on the mounted element. Pre-F2 the Stage.render path set
  // this attribute as a post-mount workaround; moving it here keeps the factory's
  // mint-site as the single point that knows how to produce a fully-wired element,
  // and removes the leaky abstraction from Stage.
  const chatShapeId =
    tag === 'jf-chat-shape-mount' && entry.consumes?.conversationShapes?.length
      ? entry.consumes.conversationShapes[0]
      : undefined;
  const factory: SurfaceFactory = {
    __dispatchBrand: DISPATCH_BRAND,
    mount(opts) {
      const klass = customElements.get(tag);
      if (!klass) {
        throw new Error(
          `SurfaceFactory(${surfaceId}): mountTag '${tag}' is not registered as a custom element. ` +
            `Make sure the surface's element class has been registered before mounting.`,
        );
      }
      const el = new klass();
      const apiBase = opts?.apiBase;
      if (apiBase) {
        el.setAttribute('api-base', apiBase);
      }
      if (chatShapeId) {
        el.setAttribute('shape-id', chatShapeId);
      }
      if (opts?.host_) {
        (el as unknown as Record<string, unknown>).host_ = opts.host_;
      }
      return el;
    },
  };
  // Register the factory in the module-private WeakSet for
  // runtime brand verification. Consumers cannot reach this set
  // even if they steal the __dispatchBrand Symbol off a real
  // factory.
  VALID_FACTORIES.add(factory);
  return factory;
}

/**
 * 478 §4.A — stamp the factory onto each catalog entry. Mutates
 * the entries in place; safe because Surfaces are constructed
 * in this module's `rebuildIndex` / `mergePluginSurfaceContributions`
 * paths.
 */
function stampFactory(entry: Surface): Surface {
  if (entry.factory) return entry;
  return { ...entry, factory: mintFactory(entry) };
}

/**
 * 478 §4.A — runtime brand verification (post-reviewer-pass:
 * WeakSet membership, not Symbol identity).
 *
 * Symbol-identity check (the original implementation) was
 * forgeable by a consumer who could read `factory.__dispatchBrand`
 * off a real factory — that's a public field. WeakSet membership
 * check rejects forgeries because the WeakSet is module-private:
 * a consumer with only a factory reference cannot insert their
 * forged factory into VALID_FACTORIES.
 *
 * The Symbol field stays for TYPE-level branding — consumers
 * can't construct a `{mount: () => el}` and pass it as a
 * SurfaceFactory through the type system. The WeakSet is the
 * additional RUNTIME defense.
 */
function verifyFactoryBrand(factory: SurfaceFactory): void {
  // Cheap path: Symbol mismatch fails fast and gives a clearer
  // error message for accidental forgeries.
  if (factory.__dispatchBrand !== DISPATCH_BRAND) {
    throw new Error(
      'SurfaceCatalogClient.mountSurface: factory __dispatchBrand ' +
        'does not match the catalog brand — this factory was not ' +
        'minted by the catalog.',
    );
  }
  // Defense-in-depth: WeakSet rejects deliberate forgeries that
  // copied __dispatchBrand from a real factory.
  if (!VALID_FACTORIES.has(factory)) {
    throw new Error(
      'SurfaceCatalogClient.mountSurface: factory passed the brand ' +
        'symbol check but is not in VALID_FACTORIES — this factory ' +
        'was likely forged by stealing __dispatchBrand from a real ' +
        'factory. Refusing to mount.',
    );
  }
}

/**
 * 478 §4.A — centralized mount helper. Verifies the factory's
 * brand before invoking mount(). Stage and other consumers use
 * this instead of calling `surface.factory.mount(...)` directly,
 * so the brand check happens at every dispatch site.
 *
 * Returns the mounted HTMLElement, or null if the surface has no
 * factory (legacy Surface entries during V1.5.1 transition).
 */
export function mountSurface(
  surface: Surface,
  opts?: { apiBase?: string; host_?: unknown },
): HTMLElement | null {
  if (!surface.factory) return null;
  verifyFactoryBrand(surface.factory);
  return surface.factory.mount(opts);
}

const STORAGE_KEY_BODY = 'justsearch.surfaceCatalog.body';
const STORAGE_KEY_ETAG = 'justsearch.surfaceCatalog.etag';

let entriesById: Map<string, Surface> = new Map();
let bootAttempted = false;
let listeners: Set<() => void> = new Set();

interface CachedEntry {
  body: SurfaceCatalog;
  etag: string;
}

function loadFromStorage(): CachedEntry | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    const bodyJson = localStorage.getItem(STORAGE_KEY_BODY);
    const etag = localStorage.getItem(STORAGE_KEY_ETAG);
    if (!bodyJson || !etag) return null;
    const parsed = JSON.parse(bodyJson);
    if (
      !parsed ||
      typeof parsed !== 'object' ||
      !Array.isArray((parsed as SurfaceCatalog).entries)
    ) {
      return null;
    }
    return { body: parsed as SurfaceCatalog, etag };
  } catch {
    return null;
  }
}

function saveToStorage(body: SurfaceCatalog, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // best-effort cache
  }
}

function rebuildIndex(catalog: SurfaceCatalog): void {
  const next = new Map<string, Surface>();
  for (const entry of catalog.entries) {
    // 478 §4.A: stamp the factory at catalog mint time. Consumers
    // get a typed `factory: SurfaceFactory` they can call directly;
    // the catalog never exposes the raw mountTag for dispatch.
    next.set(entry.id, stampFactory(entry));
  }
  entriesById = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow listener errors
    }
  }
}

/**
 * Boot-time fetch of the Surface catalog. Call once from app boot
 * (see `i18n.ts`). On subsequent calls, no-op unless the previous fetch
 * failed and the catalog is empty.
 *
 * First-install retry (observations.md fix): when there's no cached
 * catalog and the initial fetch fails (network error, empty body, JSON
 * parse error, non-2xx), we retry with capped exponential backoff so a
 * boot race against a not-yet-ready backend doesn't leave the rail
 * permanently empty until refresh. Returning users with a cached
 * catalog don't retry — the fallback already covers their UX.
 */
const FIRST_INSTALL_RETRY_DELAYS_MS = [500, 1000, 2000, 4000];

export async function bootSurfaceRegistry(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  if (bootAttempted && entriesById.size > 0) {
    return;
  }
  bootAttempted = true;

  const cached = loadFromStorage();
  if (cached) {
    rebuildIndex(cached.body);
  }

  if (!baseUrl) {
    return;
  }

  // First install if no cached catalog AND no entries yet — retry on transient
  // failures so a Vite/loader boot race against the backend doesn't leave the
  // rail empty.
  const isFirstInstall = !cached && entriesById.size === 0;
  const maxAttempts = isFirstInstall ? 1 + FIRST_INSTALL_RETRY_DELAYS_MS.length : 1;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (attempt > 0) {
      const delay = FIRST_INSTALL_RETRY_DELAYS_MS[attempt - 1] ?? 4000;
      await new Promise((r) => setTimeout(r, delay));
    }
    const populated = await tryFetchAndPopulate(baseUrl, fetchImpl, cached?.etag);
    if (populated) {
      return;
    }
    // Returning users (cached catalog already rendered) don't retry — they
    // already have a usable rail; the next user-triggered refresh will re-try.
    if (!isFirstInstall) return;
  }
}

/**
 * Single fetch + populate attempt. Returns true if the catalog now has
 * entries (either freshly fetched or 304-confirmed against the cache),
 * false on any failure path the caller should consider for retry.
 */
async function tryFetchAndPopulate(
  baseUrl: string,
  fetchImpl: typeof fetch,
  etag: string | undefined,
): Promise<boolean> {
  try {
    const headers: Record<string, string> = {};
    if (etag) headers['If-None-Match'] = etag;
    const response = await fetchImpl(`${baseUrl}/api/registry/surfaces`, {
      headers,
    });

    if (response.status === 304) {
      // Cache is current; entriesById was already rebuilt from storage.
      return entriesById.size > 0;
    }

    if (!response.ok) {
      console.debug(
        `[SurfaceCatalogClient] /api/registry/surfaces returned ${response.status}`,
      );
      return false;
    }

    let body: SurfaceCatalog;
    try {
      // Tempdoc 884: validate the raw wire against the GENERATED Zod at the parse boundary
      // (a mismatch logs `[WireContract]` loudly), then assert the tightened FE shape. The generated
      // surfaceWireSchema is the single runtime authority for the Surface wire shape.
      const raw: unknown = await response.json();
      body = parseWireContract(
        surfaceCatalogSchema,
        raw,
        'GET /api/registry/surfaces',
      ) as unknown as SurfaceCatalog;
    } catch (parseErr) {
      console.debug('[SurfaceCatalogClient] response body parse failed', parseErr);
      return false;
    }
    if (!body || !Array.isArray(body.entries)) {
      console.debug(
        '[SurfaceCatalogClient] response missing `entries` array; cached catalog retained',
      );
      return false;
    }
    const newEtag = response.headers.get('ETag') ?? '';
    if (newEtag) saveToStorage(body, newEtag);
    rebuildIndex(body);
    return entriesById.size > 0;
  } catch (err) {
    console.debug(
      '[SurfaceCatalogClient] /api/registry/surfaces fetch failed',
      err,
    );
    return false;
  }
}

/** Synchronous lookup. `undefined` when the id isn't in the catalog. */
export function getSurface(id: SurfaceRef): Surface | undefined {
  return entriesById.get(id);
}

/** Snapshot of all entries. Read-only — mutations don't propagate back. */
export function listSurfaces(): Surface[] {
  return Array.from(entriesById.values());
}

/** Filtered by chrome zone — used by per-zone dispatchers. */
export function listSurfacesByPlacement(placement: Placement): Surface[] {
  return listSurfaces().filter((s) => s.placement === placement);
}

/**
 * Subscribe to catalog changes. The listener fires after a successful fetch
 * updates the in-memory catalog. Returns an unsubscribe function.
 *
 * Per slice 449 §6.2: this is the V1.5 hot-reload teardown hook. When 8e
 * Phase 3 ships its `capability-registered { type: "surface" }` event, the
 * `SurfaceCatalogClient` will mutate its in-memory map on receipt and fire
 * these listeners; chrome dispatchers re-filter and unmount the affected
 * surfaces. The listener pattern is independent of the wire format —
 * works against either 8e's channel or any equivalent change-broadcast
 * mechanism.
 */
export function onSurfaceCatalogChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Slice 471 / 465 — merge plugin-contributed surfaces into the live
 * catalog. Each entry's `effectiveAudience` (computed by
 * `PluginRegistry.surfaceContributions()` per slice 449 phase 12)
 * becomes the merged surface's audience. Existing entries with the
 * same id are replaced (the plugin contribution wins for THIS
 * specific id; user-level surfaceOverride per slice 471 picks among
 * available surfaces by id at dispatch time).
 *
 * Listeners fire after the merge; consumers like the chrome's
 * `<jf-shell>` re-filter via the existing
 * {@link onSurfaceCatalogChange} subscriber pattern.
 *
 * V1.5 alpha shape: the host calls this after a successful
 * `PluginRegistry.install()` (or `loadPluginFromUrl` resolution).
 * V1.5.1 polish wires it automatically into the loader's success
 * path so plugin authors don't have to call it explicitly.
 */
export function mergePluginSurfaceContributions(
  entries: ReadonlyArray<{
    pluginId: string;
    contribution: {
      id: string;
      mountTag: string;
      labelKey: string;
      descriptionKey: string;
      audience: Audience;
      placement: Placement;
      // Tempdoc 571 — declared altitude (clamped to PRODUCT for non-CORE TRUST claims below).
      altitude?: Altitude;
      // Tempdoc 571 §11 / 578 — declared host/member composition (member surface ids). Single-authority
      // (the Java CoreSurfaceCatalog wire); a CORE contribution omits it and the merge preserves the
      // existing wire entry's members rather than clobbering them.
      members?: string[];
      consumes?: {
        operations?: string[];
        resources?: string[];
        prompts?: string[];
        diagnosticChannels?: string[];
        // Tempdoc 560 §28.G — the hosted ConversationShape(s); mintFactory stamps shape-id from this.
        conversationShapes?: string[];
      };
      // Tempdoc 521 §22 Phase D — declarative split pairing.
      splitPairing?: { secondary: string };
    };
    effectiveAudience: Audience;
    // 548 §4.3 — the uniform Provenance minted once at the PluginRegistry
    // install site (multi-axis: identity/capability/installedAt). Stored
    // verbatim; the catalog no longer reconstructs a lossy {tier, contributorId,
    // version} partial here (which dropped identity/capability and hardcoded
    // version '0.0.0'). One authority, projected — not re-derived per merge site.
    provenance: Provenance;
  }>,
): void {
  const next = new Map(entriesById);
  for (const entry of entries) {
    const c = entry.contribution;
    // 478 §4.A: stamp factory on plugin-contributed surfaces too.
    // Same mint-site as core catalog entries — consumers don't
    // distinguish core vs plugin via dispatch primitive.
    // Tempdoc 571 — altitude is single-authority: the Java CoreSurfaceCatalog (served on the wire,
    // gate-checked). A core contribution OMITS altitude, so the merge PRESERVES the existing wire
    // entry's altitude rather than clobbering it with PRODUCT — `entriesById` was populated by
    // bootSurfaceRegistry from /api/registry/surfaces BEFORE this merge runs (main.jsx). Only a plugin
    // that genuinely declares its own altitude overrides it.
    const existing = entriesById.get(c.id);
    const declaredAltitude = c.altitude ?? existing?.altitude ?? 'PRODUCT';
    // TRUST and DIAGNOSTIC altitude are first-party-only. Clamp a genuinely third-party plugin's TRUST
    // OR DIAGNOSTIC claim to PRODUCT — the runtime completion of the gate's `TRUST ⟹ CORE` and
    // `DIAGNOSTIC ⟹ CORE` foreclosures (tempdoc 571 §4d): a plugin cannot forge a trust surface, and a
    // diagnostic surface is plugin-ineligible until 560 §4a (backend capability-attenuation) ships —
    // streaming the raw engine-log to attenuated plugin code needs an attenuation substrate that does not
    // yet exist, so a "no for now" enforced here, not a per-surface judgment. (This forecloses a plugin
    // claiming diagnostic ALTITUDE / Diagnostics-band homing; the deeper channel-DATA attenuation is the
    // 560 §4a concern.) isFirstPartyProvenance treats both Java's Provenance.core (CORE) and the FE's
    // core-plugin (TRUSTED_PLUGIN + contributorId 'core') as first-party.
    const altitude =
      (declaredAltitude === 'TRUST' || declaredAltitude === 'DIAGNOSTIC') &&
      !isFirstPartyProvenance(entry.provenance)
        ? 'PRODUCT'
        : declaredAltitude;
    const surface: Surface = {
      id: c.id,
      presentation: {
        labelKey: c.labelKey,
        descriptionKey: c.descriptionKey,
      },
      audience: entry.effectiveAudience,
      placement: c.placement,
      altitude,
      consumes: {
        resources: c.consumes?.resources ?? [],
        operations: c.consumes?.operations ?? [],
        prompts: c.consumes?.prompts ?? [],
        diagnosticChannels: c.consumes?.diagnosticChannels ?? [],
        // Tempdoc 560 §28.G — preserve the hosted ConversationShape(s) so mintFactory can derive shape-id.
        conversationShapes: c.consumes?.conversationShapes ?? [],
      },
      mountTag: c.mountTag,
      provenance: entry.provenance,
      // Tempdoc 571 §11 / 578 — preserve the wire's declared members (single home-authority).
      members: c.members ?? existing?.members ?? [],
      ...(c.splitPairing !== undefined ? { splitPairing: c.splitPairing } : {}),
    };
    next.set(c.id, stampFactory(surface));
  }
  entriesById = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow listener errors
    }
  }
}

/**
 * Slice 469 / 471 — remove a plugin's contributed surfaces. Called
 * during plugin uninstall so the rail unmounts the plugin's
 * surfaces. Filter is by `pluginId === provenance.contributorId`.
 */
export function removePluginSurfaceContributions(pluginId: string): void {
  const next = new Map(entriesById);
  let changed = false;
  for (const [id, surface] of entriesById) {
    if (surface.provenance.contributorId === pluginId) {
      next.delete(id);
      changed = true;
    }
  }
  if (!changed) return;
  entriesById = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow listener errors
    }
  }
}

/** Test-only: reset module state. */
export function __resetForTest(): void {
  entriesById = new Map();
  bootAttempted = false;
  listeners = new Set();
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(STORAGE_KEY_BODY);
      localStorage.removeItem(STORAGE_KEY_ETAG);
    }
  } catch {
    // ignore
  }
}

/** Test-only: seed the catalog directly without an HTTP call. */
export function __seedForTest(catalog: SurfaceCatalog): void {
  rebuildIndex(catalog);
  bootAttempted = true;
}
