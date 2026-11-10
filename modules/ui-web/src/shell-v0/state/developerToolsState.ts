// SPDX-License-Identifier: Apache-2.0
/** Local per-profile developer UI preference. Reading never materializes its default. */
import { getDocument, mutateDocument, subscribeProjection, type UserStateV1 } from './UserStateDocument.js';

const effective = (doc: UserStateV1): boolean => doc.showDeveloperTools ?? (doc.viewerAudience === 'DEVELOPER');

export function getShowDeveloperTools(): boolean {
  return effective(getDocument());
}

/** Includes audience changes, profile switches and adoption from another window. */
export function subscribeShowDeveloperTools(listener: (show: boolean) => void): () => void {
  return subscribeProjection(effective, listener);
}

export function setShowDeveloperTools(show: boolean): void {
  mutateDocument(doc => ({ ...doc, showDeveloperTools: show }));
}
