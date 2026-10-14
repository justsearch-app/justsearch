/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task b6d536 b101: what the children ledger and the status probes cost the primary indexing rate.
 *
 * <p>Opt-in: it needs a Windows host and minutes of wall time. It
 * runs the real in-process indexing path ({@link EngineTestHarness}: ingest port, extraction,
 * Lucene) on a fixed generated corpus, CPU only, inside a real Engine job with its witness and the
 * real children ledger (the accounting b599d4d7e added). Each trial indexes a fresh index while a
 * background caller drives the product's own probe code at the rate the E3 window recorded:
 *
 * <ul>
 *   <li>{@code off}: no probes.
 *   <li>{@code today}: {@code GpuCapabilitiesService.snapshot()} every 2.7 s and a disk-encryption
 *       probe every 7 s, the E3 cadence. At b599d4d7e that started about 0.74 nvidia-smi and 0.14
 *       PowerShell processes per second; now NVML answers in process and the disk state is cached.
 *   <li>{@code inproc}: the same GPU readings with no process: the NVML read alone every 2.7 s.
 * </ul>
 *
 * <ul>
 *   <li>{@code nosink}: no probes, and the helper owner records discarded instead of written.
 * </ul>
 *
 * <p>Arms are interleaved round by round, in alternating order, so drift on a shared host spreads
 * over all of them; use an even number of rounds. Run:
 * {@code JS_PROBE_CHURN_MEASURE=1 gradlew :modules:app-engine:test --tests *ProbeChurnPrimaryRate*}.
 * {@code JS_PROBE_CHURN_ARMS=off,nosink JS_PROBE_CHURN_ASSERT=1} turns it into check (c), which
 * asserts the ledger's cost directly: in every {@code off} trial the helper owner's ledger writes
 * take at most {@link #MAX_WRITE_SHARE} of the trial's indexing time, and their number stays within
 * {@link #MAX_HELPER_RECORDS_PER_TRIAL} however many documents the trial indexes (a trial is one
 * burst; b599d4d7e wrote two forced records per document). The ledger writes counted are every
 * record the trial adds to the ledger: the Engine's helper owner records, timed as they happen, and
 * the rest (the Engine job witness's process records and the extraction child's own records, the
 * child being another process), read back after the trial and replayed, every one forced, through a
 * writer on the same volume and timed there (an upper bound). The write
 * time counts the whole trial, Engine start and close included, against the indexing time alone, so
 * it errs high. The rate
 * ratio of {@code off} to {@code nosink} is printed, not asserted: on a shared host single trials
 * vary by a factor of two. Each trial prints the host's mean CPU load while it ran; the summary
 * prints free memory before and after.
 * Skipped in the normal suite: it is not run without {@code JS_PROBE_CHURN_MEASURE=1}.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "JS_PROBE_CHURN_MEASURE", matches = "1")
final class ProbeChurnPrimaryRateMeasurement {

  private static final int DOCS = intEnv("JS_PROBE_CHURN_DOCS", 1500);
  /** Largest share of a trial's indexing time the helper owner's ledger writes may take. */
  static final double MAX_WRITE_SHARE = 0.02;
  /** Most helper owner records one indexing burst may write, independent of its document count. */
  static final int MAX_HELPER_RECORDS_PER_TRIAL = 32;
  private static final int ROUNDS = intEnv("JS_PROBE_CHURN_ROUNDS", 3);

  @Test
  @Timeout(value = 3, unit = java.util.concurrent.TimeUnit.HOURS)
  void primaryRateWithAndWithoutProbeChurn(@TempDir Path temp) throws Exception {
    var containment = io.justsearch.app.api.runtime.containment.EngineContainment.installAtBoot();
    Path ledgerData = temp.resolve("ledger-data");
    Files.createDirectories(ledgerData);
    io.justsearch.app.api.runtime.child.DefaultChildControl.openForEngine(ledgerData, "probe-churn-measure",
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        io.justsearch.app.api.runtime.child.VramProbe.none(), containment);
    List<Path> files = corpus(temp.resolve("corpus"), DOCS);
    List<Path> warmup = corpus(temp.resolve("warmup-corpus"), 200);
    String armList = System.getenv("JS_PROBE_CHURN_ARMS");
    List<String> arms = armList == null || armList.isBlank() ? List.of("off", "today", "inproc")
        : List.of(armList.trim().split(","));
    var recorderField = io.justsearch.app.api.runtime.child.OwnedHelper.class.getDeclaredField("recorder");
    recorderField.setAccessible(true);
    Object realRecorder = recorderField.get(null);
    Map<String, List<Double>> rates = new LinkedHashMap<>();
    Map<String, List<Integer>> processes = new LinkedHashMap<>();
    List<String> costFailures = new ArrayList<>();
    arms.forEach(a -> { rates.put(a, new ArrayList<>()); processes.put(a, new ArrayList<>()); });
    var os = (com.sun.management.OperatingSystemMXBean)
        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    long freeBefore = os.getFreeMemorySize();
    trial(temp.resolve("warmup"), warmup, "off");
    for (int round = 0; round < ROUNDS; round++) {
      // Alternate the order (ABBA) so warm-up drift within a run does not favour one arm.
      List<String> order = round % 2 == 0 ? arms : arms.reversed();
      for (String arm : order) {
        Map<String, Integer> before = ledgerTypes(ledgerData);
        java.util.Set<String> linesBefore = ledgerLines(ledgerData);
        // "nosink": the same indexing with helper owner receipts discarded instead of forced to disk.
        if (arm.equals("nosink")) {
          io.justsearch.app.api.runtime.child.OwnedHelper.recordTo((type, fields) -> true,
              new io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity(
                  ProcessHandle.current().pid(), java.util.Optional.empty(), "nosink"));
        }
        // "off": the real ledger, with the time spent writing helper owner records measured.
        RecordTiming timing = new RecordTiming();
        if (arm.equals("off") && realRecorder != null) recorderField.set(null, timing.wrap(realRecorder));
        double[] result;
        try {
          result = trial(temp.resolve("r" + round + "-" + arm), files, arm);
        } finally {
          recorderField.set(null, realRecorder);
        }
        if (arm.equals("off")) {
          timing.replayUntimedRecords(ledgerData, linesBefore, temp.resolve("replay-r" + round));
        }
        rates.get(arm).add(result[0]);
        processes.get(arm).add((int) result[1]);
        System.out.printf("PROBE-CHURN round=%d arm=%s docs=%d seconds=%.2f rate=%.2f probeLaunches=%d"
            + " hostCpuLoad=%.2f ledgerRecords=%s helperRecordWrites=%s containment=%s%n", round, arm,
            files.size(), files.size() / result[0], result[0], (int) result[1], result[2],
            delta(before, ledgerTypes(ledgerData)), timing, containment.status());
        if (arm.equals("off")) {
          double seconds = files.size() / result[0];
          double share = timing.seconds() / seconds;
          if (share > MAX_WRITE_SHARE || timing.writes() > MAX_HELPER_RECORDS_PER_TRIAL) {
            costFailures.add(String.format(java.util.Locale.ROOT,
                "round %d: %d ledger records (%.2f per document) took %.3f s of %.2f s indexing (%.2f%%)",
                round, timing.writes(), timing.writes() / (double) files.size(), timing.seconds(), seconds,
                100 * share));
          }
        }
      }
    }
    rates.forEach((arm, values) -> System.out.printf("PROBE-CHURN summary arm=%s rates=%s median=%.2f%n",
        arm, values, median(values)));
    System.out.printf(java.util.Locale.ROOT, "PROBE-CHURN machine freeMemoryGbBefore=%.2f freeMemoryGbAfter=%.2f cpus=%d%n",
        freeBefore / 1e9, os.getFreeMemorySize() / 1e9, Runtime.getRuntime().availableProcessors());
    if (rates.containsKey("off") && rates.containsKey("nosink")) {
      // Reported, not asserted: the rates move with other load on the host.
      System.out.printf(java.util.Locale.ROOT, "PROBE-CHURN ratio off/nosink=%.3f%n",
          median(rates.get("off")) / median(rates.get("nosink")));
    }
    if ("1".equals(System.getenv("JS_PROBE_CHURN_ASSERT"))) {
      // Check (c): the ledger's cost to indexing, measured directly in each ledger-on trial.
      assertTrue(rates.containsKey("off"), "check (c) needs the 'off' arm (the real ledger)");
      assertTrue(realRecorder != null, "check (c) needs the Engine's helper ledger to be open");
      assertTrue(costFailures.isEmpty(), String.format(java.util.Locale.ROOT,
          "ledger writes must take at most %.0f%% of indexing time and at most %d records per"
              + " burst, whatever the document count:%n  %s", 100 * MAX_WRITE_SHARE,
          MAX_HELPER_RECORDS_PER_TRIAL, String.join("\n  ", costFailures)));
    }
  }

  /** Index {@code files} into a fresh Engine under the arm's probe load; [docs/s, probe calls, mean host CPU load]. */
  private static double[] trial(Path dataDir, List<Path> files, String arm) throws Exception {
    try (EngineTestHarness harness = EngineTestHarness.start(dataDir)) {
      assertTrue(harness.client().isHealthy(TestEngineContexts.FOREGROUND));
      AtomicBoolean running = new AtomicBoolean(true);
      AtomicInteger calls = new AtomicInteger();
      Thread load = Thread.ofPlatform().daemon().name("probe-churn-load").start(
          () -> drive(arm, dataDir, running, calls));
      var os = (com.sun.management.OperatingSystemMXBean)
          java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      java.util.concurrent.atomic.DoubleAdder cpu = new java.util.concurrent.atomic.DoubleAdder();
      AtomicInteger samples = new AtomicInteger();
      Thread sampler = Thread.ofPlatform().daemon().name("probe-churn-cpu").start(() -> {
        while (running.get()) {
          double value = os.getCpuLoad();
          if (value >= 0) { cpu.add(value); samples.incrementAndGet(); }
          try { Thread.sleep(250); } catch (InterruptedException interrupted) { return; }
        }
      });
      try {
        long started = System.nanoTime();
        for (int i = 0; i < files.size(); i += 100) {
          harness.client().submitBatch(files.subList(i, Math.min(files.size(), i + 100)),
              TestEngineContexts.FOREGROUND);
        }
        assertTrue(harness.awaitIndexed(files.size(), 900_000), "trial " + arm + " did not finish");
        double seconds = (System.nanoTime() - started) / 1e9;
        return new double[] {files.size() / seconds, calls.get(),
            samples.get() == 0 ? Double.NaN : cpu.sum() / samples.get()};
      } finally {
        running.set(false);
        load.join(30_000);
        sampler.join(30_000);
      }
    }
  }

  /** The product's own probe code at the recorded E3 cadence. */
  private static void drive(String arm, Path dataDir, AtomicBoolean running, AtomicInteger calls) {
    if (!arm.equals("today") && !arm.equals("inproc")) return;
    var gpu = new io.justsearch.gpu.GpuCapabilitiesService();
    var nvml = new io.justsearch.gpu.NvmlService();
    long nextGpu = System.nanoTime();
    long nextDisk = System.nanoTime();
    while (running.get()) {
      long now = System.nanoTime();
      if (now >= nextGpu) {
        if (arm.equals("today")) gpu.snapshot();
        else nvml.probe();
        calls.incrementAndGet();
        nextGpu = now + 2_700_000_000L;
      }
      if (arm.equals("today") && now >= nextDisk) {
        new io.justsearch.app.services.atrest.DiskEncryptionProbe(dataDir).current();
        calls.incrementAndGet();
        nextDisk = now + 7_000_000_000L;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        return;
      }
    }
  }

  /** Deterministic unique documents of a few kilobytes: markdown, text and HTML. */
  private static List<Path> corpus(Path dir, int docs) throws Exception {
    Files.createDirectories(dir);
    List<Path> written = new ArrayList<>();
    java.util.Random random = new java.util.Random(101);
    String[] words = ("harbor ledger customs observatory storm manifest mill brume watch clock "
        + "tariff vessel cargo anchor tide lantern archive record survey chart signal").split(" ");
    for (int i = 0; i < docs; i++) {
      StringBuilder body = new StringBuilder();
      int paragraphs = 6 + random.nextInt(10);
      for (int p = 0; p < paragraphs; p++) {
        for (int w = 0; w < 60 + random.nextInt(60); w++) {
          body.append(words[random.nextInt(words.length)]).append(' ');
        }
        body.append("doc").append(i).append("p").append(p).append("\n\n");
      }
      String ext = switch (i % 3) { case 0 -> "md"; case 1 -> "txt"; default -> "html"; };
      String content = ext.equals("html")
          ? "<html><head><title>doc " + i + "</title></head><body><p>" + body + "</p></body></html>"
          : "# doc " + i + "\n\n" + body;
      Path file = dir.resolve(String.format("doc-%05d.%s", i, ext));
      Files.writeString(file, content, StandardCharsets.UTF_8);
      written.add(file);
    }
    return written;
  }

  /** Record type, executable image and purpose counts over every file of the ledger directory. */
  private static Map<String, Integer> ledgerTypes(Path data) throws Exception {
    Map<String, Integer> counts = new java.util.TreeMap<>();
    Path dir = io.justsearch.app.api.runtime.ledger.LedgerFamily.directory(data);
    if (!Files.isDirectory(dir)) return counts;
    var type = java.util.regex.Pattern.compile("\"type\":\"([^\"]+)\"");
    var exe = java.util.regex.Pattern.compile("\"(?:executable|purpose)\":\"([^\"]+)\"");
    try (var files = Files.walk(dir)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          var t = type.matcher(line);
          if (!t.find()) continue;
          var e = exe.matcher(line);
          String key = t.group(1) + (e.find() ? "|" + e.group(1).replaceAll(".*[\\\\/]", "") : "");
          counts.merge(key, 1, Integer::sum);
        }
      }
    }
    return counts;
  }

  private static Map<String, Integer> delta(Map<String, Integer> before, Map<String, Integer> after) {
    Map<String, Integer> d = new java.util.TreeMap<>();
    after.forEach((k, v) -> { int n = v - before.getOrDefault(k, 0); if (n != 0) d.put(k, n); });
    return d;
  }

  private static double median(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n == 0 ? Double.NaN : n % 2 == 1 ? sorted.get(n / 2)
        : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }

  /** Every line of every ledger file, to tell the lines a trial added. */
  private static java.util.Set<String> ledgerLines(Path data) throws Exception {
    java.util.Set<String> lines = new java.util.HashSet<>();
    Path dir = io.justsearch.app.api.runtime.ledger.LedgerFamily.directory(data);
    if (!Files.isDirectory(dir)) return lines;
    try (var files = Files.walk(dir)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
      }
    }
    return lines;
  }

  /**
   * Count and wall time of the ledger writes during one trial: the Engine's helper owner records,
   * timed as written, and every other record the trial added, replayed afterwards.
   */
  private static final class RecordTiming {
    private final java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong maxNanos = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicInteger writes = new AtomicInteger();
    private int replayedWrites;
    private long replayedNanos;

    /**
     * The records this trial added to the ledger other than the helper owner records timed as
     * written (the job witness's process records, the extraction child's records), written again
     * through a writer of the same kind on the same volume, each forced, and timed.
     */
    void replayUntimedRecords(Path data, java.util.Set<String> before, Path replay) throws Exception {
      long self = ProcessHandle.current().pid();
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      Files.createDirectories(replay);
      var writer = io.justsearch.app.api.runtime.ledger.ExitLedgerWriter.appendOnly(replay,
          io.justsearch.app.api.runtime.ledger.LedgerFamily.CHILDREN,
          io.justsearch.app.api.runtime.ledger.LedgerWriterId.currentProcess(
              io.justsearch.app.api.runtime.ledger.LedgerWriterId.Role.ENGINE, java.time.Instant.now()),
          java.time.Clock.systemUTC());
      for (String line : ledgerLines(data)) {
        if (before.contains(line) || line.isBlank()) continue;
        @SuppressWarnings("unchecked")
        Map<String, Object> record = json.readValue(line, Map.class);
        boolean timedLive = record.get("writer") instanceof Map<?, ?> writerId
            && writerId.get("pid") instanceof Number pid && pid.longValue() == self
            && String.valueOf(record.get("type")).startsWith("helper-");
        if (timedLive) continue; // the Engine's helper owner records are timed as they are written
        var type = io.justsearch.app.api.runtime.ledger.LedgerRecordType.fromToken(String.valueOf(record.get("type")));
        if (type.isEmpty()) continue;
        Map<String, Object> fields = new LinkedHashMap<>(record);
        List.of("schemaVersion", "type", "seq", "at", "writer").forEach(fields::remove);
        long started = System.nanoTime();
        if (!writer.append(type.get(), fields)) throw new IllegalStateException("replay write failed: " + line);
        replayedNanos += System.nanoTime() - started;
        replayedWrites++;
      }
    }

    /** A copy of OwnedHelper's private recorder whose sink is the real one, timed. */
    Object wrap(Object realRecorder) throws Exception {
      var type = realRecorder.getClass();
      var components = type.getRecordComponents();
      Object[] values = new Object[components.length];
      Class<?>[] types = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        var accessor = components[i].getAccessor();
        accessor.setAccessible(true);
        values[i] = accessor.invoke(realRecorder);
        types[i] = components[i].getType();
      }
      Object real = values[0];
      values[0] = java.lang.reflect.Proxy.newProxyInstance(
          io.justsearch.app.api.runtime.child.LedgerSink.class.getClassLoader(),
          new Class<?>[] {io.justsearch.app.api.runtime.child.LedgerSink.class},
          (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return method.invoke(real, args);
            long started = System.nanoTime();
            try {
              return method.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
              throw failure.getCause();
            } finally {
              // A group force (flush) is timed with the writes; only appends count as records.
              note(System.nanoTime() - started, method.getName().startsWith("append"));
            }
          });
      var constructor = type.getDeclaredConstructor(types);
      constructor.setAccessible(true);
      return constructor.newInstance(values);
    }

    private void note(long elapsed, boolean record) {
      if (record) writes.incrementAndGet();
      nanos.addAndGet(elapsed);
      maxNanos.accumulateAndGet(elapsed, Math::max);
    }

    /** Every ledger record of the trial: timed as written or replayed. */
    int writes() {
      return writes.get() + replayedWrites;
    }

    double seconds() {
      return (nanos.get() + replayedNanos) / 1e9;
    }

    @Override public String toString() {
      return String.format(java.util.Locale.ROOT, "%d/%.0fms/max%.0fms+replayed%d/%.0fms", writes.get(),
          nanos.get() / 1e6, maxNanos.get() / 1e6, replayedWrites, replayedNanos / 1e6);
    }
  }

  private static int intEnv(String name, int fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
  }
}
