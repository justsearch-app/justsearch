/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.VramProbe;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B T23 (ledger part), on real Windows processes. A holder JVM boots like the Engine: Engine
 * job first, then {@code DefaultChildControl.openForEngine} on a data directory, then two children
 * through it (one {@code cmd.exe} that starts a grandchild, one plain {@code ping}), each with its
 * console host. The holder is killed through its retained native identity. Within 3 seconds nothing it started
 * is alive, console hosts included; each {@code child-start} recorded that the child was in the
 * Engine job ({@code contained: true}); and the next Engine's {@code openForEngine} on the same
 * data directory writes {@code owner-died OWNER_DIED} (OS check GONE) for each child, naming the
 * killed holder as the previous owner.
 *
 * <p>{@code openForEngine} is reached by name with whichever arity the build has, so this class runs
 * unchanged against a build whose control does not take the Engine job.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(120)
final class EngineJobChildLedgerTest {
  private static final long SURVIVOR_BOUND_MS = 3_000;
  private static final String HOLDER_INSTANCE = "holder-1";

  @TempDir Path dataDir;

  @Test
  void killedEngineChildrenDieWithItAndTheNextEngineRecordsOwnerDied() throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    Process holder = new ProcessBuilder(List.of(java, "--enable-native-access=ALL-UNNAMED",
        "-cp", System.getProperty("java.class.path"), Holder.class.getName(),
        dataDir.toString()))
        .redirectErrorStream(true).start();
    var holderIdentity = io.justsearch.app.api.runtime.child.ProcessTerminator.forCurrentOs().open(holder);
    String holderBirth = holderIdentity.creationFileTime().orElseThrow();
    System.out.println("fixture.holder=" + holder.pid() + "/" + holderBirth);
    List<ProcessHandle> started = new ArrayList<>();
    List<String> transcript = new ArrayList<>();
    try {
      var lines = new BufferedReader(
          new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
      String containment = "";
      List<Long> children = new ArrayList<>();
      for (String line; (line = lines.readLine()) != null; ) {
        transcript.add(line);
        if (line.startsWith("CONTAINMENT ")) containment = line.substring(12);
        if (line.startsWith("CHILD ")) children.add(Long.parseLong(line.substring(6)));
        if (line.equals("READY")) break;
      }
      assertEquals("CONTAINED", containment, "the holder booted contained: " + transcript);
      assertEquals(2, children.size(), "the holder started two children: " + transcript);
      holder.toHandle().descendants().forEach(started::add);
      for (long pid : children) {
        assertTrue(started.stream().anyMatch(h -> isConsoleHost(h) && isChildOf(h, pid)),
            "child " + pid + " has a console host to account for: " + describe(started));
      }
      assertTrue(started.stream().anyMatch(h -> name(h).equalsIgnoreCase("PING.EXE")
              && h.parent().map(p -> name(p).equalsIgnoreCase("cmd.exe")).orElse(false)),
          "the cmd.exe child started a grandchild: " + describe(started));

      assertEquals(io.justsearch.app.api.runtime.child.TerminateResult.OK,
          holderIdentity.terminate(1), "abrupt stop of the held PID/birth " + holder.pid() + "/" + holderBirth);
      assertTrue(holder.waitFor(10, TimeUnit.SECONDS), "the holder died");

      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SURVIVOR_BOUND_MS);
      while (started.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertEquals(List.of(), describe(started.stream().filter(ProcessHandle::isAlive).toList()),
          "nothing the killed Engine started is alive " + SURVIVOR_BOUND_MS + " ms later");

      // The next Engine on the same data directory.
      try (AutoCloseable next = openForEngine(dataDir, "holder-2",
          EngineContainment.current())) {
        assertNotNull(next, "the next Engine must open its control to reconcile the holder's records");
      }

      List<LedgerRecord> records = ExitLedgerReader.read(
          LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records();
      Map<Long, String> startedChildren = new TreeMap<>();
      Map<String, String> contained = new TreeMap<>();
      for (LedgerRecord record : records) {
        if (record.type() != LedgerRecordType.CHILD_START) continue;
        String childId = record.text("childId").orElseThrow();
        startedChildren.put(record.field("pid").asLong(), childId);
        contained.put(childId, String.valueOf(record.field("contained")));
      }
      assertEquals(children.stream().sorted().toList(), List.copyOf(startedChildren.keySet()),
          "one child-start per child the holder reported");

      Map<String, String> endings = new TreeMap<>();
      Map<String, String> expected = new TreeMap<>();
      for (LedgerRecord record : records) {
        if (record.type() != LedgerRecordType.OWNER_DIED
            && record.type() != LedgerRecordType.CHILD_EXIT
            && record.type() != LedgerRecordType.CHILD_SURVIVED) {
          continue;
        }
        endings.merge(record.text("childId").orElse("?"), record.type().token() + " "
            + record.text("exitClass").orElse("-") + " " + record.text("osCheck").orElse("-")
            + " " + previousInstance(record),
            (a, b) -> a + "; " + b);
      }
      for (String childId : startedChildren.values()) {
        expected.put(childId, "owner-died OWNER_DIED GONE " + HOLDER_INSTANCE);
      }
      assertEquals(expected, endings,
          "the next Engine closes each child of the killed one as OWNER_DIED, and nothing else");

      Map<String, String> allContained = new TreeMap<>();
      for (String childId : startedChildren.values()) allContained.put(childId, "true");
      assertEquals(allContained, contained,
          "every child-start records that the child is in the Engine job");
    } finally {
      if (holder.isAlive()) holderIdentity.terminate(1);
      holderIdentity.close();
      // Descendants are observed only. The fixture's job owns their cleanup; ancestry never authorizes a kill.
    }
  }

  /** {@code DefaultChildControl.openForEngine}, passing the Engine job when the build takes it. */
  static AutoCloseable openForEngine(Path dataDir, String instanceId, Object containment)
      throws ReflectiveOperationException {
    for (Method method : DefaultChildControl.class.getMethods()) {
      if (!method.getName().equals("openForEngine")) continue;
      Object[] args = method.getParameterCount() == 5
          ? new Object[] {dataDir, instanceId, ManagedChildRegistry.noop(), VramProbe.none(),
              containment}
          : new Object[] {dataDir, instanceId, ManagedChildRegistry.noop(), VramProbe.none()};
      return (AutoCloseable) method.invoke(null, args);
    }
    throw new NoSuchMethodException("DefaultChildControl.openForEngine");
  }

  private static String previousInstance(LedgerRecord record) {
    var instance = record.field("previousOwner").path("instanceId");
    return instance.isString() ? instance.asString() : "-";
  }

  private static boolean isConsoleHost(ProcessHandle h) {
    return name(h).equalsIgnoreCase("conhost.exe");
  }

  private static boolean isChildOf(ProcessHandle h, long parent) {
    return h.parent().map(p -> p.pid() == parent).orElse(false);
  }

  private static String name(ProcessHandle h) {
    String command = h.info().command().orElse("?");
    return command.substring(Math.max(command.lastIndexOf('\\'), command.lastIndexOf('/')) + 1);
  }

  private static List<String> describe(List<ProcessHandle> handles) {
    return handles.stream().map(h -> name(h) + ":" + h.pid()).toList();
  }

  /**
   * Boots like the Engine: the Engine job, then the children control on the data directory in
   * {@code args[0]}, then two console children through it; then waits to be killed.
   */
  public static final class Holder {
    public static void main(String[] args) throws Exception {
      EngineContainment containment = EngineContainment.installAtBoot();
      System.out.println("CONTAINMENT " + containment.status());
      Path dataDir = Path.of(args[0]);
      ChildControl control = (ChildControl) openForEngine(dataDir, HOLDER_INSTANCE, containment);
      List<ChildHandle> children = new ArrayList<>();
      children.add(control.start(spec(dataDir, "cmd-ping",
          "cmd.exe", "/c", "ping -n 600 127.0.0.1 > NUL")));
      children.add(control.start(spec(dataDir, "ping", "ping", "-n", "600", "127.0.0.1")));
      for (ChildHandle child : children) System.out.println("CHILD " + child.pid());
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (System.nanoTime() < until && !ready(children)) Thread.sleep(20);
      System.out.println("READY");
      System.out.flush();
      Thread.sleep(120_000);
    }

    private static ChildSpec spec(Path dataDir, String label, String... argv) {
      return new ChildSpec(ChildKind.EXTRACTION, Arrays.asList(argv), null, Map.of(),
          new ChildSpec.Stdio.AppendToLog(dataDir.resolve(label + ".log")), false,
          OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, label);
    }

    /** Each child has its console host, and the cmd.exe child has started its ping. */
    private static boolean ready(List<ChildHandle> children) {
      for (ChildHandle child : children) {
        Optional<ProcessHandle> handle = ProcessHandle.of(child.pid());
        if (handle.isEmpty()) return false;
        if (handle.get().children().noneMatch(h -> endsWith(h, "conhost.exe"))) return false;
      }
      return ProcessHandle.of(children.getFirst().pid())
          .map(cmd -> cmd.children().anyMatch(h -> endsWith(h, "ping.exe"))).orElse(false);
    }

    private static boolean endsWith(ProcessHandle h, String name) {
      return h.info().command()
          .map(c -> c.toLowerCase(Locale.ROOT).endsWith(name)).orElse(false);
    }
  }
}
