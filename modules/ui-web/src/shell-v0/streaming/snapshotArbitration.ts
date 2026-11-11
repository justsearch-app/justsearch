// SPDX-License-Identifier: Apache-2.0
/**
 * Snapshot arbitration for a keyed stream of append-only records (the action ledger, the operation
 * history).
 *
 * The wire protocol (SseEnvelopeWriter): a fresh attachment sends `connected` then a `snapshot`
 * of the source's current retention; a resume replays the missed UPDATEs without a snapshot; a
 * resume the source cannot honour sends `reset` first, which discards the client's state. So a
 * snapshot is the source's retention, and only a reset says earlier observations are void.
 *
 * Every UPDATE row is held as unconfirmed until a snapshot contains its identity. A snapshot then
 * replaces what earlier snapshots established (a smaller snapshot drops what the source no longer
 * retains) but cannot erase an unconfirmed observation, which is newer than any snapshot that
 * lacks it; where both hold one identity the observed copy stands (the records are append-only, so
 * a late snapshot never brings back an earlier copy). A reset clears both.
 * A row without an identity cannot be matched against a snapshot, so a snapshot supersedes it.
 */

/**
 * Apply a snapshot over the observations no snapshot has confirmed yet. Returns the source's
 * records as this reader holds them (the snapshot, then the still-unconfirmed observations) and
 * the observations that remain unconfirmed, oldest first.
 */
export function applySnapshot<T>(
  snapshot: readonly T[],
  unconfirmed: readonly T[],
  identity: (row: T) => string | undefined,
): { readonly rows: readonly T[]; readonly unconfirmed: readonly T[] } {
  const observed = new Map<string, T>();
  for (const row of unconfirmed) {
    const id = identity(row);
    if (id !== undefined) observed.set(id, row);
  }
  const confirmed = new Set<string>();
  const rows = snapshot.map((row) => {
    const id = identity(row);
    const copy = id === undefined ? undefined : observed.get(id);
    if (id === undefined || copy === undefined) return row;
    confirmed.add(id);
    return copy;
  });
  const still = [...observed].filter(([id]) => !confirmed.has(id)).map(([, row]) => row);
  return { rows: [...rows, ...still], unconfirmed: still };
}

/** Hold an UPDATE row as unconfirmed; a re-delivered identity replaces its earlier copy. */
export function observe<T>(
  unconfirmed: readonly T[],
  row: T,
  identity: (row: T) => string | undefined,
): readonly T[] {
  const id = identity(row);
  if (id === undefined) return unconfirmed;
  return [...unconfirmed.filter((r) => identity(r) !== id), row];
}
