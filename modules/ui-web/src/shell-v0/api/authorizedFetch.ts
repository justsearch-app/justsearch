// SPDX-License-Identifier: Apache-2.0
/** Authenticated Engine requests, with one token refresh after a pre-execution rejection. */

import { fetchWithSessionToken } from '../../api/http.js';
import { fetchWithAdmissionWait } from '../../api/admissionFetch.js';
import { withClientKind } from '../../api/clientKind.js';
import { observeStatusResponse } from '../../api/statusInvalidation.js';

/** Sends requests through the origin-bound desktop credential transport.
 * A guarded mutation can opt out of automatic replay: its caller must check identity again before
 * another attempt. Both admission and stale-token refusals then return unchanged to the caller.
 */
export async function authorizedFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
  options?: { retry: false },
): Promise<Response> {
  const response = options?.retry === false
    ? await fetchWithSessionToken(input, withClientKind(input, init), false)
    : await fetchWithAdmissionWait(fetchWithSessionToken, input, init);
  await observeStatusResponse(input, response);
  return response;
}
