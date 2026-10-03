/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.benchmarks.util.BenchmarkUtils;
import io.justsearch.benchmarks.util.MachineFingerprint;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.ort.Composition;
import io.justsearch.ort.DevModeVariantProbe;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelManifest;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Throughput sweep: embedding + SPLADE ORT session.run() cost vs batch size and GPU arena cap.
 *
 * <p>Tempdoc 691: production hardcodes {@code OnnxEmbeddingEncoder.MAX_ORT_BATCH_SIZE = 8} and
 * {@code SpladeEncoder.MAX_SPLADE_BATCH_SIZE_GPU = 4}. This bench constructs the same padded
 * input tensors those encoders build (see {@code embedPreTokenizedBatch} /
 * {@code encodeBatchInternal}) and calls {@link OrtSession#run} directly, bypassing the
 * encoders' internal sub-batching, to measure batch sizes above the shipped caps.
 *
 * <p>Session construction inlines the same pattern as {@code RerankerDeadlineBench} (probe →
 * {@code GpuSessionConfig} → {@code ModelSessionPolicy.forFallback} → {@code Composition} →
 * {@code OrtSessionAssembler.buildManager}) rather than depending on {@code
 * InferenceCompositionRootTestHelper} (testFixtures) from this main-scope module — same
 * rationale recorded in {@code RerankerDeadlineBench}'s tempdoc 397 §14.28 U1 comment.
 *
 * <p>One {@link SessionHandle} is created per (encoder, arena) pair and reused across every
 * batch size in that arena's sweep, matching the production pattern of one long-lived session
 * per encoder.
 */
public final class EncoderBatchSweepBench {

  private static final Logger log = LoggerFactory.getLogger(EncoderBatchSweepBench.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int GPU_DEVICE_ID = 0;

  private EncoderBatchSweepBench() {}

  /** One (encoder, batch, arena) measurement — an OOM cell is a data point, not a failure. */
  private record CellResult(
      String encoder,
      int batch,
      long arenaMb,
      int seqLen,
      boolean gpuConfirmed,
      boolean oom,
      String oomDetail,
      double p50Ms,
      double meanMs,
      double chunksPerSec,
      Long vramUsedMbAfter) {

    Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("encoder", encoder);
      m.put("batch", batch);
      m.put("arena_mb", arenaMb);
      m.put("seq_len", seqLen);
      m.put("gpu_confirmed", gpuConfirmed);
      m.put("oom", oom);
      m.put("oom_detail", oomDetail);
      m.put("p50_ms", BenchmarkUtils.round3(p50Ms));
      m.put("mean_ms", BenchmarkUtils.round3(meanMs));
      m.put("chunks_per_sec", BenchmarkUtils.round2(chunksPerSec));
      m.put("vram_used_mb_after", vramUsedMbAfter);
      return m;
    }
  }

  public static void main(String[] args) throws Exception {
    String outDir = "tmp/bench/encoder-batch-sweep";
    String embedModelDir = "models/onnx/gte-multilingual-base";
    String spladeModelDir = "models/splade/naver-splade-v3";
    int seqLen = 512;
    int warmup = 3;
    int iterations = 10;
    List<Integer> embedBatches = List.of(8, 16, 32, 64);
    List<Long> embedArenasMb = List.of(3072L, 4096L);
    List<Integer> spladeBatches = List.of(4, 8, 16);
    List<Long> spladeArenasMb = List.of(4096L);

    for (String arg : args) {
      if (arg.startsWith("--out-dir=")) {
        outDir = arg.substring("--out-dir=".length());
      } else if (arg.startsWith("--embed-model-dir=")) {
        embedModelDir = arg.substring("--embed-model-dir=".length());
      } else if (arg.startsWith("--splade-model-dir=")) {
        spladeModelDir = arg.substring("--splade-model-dir=".length());
      } else if (arg.startsWith("--seq-len=")) {
        seqLen = Integer.parseInt(arg.substring("--seq-len=".length()));
      } else if (arg.startsWith("--warmup=")) {
        warmup = Integer.parseInt(arg.substring("--warmup=".length()));
      } else if (arg.startsWith("--iterations=")) {
        iterations = Integer.parseInt(arg.substring("--iterations=".length()));
      } else if (arg.startsWith("--embed-batches=")) {
        embedBatches = parseIntCsv(arg.substring("--embed-batches=".length()));
      } else if (arg.startsWith("--embed-arenas-mb=")) {
        embedArenasMb = parseLongCsv(arg.substring("--embed-arenas-mb=".length()));
      } else if (arg.startsWith("--splade-batches=")) {
        spladeBatches = parseIntCsv(arg.substring("--splade-batches=".length()));
      } else if (arg.startsWith("--splade-arenas-mb=")) {
        spladeArenasMb = parseLongCsv(arg.substring("--splade-arenas-mb=".length()));
      }
    }

    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);
    Path jsonlPath = outPath.resolve("cells.jsonl");

    Long vramIdleMb = queryVramUsedMb();
    log.info("Idle VRAM before any session: {} MB", vramIdleMb);

    List<CellResult> allResults = new ArrayList<>();
    try (BufferedWriter jsonl = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8)) {
      log.info("=== EMBED sweep: {} ===", embedModelDir);
      allResults.addAll(
          sweepEncoder(
              "embed",
              Paths.get(embedModelDir).toAbsolutePath(),
              embedBatches,
              embedArenasMb,
              seqLen,
              warmup,
              iterations,
              /* skipHigherArenaIfNoOom= */ true,
              jsonl));

      log.info("=== SPLADE sweep: {} ===", spladeModelDir);
      allResults.addAll(
          sweepEncoder(
              "splade",
              Paths.get(spladeModelDir).toAbsolutePath(),
              spladeBatches,
              spladeArenasMb,
              seqLen,
              warmup,
              iterations,
              /* skipHigherArenaIfNoOom= */ false,
              jsonl));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schema_version", 1);
    out.put("kind", "encoder-batch-sweep-bench.v1");
    out.put("captured_at", Instant.now().toString());
    out.put("machine_fingerprint", MachineFingerprint.capture().toMap());

    Map<String, Object> knobs = new LinkedHashMap<>();
    knobs.put("embed_model_dir", embedModelDir);
    knobs.put("splade_model_dir", spladeModelDir);
    knobs.put("seq_len", seqLen);
    knobs.put("warmup", warmup);
    knobs.put("iterations", iterations);
    knobs.put("embed_batches", embedBatches);
    knobs.put("embed_arenas_mb", embedArenasMb);
    knobs.put("splade_batches", spladeBatches);
    knobs.put("splade_arenas_mb", spladeArenasMb);
    knobs.put("vram_idle_mb", vramIdleMb);
    out.put("knobs", knobs);

    out.put("cells", allResults.stream().map(CellResult::toMap).toList());

    Path jsonPath = outPath.resolve("result.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), out);
    log.info("Wrote result to: {}", jsonPath);

    Path mdPath = outPath.resolve("summary.md");
    Files.writeString(mdPath, renderMarkdown(out, allResults), StandardCharsets.UTF_8);
    log.info("Wrote summary to: {}", mdPath);
  }

  /**
   * Sweeps one encoder's model across arenas x batches. One {@link SessionHandle} is built per
   * arena and reused across all batch sizes in {@code batches} (matches the production pattern
   * of one long-lived session per encoder). If {@code skipHigherArenaIfNoOom} and the first
   * (lowest) arena never OOMs across every batch, subsequent (higher) arenas are skipped.
   */
  private static List<CellResult> sweepEncoder(
      String encoderLabel,
      Path modelDir,
      List<Integer> batches,
      List<Long> arenasMb,
      int seqLen,
      int warmup,
      int iterations,
      boolean skipHigherArenaIfNoOom,
      BufferedWriter jsonl)
      throws Exception {
    List<CellResult> results = new ArrayList<>();

    long[] seedIds = buildSeedIds(seqLen);

    Path probeModelFile = ModelManifest.loadOrDefault(modelDir).resolveExistingModelFile(modelDir);

    boolean anyOomSoFar = false;
    for (int arenaIdx = 0; arenaIdx < arenasMb.size(); arenaIdx++) {
      long arenaMb = arenasMb.get(arenaIdx);
      if (arenaIdx > 0 && skipHigherArenaIfNoOom && !anyOomSoFar) {
        log.info(
            "{}: skipping arena={}MB — lower arena hit zero OOMs across all batches",
            encoderLabel,
            arenaMb);
        continue;
      }

      try (SessionHandle sessions = buildSession(encoderLabel + "-sweep", modelDir, arenaMb)) {
        OrtSessionAssembler.ProbedNames probed =
            OrtSessionAssembler.probeModelNames(sessions.environment(), probeModelFile);
        boolean needsTokenTypeIds = probed.inputs().contains("token_type_ids");

        for (int batch : batches) {
          CellResult r =
              runCell(
                  encoderLabel, sessions, needsTokenTypeIds, seedIds, seqLen, batch, arenaMb,
                  warmup, iterations);
          results.add(r);
          if (r.oom()) {
            anyOomSoFar = true;
          }
          log.info(
              "{} batch={} arena={}MB -> gpu={} oom={} p50={}ms mean={}ms chunks/s={} vramMb={}",
              encoderLabel,
              batch,
              arenaMb,
              r.gpuConfirmed(),
              r.oom(),
              r.p50Ms(),
              r.meanMs(),
              r.chunksPerSec(),
              r.vramUsedMbAfter());
          jsonl.write(MAPPER.writeValueAsString(r.toMap()));
          jsonl.newLine();
          jsonl.flush();
        }

        Long vramAfterArena = queryVramUsedMb();
        log.info("{}: arena={}MB VRAM peak (post-largest-batch) = {} MB",
            encoderLabel, arenaMb, vramAfterArena);
      }
    }
    return results;
  }

  private static CellResult runCell(
      String encoderLabel,
      SessionHandle sessions,
      boolean needsTokenTypeIds,
      long[] seedIds,
      int seqLen,
      int batch,
      long arenaMb,
      int warmup,
      int iterations)
      throws IOException {
    long[][] allIds = repeatRow(seedIds, batch);
    long[][] allMask = repeatRow(onesArray(seqLen), batch);
    long[][] allType = repeatRow(new long[seqLen], batch);
    long[] shape = {batch, seqLen};
    OrtEnvironment env = sessions.environment();

    List<Long> timingsNs = new ArrayList<>(iterations);
    boolean oom = false;
    String oomDetail = null;
    boolean lastLeaseIsCpu = true;

    try {
      for (int i = 0; i < warmup + iterations; i++) {
        try (OnnxTensor inputIdsTensor =
                OnnxTensor.createTensor(env, flatten(allIds, batch, seqLen), shape);
            OnnxTensor attentionMaskTensor =
                OnnxTensor.createTensor(env, flatten(allMask, batch, seqLen), shape);
            OnnxTensor tokenTypeIdsTensor =
                needsTokenTypeIds
                    ? OnnxTensor.createTensor(env, flatten(allType, batch, seqLen), shape)
                    : null) {

          Map<String, OnnxTensor> inputs = new HashMap<>();
          inputs.put("input_ids", inputIdsTensor);
          inputs.put("attention_mask", attentionMaskTensor);
          if (tokenTypeIdsTensor != null) {
            inputs.put("token_type_ids", tokenTypeIdsTensor);
          }

          try (var lease = sessions.acquire(SessionAcquisitionRequest.within(
              SessionAcquisitionRequest.Urgency.BACKGROUND, java.time.Duration.ofMinutes(2)))) {
            lastLeaseIsCpu = lease.isCpu();
            long t0 = System.nanoTime();
            OrtSession.Result result = lease.session().run(inputs, lease.runOptions());
            // The bench measures the run, not the outputs; the resource block exists only to free
            // the native tensors before the next iteration.
            try (result) {
              long elapsed = System.nanoTime() - t0;
              if (i >= warmup) {
                timingsNs.add(elapsed);
              }
            }
          }
        }
      }
    } catch (OrtException e) {
      if (NativeSessionHandle.isBfcArenaFailure(e)) {
        oom = true;
        oomDetail = summarizeOomMessage(e.getMessage());
        log.info(
            "{}: OOM at batch={} arena={}MB: {}", encoderLabel, batch, arenaMb, oomDetail);
      } else {
        throw new RuntimeException(
            "Non-OOM OrtException for " + encoderLabel + " batch=" + batch + " arena=" + arenaMb,
            e);
      }
    }

    Long vramMb = queryVramUsedMb();

    if (oom || timingsNs.isEmpty()) {
      return new CellResult(
          encoderLabel, batch, arenaMb, seqLen, !lastLeaseIsCpu, true, oomDetail, 0, 0, 0, vramMb);
    }

    double p50Ms = BenchmarkUtils.percentileLong(timingsNs, 0.50) / 1_000_000.0;
    double meanMs =
        timingsNs.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
    double chunksPerSec = meanMs > 0 ? (batch * 1000.0 / meanMs) : 0;

    return new CellResult(
        encoderLabel,
        batch,
        arenaMb,
        seqLen,
        !lastLeaseIsCpu,
        false,
        null,
        p50Ms,
        meanMs,
        chunksPerSec,
        vramMb);
  }

  /** Inlines the same session-build pattern as RerankerDeadlineBench (tempdoc 397 §14.28 U1). */
  private static SessionHandle buildSession(String consumerName, Path modelDir, long gpuMemMb)
      throws OrtException {
    VariantSelection variant = DevModeVariantProbe.probe(modelDir, /* gpuEnabled= */ true);
    if (variant == null) {
      throw new IllegalStateException("No loadable ONNX model under " + modelDir);
    }
    GpuSessionConfig gpuSessionConfig =
        new GpuSessionConfig(GPU_DEVICE_ID, gpuMemMb * 1024L * 1024L);
    OrtSession.SessionOptions.OptLevel cpuOptLevel =
        ModelSessionPolicyResolver.deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU);
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            gpuSessionConfig,
            cpuOptLevel,
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ true,
            /* gpuRetryIntervalMs= */ 60_000L);
    Composition comp =
        new Composition(
            RuntimePolicy.defaults(), policy, new ModelArtifacts(variant.modelFile(), variant.modelFile()));
    return OrtSessionAssembler.buildManager(consumerName, comp, () -> true);
  }

  // Safe low-range token ids: below the smallest real-world subword vocab size in this repo
  // (SPLADE's multilingual-BERT-derived vocab is ~105879 entries; the embed model's SentencePiece
  // Unigram vocab is ~207k) and above the reserved special-token block both tokenizer families
  // keep at the front (BERT special/[unused] ids, XLM-R <unk>/<s>/</s>/<pad>). Per the task's
  // explicit allowance ("plausible random token ids in-vocab — timing is shape-driven"): ORT
  // session.run() cost is a function of tensor shape, not token *identity*, so synthetic ids in
  // this range reproduce the real per-batch compute cost without needing a tokenizer dependency
  // in this main-scope benchmarks module.
  private static final int SEED_ID_MIN = 1000;
  private static final int SEED_ID_MAX = 2000;

  private static long[] buildSeedIds(int seqLen) {
    java.util.Random rnd = new java.util.Random(42);
    long[] out = new long[seqLen];
    for (int i = 0; i < seqLen; i++) {
      out[i] = (long) SEED_ID_MIN + rnd.nextInt(SEED_ID_MAX - SEED_ID_MIN);
    }
    return out;
  }

  private static long[] onesArray(int len) {
    long[] a = new long[len];
    java.util.Arrays.fill(a, 1L);
    return a;
  }

  private static long[][] repeatRow(long[] row, int times) {
    long[][] out = new long[times][];
    for (int i = 0; i < times; i++) {
      out[i] = row;
    }
    return out;
  }

  private static LongBuffer flatten(long[][] array, int rows, int cols) {
    long[] flat = new long[rows * cols];
    for (int i = 0; i < rows; i++) {
      System.arraycopy(array[i], 0, flat, i * cols, cols);
    }
    return LongBuffer.wrap(flat);
  }

  private static String summarizeOomMessage(String msg) {
    if (msg == null) {
      return "OOM (no message)";
    }
    int idx = msg.indexOf("Available memory of");
    return idx >= 0 ? msg.substring(idx, Math.min(msg.length(), idx + 160)) : msg.substring(0, Math.min(msg.length(), 200));
  }

  private static List<Integer> parseIntCsv(String csv) {
    List<Integer> out = new ArrayList<>();
    for (String s : csv.split(",")) {
      if (!s.isBlank()) {
        out.add(Integer.parseInt(s.trim()));
      }
    }
    return out;
  }

  private static List<Long> parseLongCsv(String csv) {
    List<Long> out = new ArrayList<>();
    for (String s : csv.split(",")) {
      if (!s.isBlank()) {
        out.add(Long.parseLong(s.trim()));
      }
    }
    return out;
  }

  /** One-shot {@code nvidia-smi} VRAM query. Returns null if unavailable/times out. */
  private static Long queryVramUsedMb() {
    Process proc = null;
    try {
      proc =
          new ProcessBuilder(
                  "nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits")
              .redirectErrorStream(true)
              .start();
      String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        return null;
      }
      if (proc.exitValue() != 0) {
        return null;
      }
      String firstLine = stdout.lines().findFirst().orElse("").trim();
      return firstLine.isEmpty() ? null : Long.parseLong(firstLine);
    } catch (Exception e) {
      log.debug("nvidia-smi query failed: {}", e.getMessage());
      return null;
    } finally {
      if (proc != null && proc.isAlive()) {
        proc.destroyForcibly();
      }
    }
  }

  private static String renderMarkdown(Map<String, Object> out, List<CellResult> cells) {
    @SuppressWarnings("unchecked")
    Map<String, Object> knobs = (Map<String, Object>) out.getOrDefault("knobs", Map.of());

    StringBuilder sb = new StringBuilder();
    sb.append("# Encoder Batch Sweep Bench\n\n");
    sb.append("- captured_at: ").append(out.get("captured_at")).append("\n");
    sb.append("- seq_len: ").append(knobs.get("seq_len")).append("\n");
    sb.append("- warmup: ").append(knobs.get("warmup")).append("\n");
    sb.append("- iterations: ").append(knobs.get("iterations")).append("\n");
    sb.append("- vram_idle_mb: ").append(knobs.get("vram_idle_mb")).append("\n\n");

    for (String encoder : List.of("embed", "splade")) {
      sb.append("## ").append(encoder).append("\n\n");
      sb.append("| batch | arena_mb | gpu | oom | p50_ms | mean_ms | chunks/s | vram_mb |\n");
      sb.append("|---:|---:|:---:|:---:|---:|---:|---:|---:|\n");
      for (CellResult c : cells) {
        if (!c.encoder().equals(encoder)) {
          continue;
        }
        sb.append("| ")
            .append(c.batch())
            .append(" | ")
            .append(c.arenaMb())
            .append(" | ")
            .append(c.gpuConfirmed())
            .append(" | ")
            .append(c.oom() ? "OOM" : "-")
            .append(" | ")
            .append(c.oom() ? "-" : BenchmarkUtils.round2(c.p50Ms()))
            .append(" | ")
            .append(c.oom() ? "-" : BenchmarkUtils.round2(c.meanMs()))
            .append(" | ")
            .append(c.oom() ? "-" : BenchmarkUtils.round2(c.chunksPerSec()))
            .append(" | ")
            .append(c.vramUsedMbAfter())
            .append(" |\n");
      }
      sb.append("\n");
    }
    return sb.toString();
  }
}
