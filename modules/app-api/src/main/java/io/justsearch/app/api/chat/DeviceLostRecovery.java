/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * What the chat controller needs to restart the Engine after a device loss (Spec A §7.3,
 * reconciliation F10): the Engine's earlier device-lost restarts, from the exit ledger, and the
 * requested-restart path.
 */
public interface DeviceLostRecovery {

  /**
   * The Engine's earlier restarts for device loss, by when each was recorded.
   *
   * @throws IOException when the record cannot be read completely, or a ledger write failed in this
   *     incarnation; the controller then does not restart (fail closed)
   */
  List<Instant> priorRestarts() throws IOException;

  /**
   * Asks the Engine to restart for device loss: it records the intent, closes in order and exits
   * with the requested-restart code. Returns at once.
   */
  void requestRestart();

  /** No restart path: every device loss fails closed. */
  DeviceLostRecovery UNAVAILABLE =
      new DeviceLostRecovery() {
        @Override
        public List<Instant> priorRestarts() throws IOException {
          throw new IOException("no device-lost restart path is bound");
        }

        @Override
        public void requestRestart() {
          throw new IllegalStateException("no device-lost restart path is bound");
        }
      };
}
