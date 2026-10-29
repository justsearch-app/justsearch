/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.benchmarks.util.BenchmarkCli;
import io.justsearch.benchmarks.util.BenchmarkUtils;
import io.justsearch.benchmarks.util.MachineFingerprint;
import io.justsearch.reranker.CrossEncoderReranker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-encoder reranker deadline benchmark (Tier 2 uncertainty gate).
 *
 * <p>Measures latency and skip rate across candidate counts and deadline budgets to pick sane
 * defaults for RAG chunk reranking.
 */
public final class RerankerDeadlineBench {

  private static final Logger log = LoggerFactory.getLogger(RerankerDeadlineBench.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    // This standalone tokenizer JVM needs the same bundled CPU native flavour as the Engine.
    String flavor = io.justsearch.configuration.SystemAccess.rawEnvVar("RUST_FLAVOR");
    if ((flavor != null && !flavor.equals("cpu"))
        || io.justsearch.configuration.SystemAccess.rawEnvVar("RUST_LIBRARY_PATH") != null
        || io.justsearch.configuration.SystemAccess.rawSysProp("RUST_LIBRARY_PATH") != null) {
      throw new IllegalStateException(
          "Tokenizers require bundled CPU natives: remove RUST_LIBRARY_PATH and use RUST_FLAVOR=cpu");
    }
    io.justsearch.configuration.SystemAccess.setSysProp("RUST_FLAVOR", "cpu");
    io.justsearch.configuration.SystemAccess.setSysProp("ai.djl.util.cuda.fork", "true");
    String outDir = "tmp/bench/reranker";
    String modelDir = "models/reranker/ms-marco-MiniLM-L6-v2";
    int maxSeqLen = 512; // BERT/MiniLM max sequence length (model architecture limit)
    String candidateCountsCsv = "1,3,5,10";
    String docCharsCsv = "512,1024,2048";
    String deadlinesCsv = "50,100,150,250";
    int warmup = 3; // ONNX session warmup iterations (less than JIT since ONNX is pre-compiled)
    int iterations = 20; // Reranker latency samples per scenario
    boolean gpuEnabled = false;
    int gpuDeviceId = 0;

    for (String arg : args) {
      if (arg.startsWith("--out-dir=")) {
        outDir = arg.substring("--out-dir=".length());
      } else if (arg.startsWith("--model-dir=")) {
        modelDir = arg.substring("--model-dir=".length());
      } else if (arg.startsWith("--max-seq-len=")) {
        maxSeqLen = Integer.parseInt(arg.substring("--max-seq-len=".length()));
      } else if (arg.startsWith("--candidate-counts=")) {
        candidateCountsCsv = arg.substring("--candidate-counts=".length());
      } else if (arg.startsWith("--doc-chars=")) {
        docCharsCsv = arg.substring("--doc-chars=".length());
      } else if (arg.startsWith("--deadlines-ms=")) {
        deadlinesCsv = arg.substring("--deadlines-ms=".length());
      } else if (arg.startsWith("--warmup=")) {
        warmup = Integer.parseInt(arg.substring("--warmup=".length()));
      } else if (arg.startsWith("--iterations=")) {
        iterations = Integer.parseInt(arg.substring("--iterations=".length()));
      } else if (arg.equals("--gpu")) {
        gpuEnabled = true;
      } else if (arg.startsWith("--gpu-device-id=")) {
        gpuDeviceId = Integer.parseInt(arg.substring("--gpu-device-id=".length()));
      }
    }

    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);

    Path modelPath = Paths.get(modelDir).toAbsolutePath();
    Path onnx = modelPath.resolve("model.onnx");
    Path tok = modelPath.resolve("tokenizer.json");
    if (!Files.exists(onnx) || !Files.exists(tok)) {
      throw new IllegalArgumentException("Model files not found under: " + modelPath);
    }

    List<Integer> candidateCounts = BenchmarkCli.parsePositiveIntCsv(candidateCountsCsv);
    List<Integer> docChars = BenchmarkCli.parsePositiveIntCsv(docCharsCsv);
    List<Integer> deadlines = BenchmarkCli.parsePositiveIntCsv(deadlinesCsv);

    String query = "What is the main idea of the provided context?";

    log.info(
        "Loading reranker: model={} maxSeqLen={} gpuEnabled={} gpuDeviceId={}",
        modelPath,
        maxSeqLen,
        gpuEnabled,
        gpuDeviceId);
    long gpuMemMb =
        io.justsearch.configuration.resolved.ConfigStore.globalOrNull() != null
            ? io.justsearch.configuration.resolved.ConfigStore.global()
                .get()
                .ai()
                .reranker()
                .gpuMemMb()
            : 2048L;
    // Tempdoc 397 §14.28 U1: composeRerankFallback deleted. Benchmarks construct the
    // Composition inline rather than depend on testFixtures from a main source set (benchmarks
    // are main-scope, so consuming testFixtures is architecturally awkward). This mirrors what
    // InferenceCompositionRootTestHelper.sessionFor does internally.
    io.justsearch.configuration.model.VariantSelection variant =
        io.justsearch.ort.DevModeVariantProbe.probe(modelPath, gpuEnabled);
    if (variant == null) {
      throw new IllegalStateException("No loadable ONNX model under " + modelPath);
    }
    io.justsearch.ort.GpuSessionConfig gpuSessionConfig =
        gpuEnabled ? new io.justsearch.ort.GpuSessionConfig(gpuDeviceId, gpuMemMb * 1024L * 1024) : null;
    ai.onnxruntime.OrtSession.SessionOptions.OptLevel cpuOptLevel =
        io.justsearch.ort.ModelSessionPolicyResolver.deriveCpuOptLevel(
            variant.precision(), io.justsearch.configuration.model.ExecutionProvider.CPU);
    io.justsearch.ort.ModelSessionPolicy policy =
        io.justsearch.ort.ModelSessionPolicy.forFallback(
            gpuSessionConfig,
            cpuOptLevel,
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ gpuEnabled,
            /* gpuRetryIntervalMs= */ 60_000L);
    io.justsearch.ort.Composition comp =
        new io.justsearch.ort.Composition(
            io.justsearch.ort.RuntimePolicy.defaults(),
            policy,
            new io.justsearch.ort.ModelArtifacts(variant.modelFile(), variant.modelFile()));
    final boolean gpuFinal = gpuEnabled;
    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.OrtSessionAssembler.buildManager("reranker-bench", comp, () -> gpuFinal);
    io.justsearch.reranker.RerankerAssembly assembly =
        CrossEncoderReranker.buildAssembly(sessions, tok, maxSeqLen);
    try (CrossEncoderReranker reranker =
        new CrossEncoderReranker(assembly.sessions(), assembly.shape(), assembly.tokenizer())) {

      List<Map<String, Object>> scenarios = new ArrayList<>();

      for (int c : candidateCounts) {
        for (int len : docChars) {
          List<String> docs = syntheticDocs(c, len);
          for (int deadline : deadlines) {
            scenarios.add(runScenario(reranker, query, docs, c, len, deadline, warmup, iterations));
          }
        }
      }

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("schema_version", 1);
      out.put("kind", "reranker-deadline-bench.v1");
      out.put("captured_at", Instant.now().toString());

      out.put("machine_fingerprint", MachineFingerprint.capture().toMap());

      Map<String, Object> knobs = new LinkedHashMap<>();
      knobs.put("model_dir", modelPath.toString());
      knobs.put("max_seq_len", maxSeqLen);
      knobs.put("gpu_enabled", gpuEnabled);
      knobs.put("gpu_device_id", gpuDeviceId);
      knobs.put("warmup", warmup);
      knobs.put("iterations", iterations);
      knobs.put("candidate_counts", candidateCountsCsv);
      knobs.put("doc_chars", docCharsCsv);
      knobs.put("deadlines_ms", deadlinesCsv);
      out.put("knobs", knobs);

      out.put("scenarios", scenarios);

      Path jsonPath = outPath.resolve("result.json");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), out);
      log.info("Wrote result to: {}", jsonPath);

      Path mdPath = outPath.resolve("summary.md");
      Files.writeString(mdPath, renderMarkdown(out), StandardCharsets.UTF_8);
      log.info("Wrote summary to: {}", mdPath);
    }
  }

  private static Map<String, Object> runScenario(
      CrossEncoderReranker reranker,
      String query,
      List<String> docs,
      int candidateCount,
      int docChars,
      int deadlineMs,
      int warmup,
      int iterations) {

    for (int i = 0; i < warmup; i++) {
      reranker.rerank(query, docs, deadlineMs);
    }

    List<Long> latencyMs = new ArrayList<>(iterations);
    int skipped = 0;
    for (int i = 0; i < iterations; i++) {
      var r = reranker.rerank(query, docs, deadlineMs);
      latencyMs.add(r.latencyMs());
      if (r.skipped()) skipped++;
    }

    // Note: BenchmarkUtils.percentileLong uses R-7 linear interpolation (fixed algorithm)
    double p50 = BenchmarkUtils.percentileLong(latencyMs, 0.50);
    double p95 = BenchmarkUtils.percentileLong(latencyMs, 0.95);
    double p99 = BenchmarkUtils.percentileLong(latencyMs, 0.99);

    Map<String, Object> lat = new LinkedHashMap<>();
    lat.put("unit", "ms");
    lat.put("min", latencyMs.getFirst());
    lat.put("p50", p50);
    lat.put("p95", p95);
    lat.put("p99", p99);
    lat.put("max", latencyMs.getLast());
    lat.put("samples", iterations);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("candidate_count", candidateCount);
    out.put("doc_chars", docChars);
    out.put("deadline_ms", deadlineMs);
    out.put("skipped", skipped);
    out.put("skip_rate", Math.round((skipped * 1_000.0) / iterations) / 1_000.0);
    out.put("latency", lat);
    return out;
  }

  private static List<String> syntheticDocs(int count, int targetChars) {
    List<String> out = new ArrayList<>(count);
    // Random seed 42: Deterministic synthetic document generation for reproducibility
    Random rnd = new Random(42);
    for (int i = 0; i < count; i++) {
      StringBuilder sb = new StringBuilder(Math.max(0, targetChars));
      sb.append("Document ").append(i).append(". ");
      while (sb.length() < targetChars) {
        sb.append("This sentence provides context about topic ");
        sb.append((char) ('A' + rnd.nextInt(26)));
        sb.append(". ");
      }
      out.add(sb.substring(0, Math.min(sb.length(), targetChars)));
    }
    return out;
  }

  private static String renderMarkdown(Map<String, Object> out) {
    @SuppressWarnings("unchecked")
    Map<String, Object> knobs = (Map<String, Object>) out.getOrDefault("knobs", Map.of());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> scenarios = (List<Map<String, Object>>) out.getOrDefault("scenarios", List.of());

    StringBuilder sb = new StringBuilder();
    sb.append("# Reranker Deadline Benchmark\n\n");
    sb.append("- captured_at: ").append(out.get("captured_at")).append("\n");
    sb.append("- model_dir: ").append(knobs.get("model_dir")).append("\n");
    sb.append("- max_seq_len: ").append(knobs.get("max_seq_len")).append("\n");
    sb.append("- gpu_enabled: ").append(knobs.get("gpu_enabled")).append("\n");
    sb.append("- gpu_device_id: ").append(knobs.get("gpu_device_id")).append("\n");
    sb.append("- iterations: ").append(knobs.get("iterations")).append("\n\n");

    sb.append("| candidates | doc_chars | deadline_ms | skip_rate | p50_ms | p95_ms | p99_ms | max_ms |\n");
    sb.append("|---:|---:|---:|---:|---:|---:|---:|---:|\n");
    for (Map<String, Object> s : scenarios) {
      @SuppressWarnings("unchecked")
      Map<String, Object> lat = (Map<String, Object>) s.getOrDefault("latency", Map.of());
      sb.append("| ")
          .append(s.getOrDefault("candidate_count", 0))
          .append(" | ")
          .append(s.getOrDefault("doc_chars", 0))
          .append(" | ")
          .append(s.getOrDefault("deadline_ms", 0))
          .append(" | ")
          .append(s.getOrDefault("skip_rate", 0))
          .append(" | ")
          .append(lat.getOrDefault("p50", 0))
          .append(" | ")
          .append(lat.getOrDefault("p95", 0))
          .append(" | ")
          .append(lat.getOrDefault("p99", 0))
          .append(" | ")
          .append(lat.getOrDefault("max", 0))
          .append(" |\n");
    }
    return sb.toString();
  }
}
