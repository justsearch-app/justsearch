/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * The Engine has nothing to serve a capture from right now: the index has no serving view (a
 * runtime reload is retiring or has not yet published one, or close, recovery startup or an
 * encoder-recovery fence holds new captures), or the Knowledge Server client is retiring or not
 * verified. The request was refused before it ran, so it is safe to send again.
 *
 * <p>It stays an {@link IllegalStateException} so health and readiness callers that already catch
 * that type keep treating the refusal exactly as before. The API front recognises this subtype
 * and answers it as a retryable "service unavailable".
 */
public final class ServingUnavailableException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  private final int retryAfterSeconds;

  public ServingUnavailableException(String message, int retryAfterSeconds) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int retryAfterSeconds() { return retryAfterSeconds; }
}
