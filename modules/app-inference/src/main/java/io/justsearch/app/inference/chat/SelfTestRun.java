/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

/**
 * What one self-test launch did. The candidate has exited when this is returned, unless {@code
 * failure} says it, or a process it started, did not: such a run is never {@code exercised}.
 *
 * @param exercised true when the candidate became healthy and the probe's exercise succeeded
 * @param failure why it did not, or {@code null} when {@code exercised}
 */
public record SelfTestRun(boolean exercised, String failure) {

  public static SelfTestRun passed() {
    return new SelfTestRun(true, null);
  }

  public static SelfTestRun failed(String failure) {
    return new SelfTestRun(false, failure == null ? "unknown" : failure);
  }
}
