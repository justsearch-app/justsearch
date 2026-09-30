/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OnlineAiService.VisionCompletionResult;
import io.justsearch.app.util.TempFileManager;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Processor-level tests for the tempdoc 677 abstention cascade (Stages 0+1) wired into {@link
 * VduProcessor#process}. Uses a real {@code VduProcessor} against a {@link
 * FakeVisionOnlineAiService} — no real llama-server — so these tests exercise the actual
 * production wiring (Stage 0 pixel measurement, Stage 1 signal aggregation, gate consultation),
 * not a reimplementation.
 */
@DisplayName("VduProcessor — abstention cascade (tempdoc 677 Stages 0+1)")
final class VduProcessorAbstentionTest {
  private static final io.justsearch.core.context.EngineContext TEST_CONTEXT =
      io.justsearch.app.services.intent.EngineProvenance.internal("vdu-test",
          io.justsearch.core.context.EngineContext.Survival.DURABLE,
          io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);

  @TempDir Path tempDir;

  private StubInferenceLifecycleManager inferenceFacade;
  private FakeVisionOnlineAiService aiService;
  private TempFileManager tempFileManager;
  private VduProcessor processor;

  @BeforeEach
  void setUp() throws Exception {
    inferenceFacade = new StubInferenceLifecycleManager().withVisionCapable(true);
    aiService = new FakeVisionOnlineAiService();
    tempFileManager = new TempFileManager(tempDir.resolve("temp"));
    processor =
        new VduProcessor(
            inferenceFacade, inferenceFacade, aiService, tempFileManager, new ImagePreparer());
  }

  @Test
  @DisplayName("Stage 0: an all-illegible page never calls the model and is rejected")
  void allPagesIllegibleSkipsModelAndRejects() throws Exception {
    Path image = writeUniformGrayImage("blank.png");

    VduProcessor.VduResult result = processor.process(image, TEST_CONTEXT);

    assertEquals(0, aiService.getVisionCallCount(), "the model must never be called");
    assertEquals(0, aiService.getChatCompletionCallCount(), "pass 2 must not run either");
    assertTrue(result.gateVerdict().rejected());
    assertEquals(VduAbstentionGate.STAGE_INPUT_LEGIBILITY, result.gateVerdict().stage());
    assertEquals("", result.extractedText());
    assertNull(result.enrichment());
  }

  @Test
  @DisplayName("Stage 1: suspect logprobs reject the output, omit pass 2, but keep the raw text on the result")
  void suspectLogprobsRejectAndSkipPass2() throws Exception {
    Path image = writeSharpTextImage("suspect.png");
    // tempdoc 677 probe: refusal-shaped noise measured mean -0.442 / 14% low-confidence; use a
    // value clearly past MEAN_LOGPROB_FLOOR to unambiguously trip Stage 1.
    aiService.withDefaultVisionResult(
        new VisionCompletionResult("plausible-looking fabricated text", "stop", 50, -2.0, 0.5));

    VduProcessor.VduResult result = processor.process(image, TEST_CONTEXT);

    assertEquals(1, aiService.getVisionCallCount(),
        "REJECT band must not run the Stage 2 probe — a single call total");
    assertEquals(0, aiService.getChatCompletionCallCount(), "pass 2 must be skipped once rejected");
    assertTrue(result.gateVerdict().rejected());
    assertEquals(GateVerdict.Band.REJECT, result.gateVerdict().band());
    assertEquals(VduAbstentionGate.STAGE_OUTPUT_CONFIDENCE, result.gateVerdict().stage());
    assertEquals(-2.0, result.gateVerdict().meanLogprob());
    assertNull(result.enrichment(), "pass 2 skipped means no enrichment");
    assertTrue(
        result.extractedText().contains("plausible-looking fabricated text"),
        "Pass 1's raw text is still returned on VduResult — VduBatchProcessor omits it from the "
            + "wire, VduProcessor itself just reports what happened");
  }

  @Test
  @DisplayName("healthy signals: gate passes, SUCCESS path unchanged (pass 2 runs), no Stage 2 probe")
  void healthySignalsPassGate() throws Exception {
    Path image = writeSharpTextImage("healthy.png");
    aiService.withDefaultVisionResult(
        new VisionCompletionResult("Hello World transcription", "stop", 30, -0.058, 0.0));
    aiService.withChatCompletionResult("{\"summary\":\"a document\"}");

    VduProcessor.VduResult result = processor.process(image, TEST_CONTEXT);

    assertFalse(result.gateVerdict().rejected());
    assertEquals(GateVerdict.Band.PASS, result.gateVerdict().band());
    assertNull(result.gateVerdict().stage());
    assertEquals(1, aiService.getVisionCallCount(),
        "PASS band must not run the Stage 2 probe — a single call total");
    assertEquals(1, aiService.getChatCompletionCallCount(), "pass 2 must run when the gate passes");
    assertEquals("Hello World transcription", result.extractedText());
    assertEquals("{\"summary\":\"a document\"}", result.enrichment());
  }

  @Test
  @DisplayName("Stage 2: AMBIGUOUS Stage-1 signals + low probe agreement reject at stage=agreement")
  void ambiguousSignalsLowAgreementRejects() throws Exception {
    Path image = writeSharpTextImage("ambiguous-low.png");
    // meanLogprob=-0.2 breaches AMBIGUOUS_MEAN_LOGPROB_FLOOR (-0.09) but not
    // REJECT_MEAN_LOGPROB_FLOOR (-0.35); lowConfidenceFraction=0.01 breaches
    // AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING (0.005) but not REJECT's (0.06) — Stage 1 band is
    // AMBIGUOUS, not REJECT.
    VisionCompletionResult pass1Result =
        new VisionCompletionResult(
            "original document transcription content here", "stop", 60, -0.2, 0.01);
    VisionCompletionResult probeResult =
        new VisionCompletionResult(
            "completely different unrelated confabulated zzz words", "stop", 60, -0.2, 0.01);
    aiService.withVisionResults(pass1Result, probeResult);

    VduProcessor.VduResult result = processor.process(image, TEST_CONTEXT);

    assertEquals(2, aiService.getVisionCallCount(),
        "AMBIGUOUS band must run exactly one Stage 2 probe call (pass 1 + probe)");
    assertEquals(0, aiService.getChatCompletionCallCount(),
        "pass 2 must be skipped once Stage 2 rejects");
    assertTrue(result.gateVerdict().rejected());
    assertEquals(GateVerdict.Band.REJECT, result.gateVerdict().band());
    assertEquals(VduAbstentionGate.STAGE_AGREEMENT, result.gateVerdict().stage());
    assertEquals(1, result.gateVerdict().probedPage());
    assertNotNull(result.gateVerdict().agreement());
    assertTrue(
        result.gateVerdict().agreement() < VduAbstentionGate.AGREEMENT_FLOOR,
        "disjoint pass1/probe text must measure low agreement: " + result.gateVerdict().agreement());
    assertNull(result.enrichment(), "pass 2 skipped means no enrichment");
  }

  @Test
  @DisplayName("Stage 2: AMBIGUOUS Stage-1 signals + high probe agreement resolve to SUCCESS (pass 2 runs)")
  void ambiguousSignalsHighAgreementPasses() throws Exception {
    Path image = writeSharpTextImage("ambiguous-high.png");
    VisionCompletionResult pass1Result =
        new VisionCompletionResult(
            "stable document transcription content words", "stop", 60, -0.2, 0.01);
    VisionCompletionResult probeResult =
        new VisionCompletionResult(
            "stable document transcription content words", "stop", 60, -0.2, 0.01);
    aiService.withVisionResults(pass1Result, probeResult);
    aiService.withChatCompletionResult("{\"summary\":\"resolved\"}");

    VduProcessor.VduResult result = processor.process(image, TEST_CONTEXT);

    assertEquals(2, aiService.getVisionCallCount(),
        "AMBIGUOUS band must run exactly one Stage 2 probe call (pass 1 + probe)");
    assertEquals(1, aiService.getChatCompletionCallCount(),
        "pass 2 must run once Stage 2 resolves to a pass");
    assertFalse(result.gateVerdict().rejected());
    assertEquals(1, result.gateVerdict().probedPage());
    assertEquals(1.0, result.gateVerdict().agreement(), "identical pass1/probe text must agree fully");
    assertEquals("stable document transcription content words", result.extractedText());
    assertEquals("{\"summary\":\"resolved\"}", result.enrichment());
  }

  @Test
  @DisplayName("logprobs absent (server returned no signal): gate does not false-abstain")
  void nullLogprobsDoNotFalseAbstain() throws Exception {
    Path image = writeSharpTextImage("nulls.png");
    // tokenCount=0 and null logprob fields is exactly the "server did not return logprobs" shape
    // documented on VisionCompletionResult.
    aiService.withDefaultVisionResult(
        new VisionCompletionResult("some transcription", "stop", 0, null, null));

    VduProcessor.VduResult result = processor.process(image, TEST_CONTEXT);

    assertFalse(
        result.gateVerdict().rejected(), "NO SIGNAL must never be treated as low confidence");
    assertEquals(1, aiService.getChatCompletionCallCount(), "pass 2 runs — this is a SUCCESS path");
    assertNotNull(result.enrichment());
  }

  private Path writeUniformGrayImage(String name) throws Exception {
    BufferedImage image = new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(new Color(180, 180, 180));
    g.fillRect(0, 0, 240, 120);
    g.dispose();
    Path path = tempDir.resolve(name);
    ImageIO.write(image, "png", path.toFile());
    return path;
  }

  private Path writeSharpTextImage(String name) throws Exception {
    BufferedImage image = new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 240, 120);
    g.setColor(Color.BLACK);
    g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
    g.drawString("Hello World", 10, 40);
    g.drawString("Sample Text", 10, 70);
    g.drawString("12345 ABCDE", 10, 100);
    g.dispose();
    Path path = tempDir.resolve(name);
    ImageIO.write(image, "png", path.toFile());
    return path;
  }
}
