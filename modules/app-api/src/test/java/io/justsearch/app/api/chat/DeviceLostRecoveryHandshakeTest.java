/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DeviceLostRecoveryHandshakeTest {
  @Test void aLegacyAdapterCannotAcceptBeforeCommit() {
    var requested = new AtomicBoolean();
    DeviceLostRecovery legacy = new DeviceLostRecovery() {
      public List<Instant> priorRestarts() { return List.of(); }
      public void requestRestart() { requested.set(true); }
    };
    var accepted = new AtomicBoolean();
    var refused = new AtomicReference<RuntimeException>();
    legacy.requestRestart(() -> accepted.set(true), refused::set);
    assertFalse(accepted.get(), "an adapter without a commit handshake cannot make chat terminal");
    assertFalse(requested.get(), "a plain request cannot prove durable acceptance");
    assertNotNull(refused.get(), "the operator must receive the unsupported-handshake reason");
  }
}
