/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real file parsing, image/PDF guards and VduProcessor with controlled inference and index ports. */
final class VerifierVduDocumentContractTest {
  @TempDir Path directory;

  @ParameterizedTest @ValueSource(strings = {"generation", "timeout", "fatal"})
  void p1RealProcessorServiceFailuresOpenAndLeaveUntriedDocumentUnclaimed(String kind) throws Exception {
    Path source = sharpImage();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) ids.add(Files.copy(source, directory.resolve("service-" + i + ".png")).toString());
    var service = spy(new FakeVisionOnlineAiService());
    Throwable failure = kind.equals("timeout") ? new java.util.concurrent.TimeoutException("vision timed out")
        : new IllegalStateException(kind.equals("fatal") ? "native_error: server crash" : "generation request failed");
    doReturn(CompletableFuture.failedFuture(failure)).when(service)
        .visionCompletionDetailed(anyString(), any(byte[].class), anyInt(), any(SamplingParams.class), isNull());
    var facade = new StubInferenceLifecycleManager().withVisionCapable(true);
    Path scratch = directory.resolve("service-scratch");
    try (var temps = new TempFileManager(scratch)) {
      var processor = new VduProcessor(facade, facade, service, temps, new ImagePreparer());
      var index = index(ids);
      var gpu = mock(GpuCapabilitiesService.class);
      when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
      var result = new VduBatchProcessor(processor, gpu, () -> index)
          .processPendingFiles(TestEngineContexts.durableInternal(), ignored -> {});
      int attempts = kind.equals("fatal") ? 1 : 5;
      assertEquals(attempts, result.failed());
      assertEquals(6 - attempts, result.remaining());
      assertEquals(io.justsearch.app.api.OfflineProcessingOutcome.BlockReason.CIRCUIT_OPEN, result.blockedReason());
      verify(index, times(attempts)).markVduProcessing(anyString(), anyInt(), anyString(), any());
      verify(index, never()).markVduProcessing(eq(ids.get(attempts)), anyInt(), anyString(), any());
      assertEmpty(scratch);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"pixels", "corrupt", "pdf"})
  void s3DocumentFailuresDoNotTripServiceBreaker(String kind) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Path bad = switch (kind) {
        case "pixels" -> pngHeader("bad-" + i + ".png", 4001, 4000);
        case "pdf" -> Files.writeString(directory.resolve("bad-" + i + ".pdf"), "corrupt PDF");
        default -> Files.writeString(directory.resolve("bad-" + i + ".png"), "corrupt image");
      };
      ids.add(bad.toString());
    }
    Path good = sharpImage(); ids.add(good.toString());
    var service = new FakeVisionOnlineAiService();
    Path scratch = directory.resolve("scratch");
    try (var temps = new TempFileManager(scratch)) {
      var facade = new StubInferenceLifecycleManager().withVisionCapable(true);
      var processor = new VduProcessor(facade, facade, service, temps, new ImagePreparer());
      var index = index(ids);
      var gpu = mock(GpuCapabilitiesService.class);
      when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
      var batch = new VduBatchProcessor(processor, gpu, () -> index);
      var result = batch.processPendingFiles(TestEngineContexts.durableInternal(), ignored -> {});
      assertAll("S3: document-local " + kind + " errors must not block valid following input",
          () -> assertEquals(1, result.processed(), "valid document after five local failures must be read"),
          () -> assertEquals(5, result.failed()),
          () -> assertEquals(1, service.getVisionCallCount(), "only the valid document should reach inference"),
          () -> assertEmpty(scratch));
    }
  }

  @Test void p4HeaderGuardsRejectBeforeDecodingAndProcessorCleansSnapshots() throws Exception {
    Path dimension = pngHeader("wide.png", 8193, 1);
    Path pixels = pngHeader("pixels.png", 4001, 4000);
    var facade = new StubInferenceLifecycleManager().withVisionCapable(true);
    var ai = new FakeVisionOnlineAiService();
    Path scratch = directory.resolve("image-scratch");
    try (var temps = new TempFileManager(scratch)) {
      var processor = new VduProcessor(facade, facade, ai, temps, new ImagePreparer());
      for (Path input : List.of(dimension, pixels)) {
        IOException direct = assertThrows(IOException.class, () -> new ImagePreparer().prepare(input));
        assertTrue(direct.getMessage().contains("allocation limit"), direct.getMessage());
        var failure = assertThrows(VduProcessor.VduException.class,
            () -> processor.process(input, TestEngineContexts.durableInternal()));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("allocation limit"), failure.toString());
        assertEmpty(scratch);
      }
      assertEquals(0, ai.getVisionCallCount());
    }
  }

  @Test void p4PdfGuardsRejectBeforeRenderingAndSuccessfulRenderCleansFiles() throws Exception {
    Path unsafe = pdf("unsafe.pdf", 6000, 1);
    Path safe = pdf("safe.pdf", 72, 72);
    Path scratch = directory.resolve("pdf-scratch");
    try (var temps = new TempFileManager(scratch)) {
      try (var renderer = new PdfImageRenderer(temps)) {
        var failure = assertThrows(IOException.class, () -> renderer.render(unsafe));
        assertTrue(failure.getMessage().contains("allocation limit"));
        assertEquals(0, renderer.getRenderedCount());
        assertEmpty(scratch);
        Path rendered = renderer.render(safe).getFirst();
        assertTrue(Files.isRegularFile(rendered));
      }
      assertEmpty(scratch);
    }
  }

  @Test void p5MetadataFailureRetainsTextAndSuccessfulOutcome() throws Exception {
    var service = spy(new FakeVisionOnlineAiService());
    doReturn(CompletableFuture.failedFuture(new IllegalStateException("metadata service unavailable")))
        .when(service).chatCompletion(anyList(), anyInt(), any(SamplingParams.class));
    var facade = new StubInferenceLifecycleManager().withVisionCapable(true);
    Path input = sharpImage();
    Path scratch = directory.resolve("metadata-scratch");
    try (var temps = new TempFileManager(scratch)) {
      var processor = new VduProcessor(facade, facade, service, temps, new ImagePreparer());
      var index = index(List.of(input.toString()));
      var gpu = mock(GpuCapabilitiesService.class);
      when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
      var result = new VduBatchProcessor(processor, gpu, () -> index)
          .processPendingFiles(TestEngineContexts.durableInternal(), ignored -> {});
      assertEquals(1, result.processed()); assertEquals(0, result.failed());
      verify(service).chatCompletion(anyList(), anyInt(), any(SamplingParams.class));
      verify(index).updateVduResult(eq(input.toString()), eq("default extracted text"),
          eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT), isNull(), eq(1), anyString(),
          anyString(), eq(false), any());
      assertEmpty(scratch);
    }
  }

  private KnowledgeClient index(List<String> ids) {
    var index = mock(KnowledgeClient.class);
    when(index.queryPendingVduDocIds(any())).thenReturn(ids);
    when(index.markVduProcessing(anyString(), anyInt(), anyString(), any())).thenReturn(1);
    when(index.updateVduResult(anyString(), nullable(String.class), any(), nullable(String.class),
        anyInt(), anyString(), any())).thenReturn(true);
    when(index.updateVduResult(anyString(), nullable(String.class), any(), nullable(String.class),
        anyInt(), anyString(), nullable(String.class), anyBoolean(), any())).thenReturn(true);
    return index;
  }
  private void assertEmpty(Path path) throws Exception {
    try (var children = Files.list(path)) { assertEquals(0, children.count(), "temporary files must be cleaned before manager closes"); }
  }
  private Path sharpImage() throws Exception {
    var image = new BufferedImage(800, 240, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 800, 240);
      graphics.setColor(Color.BLACK); graphics.setFont(new Font("Monospaced", Font.BOLD, 28));
      for (int y = 40; y < 220; y += 35) graphics.drawString("INVOICE 1234 TOTAL 5678 RECOVERED", 15, y);
    } finally { graphics.dispose(); }
    Path path = directory.resolve("good.png"); ImageIO.write(image, "PNG", path.toFile()); return path;
  }
  // Header-only PNGs deliberately contain no decodable pixels: a guard after decode cannot pass.
  private Path pngHeader(String name, int width, int height) throws Exception {
    Path path = directory.resolve(name);
    var headerBytes = new ByteArrayOutputStream();
    try (var header = new DataOutputStream(headerBytes)) {
      header.writeInt(width); header.writeInt(height); header.write(new byte[] {8, 2, 0, 0, 0});
    }
    try (var output = new DataOutputStream(Files.newOutputStream(path))) {
      output.writeLong(0x89504e470d0a1a0aL);
      pngChunk(output, "IHDR", headerBytes.toByteArray()); pngChunk(output, "IEND", new byte[0]);
    }
    return path;
  }
  private void pngChunk(DataOutputStream output, String type, byte[] bytes) throws Exception {
    byte[] tag = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    output.writeInt(bytes.length); output.write(tag); output.write(bytes);
    var crc = new CRC32(); crc.update(tag); crc.update(bytes); output.writeInt((int) crc.getValue());
  }
  private Path pdf(String name, float width, float height) throws Exception {
    Path path = directory.resolve(name);
    try (var document = new PDDocument()) { document.addPage(new PDPage(new PDRectangle(width, height))); document.save(path.toFile()); }
    return path;
  }
}
