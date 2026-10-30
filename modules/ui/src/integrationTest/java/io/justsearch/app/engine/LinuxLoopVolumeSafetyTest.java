/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static io.justsearch.app.engine.LinuxLoopVolume.OwnerState.*;
import static io.justsearch.app.engine.LinuxLoopVolume.SweepDecision.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** No drive commands: snapshots are values and the only subprocess is a disposable shell stub. */
@EnabledOnOs(OS.LINUX)
@Timeout(15)
final class LinuxLoopVolumeSafetyTest {
  @TempDir Path directory;
  private static final Path IMAGES = Path.of("/checkout/modules/ui/build/drive-return-images");
  private static final Path IMAGE = IMAGES.resolve("drive-return-123-456-789-8-1-11.img");
  private static final LinuxLoopVolume.FileIdentity BACKING = new LinuxLoopVolume.FileIdentity(11, "8:1");
  private static final LinuxLoopVolume.FileIdentity REPLACEMENT = new LinuxLoopVolume.FileIdentity(12, "8:1");

  @BeforeEach void setUserIdentity() throws Exception {
    var uid = LinuxLoopVolume.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(null, ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue());
  }

  @Test void unprivilegedAssociationUsesSysfsWithoutLoopStatus() throws Exception {
    Path file = Files.writeString(directory.resolve("own.img"), "owned");
    var identityMethod = LinuxLoopVolume.class.getDeclaredMethod("fileIdentity", Path.class);
    identityMethod.setAccessible(true);
    var identity = (LinuxLoopVolume.FileIdentity) identityMethod.invoke(null, file);
    Path backing = Path.of("/sys/class/block/loop999999/loop/backing_file");
    // An ordinary user gets no BACK-INO/BACK-MAJ:MIN values. The stub leaves the output
    // file empty, and construction mocking prevents any native command from executing.
    Process process = mock(Process.class);
    when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
    when(process.exitValue()).thenReturn(0);
    when(process.descendants()).thenAnswer(ignored -> java.util.stream.Stream.empty());
    try (var builders = mockConstruction(ProcessBuilder.class, withSettings().defaultAnswer(RETURNS_SELF),
        (builder, context) -> when(builder.start()).thenReturn(process));
        var snapshots = mockStatic(Files.class, invocation -> {
          if (invocation.getMethod().getName().equals("readString") && invocation.getArgument(0).equals(backing)) {
            return file.toString();
          }
          return invocation.callRealMethod();
        })) {
      assertAssociation(file, identity);
      snapshots.verify(() -> Files.readString(backing), times(2));
      assertTrue(builders.constructed().isEmpty(), "association identity requires no root-only loop query");
    }
  }

  private static void assertAssociation(Path file, LinuxLoopVolume.FileIdentity identity) throws Exception {
    var method = LinuxLoopVolume.class.getDeclaredMethod("checkAssociation", String.class,
        Path.class, LinuxLoopVolume.FileIdentity.class);
    method.setAccessible(true);
    try { method.invoke(null, "/dev/loop999999", file, identity); }
    catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      throw failure;
    }
  }

  @Test void associationRejectsReusedPathAndAChangeDuringTheRead() throws Exception {
    Path file = LinuxLoopVolume.createImage(directory, false);
    var identity = LinuxLoopVolume.recordedIdentity(file);
    Path backing = Path.of("/sys/class/block/loop999999/loop/backing_file");
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    try (var snapshots = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("readString") && invocation.getArgument(0).equals(backing)) {
        return file + (reads.getAndIncrement() == 0 ? "" : " (deleted)");
      }
      return invocation.callRealMethod();
    })) {
      assertThrows(IOException.class, () -> assertAssociation(file, identity), "read/stat/read closes the sysfs race");
      snapshots.verify(() -> Files.readString(backing), times(2));
    }
    Files.move(file, directory.resolve("original"));
    Files.writeString(file, "replacement");
    try (var snapshots = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("readString") && invocation.getArgument(0).equals(backing)) {
        return file.toString();
      }
      return invocation.callRealMethod();
    })) {
      assertThrows(IOException.class, () -> assertAssociation(file, identity));
      snapshots.verify(() -> Files.readString(backing));
    }
    try (var snapshots = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("readString") && invocation.getArgument(0).equals(backing)) {
        return file + " (deleted)";
      }
      return invocation.callRealMethod();
    })) {
      assertAssociation(file, identity); // Deleted backings authorize detach only.
      snapshots.verify(() -> Files.readString(backing), times(2));
    }
    assertEquals("replacement", Files.readString(file));
  }

  @Test void orphanSweepDeletesOnlyDeadUnreferencedIdentityMatchingImages() throws Exception {
    for (boolean decoy : List.of(false, true)) {
      Path file = LinuxLoopVolume.createImage(directory, decoy);
      var identityMethod = LinuxLoopVolume.class.getDeclaredMethod("fileIdentity", Path.class);
      identityMethod.setAccessible(true);
      assertEquals(identityMethod.invoke(null, file), LinuxLoopVolume.recordedIdentity(file));
      assertEquals(new LinuxLoopVolume.Owner(ProcessHandle.current().pid(),
          LinuxLoopVolume.processStart(Files.readString(Path.of("/proc/self/stat")))), LinuxLoopVolume.owner(file));
      for (var state : List.of(ALIVE, UNKNOWN)) {
        LinuxLoopVolume.deleteOrphan(directory, file, state, false);
        assertTrue(Files.exists(file));
      }
      LinuxLoopVolume.deleteOrphan(directory, file, DEAD, true);
      assertTrue(Files.exists(file), "a referenced image is left for loop cleanup");
      LinuxLoopVolume.deleteOrphan(directory, file, DEAD, false);
      assertFalse(Files.exists(file));
    }
    Path file = LinuxLoopVolume.createImage(directory, false);
    Files.move(file, directory.resolve("original-orphan"));
    Files.writeString(file, "replacement");
    LinuxLoopVolume.deleteOrphan(directory, file, DEAD, false);
    assertEquals("replacement", Files.readString(file));
    Path legacy = Files.writeString(directory.resolve("drive-return-123-456-789.img"), "unknown identity");
    LinuxLoopVolume.deleteOrphan(directory, legacy, DEAD, false);
    assertTrue(Files.exists(legacy));
    Path unrelated = Files.writeString(directory.resolve("outside.img"), "unrelated");
    LinuxLoopVolume.deleteOrphan(directory, unrelated, DEAD, false);
    assertTrue(Files.exists(unrelated));
  }

  @Test void deletedBackingNeverUnlinksItsReplacement() {
    assertEquals(DETACH, LinuxLoopVolume.decide(IMAGES, IMAGE, true, BACKING, REPLACEMENT, true, true, DEAD, false));
    assertEquals(DETACH, LinuxLoopVolume.decide(IMAGES, IMAGE, true, BACKING, null, false, true, DEAD, false));
  }

  @Test void existingFileRequiresBothDeviceAndInode() {
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, false, BACKING, REPLACEMENT, true, true, DEAD, false));
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, false, BACKING,
        new LinuxLoopVolume.FileIdentity(11, "8:2"), true, true, DEAD, false));
    assertEquals(DETACH_AND_DELETE, LinuxLoopVolume.decide(IMAGES, IMAGE, false, BACKING, BACKING, true, true, DEAD, false));
  }

  @Test void liveOwnerSurvivesCleanAndAnotherJvmSweep() {
    // Even with deleted build output and the same udisks UID, the original process is alive.
    var owner = LinuxLoopVolume.owner(IMAGE);
    assertEquals(new LinuxLoopVolume.Owner(123, 456), owner);
    var live = LinuxLoopVolume.ownerState(owner, 456L, true);
    assertEquals(ALIVE, live);
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, true, BACKING, null, false, true, live, false));
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, false, BACKING, BACKING, true, true, live, false));
  }

  @Test void onlyProvenDeadOwnersCanBeRecovered() {
    var owner = LinuxLoopVolume.owner(IMAGE);
    assertEquals(DEAD, LinuxLoopVolume.ownerState(owner, null, true));
    assertEquals(DEAD, LinuxLoopVolume.ownerState(owner, 999L, true), "PID reuse is a different process");
    assertEquals(UNKNOWN, LinuxLoopVolume.ownerState(owner, null, false));
    assertEquals(UNKNOWN, LinuxLoopVolume.ownerState(null, null, true));
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, true, BACKING, null, false, true, UNKNOWN, false));
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGES.resolve("drive-return-789.img"), true,
        BACKING, null, false, true, DEAD, false), "old names have no provable process owner");
  }

  @Test void unrelatedOrUnprovenAssociationsArePreserved() {
    for (Path file : List.of(IMAGES.resolve("outside-pattern-decoy.img"),
        Path.of("/another-worktree/build/drive-return-images/drive-return-123-456-789-8-1-11.img"),
        IMAGES.resolve("../drive-return-123-456-789-8-1-11.img"))) {
      assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, file, false, BACKING, BACKING, true, true, DEAD, false));
    }
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, false, BACKING, BACKING, true, false, DEAD, false));
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, false, null, BACKING, true, true, DEAD, false));
    assertEquals(LEAVE, LinuxLoopVolume.decide(IMAGES, IMAGE, false, BACKING, BACKING, false, true, DEAD, false));
    assertEquals(DETACH, LinuxLoopVolume.decide(IMAGES, IMAGE, true, BACKING, null, false, true, ALIVE, true),
        "the owning JVM can clean its own tracked association");
  }

  @Test void processStartHandlesSpacesAndParenthesesInProcessName() throws Exception {
    String stat = "123 (name with ) parentheses) S " + "0 ".repeat(18) + "456 0";
    assertEquals(456, LinuxLoopVolume.processStart(stat));
    assertTrue(LinuxLoopVolume.processStart(Files.readString(Path.of("/proc/self/stat"))) > 0);
  }

  @Test void shutdownCancelsHangingCommandAndRunsCleanupWhileMonitorsAreHeld() throws Exception {
    var commands = new LinuxLoopVolume.Commands();
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cleaned = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    Path ready = directory.resolve("ready");
    Path script = Files.writeString(directory.resolve("hang.sh"),
        "sleep 60 &\nchild=$!\nprintf '%s %s' \"$$\" \"$child\" > \"$1\"\nwait\n");
    var constructor = LinuxLoopVolume.class.getDeclaredConstructor(Path.class);
    constructor.setAccessible(true);
    var volumeMonitor = constructor.newInstance(LinuxLoopVolume.createImage(directory, false));
    Thread test = new Thread(() -> {
      synchronized (LinuxLoopVolume.class) {
        synchronized (volumeMonitor) {
          holding.countDown();
          try { commands.run(false, "/bin/sh", script.toString(), ready.toString()); }
          catch (IOException cancelled) { /* Expected when shutdown cancels the in-flight command. */ }
          catch (Exception unexpected) { failure.set(unexpected); }
          finally {
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          }
        }
      }
    }, "drive-safety-hanging-command");
    test.start();
    try {
      assertTrue(holding.await(2, TimeUnit.SECONDS));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while ((!Files.exists(ready) || Files.readString(ready).isBlank()) && System.nanoTime() < until) Thread.sleep(10);
      assertTrue(Files.exists(ready), "shell stub started");
      List<ProcessHandle> processes = java.util.Arrays.stream(Files.readString(ready).split(" "))
          .map(Long::parseLong).map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
      long started = System.nanoTime();
      commands.shutdown(() -> {
        try { assertEquals("cleaned", commands.run(true, "/bin/sh", "-c", "printf cleaned")); }
        catch (Throwable unexpected) { failure.set(unexpected); }
        finally { cleaned.countDown(); }
      }, 2_000);
      assertEquals(0, cleaned.getCount(), "cleanup ran before the hook returned, despite held monitors");
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "hook bounded to two seconds");
      assertNull(failure.get());
      assertThrows(IOException.class, () -> commands.run(false, "/bin/sh", "-c", "exit 0"));
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < until) Thread.sleep(10);
      assertTrue(processes.stream().noneMatch(ProcessHandle::isAlive), "stub and child were cancelled");
    } finally {
      commands.shutdown(() -> { }, 1_000);
      release.countDown();
      test.join(2_000);
      assertFalse(test.isAlive());
    }
  }
}
