// SPDX-License-Identifier: Apache-2.0
/** Authenticated Engine requests, with one token refresh after a pre-execution rejection. */

import { fetchWithSessionToken } from '../../api/http.js';
import { fetchWithAdmissionWait } from '../../api/admissionFetch.js';
import { observeStatusResponse } from '../../api/statusInvalidation.js';

/** Sends requests through the origin-bound desktop credential transport. */
export async function authorizedFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const response = await fetchWithAdmissionWait(fetchWithSessionToken, input, init);
  await observeStatusResponse(input, response);
  return response;
}
