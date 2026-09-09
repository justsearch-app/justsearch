package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-family in-process vs out-of-process extraction latency (tempdoc 885 item 14, design decision
 * 2's "the round-trip cost must stay under 10 ms per file for the in-process families").
 *
 * <p>Unit-level measurement, not a live one: it drives both sandboxes over the repo's own fixtures
 * and prints a p50/p95 table plus the text-family round-trip overhead for the tempdoc. It asserts
 * only that both paths were measured — a wall-clock threshold would be load-flaky on a box running
 * several agent worktrees, so the 10 ms criterion is judged from the recorded table.
 */
final class ExtractionSandboxLatencyBenchmarkTest {

  private static final int WARMUP = 3;
  private static final int SAMPLES = 15;

  @TempDir Path tempDir;

  @Test
  @Timeout(600)
  void perFamilyLatencyTable() throws Exception {
    Map<String, Path> corpus = new LinkedHashMap<>();
    corpus.put("text", write("notes.txt", "lorem ipsum dolor sit amet\n".repeat(400)));
    corpus.put("markdown", write("readme.md", "# Heading\n\nSome **bold** text.\n".repeat(200)));
    corpus.put("code", write("App.java", "class App { void run() {} }\n".repeat(200)));
    corpus.put("json", write("data.json", "{\"a\":[1,2,3],\"b\":\"c\"}"));
    // "Nasty" rows from the tempdoc-410 adversarial corpus that survive to the parser.
    corpus.put("nasty_long_line", write("long-line.txt", "x".repeat(64 * 1024)));
    corpus.put("nasty_empty", write("empty.txt", ""));
    corpus.put("pdf", copyFixture("/fixtures/pdf/pdf-text-layer.pdf", "doc.pdf"));
    corpus.put("office_docx", copyFixture("/fixtures/office/structured-test.docx", "doc.docx"));
    corpus.put("office_xlsx", copyFixture("/fixtures/office/office-marker.xlsx", "sheet.xlsx"));
    corpus.put("office_pptx", copyFixture("/fixtures/office/office-marker.pptx", "deck.pptx"));

    ExtractionSandbox inProcess =
        new InProcessExtractionSandbox(
            new PolicyDrivenTikaExtractor(
                TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled()));

    StringBuilder table = new StringBuilder();
    table.append("\n| family | in_process p50 | in_process p95 | process p50 | process p95 |\n");
    table.append("|---|---|---|---|---|\n");
    boolean textMeasured = false;
    double textRoundTripOverheadMs = 0.0d;

    try (PersistentExtractionSandbox outOfProcess =
        new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), ""),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(60),
            1,
            10_000,
            null)) {
      for (Map.Entry<String, Path> entry : corpus.entrySet()) {
        double[] in = measure(inProcess, entry.getValue());
        double[] out = measure(outOfProcess, entry.getValue());
        table.append(
            String.format(
                "| %s | %.2f ms | %.2f ms | %.2f ms | %.2f ms |%n",
                entry.getKey(), in[0], in[1], out[0], out[1]));
        if ("text".equals(entry.getKey())) {
          textRoundTripOverheadMs = out[0] - in[0];
          textMeasured = true;
        }
      }
    }

    // Printed for the tempdoc's measurement table; the reviewer re-runs this test to reproduce it.
    table.append(
        String.format(
            "%ntext-family round-trip overhead (process p50 - in_process p50): %.2f ms "
                + "(design decision 2 criterion: < 10 ms)%n",
            textRoundTripOverheadMs));
    System.out.println("[885 item 14] extraction latency by family" + table);

    // Deliberately NOT asserting the 10 ms criterion: this runs on a box that may be building
    // three other worktrees, and a wall-clock threshold here would be a load-flaky test rather
    // than a useful one. The criterion is judged from the recorded table (tempdoc 885 SB.4).
    assertTrue(textMeasured, "the text family must have been measured on both paths");
  }

  private double[] measure(ExtractionSandbox sandbox, Path file) throws Exception {
    for (int i = 0; i < WARMUP; i++) {
      extractQuietly(sandbox, file);
    }
    List<Double> samples = new ArrayList<>(SAMPLES);
    for (int i = 0; i < SAMPLES; i++) {
      long start = System.nanoTime();
      extractQuietly(sandbox, file);
      samples.add((System.nanoTime() - start) / 1_000_000.0d);
    }
    samples.sort(Double::compare);
    return new double[] {percentile(samples, 50), percentile(samples, 95)};
  }

  private static void extractQuietly(ExtractionSandbox sandbox, Path file) throws Exception {
    try {
      sandbox.extract(file);
    } catch (ContentExtractor.ExtractionException e) {
      // A family that fails to parse still has a meaningful round-trip cost; the table measures
      // the boundary, not the parser's verdict.
    }
  }

  private static double percentile(List<Double> sorted, int p) {
    int index = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0d * sorted.size()) - 1);
    return sorted.get(Math.max(0, index));
  }

  private Path write(String name, String content) throws IOException {
    Path target = tempDir.resolve(name);
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return target;
  }

  private Path copyFixture(String resource, String name) throws IOException {
    Path target = tempDir.resolve(name);
    try (InputStream in = ExtractionSandboxLatencyBenchmarkTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Missing test fixture: " + resource);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }
}
