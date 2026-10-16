/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * Generation was refused because the chat server is draining, starting, stopping, relaunching or
 * otherwise not serving. It is transient: the same request can succeed once the transition ends.
 *
 * <p>An optional AI step (query expansion, query understanding, filter normalization, synthetic
 * queries, VDU) treats this refusal exactly as it treats AI being off: the request it serves goes
 * on without the step, and a background job defers the work. The refusal may arrive thrown at the
 * call or as the failure of the returned future; {@link #causedBy(Throwable)} recognises both.
 *
 * <p>It extends {@link IllegalStateException} so existing callers that already catch an unusable
 * generative component keep working.
 */
public final class GenerationUnavailableException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  public GenerationUnavailableException(String message) {
    super(message);
  }

  /**
   * True when {@code failure} is this refusal or wraps it, through any chain of completion,
   * execution or domain wrappers.
   */
  public static boolean causedBy(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 32; depth++) {
      if (current instanceof GenerationUnavailableException) return true;
      Throwable next = current.getCause();
      if (next == current) return false;
      current = next;
    }
    return false;
  }
}
