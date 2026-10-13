/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static io.justsearch.app.services.chat.ManagedChatSeam.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.chat.StartedProcesses;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

final class SelfTestProcessOwnershipTest {
  @TempDir Path directory;

  @Test
  void exitedSelfTestWrapperWithLiveDescendantCannotPass() throws Exception {
    try (var fixture = new Candidate(directory);
        var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      SelfTestRun result = new ChildSelfTestLauncher(control).run(fixture.spec(control, true));
      assertTrue(fixture.descendantAlive(), "the serving descendant outlived its wrapper");
      assertFalse(result.exercised(), "a live descendant must make cleanup fail");
      assertTrue(result.failure().contains("still running"), result.failure());
    }
  }

  @Test
  void selfTestDescendantSurvivalBlocksMainReload() throws Exception {
    try (var fixture = new Candidate(directory);
        var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop());
        var seam = new ManagedChatSeam(directory, 0, control);
        var intent = new RuntimeIntentTestFixture(directory.resolve("intent"), true)) {
      var manager = seam.manager();
      try (var reconciler = new RuntimeReconciler(manager, manager::getCurrentMode,
          () -> manager.view().usingExternalLlamaServer(), null, intent.spec(),
          manager::setActivation, manager::componentRecoveryPending);
          var _ = reconciler.followHolds(manager.chatHolds())) {
        // Open the real controller session before forwarding desired chat, so no initial load.
        var session = manager.chatHolds().beginSelfTest("runtime-variant-activation");
        try (session) {
          reconciler.start();
          reconciler.requestBootConvergence();
          assertTrue(reconciler.awaitQuiescent(30_000));
          assertFalse(session.run(fixture.spec(control, false)).exercised(),
              "the descendant survives the forced wrapper stop");
          assertTrue(fixture.descendantAlive());
        }
        reconciler.specChanged();
        assertTrue(reconciler.awaitQuiescent(30_000));
        assertEquals(List.of(), seam.models(),
            "closing the self-test session must not spawn main beside its surviving descendant");
        assertFalse(manager.isOnline());
        assertTrue(manager.view().toString().contains("descendant"),
            "chat status must explain the refusal: " + manager.view());
        fixture.endDescendant();
        assertDoesNotThrow(() -> { manager.switchToOnlineMode(); },
            "an explicit retry can launch once the recorded descendant exits");
        await(manager::isOnline, "chat reload after descendant exit");
        assertEquals(1, seam.models().size());
      }
    }
  }

  @Test
  void unreadableDescendantsAreNotAnEmptySuccessfulSnapshot() {
    ChildHandle child = Mockito.mock(ChildHandle.class, Mockito.CALLS_REAL_METHODS);
    ProcessHandle root = Mockito.mock(ProcessHandle.class);
    Instant birth = Instant.parse("2026-10-13T00:00:00Z");
    ProcessHandle.Info info = Mockito.mock(ProcessHandle.Info.class);
    Mockito.when(child.pid()).thenReturn(123L);
    Mockito.when(child.processStartedAt()).thenReturn(birth);
    Mockito.when(child.exit()).thenReturn(new CompletableFuture<>());
    Mockito.when(root.isAlive()).thenReturn(true);
    Mockito.when(root.info()).thenReturn(info);
    Mockito.when(info.startInstant()).thenReturn(Optional.of(birth));
    Mockito.when(root.descendants()).thenThrow(new IllegalStateException("enumeration unavailable"));
    try (var handles = Mockito.mockStatic(ProcessHandle.class)) {
      handles.when(() -> ProcessHandle.of(123L)).thenReturn(Optional.of(root));
      assertThrows(IllegalStateException.class, () -> StartedProcesses.of(child),
          "enumeration failure cannot certify that cleanup has no descendants to wait for");
    }
  }

  /** A wrapper and an independently serving descendant, both with bounded self-exit signals. */
  private static final class Candidate implements AutoCloseable {
    final Path home;
    final int port;

    Candidate(Path directory) throws Exception {
      home = Files.createDirectories(directory.resolve("candidate"));
      try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        port = socket.getLocalPort();
      }
    }

    SelfTestSpec spec(DefaultChildControl control, boolean exitInProbe) throws Exception {
      return new SelfTestSpec(FixtureProcess.command("wrapper", home, port), null, List.of(),
          home.resolve("wrapper.log"), port, Duration.ofSeconds(20), new SelfTestSpec.Probe() {
            @Override public boolean healthy(int ignored) {
              try {
                return status(port) == 200;
              } catch (Exception notReady) {
                return false;
              }
            }

            @Override public void exercise(int ignored) throws Exception {
              assertEquals(200, status(port));
              if (!exitInProbe) return;
              var root = control.list().stream()
                  .filter(c -> c.kind() == io.justsearch.app.api.runtime.child.ChildKind.LLAMA_SELF_TEST)
                  .findFirst().orElseThrow();
              Files.writeString(home.resolve("wrapper-exit"), "exit");
              await(() -> control.recentExits(32).stream()
                  .anyMatch(exit -> exit.childId().equals(root.childId())), "recorded wrapper exit");
              assertTrue(descendantAlive());
            }
          });
    }

    boolean descendantAlive() throws Exception {
      return owned("server").map(ProcessHandle::isAlive).orElse(false);
    }

    private Optional<ProcessHandle> owned(String role) throws Exception {
      Path identity = home.resolve(role + ".identity");
      if (!Files.exists(identity)) return Optional.empty();
      String[] fields = Files.readString(identity).trim().split(" ");
      Instant birth = Instant.parse(fields[1]);
      return ProcessHandle.of(Long.parseLong(fields[0]))
          .filter(p -> p.info().startInstant().filter(birth::equals).isPresent());
    }

    void endDescendant() throws Exception {
      Files.writeString(home.resolve("server-exit"), "exit");
      await(() -> {
        try { return !descendantAlive(); } catch (Exception failure) { return false; }
      }, "signalled descendant exit");
    }

    @Override public void close() throws Exception {
      Files.writeString(home.resolve("wrapper-exit"), "exit");
      endDescendant();
      // No PID-only or parent-tree kills: only producer-recorded identities are awaited.
      for (String role : List.of("wrapper", "server")) {
        var process = owned(role);
        if (process.isPresent()) process.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
  }

  private static int status(int port) throws Exception {
    var connection = (java.net.HttpURLConnection)
        URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
    connection.setConnectTimeout(500);
    connection.setReadTimeout(500);
    try { return connection.getResponseCode(); } finally { connection.disconnect(); }
  }

  /** JDK-only child entry point; all exits are graceful and bounded, without process termination. */
  public static final class FixtureProcess {
    private FixtureProcess() {}

    private static List<String> command(String mode, Path home, int port) throws Exception {
      return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", Path.of(FixtureProcess.class.getProtectionDomain().getCodeSource()
              .getLocation().toURI()).toString(), FixtureProcess.class.getName(), mode,
          home.toString(), Integer.toString(port));
    }

    public static void main(String[] args) throws Exception {
      String role = args[0];
      Path home = Path.of(args[1]);
      int port = Integer.parseInt(args[2]);
      var self = ProcessHandle.current();
      Files.writeString(home.resolve(role + ".identity"),
          self.pid() + " " + self.info().startInstant().orElseThrow());
      HttpServer server = null;
      if (role.equals("wrapper")) {
        new ProcessBuilder(command("server", home, port))
            .redirectOutput(home.resolve("server.log").toFile())
            .redirectErrorStream(true).start();
      } else {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/health", exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
        server.start();
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
      while (!Files.exists(home.resolve(role + "-exit")) && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      if (server != null) server.stop(0);
    }
  }
}
