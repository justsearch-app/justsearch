/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.VramProbe;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A wrapper exits in the successful probe while its own recorded descendant stays alive. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public final class ExitedWrapperDescendantTest {
  @TempDir Path tmp;

  @Test
  void exitedSelfTestWrapperWithLiveDescendantCannotPass() throws Exception {
    try (DefaultChildControl control = control()) {
      try {
        SelfTestRun result = run(control);
        assertFalse(result.exercised(), "an exited wrapper's serving descendant must prevent success");
        assertTrue(result.failure().contains("still running"), result.failure() + "; log=" + Files.readString(tmp.resolve("self-test.log")));
      } finally { releaseLeaf(); }
    }
  }

  @Test
  void selfTestDescendantSurvivalBlocksMainReload() throws Exception {
    try (DefaultChildControl control = control()) {
      try {
        run(control); // the activation session has returned and its root has exited
        IOException refused = assertThrows(IOException.class, () -> control.start(spec(ChildKind.LLAMA_SERVER)),
            "closing the self-test scope must not allow a main server beside its descendant");
        assertTrue(refused.getMessage().contains("descendant"), refused.getMessage());
        releaseLeaf();
        assertTrue(control.start(spec(ChildKind.LLAMA_SERVER)).pid() > 0, "retry after descendant exit works");
      } finally { releaseLeaf(); }
    }
  }

  private DefaultChildControl control() throws IOException {
    return DefaultChildControl.openForEngine(tmp.resolve("data"), "wrapper-test", ManagedChildRegistry.noop(),
        VramProbe.none(), EngineContainment.current());
  }

  private ChildSpec spec(ChildKind kind) {
    return new ChildSpec(kind, WrapperProcessFixture.argv(kind == ChildKind.LLAMA_SELF_TEST ? "wrapper" : "leaf", tmp), null,
        Map.of(), new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(), Optional.empty(),
        ChildSpec.CooperativeStop.NONE, "wrapper-fixture");
  }

  private SelfTestRun run(DefaultChildControl control) {
    return new ChildSelfTestLauncher(control).run(new SelfTestSpec(spec(ChildKind.LLAMA_SELF_TEST).argv(),
        null, List.of(), tmp.resolve("self-test.log"), 12345, Duration.ofSeconds(20), new SelfTestSpec.Probe() {
          @Override public boolean healthy(int port) { return Files.exists(tmp.resolve("leaf.identity")); }
          @Override public void exercise(int port) throws Exception {
            Files.writeString(tmp.resolve("wrapper.exit"), "exit");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (control.recentExits(10).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(control.recentExits(10).isEmpty(), "wrapper's exit must be published inside the probe");
          }
        }));
  }

  private void releaseLeaf() throws Exception {
    Files.writeString(tmp.resolve("leaf.exit"), "exit");
    Path identity = tmp.resolve("leaf.identity");
    if (!Files.exists(identity)) return;
    long pid = Long.parseLong(Files.readString(identity).split("/")[0]);
    // Observation only; cleanup asks this fixture through its private exit file, never by PID.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "our fixture leaf exited");
  }

}
