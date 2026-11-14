// SPDX-License-Identifier: Apache-2.0
/**
 * originatorTone — Tempdoc 574 §23.B: THE single originator(role) → accent-token authority, the SIBLING
 * of {@link ./statusTone} for the ORIGINATOR axis (who acted: agent / user / system) that 565 §3.B
 * deliberately left un-unified ("the retrospective drawer's Timeline by originator (purple/teal)").
 *
 * Before this, originator colour drifted AND broke: EffectAuditLog coloured user=info / agent=warning /
 * system=purple (solid fills), while RetrospectivePanel's Timeline used `.badge.purple` / `.badge.teal`
 * that BOTH resolved to teal (`--accent-primary === --accent-tint`), so its two originators were visually
 * identical. This module makes one originator→accent map the authority, projected through `jf-status-badge`'s
 * `role` attr, so a "who acted" badge reads consistently — agent=purple / user=teal / system=neutral — by
 * construction, the same single-authority cut `statusTone` made for lifecycle status.
 *
 * The role hues are deliberately the NON-status accents (command/purple + tint/teal + neutral) so a role
 * badge never collides in meaning with a status badge's green/amber/red. Like the status authority, the raw
 * `var(--accent-*)` tokens live here and nowhere else for originator colour.
 */
export type Originator = 'agent' | 'user' | 'system';

/** Map any originator word — an effect/journal `origin`, a timeline `originator` — to its canonical role.
 *  Unknown/absent ⇒ `system` (the neutral, infrastructural default). */
export function toOriginator(origin: string | undefined | null): Originator {
  switch ((origin ?? '').toLowerCase()) {
    case 'agent':
    case 'assistant':
      return 'agent';
    case 'user':
    case 'human':
      return 'user';
    default:
      return 'system';
  }
}

/** Originator → solid accent token (the badge's text colour): agent=purple, user=primary, system=secondary. */
export function originatorAccent(origin: Originator): string {
  switch (origin) {
    case 'agent':
      return 'var(--accent-command)';
    case 'user':
      return 'var(--text-primary)';
    case 'system':
    default:
      return 'var(--text-secondary)';
  }
}

/** Originator → SOFT (tinted-background) accent token — the alpha-graded fill behind the solid text, the
 *  sibling of `toneAccentSoft`. */
export function originatorAccentSoft(origin: Originator): string {
  switch (origin) {
    case 'agent':
      return 'var(--accent-command-16)';
    case 'user':
      return 'var(--surface-active)';
    case 'system':
    default:
      return 'var(--surface-2)';
  }
}
