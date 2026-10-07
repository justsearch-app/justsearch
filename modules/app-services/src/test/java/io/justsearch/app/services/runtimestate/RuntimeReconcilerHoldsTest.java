/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.inference.chat.ChatHolds;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec A step 6: {@link RuntimeReconciler} follows the chat controller's holds. */
final class RuntimeReconcilerHoldsTest {

  @TempDir Path tmp;
  private RuntimeIntentTestFixture fixture;

  @AfterEach
  void closeFixture() {
    if (fixture != null) {
      fixture.close();
    }
  }

  private RuntimeReconciler reconciler(RecordingLifecycleControl control, RuntimeSpecStore spec) {
    return new RuntimeReconciler(
        control,
        control::currentModeValue,
        control::externalValue,
        null,
        spec,
        TestActivationForwarders.switching(control, control::externalValue, control::detach),
        () -> false);
  }

  // Spec A step 6: the reconciler's procedures are the chat controller's holds. Each hold kind is
  // mirrored as the procedure it replaced, and forwarding stays deferred until the last one closes.
  @Test
  void followHoldsMirrorsEachHoldKindAsItsProcedureAndDefersForwarding() throws Exception {
    RecordingLifecycleControl control =
        new RecordingLifecycleControl().withMode(Mode.ONLINE).withExternal(false);
    fixture = new RuntimeIntentTestFixture(tmp.resolve("runtime-intent"), true);
    RuntimeSpecStore spec = fixture.spec();
    ChatHolds holds = org.mockito.Mockito.mock(ChatHolds.class);
    var listener = new AtomicReference<ChatHolds.Listener>();
    var removed = new AtomicBoolean();
    org.mockito.Mockito.when(holds.addListener(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> {
          listener.set(call.getArgument(0));
          return (AutoCloseable) () -> removed.set(true);
        });
    var expected = Map.of(
        ChatHolds.Kind.FREEZE, RuntimeStatus.ProcedureKind.INSTALL_ACQUISITION,
        ChatHolds.Kind.SELF_TEST, RuntimeStatus.ProcedureKind.ACTIVATION,
        ChatHolds.Kind.VDU, RuntimeStatus.ProcedureKind.VDU_BATCH,
        ChatHolds.Kind.RUNNING, RuntimeStatus.ProcedureKind.INSTALL_SMOKE_TEST);
    try (RuntimeReconciler r = reconciler(control, spec)) {
      r.start();
      AutoCloseable following = r.followHolds(holds);
      assertNotNull(listener.get());
      for (ChatHolds.Kind kind : ChatHolds.Kind.values()) {
        listener.get().kindOpened(new ChatHolds.OpenHold(kind, "owner", Instant.now()));
        assertTrue(r.awaitQuiescent(5_000));
        assertEquals(expected.get(kind).name(),
            r.current().condition(RuntimeStatus.Axis.PROCEDURE).orElseThrow().status(), kind.name());
        listener.get().kindClosed(kind, true);
        assertTrue(r.awaitQuiescent(5_000));
        assertEquals("none",
            r.current().condition(RuntimeStatus.Axis.PROCEDURE).orElseThrow().status(), kind.name());
      }

      listener.get().kindOpened(
          new ChatHolds.OpenHold(ChatHolds.Kind.SELF_TEST, "activation", Instant.now()));
      spec.setChatEnabled(false);
      r.specChanged();
      assertTrue(r.awaitQuiescent(5_000));
      assertEquals(0, control.indexingSwitchCount.get(), "forwarding waits while a hold is open");
      listener.get().kindClosed(ChatHolds.Kind.SELF_TEST, true);
      assertTrue(r.awaitQuiescent(5_000));
      assertEquals(1, control.indexingSwitchCount.get(), "the last close forwards the intent");

      following.close();
      assertTrue(removed.get());
    }
  }
}
