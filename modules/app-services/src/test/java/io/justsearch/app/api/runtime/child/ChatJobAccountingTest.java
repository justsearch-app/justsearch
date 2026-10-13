/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestSpec;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

@EnabledOnOs(OS.WINDOWS)
final class ChatJobAccountingTest {
  @TempDir Path directory;

  @Test @Timeout(60)
  void idleChatControlAddsLessThanOnePercentOfACore() throws Exception {
    var cpu = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    List<String> ping = List.of(Path.of(System.getenv("SystemRoot"), "System32", "ping.exe").toString(),
        "-t", "127.0.0.1");
    long without, with;
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      ChildHandle plain = control.start(spec(ChildKind.EXTRACTION, ping, null, Map.of()));
      Thread.sleep(1_000);
      without = cpuWindow(cpu);
      control.stop(plain, StopReason.QUIT, StopMode.NOW).toCompletableFuture().get(15, TimeUnit.SECONDS);
      ChildHandle chat = control.start(spec(ChildKind.LLAMA_SERVER, ping, null, Map.of()));
      Thread.sleep(1_000);
      with = cpuWindow(cpu);
      control.stop(chat, StopReason.QUIT, StopMode.NOW).toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
    System.out.println("idle CPU over 10 s: plain=" + without + " ns, chat=" + with + " ns");
    assertTrue(with - without <= 100_000_000L,
        "idle chat adds over 1% of a core: baseline=" + without + " ns, chat=" + with + " ns over 10 s");
  }

  private static long cpuWindow(com.sun.management.OperatingSystemMXBean cpu) throws Exception {
    long start = cpu.getProcessCpuTime();
    Thread.sleep(10_000);
    return cpu.getProcessCpuTime() - start;
  }

  @Test
  void laterAdmissionRetriesAListingFailureAndClearsTheBlock() throws Exception {
    ChildDescendants original = null;
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      ChildHandle first = control.start(spec(ChildKind.LLAMA_SERVER, Fixture.argv("idle", directory), null, Map.of()));
      control.stop(first, StopReason.DEACTIVATE, StopMode.NOW).toCompletableFuture().get(15, TimeUnit.SECONDS);
      assertEquals(ChildHandle.State.EXITED, first.state());
      var field = first.getClass().getDeclaredField("descendants");
      field.setAccessible(true);
      original = (ChildDescendants) field.get(first);
      var fail = new AtomicBoolean(true);
      ProcessHandle listing = Mockito.mock(ProcessHandle.class);
      Mockito.when(listing.pid()).thenReturn(first.pid());
      Mockito.when(listing.isAlive()).thenReturn(true);
      Mockito.when(listing.descendants()).thenAnswer(invocation -> {
        if (fail.get()) throw new IllegalStateException("transient listing failure");
        return Stream.empty();
      });
      field.set(first, new ChildDescendants(listing));
      assertThrows(java.io.IOException.class,
          () -> control.start(spec(ChildKind.LLAMA_SERVER, Fixture.argv("idle", directory), null, Map.of())));
      fail.set(false);
      ChildHandle next = assertDoesNotThrow(() -> control.start(
          spec(ChildKind.LLAMA_SERVER, Fixture.argv("idle", directory), null, Map.of())),
          "verified empty membership must clear the block without restarting the control");
      control.stop(next, StopReason.QUIT, StopMode.NOW).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } finally {
      // Before the repair this type has no close method; keep the check source compatible.
      if ((Object) original instanceof AutoCloseable resource) resource.close();
    }
  }

  @Test @Timeout(120)
  void immediateWrapperExitRetainsTheActualSurvivorEveryTime() throws Exception {
    int captured = 0;
    for (int round = 0; round < 20; round++) {
      Path home = Files.createDirectory(directory.resolve("round-" + round));
      try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
        try {
          ChildHandle root = control.start(spec(ChildKind.LLAMA_SELF_TEST,
              Fixture.argv("wrapper", home), null, Map.of()));
          root.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
          long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
          while (!Files.exists(home.resolve("identity")) && System.nanoTime() < deadline) Thread.sleep(10);
          String[] identity = Files.readString(home.resolve("identity")).trim().split(" ");
          long pid = Long.parseLong(identity[0]);
          Instant birth = Instant.parse(identity[1]);
          assertTrue(ProcessHandle.of(pid).filter(p -> p.info().startInstant().filter(birth::equals).isPresent())
              .map(ProcessHandle::isAlive).orElse(false), "the serving process actually survived");
          if (root.descendants().stream().anyMatch(p -> p.pid() == pid
              && p.info().startInstant().filter(birth::equals).isPresent())) captured++;
        } finally {
          Files.writeString(home.resolve("exit"), "exit");
          awaitOwnExit(home);
        }
      }
    }
    System.out.println("immediate wrapper survivor captures=" + captured + "/20");
    assertEquals(20, captured, "capture must identify the actual server in every round, not just conhost");
  }

  private static ChildSpec spec(ChildKind kind, List<String> argv, Path cwd, Map<String, String> env) {
    return new ChildSpec(kind, argv, cwd, env, new ChildSpec.Stdio.Pipes(), false,
        OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "accounting-check");
  }

  @Test
  void selfTestChecksMembershipAfterObservedDescendantsDrain() throws Exception {
    // A logical drain seam: no system process is started, queried by PID, or terminated here.
    var drained = new AtomicBoolean();
    ProcessHandle observed = Mockito.mock(ProcessHandle.class);
    ProcessHandle late = Mockito.mock(ProcessHandle.class);
    Mockito.when(late.isAlive()).thenReturn(true);
    Mockito.when(observed.isAlive()).thenAnswer(invocation -> !drained.get());
    Mockito.when(observed.onExit()).thenAnswer(invocation -> {
      drained.set(true);
      return CompletableFuture.completedFuture(observed);
    });
    ChildHandle candidate = Mockito.mock(ChildHandle.class);
    var exit = new CompletableFuture<ExitOutcome>();
    Mockito.when(candidate.exit()).thenReturn(exit);
    Mockito.when(candidate.descendants()).thenAnswer(invocation ->
        drained.get() ? List.of(late) : List.of(observed));
    ChildControl control = Mockito.mock(ChildControl.class);
    Mockito.when(control.start(Mockito.any(ChildSpec.class), Mockito.any(ChildControl.ManifestEntry.class)))
        .thenReturn(candidate);
    Mockito.when(control.stop(Mockito.eq(candidate), Mockito.any(StopRequest.class)))
        .thenAnswer(invocation -> {
          exit.complete(Mockito.mock(ExitOutcome.class));
          return exit;
        });
    Mockito.when(control.awaitGpuRelease(Mockito.any(Duration.class))).thenReturn(true);
    var spec = new SelfTestSpec(List.of("logical-drain-fixture"), null, List.of(),
        directory.resolve("drain.log"), 1, Duration.ofSeconds(1), new SelfTestSpec.Probe() {
          @Override public boolean healthy(int port) { return true; }
          @Override public void exercise(int port) { /* successful exercise */ }
        });
    var result = new ChildSelfTestLauncher(control).run(spec);
    assertTrue(drained.get(), "the initially observed descendant exited during cleanup");
    assertTrue(late.isAlive(), "the later scope member is still running");
    assertFalse(result.exercised(), "a late scope member must prevent a successful self-test");
    assertTrue(result.failure().contains("still running"), result.failure());
  }

  private static void awaitOwnExit(Path home) throws Exception {
    Path identity = home.resolve("identity");
    if (!Files.exists(identity)) identity = home.resolve("spawn-identity");
    if (!Files.exists(identity)) return;
    String[] fields = Files.readString(identity).trim().split(" ");
    Instant birth = Instant.parse(fields[1]);
    var process = ProcessHandle.of(Long.parseLong(fields[0]))
        .filter(p -> p.info().startInstant().filter(birth::equals).isPresent());
    if (process.isPresent()) process.get().onExit().get(10, TimeUnit.SECONDS);
  }

  /** Every child records its own PID/birth; cleanup signals and waits, never kills a process tree. */
  public static final class Fixture {
    private Fixture() {}
    static List<String> argv(String mode, Path home) throws Exception {
      return List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
          "-cp", Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
          Fixture.class.getName(), mode, home.toString());
    }
    public static void main(String[] args) throws Exception {
      Path home = Path.of(args[1]);
      if (args[0].equals("wrapper")) {
        Process child = new ProcessBuilder(argv("server", home)).inheritIO().start();
        // Record at spawn, before the wrapper exits. The server records the same identity itself.
        publishIdentity(home, "spawn-identity", child.pid(), child.info().startInstant().orElseThrow());
        // Exit at once, without JVM shutdown work, so no observation window follows the spawn.
        Runtime.getRuntime().halt(0);
      }
      if (args[0].equals("idle")) { System.in.readAllBytes(); return; }
      var self = ProcessHandle.current();
      Instant birth = self.info().startInstant().orElseThrow();
      var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/health", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
      server.start();
      publishIdentity(home, "identity", self.pid(), birth);
      long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
      while (!Files.exists(home.resolve("exit")) && System.nanoTime() < deadline) Thread.sleep(10);
      server.stop(0);
    }

    private static void publishIdentity(Path home, String name, long pid, Instant birth) throws Exception {
      Path staged = home.resolve(name + ".tmp");
      Files.writeString(staged, pid + " " + birth);
      Files.move(staged, home.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
  }
}
