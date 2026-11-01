/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiService.VisionCompletionResult;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real processor and batch boundaries with local sources and fake model completions. */
class VduFallbackProcessorTest {
  @TempDir Path directory;
  private final EngineContext context = TestEngineContexts.durableInternal();
  private final AtomicInteger visionCalls = new AtomicInteger();
  private final AtomicInteger metadataCalls = new AtomicInteger();
  private final AtomicBoolean eligible = new AtomicBoolean(true);
  private final List<UpdateVduResultRequest> published = new ArrayList<>();
  private final AtomicInteger releases = new AtomicInteger();

  @ParameterizedTest
  @CsvSource({"1,false,true", "50,false,true", "51,false,false", "2,true,false"})
  void coverageDistinguishesPageCapAndSkippedPages(int pages, boolean blankLast, boolean complete) throws Exception {
    Path file = pdf(pages, blankLast);
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var result = processor(temporary, model("transcription", "stop", false, null)).process(file, context);
      assertEquals(Math.min(pages, 50) - (blankLast ? 1 : 0), visionCalls.get());
      assertEquals(Math.min(pages, 50), result.pageCount());
      assertEquals(complete, complete(result), "S-D76/C5/C6/C9: coverage must describe the whole PDF");
      assertFalse(result.gateVerdict().rejected());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void tokenLimitIsIncompleteEvenAfterPassingProbe(boolean ambiguous) throws Exception {
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var result = processor(temporary, model("stable extracted words", "length", ambiguous, null))
          .process(image(), context);
      assertFalse(result.gateVerdict().rejected(), "C8: truncation is not hallucination evidence");
      assertFalse(complete(result), "C7: a passing probe cannot restore missing coverage");
      assertEquals(ambiguous ? 2 : 1, visionCalls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void emptyPayloadsPublishSuccessEmptyWithoutPageMarkers(int pages) throws Exception {
    Path file = pdf(pages, false);
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var processor = processor(temporary, model(" \n\t", "stop", false, null));
      var outcome = batch(processor, file).processPendingFiles(context, ignored -> {});
      assertEquals(1, published.size());
      assertEquals(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY, published.getFirst().getOutcome(), "S-D75/C11");
      assertFalse(published.getFirst().hasExtractedContent());
      assertEquals(0, metadataCalls.get());
      assertEquals(1, outcome.failed());
    }
  }

  @Test
  void mixedBlankPayloadsKeepActualPageNumbers() throws Exception {
    var ai = model("visible page two", "stop", false, null);
    when(ai.visionCompletionDetailed(anyString(), any(byte[].class), anyInt(), any(), any(), same(context)))
        .thenReturn(CompletableFuture.completedFuture(new VisionCompletionResult(" ", "stop", 1, null, null)))
        .thenReturn(CompletableFuture.completedFuture(new VisionCompletionResult("visible page two", "stop", 4, null, null)));
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var result = processor(temporary, ai).process(pdf(2, false), context);
      assertEquals("--- Page 2 ---\nvisible page two", result.extractedText(), "C12");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"page", "probe", "metadata"})
  void lostEligibilityPreventsEveryNextInferenceAndReturnsClaim(String boundary) throws Exception {
    Path file = pdf(boundary.equals("page") ? 2 : 1, false);
    var ai = model("stable document transcription", "stop", boundary.equals("probe"), null);
    var delegate = ai.visionCompletionDetailed("", new byte[0], 1, SamplingParams.VDU, null, context);
    visionCalls.set(0);
    when(ai.visionCompletionDetailed(anyString(), any(byte[].class), anyInt(), any(), any(), same(context)))
        .thenAnswer(call -> { visionCalls.incrementAndGet(); eligible.set(false); return delegate; });
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var processor = processor(temporary, ai);
      var outcome = batch(processor, file).processPendingFiles(context, ignored -> {});
      assertEquals(1, visionCalls.get(), "S-D46/C18: no second inference after eligibility loss");
      assertEquals(0, metadataCalls.get());
      if (boundary.equals("metadata")) {
        assertEquals(0, releases.get());
        assertEquals(1, outcome.processed(), "accepted transcription survives optional metadata deferral");
        assertEquals(1, published.size());
      } else {
        assertEquals(1, releases.get(), "C19: interrupted document must return its claim and allowance");
        assertTrue(published.isEmpty());
        assertEquals(BlockReason.ACTIVITY_OR_ENERGY, outcome.blockedReason());
      }
      eligible.set(true);
      var recovery = processor(temporary, model("recovered transcription", "stop", false, null));
      assertEquals(1, batch(recovery, file).processPendingFiles(context, ignored -> {}).processed());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "exceptional", "synchronous"})
  void acceptedTextSurvivesAuxiliaryFailure(String failure) throws Exception {
    var ai = model("searchable recovered transcription", "stop", false, failure);
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var outcome = batch(processor(temporary, ai), image()).processPendingFiles(context, ignored -> {});
      assertEquals(1, outcome.processed(), "S-D80/C23");
      assertEquals("searchable recovered transcription", published.getFirst().getExtractedContent());
      assertEquals("", published.getFirst().getVduEnrichment());
    }
  }

  @ParameterizedTest
  @CsvSource({"发票金额已经付款,森林河流山川景色", "a,b", "12,34", "中,文", "你好,再见", "Ж,Я", "नमस्ते,विदाई", " ,字"})
  void unrelatedShortAndUnicodeOutputsDisagree(String first, String second) {
    assertTrue(VduAbstentionGate.jaccardAgreement(first == null ? "" : first, second) < 0.5, "S-D74/C21");
  }

  @ParameterizedTest
  @ValueSource(strings = {"发票金额已经付款", "Ж", "12", "مرحبا", ""})
  void identicalUnicodeShortAndEmptyOutputsAgree(String text) {
    assertEquals(1.0, VduAbstentionGate.jaccardAgreement(text, text), "C22");
  }

  @Test
  void shortOutputFallbackPreservesExistingAsciiWordFiltering() {
    assertEquals(1.0, VduAbstentionGate.jaccardAgreement("a cat", "an cat"), "C22: preserve calibrated word filtering");
    assertEquals(1.0, VduAbstentionGate.jaccardAgreement("A", "a"));
    assertEquals(0.0, VduAbstentionGate.jaccardAgreement("a", "b"), "C21: short outputs must not agree by default");
  }

  @Test
  void ambiguousChineseProbeRejectsUnrelatedOutput() throws Exception {
    var ai = model("发票金额已经付款", "stop", true, null);
    when(ai.visionCompletionDetailed(anyString(), any(byte[].class), anyInt(), any(), any(), same(context)))
        .thenReturn(CompletableFuture.completedFuture(new VisionCompletionResult("发票金额已经付款", "stop", 20, -0.2, 0.01)))
        .thenReturn(CompletableFuture.completedFuture(new VisionCompletionResult("森林河流山川景色", "stop", 20, -0.2, 0.01)));
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var result = processor(temporary, ai).process(image(), context);
      assertTrue(result.gateVerdict().rejected(), "S-D74/C22 full probe");
      assertEquals(VduAbstentionGate.STAGE_AGREEMENT, result.gateVerdict().stage());
      assertEquals(0, metadataCalls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void originalImageMutationCannotChangePreparedOrProbedSource(boolean atomic) throws Exception {
    Path source = image();
    String expectedHash = sha(source);
    List<byte[]> prepared = new ArrayList<>();
    var preparer = new ImagePreparer() {
      @Override public byte[] prepare(Path file) throws java.io.IOException {
        try {
          Path replacement = directory.resolve("replacement.png");
          Files.copy(source, replacement, StandardCopyOption.REPLACE_EXISTING);
          byte[] changed = Files.readAllBytes(replacement);
          changed[changed.length - 1] ^= 1;
          Files.write(replacement, changed);
          if (atomic) Files.move(replacement, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          else Files.write(source, changed);
          assertEquals(expectedHash, sha(file), "C2: every read must use the hashed snapshot, including same-size replacement");
          byte[] bytes = super.prepare(file);
          prepared.add(bytes);
          return bytes;
        } catch (java.io.IOException e) { throw e; }
        catch (Exception e) { throw new java.io.IOException(e); }
      }
    };
    try (var temporary = new TempFileManager(directory.resolve("images"))) {
      var stub = new StubInferenceLifecycleManager().withVisionCapable(true);
      var processor = new VduProcessor(stub, stub, model("stable document text", "stop", true, null), temporary, preparer);
      var result = processor.process(source, context);
      assertEquals(expectedHash, sourceHash(result), "S-D19/C2: publish the hash of the source actually read");
      assertEquals(2, prepared.size());
      assertArrayEquals(prepared.get(0), prepared.get(1));
      try (var files = Files.list(directory.resolve("images"))) { assertEquals(0, files.count()); }
    }
  }

  private VduProcessor processor(TempFileManager files, OnlineAiService ai) {
    var stub = new StubInferenceLifecycleManager().withVisionCapable(true);
    return new VduProcessor(stub, stub, ai, files, new ImagePreparer());
  }

  private OnlineAiService model(String text, String finish, boolean ambiguous, String metadataFailure) {
    var ai = mock(OnlineAiService.class);
    when(ai.visionCompletionDetailed(anyString(), any(byte[].class), anyInt(), any(), any(), same(context)))
        .thenAnswer(call -> {
          visionCalls.incrementAndGet();
          return CompletableFuture.completedFuture(new VisionCompletionResult(text, finish, 20, ambiguous ? -0.2 : -0.02, 0.0));
        });
    when(ai.chatCompletion(anyList(), anyInt(), any(), same(context))).thenAnswer(call -> {
      metadataCalls.incrementAndGet();
      if ("synchronous".equals(metadataFailure)) throw new IllegalStateException("metadata unavailable");
      if (metadataFailure != null) return CompletableFuture.failedFuture("timeout".equals(metadataFailure)
          ? new TimeoutException("metadata timeout") : new IllegalStateException("metadata failed"));
      return CompletableFuture.completedFuture("{\"summary\":\"ok\"}");
    });
    return ai;
  }

  private VduBatchProcessor batch(VduProcessor processor, Path source) {
    KnowledgeClient client = mock(KnowledgeClient.class, call -> {
      Object[] args = call.getArguments();
      return switch (call.getMethod().getName()) {
        case "queryPendingVduDocIds" -> List.of(source.toString());
        case "markVduProcessing" -> 1;
        case "releaseVduProcessing" -> { releases.incrementAndGet(); yield true; }
        case "updateVduResult" -> {
          var request = UpdateVduResultRequest.newBuilder().setDocId((String) args[0])
              .setOutcome((VduUpdateOutcome) args[2]).setVduEnrichment(args[3] == null ? "" : (String) args[3])
              .setPageCount((Integer) args[4]);
          if (args[1] != null) request.setExtractedContent((String) args[1]);
          if (args.length == 9) {
            optional(request, "setSourceSha256", String.class, args[6]);
            optional(request, "setIncompleteCoverage", boolean.class, args[7]);
          }
          published.add(request.build());
          yield true;
        }
        default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      };
    });
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    return new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
        new VduCapabilityState(), () -> !eligible.get());
  }

  private Path image() throws Exception {
    var image = new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 160; y++) for (int x = 0; x < 160; x++) {
      image.setRGB(x, y, (x / 4 + y / 4) % 2 == 0 ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
    }
    Path file = directory.resolve("source.png");
    ImageIO.write(image, "png", file.toFile());
    return file;
  }

  private Path pdf(int pages, boolean blankLast) throws Exception {
    Path file = directory.resolve("source.pdf");
    try (var doc = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        var page = new PDPage(new PDRectangle(120, 120));
        doc.addPage(page);
        if (blankLast && i == pages - 1) continue;
        try (var content = new PDPageContentStream(doc, page)) {
          for (int x = 0; x < 120; x += 8) for (int y = 0; y < 120; y += 8) {
            if ((x / 8 + y / 8) % 2 == 0) { content.addRect(x, y, 8, 8); content.fill(); }
          }
        }
      }
      doc.save(file.toFile());
    }
    return file;
  }

  // Reflection lets the same durable regressions execute against the pre-change candidate.
  private static boolean complete(VduProcessor.VduResult result) throws Exception {
    try { return (boolean) result.getClass().getMethod("completeCoverage").invoke(result); }
    catch (NoSuchMethodException baseline) { return true; }
  }
  private static String sourceHash(VduProcessor.VduResult result) throws Exception {
    try { return (String) result.getClass().getMethod("sourceSha256").invoke(result); }
    catch (NoSuchMethodException baseline) { return ""; }
  }
  private static void optional(Object target, String name, Class<?> type, Object value) throws Exception {
    try { target.getClass().getMethod(name, type).invoke(target, value); }
    catch (NoSuchMethodException baseline) { /* baseline has no coverage or source witness */ }
  }
  private static String sha(Path file) throws Exception {
    return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
}
