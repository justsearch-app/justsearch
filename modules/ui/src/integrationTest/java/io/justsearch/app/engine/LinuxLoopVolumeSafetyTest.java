/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

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

  private static LinuxLoopVolume volume(Path file) throws Exception {
    var constructor = LinuxLoopVolume.class.getDeclaredConstructor(Path.class);
    constructor.setAccessible(true);
    return constructor.newInstance(file);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    var field = LinuxLoopVolume.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void shutdownCleanup(LinuxLoopVolume volume) throws Exception {
    var method = LinuxLoopVolume.class.getDeclaredMethod("cleanup", boolean.class);
    method.setAccessible(true);
    method.invoke(volume, true);
  }

  private LinuxLoopVolume attachedVolume(Path images) throws Exception {
    Path file = LinuxLoopVolume.createImage(images, false);
    var volume = volume(file);
    set(volume, "loop", new LinuxLoopVolume.Attachment("/dev/loop999998", file,
        LinuxLoopVolume.recordedIdentity(file)));
    return volume;
  }

  /** Only recorded devices are exposed. Any global scan or native command escapes fail. */
  private org.mockito.MockedStatic<Files> associations(Path images) {
    return mockStatic(Files.class, invocation -> {
      String method = invocation.getMethod().getName();
      if (method.equals("list")) throw new AssertionError("cleanup must never scan devices or images");
      if (method.equals("readString")) {
        Path path = invocation.getArgument(0);
        if (path.startsWith("/sys/class/block")) {
          if (path.endsWith("dev")) return "7:999998";
          String suffix = path.toString().contains("loop999999") ? "-decoy.img" : ".img";
          try (var files = Files.walk(images)) {
            return files.filter(p -> p.toString().endsWith(suffix)
                && (suffix.equals("-decoy.img") || !p.toString().endsWith("-decoy.img")))
                .findFirst().orElseThrow().toString();
          }
        }
      }
      if (method.equals("readAllLines") && invocation.getArgument(0).equals(Path.of("/proc/self/mountinfo"))) {
        return List.of();
      }
      return invocation.callRealMethod();
    });
  }

  private static Process completedProcess() throws Exception {
    Process process = mock(Process.class);
    when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
    when(process.exitValue()).thenReturn(0);
    when(process.descendants()).thenAnswer(ignored -> java.util.stream.Stream.empty());
    return process;
  }

  @Test void teardownThenHookNeverActsOnAReusedDevice() throws Exception {
    Path images = LinuxLoopVolume.createImageDirectory(directory);
    var volume = attachedVolume(images);
    Process process = completedProcess();
    try (var snapshots = associations(images);
        var builders = mockConstruction(ProcessBuilder.class, withSettings().defaultAnswer(RETURNS_SELF),
            (builder, context) -> when(builder.start()).thenReturn(process))) {
      volume.close();
      shutdownCleanup(volume); // The same device number may now belong to another JVM.
      assertEquals(1, builders.constructed().size());
      snapshots.verify(() -> Files.readString(Path.of("/sys/class/block/loop999998/loop/backing_file")), times(4));
    }
    assertFalse(Files.exists(images));
  }

  @Test void hookDuringTeardownNeverClaimsTheSameVolume() throws Exception {
    Path images = LinuxLoopVolume.createImageDirectory(directory);
    var volume = attachedVolume(images);
    Process process = completedProcess();
    when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(ignored -> {
      var failure = new AtomicReference<Throwable>();
      Thread hook = new Thread(() -> {
        try { shutdownCleanup(volume); }
        catch (Throwable problem) { failure.set(problem); }
      });
      hook.start();
      hook.join(1_000);
      assertFalse(hook.isAlive(), "hook does not wait for teardown");
      assertNull(failure.get());
      return true;
    });
    try (var snapshots = associations(images);
        var builders = mockConstruction(ProcessBuilder.class, withSettings().defaultAnswer(RETURNS_SELF),
            (builder, context) -> when(builder.start()).thenReturn(process))) {
      volume.close();
      assertEquals(1, builders.constructed().size());
      snapshots.verify(() -> Files.readString(Path.of("/sys/class/block/loop999998/loop/backing_file")), times(4));
    }
    assertFalse(Files.exists(images));
  }

  @Test void hookDuringAwayLeavesTheDeviceWithItsFirstCleanupOwner() throws Exception {
    Path images = LinuxLoopVolume.createImageDirectory(directory);
    var volume = attachedVolume(images);
    Process process = completedProcess();
    when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(ignored -> {
      shutdownCleanup(volume); // Deterministic interleaving after the device claim, before detach completes.
      assertTrue(Files.exists(images), "in-flight image must not be unlinked");
      return true;
    });
    try (var snapshots = associations(images);
        var builders = mockConstruction(ProcessBuilder.class, withSettings().defaultAnswer(RETURNS_SELF),
            (builder, context) -> when(builder.start()).thenReturn(process))) {
      volume.away();
      volume.close();
      assertEquals(1, builders.constructed().size());
      snapshots.verify(() -> Files.readString(Path.of("/sys/class/block/loop999998/loop/backing_file")), times(4));
    }
    assertFalse(Files.exists(images));
  }

  @Test void failedDeviceDoesNotPreventOtherOwnedDeviceCleanup() throws Exception {
    Path images = LinuxLoopVolume.createImageDirectory(directory);
    var volume = attachedVolume(images);
    Path decoy = LinuxLoopVolume.createImage(images, true);
    set(volume, "decoyImage", decoy);
    set(volume, "decoyIdentity", LinuxLoopVolume.recordedIdentity(decoy));
    set(volume, "decoy", new LinuxLoopVolume.Attachment("/dev/loop999999", decoy,
        LinuxLoopVolume.recordedIdentity(decoy)));
    Process failed = completedProcess();
    when(failed.exitValue()).thenReturn(1);
    Process success = completedProcess();
    try (var snapshots = associations(images);
        var builders = mockConstruction(ProcessBuilder.class, withSettings().defaultAnswer(RETURNS_SELF),
            (builder, context) -> when(builder.start()).thenReturn(context.getCount() == 1 ? failed : success))) {
      assertThrows(IOException.class, volume::close);
      assertEquals(2, builders.constructed().size());
      snapshots.verify(() -> Files.readString(Path.of("/sys/class/block/loop999999/loop/backing_file")), times(4));
    }
    assertFalse(Files.exists(decoy));
    assertTrue(Files.exists(images), "failed device retains its image for manual cleanup");
  }

  @Test void privateImageCleanupChecksCreationIdentityAndPreservesOtherRuns() throws Exception {
    Path images = LinuxLoopVolume.createImageDirectory(directory);
    assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(images));
    Path otherImages = LinuxLoopVolume.createImageDirectory(directory);
    Path other = LinuxLoopVolume.createImage(otherImages, false);
    Path file = LinuxLoopVolume.createImage(images, false);
    var volume = volume(file);
    Files.move(file, images.resolve("original"));
    Files.writeString(file, "replacement");
    assertThrows(IOException.class, volume::close);
    assertEquals("replacement", Files.readString(file));
    assertTrue(Files.exists(other));
    Path cleanImages = LinuxLoopVolume.createImageDirectory(directory);
    volume(LinuxLoopVolume.createImage(cleanImages, false)).close();
    assertFalse(Files.exists(cleanImages));
    assertTrue(Files.exists(other));
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
