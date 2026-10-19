/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * The chat server controller's device-health inputs (Spec A §7.3 and §7.4). Both return at once:
 * the controller acts on its own thread.
 */
public interface ChatDeviceLoss {

  /**
   * The GPU was lost ({@code evidence} names the source and the status code). Interrupts any
   * in-progress step; the controller then stops the server at once and, within the device-lost
   * limit, restarts the Engine. Over the limit, or when the limit cannot be read, chat is FAILED
   * with {@link FailedCode#DEVICE_LOST_LIMIT} until an operator retry. A report while one is being
   * handled, or after the controller stopped for good, is ignored.
   */
  void reportDeviceLost(DeviceLostEvidence evidence);

  /**
   * The machine resumed from sleep. The controller restarts its timers in full (suspended time is
   * not counted), resets the server's health streak, clears a FAILED latch and probes a serving
   * server before it is trusted again.
   */
  void reportResume();
}
