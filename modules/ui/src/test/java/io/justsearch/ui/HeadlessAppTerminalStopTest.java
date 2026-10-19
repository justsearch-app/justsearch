/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec B §2.8 "SHUTDOWN → Engine reason": the llama-server stop each Engine shutdown records. */
final class HeadlessAppTerminalStopTest {

  @Test
  void everyShutdownReasonMapsToItsRecordedStop() {
    Map<Reason, StopRequest> expected = new EnumMap<>(Reason.class);
    expected.put(Reason.QUIT, StopRequest.of(StopReason.QUIT, StopMode.NOW, "engine-shutdown"));
    expected.put(Reason.RESTART,
        StopRequest.of(StopReason.ENGINE_RESTART, StopMode.NOW, "engine-shutdown"));
    expected.put(Reason.UPGRADE,
        StopRequest.of(StopReason.UPGRADE, StopMode.NOW, "engine-shutdown"));
    expected.put(Reason.HANG,
        StopRequest.of(StopReason.ENGINE_RESTART, StopMode.NOW, "engine-shutdown")
            .withDetail(StopDetail.HANG));
    assertEquals(Reason.values().length, expected.size(), "every reason has an expected stop");
    for (Reason reason : Reason.values()) {
      assertEquals(expected.get(reason), HeadlessApp.terminalStopOf(reason), reason.name());
    }
  }
}
