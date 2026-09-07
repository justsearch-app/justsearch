// SPDX-License-Identifier: Apache-2.0
/**
 * present() — the single display projector (tempdoc 557 §2.A).
 *
 * ONE authority that turns any entity reference into a human-facing
 * presentation (label + optional icon/description), resolving at call/render
 * time against the live catalogs. The previously-scattered resolvers
 * (operation label, surface title, effect description, i18n key) are folded in
 * here as private cases, so a consumer renders `present(ref).label` — never a
 * raw id/key/route. The render boundary takes the branded `DisplayLabel`, so a
 * raw string can't be passed where a presented label is expected (§5: tier-2 at
 * the seam; the html`` template itself is guarded by ESLint, since Lit slots
 * aren't type-checked — the presentation-purity gate was retired in 930 chunk H).
 */
import { getOperation } from '../../api/registry/OperationCatalogClient.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { getStatusBarItem } from '../commands/StatusBarRegistry.js';
import { localizeResourceKey } from '../../i18n/resourceCatalog.js';
import { deriveTitleFromSurfaceId } from '../utils/deriveRichLabel.js';
import { describeEffect } from '../substrates/effects/describe.js';
import type { Effect } from '../substrates/effect.js';

/** A string that has been through the display projector. */
export type DisplayLabel = string & { readonly __display: unique symbol };

export type EntityRef =
  | { readonly kind: 'operation'; readonly id: string }
  | { readonly kind: 'surface'; readonly id: string }
  | { readonly kind: 'effect'; readonly effect: Effect }
  | { readonly kind: 'resource'; readonly key: string }
  /**
   * A health/recovery condition id (e.g. `schema.reindex-required`), optionally with the
   * condition's REASON code (`AssertedCondition.reason`, e.g. `SchemaMismatch`).
   *
   * Tempdoc 941 — the reason is what selects the authored per-reason sentence
   * (`health-events.<id>.reason.<ReasonCode>.message`). See the `condition` case below.
   */
  | { readonly kind: 'condition'; readonly id: string; readonly reason?: string }
  /** A navigation target (surface id or `justsearch://surface/<id>` route). */
  | { readonly kind: 'route'; readonly target: string }
  /**
   * Tempdoc 559 Authority V — a status-bar metric id (e.g. `core.files`). Its
   * accessible NAME is the declared `StatusBarItem.accessibleLabel`, so a
   * metric's name projects from the registry through this one authority instead
   * of being hand-stamped inline in StatusDeck. The live VALUE stays the
   * renderer's; this resolves only the name.
   */
  | { readonly kind: 'metric'; readonly id: string }
  /**
   * Tempdoc 565 §26.C — a workflow id + its i18n `labelKey` (the run-window picker projects the
   * `/api/registry/workflows` catalog through this one display authority instead of humanizing inline).
   * The localized label wins; the humanized id is the fallback (mirrors the operation case).
   */
  | { readonly kind: 'workflow'; readonly id: string; readonly labelKey: string };

export interface Presented {
  readonly label: DisplayLabel;
  readonly icon?: string;
  readonly description?: string;
}

const brand = (s: string): DisplayLabel => s as DisplayLabel;

/** Title-case the last dotted segment of an id (the humanized fallback). */
function humanizeId(id: string): string {
  const last = id.split('.').pop() ?? '';
  // Tempdoc 577 §2.12 Move 4 — also split underscores so an AGENT WIRE NAME (`core_search_index`,
  // the snake_case tool id the ledger stamps) humanizes to "Search Index" rather than the raw
  // "Core_search_index" the History tab showed (§2.11 #5); drop a leading `core`/`vop` wire prefix.
  const segs = last
    .replace(/-surface$/, '')
    .split(/[-_]/)
    .filter((s) => s.length > 0);
  const trimmed =
    segs.length > 1 && (segs[0] === 'core' || segs[0] === 'vop') ? segs.slice(1) : segs;
  return trimmed.map((s) => s.charAt(0).toUpperCase() + s.slice(1)).join(' ');
}

/**
 * Humanize a full dotted/dashed id, keeping the namespace for context
 * (e.g. `schema.reindex-required` → "Schema Reindex Required"). Used for
 * condition ids, which carry no i18n catalog entry.
 */
function humanizeFullId(id: string): string {
  return id
    .split(/[.\-]/)
    .filter((s) => s.length > 0)
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join(' ');
}

/**
 * Tempdoc 941 — resolve a `health-events.*` catalog key, or `null` when the catalog has no
 * entry for it.
 *
 * `localizeResourceKey` echoes the RAW KEY back on a miss (its documented defensive contract),
 * so "resolved" has to be tested as "came back different from what went in". Every caller here
 * needs that same test, so it lives once.
 */
function healthEventCopy(key: string): string | null {
  const resolved = localizeResourceKey(key);
  return resolved.length > 0 && resolved !== key ? resolved : null;
}

/** Extract a surface id from a `justsearch://surface/<id>` route or a bare id. */
function routeToSurfaceId(target: string): string {
  const m = target.match(/surface\/([^?#]+)/);
  return m ? m[1]! : target;
}

// Stable ids can outlive their product names. These exceptions belong inside the one display
// projector so a missing message catalog cannot resurrect a superseded label. Most surfaces still
// use the mechanical id fallback below; Search is the sole compatibility-id exception today.
const SURFACE_FALLBACK_LABELS: Readonly<Record<string, string>> = {
  'core.unified-chat-surface': 'Search',
};

/**
 * Surface label authority: the catalog's localized `presentation.labelKey`
 * wins when resolvable; otherwise the id-derived title. The `registry-surface.*`
 * messages are loaded at boot by `bootSurfaceCatalog` (i18n.ts), so the catalog
 * branch is live in production — a surface with a backing message resolves to it.
 * deriveTitleFromSurfaceId is the fallback only for surfaces with no catalog
 * entry (e.g. token-editor-surface, command-palette).
 */
function surfaceLabel(id: string): string {
  const surface = getSurface(id);
  const labelKey = surface?.presentation.labelKey ?? '';
  if (labelKey) {
    const localized = localizeResourceKey(labelKey);
    if (localized.length > 0 && localized !== labelKey) return localized;
  }
  return SURFACE_FALLBACK_LABELS[id] ?? (deriveTitleFromSurfaceId(id) || id);
}

export function present(ref: EntityRef): Presented {
  switch (ref.kind) {
    case 'operation': {
      const op = getOperation(ref.id);
      if (op?.presentation?.labelKey) {
        const resolved = localizeResourceKey(op.presentation.labelKey);
        if (resolved !== op.presentation.labelKey) {
          return { label: brand(resolved) };
        }
      }
      return { label: brand(humanizeId(ref.id)) };
    }
    case 'surface':
      return { label: brand(surfaceLabel(ref.id)) };
    case 'effect': {
      // 557 Q7 — describeEffect is pure (it must not consult catalogs), so it
      // labels a navigate with its raw route. Humanize the route HERE, in the
      // one display projector, via the same surface-label authority present's
      // `route` kind uses (yields "Search"/"Chat", not the raw justsearch:// id).
      const eff = ref.effect;
      if (eff.kind === 'navigate') {
        return { label: brand(`Navigate to ${surfaceLabel(routeToSurfaceId(eff.to)) || eff.to}`) };
      }
      return { label: brand(describeEffect(eff)) };
    }
    case 'resource':
      return { label: brand(localizeResourceKey(ref.key)) };
    case 'condition': {
      // Tempdoc 941 — the backend AUTHORS this copy and the frontend never read it.
      //
      // `health-events.en.properties` declares, per condition id, a short `.label` (badges,
      // lists), a full `.message` sentence, and — where the reason merits its own wording — a
      // per-reason override `health-events.<id>.reason.<ReasonCode>.message`. The catalog is
      // boot-fetched into the same store `localizeResourceKey` reads (`bootHealthEventsCatalog`,
      // i18n.ts), so all three are resolvable here. This case used to look up the BARE condition
      // id (`localizeResourceKey('schema.reindex-required')`) — a key no namespace in that
      // catalog ever emits — so its i18n branch could not hit and every condition rendered as a
      // humanized id with no description at all.
      //
      // The per-reason sentence is the point: `index.unavailable` reads "The indexer is
      // unavailable" generically, but with reason `WorkerStarting` the authored copy says the
      // Worker is starting and the index will be along shortly — the difference between an
      // alarm and a status. The generic `.message` is the fallback when a reason has no
      // override (most do not), and the pre-941 humanized id remains the last resort so an
      // unbooted catalog degrades exactly as before.
      const authoredLabel = healthEventCopy(`health-events.${ref.id}.label`);
      const legacy = localizeResourceKey(ref.id);
      const label =
        authoredLabel ?? (legacy !== ref.id ? legacy : humanizeFullId(ref.id));
      const reasonMessage = ref.reason
        ? healthEventCopy(`health-events.${ref.id}.reason.${ref.reason}.message`)
        : null;
      const description = reasonMessage ?? healthEventCopy(`health-events.${ref.id}.message`);
      return {
        label: brand(label),
        ...(description !== null ? { description } : {}),
      };
    }
    case 'route': {
      const id = routeToSurfaceId(ref.target);
      return { label: brand(surfaceLabel(id) || ref.target) };
    }
    case 'metric': {
      // 559 Authority V — the metric's DECLARED accessibleLabel is the name;
      // humanized id is the fallback (mirrors the operation case) for a metric
      // registered without one.
      const declared = getStatusBarItem(ref.id)?.accessibleLabel;
      return { label: brand(declared && declared.length > 0 ? declared : humanizeId(ref.id)) };
    }
    case 'workflow': {
      // Tempdoc 565 §26.C — the localized workflow label wins; humanized id is the fallback.
      const resolved = localizeResourceKey(ref.labelKey);
      return {
        label: brand(
          resolved.length > 0 && resolved !== ref.labelKey ? resolved : humanizeId(ref.id),
        ),
      };
    }
  }
}

/** Convenience: just the label (the common case). */
export function presentLabel(ref: EntityRef): DisplayLabel {
  return present(ref).label;
}
