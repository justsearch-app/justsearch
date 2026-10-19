/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.deadcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Which production classes may start an OS process (task b6d536 b101).
 *
 * <p><b>What it asserts.</b> A process launch — {@code ProcessBuilder.start/startPipeline},
 * {@code Runtime.exec}, {@code OwnedHelper.start/own} or {@code WindowsEngineJob.createChat} — is
 * made only by a class on {@link #ALLOWED}, each with a one-line reason. A new launcher fails; an
 * entry whose class no longer launches must be deleted. Probes that ran on every status tick (three
 * nvidia-smi per GPU snapshot, a PowerShell every few seconds) were each one more class calling
 * {@code OwnedHelper.start}; a launch now has to be argued for here first.
 *
 * <p>llama-server and the extraction child are started by {@code DefaultChildControl}, the one
 * owner of long-lived children; their callers ask it rather than launching. {@code WindowsEngineJob}
 * creates the chat process natively ({@code CreateProcessW} through the foreign-function API),
 * which bytecode does not show as a launch; its one entry point, {@code createChat}, is on the
 * launch list, so whoever calls it is checked here.
 *
 * <p><b>Limits.</b> Like {@link SystemAccessFunnelTest}: it sees the classes the audited-module list
 * puts on the classpath, reflection and native calls are invisible to it, and it bounds who
 * launches, not how often.
 */
class ProcessLaunchFunnelTest {

  /** Owner class -> the members on it that start an OS process. */
  private static final Map<String, Set<String>> LAUNCHES =
      Map.of(
          "java.lang.ProcessBuilder", Set.of("start", "startPipeline"),
          "java.lang.Runtime", Set.of("exec"),
          "io.justsearch.app.api.runtime.child.BoundChildProcess", Set.of("start"),
          "io.justsearch.app.api.runtime.child.OwnedHelper", Set.of("start", "own"),
          "io.justsearch.app.api.runtime.containment.WindowsEngineJob", Set.of("createChat"));

  /** Top-level class -> why it may start a process. */
  static final Map<String, String> ALLOWED =
      Map.ofEntries(
          Map.entry("io.justsearch.app.api.runtime.child.LinuxProcessIdentity",
              "One pidfd signal helper per recorded-process signal; two-second deadline."),
          Map.entry("io.justsearch.app.api.runtime.child.BoundChildProcess",
              "the shared platform spawn backend; once per owned launch and one boot probe"),
          Map.entry("io.justsearch.app.api.runtime.child.DefaultChildControl",
              "the one owner of long-lived children: spawn, stop intent and exit record"),
          Map.entry("io.justsearch.app.api.runtime.child.OwnedHelper",
              "the one owner of short helper processes; every helper launch goes through it"),
          Map.entry("io.justsearch.app.services.ai.install.DownloadExecutor",
              "runs the curl or BITS transfer of an AI component download"),
          Map.entry("io.justsearch.app.services.atrest.DiskEncryptionProbe",
              "reads the volume's BitLocker state; at most once per volume per ten minutes"),
          Map.entry("io.justsearch.gpu.NvidiaSmiReadings",
              "the one nvidia-smi reading, only without NVML; at most one per five seconds"),
          Map.entry("io.justsearch.app.services.mcphost.StdioMcpTransport",
              "starts a user-configured MCP server over stdio"),
          Map.entry("io.justsearch.benchmarks.EncoderBatchSweepBench",
              "benchmark harness, never part of the running app"),
          Map.entry("io.justsearch.benchmarks.util.BenchmarkUtils",
              "benchmark harness, never part of the running app"),
          Map.entry("io.justsearch.indexerworker.extract.PdfOcrEngine",
              "runs Tesseract page OCR inside the extraction OCR window"),
          Map.entry("io.justsearch.indexerworker.extract.TikaOcrRuntime",
              "checks the Tesseract executable and languages, cached"));

  @Test
  void only_allowlisted_classes_start_os_processes() {
    Map<String, Set<String>> observed = launchers(ImportedProgram.classes());

    List<String> unlisted = new ArrayList<>();
    observed.forEach((owner, sites) -> {
      if (!ALLOWED.containsKey(owner)) unlisted.add(owner + " via " + sites);
    });
    List<String> stale = new ArrayList<>(new TreeSet<>(ALLOWED.keySet()));
    stale.removeAll(observed.keySet());

    if (!unlisted.isEmpty()) {
      fail("Class(es) that start an OS process but are not on ProcessLaunchFunnelTest.ALLOWED ("
          + unlisted.size() + "):\n  " + String.join("\n  ", unlisted)
          + "\n\nRoute the work through an existing owner (OwnedHelper for helpers,"
          + " DefaultChildControl for children) or read the value in-process. A new entry needs a"
          + " one-line reason and a bound on how often it launches.");
    }
    if (!stale.isEmpty()) {
      fail("ALLOWED entr(ies) that no longer start a process — delete them:\n  " + String.join("\n  ", stale));
    }
    assertTrue(observed.size() >= 8,
        "Observed only " + observed.size() + " launching classes — the import probably failed.");
  }

  @Test
  void the_scan_sees_each_kind_of_launch_and_folds_nested_code_onto_its_class() {
    var classes = new ClassFileImporter().importClasses(
        Fixtures.Builder.class, Fixtures.Pipeline.class, Fixtures.Exec.class, Fixtures.Owned.class,
        Fixtures.Lambda.class, Fixtures.Quiet.class, Fixtures.Bound.class);
    Map<String, Set<String>> observed = launchers(classes);
    String base = Fixtures.class.getName() + "$";
    assertEquals(Set.of(base + "Builder", base + "Pipeline", base + "Exec", base + "Owned", base + "Lambda", base + "Bound"),
        observed.keySet(), observed.toString());
    assertEquals(Set.of("java.lang.ProcessBuilder#start"), observed.get(base + "Lambda"));
  }

  /** Each launching top-level class, with the launch members it calls. */
  static Map<String, Set<String>> launchers(Iterable<JavaClass> classes) {
    Map<String, Set<String>> sites = new TreeMap<>();
    for (JavaClass clazz : classes) {
      for (JavaAccess<?> access : clazz.getAccessesFromSelf()) {
        String target = access.getTargetOwner().getFullName();
        Set<String> members = LAUNCHES.get(target);
        if (members == null || !members.contains(access.getTarget().getName())) continue;
        sites.computeIfAbsent(topLevel(access.getOrigin().getOwner()), k -> new TreeSet<>())
            .add(target + "#" + access.getTarget().getName());
      }
    }
    return sites;
  }

  /** Anonymous, inner and lambda-hosting classes count as their enclosing top-level class. */
  private static String topLevel(JavaClass owner) {
    String name = owner.getFullName();
    if (name.startsWith(Fixtures.class.getName() + "$")) {
      int next = name.indexOf('$', Fixtures.class.getName().length() + 1);
      return next < 0 ? name : name.substring(0, next);
    }
    int dollar = name.indexOf('$');
    return dollar < 0 ? name : name.substring(0, dollar);
  }

  /** Fixture launchers for the self-test; never run. */
  static final class Fixtures {
    static final class Builder {
      Process run() throws Exception { return new ProcessBuilder("x").start(); }
    }

    static final class Pipeline {
      Object run() throws Exception { return ProcessBuilder.startPipeline(List.of(new ProcessBuilder("x"))); }
    }

    static final class Exec {
      @SuppressWarnings("deprecation")
      Process run() throws Exception { return Runtime.getRuntime().exec("x"); }
    }

    static final class Owned {
      Process run() throws Exception {
        return io.justsearch.app.api.runtime.child.OwnedHelper.start(new ProcessBuilder("x"), "fixture");
      }
    }

    static final class Lambda {
      java.util.concurrent.Callable<Process> run() { return () -> new ProcessBuilder("x").start(); }
    }

    static final class Bound {
      Process run() throws Exception {
        return io.justsearch.app.api.runtime.child.BoundChildProcess.start(new ProcessBuilder("x"));
      }
    }

    static final class Quiet {
      ProcessBuilder build() { return new ProcessBuilder("x"); }
    }
  }
}
