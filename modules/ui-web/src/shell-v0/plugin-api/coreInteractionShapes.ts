// SPDX-License-Identifier: Apache-2.0
/**
 * FE mirror of `CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES` (Tempdoc 561 surface
 * tier). The Java catalog is the single authority; this mirror exists so FE code (the one
 * interaction window) can enumerate the user-facing direct-LLM interaction shapes — the "modes" of
 * `jf-sv3-window`. The `interaction-surface` discipline gate enforces that this list stays in
 * sync with the Java authority, and that every shape here registers its view factory to the one
 * window (never a second view), so the two cannot drift into a fork.
 */

/** The user-facing direct-LLM interaction shapes — the modes of the one interaction window. */
export const CORE_INTERACTION_SHAPES = [
  'core.rag-ask',
  'core.free-chat',
  'core.extract',
  'core.agent-run',
  // Tempdoc 565 §15.C — the workflow run is a mode of the one window (its bespoke surface retired).
  'core.workflow-run',
] as const;

/** A core interaction shape id (the affordance "modes" of the one window). */
export type CoreInteractionShapeId = (typeof CORE_INTERACTION_SHAPES)[number];

/** The custom element tag of the one interaction window — the only view any interaction shape mounts. */
export const ONE_WINDOW_MOUNT_TAG = 'jf-sv3-window';
