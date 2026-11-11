// SPDX-License-Identifier: Apache-2.0
/** Arrival-only folder request. Library owns revalidation, focus and removal confirmation. */
export interface LibraryRootRequest { readonly pathHash: string; readonly action: 'open' | 'remove' }
let pending: LibraryRootRequest | undefined;
const listeners = new Set<(request: LibraryRootRequest) => void>();
export function requestLibraryRoot(request: LibraryRootRequest): void {
  if (!listeners.size) pending = request;
  else for (const listener of listeners) listener(request);
}
export function takeLibraryRoot(): LibraryRootRequest | undefined {
  const request = pending;
  pending = undefined;
  return request;
}
export function subscribeLibraryRoot(listener: (request: LibraryRootRequest) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
