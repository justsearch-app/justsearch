// SPDX-License-Identifier: Apache-2.0
import { localizeError } from '../../i18n/errorCatalog.js';
import { OperationError, isStaleAuthorization } from './OperationClient.js';

/** Shell failure copy projects the existing handler code and retry hint. Dispatch classes
 * remain a separate axis; raw exception messages can contain operation IDs and paths. */
export function operationFailureMessage(error: unknown, undo = false): string {
  if (isStaleAuthorization(error)) return 'This approval request is no longer pending.';
  const fallback = undo ? 'The action could not be undone.' : 'The action could not be completed.';
  if (!(error instanceof OperationError)) {
    return `${fallback} Open Health for details.`;
  }
  const message = localizeError({
    code: error.errorCode ?? error.errorClass,
    message: fallback,
  }).message;
  if (error.errorClass === 'CONFIRMATION_REQUIRED') {
    return `${message} Start the action again if you want to review its approval request.`;
  }
  // Tempdoc 875 §C.7 — a 403 from the trust lattice is a DENIAL, not a failure to retry past.
  // The generic tail below ("Open Health for details before trying again") invites exactly the
  // retry the lattice already refused; naming the denial is the whole point of a distinct 403.
  if (error.errorClass === 'TRUST_DENIED') {
    return `${undo ? 'Undo denied. ' : 'Denied. '}${message} Retrying will not change this.`;
  }
  if (error.errorClass === 'NETWORK_ERROR' || error.errorClass === 'SERIALIZATION_ERROR') {
    return `${message} Check Health and verify whether the action took effect before trying again.`;
  }
  const recovery = error.retryable === true
    ? 'You can try the action again. If it still fails, open Health for details.'
    : error.retryable === false
      ? 'Resolve the restriction before trying again. Open Health for details.'
      : 'Open Health for details before trying again.';
  return `${undo ? 'Undo failed. ' : ''}${message} ${recovery}`;
}
