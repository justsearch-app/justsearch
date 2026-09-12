/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("VduProcessor cancellation and cleanup")
@Timeout(30)
final class VduProcessorCancellationTest {

  @TempDir Path tempDir;

  @ParameterizedTest(name = "interrupt while waiting for {0}")
  @EnumSource(PendingStage.class)
  void interruptCancelsPendingModelStageAndCleansRenderedImages(PendingStage stage)
      throws Exception {
    EngineContext context =
        EngineProvenance.internal(
            "vdu-cancellation-" + stage.name().toLowerCase(java.util.Locale.ROOT),
            EngineContext.Survival.DURABLE,
            EngineContext.Urgency.BACKGROUND);
    PendingAiService ai = new PendingAiService(stage);
    Path renderedRoot =
        tempDir.resolve("rendered-" + stage.name().toLowerCase(java.util.Locale.ROOT));
    Path input = writeLegiblePdf(stage.name().toLowerCase(java.util.Locale.ROOT) + ".pdf");

    try (TempFileManager tempFiles = new TempFileManager(renderedRoot)) {
      OnlineAiRuntimeIntrospection introspection = mock(OnlineAiRuntimeIntrospection.class);
      when(introspection.hasVisionCapability()).thenReturn(true);
      VduProcessor processor =
          new VduProcessor(
              introspection,
              mock(OnlineAiLifecycleControl.class),
              ai,
              tempFiles,
              new ImagePreparer());
      AtomicReference<Throwable> observed = new AtomicReference<>();
      AtomicBoolean interruptRestored = new AtomicBoolean();
      Thread caller =
          Thread.ofVirtual()
              .unstarted(
                  () -> {
                    try {
                      processor.process(input, context);
                    } catch (Throwable failure) {
                      observed.set(failure);
                    } finally {
                      interruptRestored.set(Thread.currentThread().isInterrupted());
                    }
                  });

      caller.start();
      assertTrue(
          ai.pendingEntered.await(10, TimeUnit.SECONDS), "the selected model wait must start");
      caller.interrupt();
      caller.join(10_000);

      assertFalse(caller.isAlive(), "interruption must release the synchronous processor caller");
      assertTrue(
          ai.pending.get().isCancelled(), "the accepted pending model future must be cancelled");
      CompletionException cancellation =
          assertInstanceOf(CompletionException.class, observed.get());
      assertInstanceOf(InterruptedException.class, cancellation.getCause());
      assertTrue(interruptRestored.get(), "EngineFutures.await must restore interrupt status");
      assertEquals(stage.expectedVisionCalls, ai.visionCalls.get());
      assertEquals(stage.expectedChatCalls, ai.chatCalls.get());
      assertTrue(
          ai.contexts.stream().allMatch(actual -> actual == context),
          "every reached model stage must receive the exact caller context instance");
      try (var rendered = Files.list(renderedRoot)) {
        assertTrue(rendered.findAny().isEmpty(), "cancellation must clean every rendered PDF page");
      }
    }
  }

  @Test
  void exitModeFailureIsVisibleWithItsTypedCause() throws Exception {
    OnlineAiLifecycleControl lifecycle = mock(OnlineAiLifecycleControl.class);
    ModeTransitionException transition =
        new ModeTransitionException(
            ModeTransitionException.Reason.ONLINE_START_FAILED, "exit restart failed");
    doThrow(transition).when(lifecycle).exitVduMode();
    OnlineAiRuntimeIntrospection introspection = mock(OnlineAiRuntimeIntrospection.class);

    try (TempFileManager tempFiles = new TempFileManager(tempDir.resolve("exit-temp"))) {
      VduProcessor processor =
          new VduProcessor(
              introspection,
              lifecycle,
              mock(OnlineAiService.class),
              tempFiles,
              new ImagePreparer());

      IllegalStateException failure =
          assertThrows(IllegalStateException.class, processor::exitVduMode);

      assertSame(transition, failure.getCause());
      assertTrue(failure.getMessage().contains("Failed to exit VDU mode"));
    }
  }

  private Path writeLegiblePdf(String name) throws Exception {
    Path pdf = tempDir.resolve(name);
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 28);
        content.newLineAtOffset(54, 700);
        content.showText("Cancellation cleanup model context proof");
        content.newLineAtOffset(0, -48);
        content.showText("JustSearch visual document processing");
        content.newLineAtOffset(0, -48);
        content.showText("ABCDEFGHIJKLMNOPQRSTUVWXYZ 1234567890");
        content.endText();
      }
      document.save(pdf.toFile());
    }
    return pdf;
  }

  private enum PendingStage {
    PASS1(1, 0),
    AGREEMENT_PROBE(2, 0),
    PASS2(1, 1);

    private final int expectedVisionCalls;
    private final int expectedChatCalls;

    PendingStage(int expectedVisionCalls, int expectedChatCalls) {
      this.expectedVisionCalls = expectedVisionCalls;
      this.expectedChatCalls = expectedChatCalls;
    }
  }

  private static final class PendingAiService implements OnlineAiService {
    private static final VisionCompletionResult AMBIGUOUS =
        new VisionCompletionResult("stable extracted document text", "stop", 60, -0.2, 0.01);
    private static final VisionCompletionResult PASS =
        new VisionCompletionResult("stable extracted document text", "stop", 60, -0.058, 0.0);

    private final PendingStage stage;
    private final AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();
    private final CountDownLatch pendingEntered = new CountDownLatch(1);
    private final AtomicInteger visionCalls = new AtomicInteger();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final List<EngineContext> contexts = new CopyOnWriteArrayList<>();

    private PendingAiService(PendingStage stage) {
      this.stage = stage;
    }

    @Override
    public CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
        String prompt,
        byte[] imageBytes,
        int maxTokens,
        SamplingParams sampling,
        Long seed,
        EngineContext context) {
      contexts.add(context);
      int call = visionCalls.incrementAndGet();
      if (stage == PendingStage.PASS1 || (stage == PendingStage.AGREEMENT_PROBE && call == 2)) {
        CompletableFuture<VisionCompletionResult> modelFuture = new CompletableFuture<>();
        pending.set(modelFuture);
        pendingEntered.countDown();
        return modelFuture;
      }
      return CompletableFuture.completedFuture(
          stage == PendingStage.AGREEMENT_PROBE ? AMBIGUOUS : PASS);
    }

    @Override
    public CompletableFuture<String> chatCompletion(
        List<Map<String, Object>> messages,
        int maxTokens,
        SamplingParams sampling,
        EngineContext context) {
      contexts.add(context);
      chatCalls.incrementAndGet();
      if (stage == PendingStage.PASS2) {
        CompletableFuture<String> modelFuture = new CompletableFuture<>();
        pending.set(modelFuture);
        pendingEntered.countDown();
        return modelFuture;
      }
      throw new AssertionError("chat must not run after cancellation at " + stage);
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new AssertionError("summarize is not a VDU stage"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new AssertionError("askQuestion is not a VDU stage"));
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }
  }
}
