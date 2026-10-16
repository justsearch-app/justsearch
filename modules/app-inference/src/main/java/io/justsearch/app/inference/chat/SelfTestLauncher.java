/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

/**
 * Launches a self-test candidate for the chat controller (Spec A T38). Only the controller calls it,
 * on its thread, with the main server stopped, so a candidate never shares the GPU with it. This is
 * the seam where Spec B step 6 starts the candidate through the child-process owner as a {@code
 * LLAMA_SELF_TEST} child.
 */
public interface SelfTestLauncher {

  /**
   * Starts the candidate, waits for it to become healthy, runs the probe's exercise, then stops it
   * and waits for it to exit before returning, also when the probe fails or the thread is
   * interrupted.
   */
  SelfTestRun run(SelfTestSpec spec);
}
