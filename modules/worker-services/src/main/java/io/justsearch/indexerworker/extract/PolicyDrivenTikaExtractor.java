/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.BudgetExceededException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Policy-composed Tika extractor.
 *
 * <p>This is the Worker-side adapter where JustSearch budgets are translated to Tika-native
 * parser configuration, output limits, MIME admission, and artifact provenance.
 */
public final class PolicyDrivenTikaExtractor implements ContentExtractorProvider, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(PolicyDrivenTikaExtractor.class);
  private static final String OCR_FALLBACK_DIRECT_TESSERACT = "direct_tesseract";
  private static final String OCR_FALLBACK_RENDERED_PDF = "rendered_pdf";

  private final TikaExtractionPolicy policy;
  private final OcrRoutingConfig ocrConfig;
  private final OcrMetricCatalog ocrMetricCatalog;
  private final Tika tika;
  private final StructuredContentExtractor structuredExtractor;
  private final PdfOcrEngine ocrEngine;
  private final ExtractionFallbackBudget fallbackBudget;

  public PolicyDrivenTikaExtractor(IntFunction<ExecutorService> poolFactory) {
    this(poolFactory, TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled());
  }

  public PolicyDrivenTikaExtractor(
      IntFunction<ExecutorService> poolFactory, TikaExtractionPolicy policy) {
    this(poolFactory, policy, OcrRoutingConfig.disabled());
  }

  public PolicyDrivenTikaExtractor(
      IntFunction<ExecutorService> poolFactory,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig) {
    this(poolFactory, policy, ocrConfig, OcrMetricCatalog.noop());
  }

  public PolicyDrivenTikaExtractor(
      IntFunction<ExecutorService> poolFactory,
      TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    this(poolFactory, policy, ocrConfig, ocrMetricCatalog, ExtractionFallbackBudget.defaults());
  }

  public PolicyDrivenTikaExtractor(
      IntFunction<ExecutorService> poolFactory,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      OcrMetricCatalog ocrMetricCatalog,
      ExtractionFallbackBudget fallbackBudget) {
    this.fallbackBudget = fallbackBudget == null ? ExtractionFallbackBudget.defaults() : fallbackBudget;
    this.policy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    this.ocrConfig = ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
    this.ocrMetricCatalog = ocrMetricCatalog == null ? OcrMetricCatalog.noop() : ocrMetricCatalog;
    // The MIME this extractor detects drives OCR/VDU routing, so it must agree with the detector
    // the structured extractor parses under (tempdoc 803).
    this.tika = new Tika(TextNameMagicConflictDetector.wrapDefault());
    this.tika.setMaxStringLength(this.policy.maxExtractedChars());
    this.structuredExtractor = new StructuredContentExtractor(this.policy.maxExtractedChars());
    this.ocrEngine = PdfOcrEngine.create(poolFactory, this.ocrConfig, log);
  }

  public TikaExtractionPolicy policy() {
    return policy;
  }

  @Override
  public void close() {
    ocrEngine.close();
  }

  @Override
  public ExtractionResult extract(Path file) throws IOException, ExtractionException {
    return extractArtifact(file).result();
  }

  public ExtractionArtifact extractArtifact(Path file) throws IOException, ExtractionException {
    Objects.requireNonNull(file, "file");
    long documentStartedAtNanos = System.nanoTime();
    if (!Files.exists(file)) {
      throw new IOException("File does not exist: " + file);
    }
    if (!Files.isReadable(file)) {
      throw new IOException("File is not readable: " + file);
    }

    long fileSize = Files.size(file);
    if (fileSize > policy.maxInputBytes()) {
      throw new BudgetExceededException("Input exceeds policy size limit", "INPUT_TOO_LARGE");
    }

    String detectedMime = detectMimeType(file);
    if (!policy.permitsMimeType(detectedMime)) {
      throw new ExtractionException("MIME type excluded by extraction policy");
    }
    if (fileSize > policy.maxOfficeInputBytes() && ContentExtractor.isOfficeMimeType(detectedMime)) {
      throw new BudgetExceededException("Office input exceeds policy size limit", "OFFICE_INPUT_TOO_LARGE");
    }
    if (fileSize == 0) {
      return ExtractionArtifact.full(
          new ExtractionResult("", null, "text/plain"), policy, "tika-policy", false);
    }

    StructuredContentExtractor.StructuredExtractionResult structured =
        structuredExtractor.extractWithStatus(file);
    ExtractionResult result = structured.result();
    StructuredDocumentSummary summary = structured.summary();
    if (isPdfFile(file, detectedMime)) {
      summary = PdfVisualAnalyzer.enrich(file, summary);
    }
    boolean truncated = structured.truncated();

    OcrEvidenceBuilder ocrEvidence = new OcrEvidenceBuilder();
    OcrAttemptDecision ocrAttempt =
        evaluateOcrAttempt(
            file,
            detectedMime,
            result.content(),
            summary,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - documentStartedAtNanos));
    if (ocrAttempt.skipReason() != null) {
      ocrEvidence.skip(ocrAttempt.skipReason());
    }
    if (ocrAttempt.shouldAttempt()) {
      try {
      ExtractionArtifact ocrArtifact =
          summary.mixedPdf()
              ? trySelectivePdfOcr(file, result, summary, ocrEvidence)
              : tryOcr(file, result, summary, ocrEvidence);
      if (ocrArtifact != null) {
        return ocrArtifact;
      }
      } catch (java.util.concurrent.RejectedExecutionException refusal) {
        // OCR is an enhancement of an already extracted document. Keep that baseline while
        // making this capacity failure visible; the OCR engine itself preserves typed refusal.
        String reason = refusal instanceof EngineExecutorRejectedException registered
            ? registered.reason().name()
            : refusal instanceof PdfOcrEngine.OcrCapacityException capacity
                ? capacity.reason().name() : "QUEUE_LIMIT";
        ocrEvidence.skip(OcrSkipReason.UNKNOWN);
        ocrMetricCatalog.failedTotal.increment(OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, reason));
        log.warn("OCR capacity refused for {} ({}); preserving structured extraction",
            file.getFileName(), reason);
      }
    }
    return withVisualEvidence(
        ExtractionArtifact.full(result, policy, "tika-policy-structured", truncated),
        summary,
        "structured",
        false,
        OcrConfidenceExtractor.Summary.empty(),
        false,
        ocrEvidence);
  }

  private ExtractionArtifact withVisualEvidence(
      ExtractionArtifact artifact,
      StructuredDocumentSummary summary,
      String route,
      boolean ocrRoute,
      OcrConfidenceExtractor.Summary ocrConfidence,
      boolean mixedPdfOverride,
      OcrEvidenceBuilder ocrEvidence) {
    // ExtractionArtifact.full is the final representation boundary and may clamp annotated or
    // OCR-merged text. Persist routing evidence from that effective representation, not from the
    // discarded pre-clamp value.
    ExtractionResult effectiveResult = artifact.result();
    return artifact.withVisualExtractionEvidence(
        VisualExtractionEvidence.from(
            effectiveResult == null ? null : effectiveResult.content(),
            summary,
            route,
            ocrConfig,
            ocrRoute,
            ocrConfidence,
            mixedPdfOverride,
            ocrEvidence.facts(artifact.truncated())));
  }

  private OcrAttemptDecision evaluateOcrAttempt(
      Path file,
      String detectedMime,
      String content,
      StructuredDocumentSummary summary,
      long elapsedMs) {
    if (!isOcrEligibleFile(file, detectedMime)) {
      log.debug("Skipping OCR for {}: file is not OCR-eligible (mime={})", file.getFileName(), detectedMime);
      return OcrAttemptDecision.skip(null);
    }
    // Tempdoc 790: OCR is fallback tier 1 of the dropout chain, so the per-document budget gates
    // it before any other consideration — a document that already burned its wall-clock in
    // structured extraction does not also get to start an OCR pass.
    boolean dropout = ExtractionDropoutPolicy.isDropout(content);
    if (!fallbackBudget.permitsTierNow(1, elapsedMs)) {
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.BUDGET));
      log.debug(
          "Skipping OCR for {}: per-document fallback budget spent (elapsedMs={}, budget={})",
          file.getFileName(),
          elapsedMs,
          fallbackBudget);
      return OcrAttemptDecision.skip(OcrSkipReason.BUDGET);
    }
    int pageCount = summary == null ? 0 : summary.pageCount();
    if (ocrConfig.maxPages() != null && ocrConfig.maxPages() > 0 && pageCount > ocrConfig.maxPages()) {
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE));
      log.debug("Skipping OCR for {}: page count {} exceeds limit {}", file.getFileName(), pageCount, ocrConfig.maxPages());
      return OcrAttemptDecision.skip(OcrSkipReason.SIZE);
    }
    if (!imageWithinConfiguredGuards(file, detectedMime)) {
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE));
      log.debug("Skipping OCR for {}: image dimensions exceed configured OCR guards", file.getFileName());
      return OcrAttemptDecision.skip(OcrSkipReason.SIZE);
    }
    boolean hasMissingReadablePages = summary != null && summary.pagesMissingReadableText() > 0;
    // A dropout is never "textually sufficient", whatever the quality score says. The score
    // happens to be 0.0 for empty text today, so this is belt-and-braces — but it makes the
    // dropout→fallback invariant explicit at the gate that decides it, instead of leaving it
    // implied by a threshold in a different class (wrong-gate discipline).
    if (!dropout
        && !hasMissingReadablePages
        && TextQualityAnalyzer.computeQualityScore(content, pageCount) >= 0.3d) {
      log.debug("Skipping OCR for {}: structured text quality is already sufficient", file.getFileName());
      return OcrAttemptDecision.skip(OcrSkipReason.TEXTUAL);
    }
    if (!ocrConfig.enabled()) {
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.DISABLED));
      log.debug("Skipping OCR for {}: OCR config is disabled", file.getFileName());
      return OcrAttemptDecision.skip(OcrSkipReason.DISABLED);
    }
    String blockedReason = TikaOcrRuntime.blockedReason(ocrConfig);
    if (!blockedReason.isBlank()) {
      OcrSkipReason reason = OcrSkipReason.fromBlockedReason(blockedReason);
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(reason));
      log.debug("Skipping OCR for {}: {}", file.getFileName(), blockedReason);
      return OcrAttemptDecision.skip(reason);
    }
    log.debug(
        "Attempting OCR for {} (mime={}, pageCount={}, missingReadablePages={}, contentChars={})",
        file.getFileName(),
        detectedMime,
        pageCount,
        summary == null ? 0 : summary.pagesMissingReadableText(),
        content == null ? 0 : content.length());
    return OcrAttemptDecision.yes();
  }

  boolean shouldAttemptOcrForTesting(
      Path file, String detectedMime, String content, StructuredDocumentSummary summary) {
    return evaluateOcrAttempt(file, detectedMime, content, summary, 0L).shouldAttempt();
  }

  OcrAttemptDecision evaluateOcrAttemptForTesting(
      Path file,
      String detectedMime,
      String content,
      StructuredDocumentSummary summary,
      long elapsedMs) {
    return evaluateOcrAttempt(file, detectedMime, content, summary, elapsedMs);
  }

  private boolean imageWithinConfiguredGuards(Path file, String detectedMime) {
    if (!isRasterImageFile(file, detectedMime)) {
      return true;
    }
    Integer maxDimension = ocrConfig.maxImageDimension();
    Integer maxPixels = ocrConfig.maxImagePixels();
    if ((maxDimension == null || maxDimension <= 0) && (maxPixels == null || maxPixels <= 0)) {
      return true;
    }
    ImageSize size = readImageSize(file);
    if (size == null) {
      return true;
    }
    if (maxDimension != null && maxDimension > 0 && Math.max(size.width(), size.height()) > maxDimension) {
      return false;
    }
    long pixels = (long) size.width() * (long) size.height();
    return maxPixels == null || maxPixels <= 0 || pixels <= maxPixels;
  }

  private static ImageSize readImageSize(Path file) {
    try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
      if (stream == null) {
        return null;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        return null;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        return new ImageSize(reader.getWidth(0), reader.getHeight(0));
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      log.debug("Could not read image dimensions for {}: {}", file, e.getMessage());
      return null;
    }
  }

  private ExtractionArtifact tryOcr(
      Path file,
      ExtractionResult baseline,
      StructuredDocumentSummary baselineSummary,
      OcrEvidenceBuilder ocrEvidence) {
    if (isRasterImageFile(file, baseline.mimeType())) {
      return tryImageOcr(file, baseline, ocrEvidence);
    }
    if (isPdfFile(file, baseline.mimeType())) {
      return tryPdfOcr(file, baseline, baselineSummary, ocrEvidence);
    }
    return null;
  }

  private ExtractionArtifact tryPdfOcr(
      Path file,
      ExtractionResult baseline,
      StructuredDocumentSummary baselineSummary,
      OcrEvidenceBuilder ocrEvidence) {
    long startedAtNanos = System.nanoTime();
    String baselineText = baseline.content() == null ? "" : baseline.content().stripTrailing();
    int maxOcrChars = Math.max(0, policy.maxExtractedChars() - baselineText.length());
    PdfOcrEngine.OcrEngineResult ocr = ocrEngine.ocrPdf(file, maxOcrChars);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    ocrMetricCatalog.timeMs.record(elapsedMs, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));

    int pages = Math.max(1, baselineSummary == null ? 1 : baselineSummary.pageCount());
    double baselineQuality = TextQualityAnalyzer.computeQualityScore(baseline.content(), pages);
    String engineText = ocr.text();
    if (engineText != null && !engineText.isBlank()) {
      String merged = baselineText + engineText;
      double mergedQuality = TextQualityAnalyzer.computeQualityScore(merged, pages);
      if (mergedQuality >= baselineQuality) {
        ocrMetricCatalog.succeededTotal.increment(OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
        ocrEvidence.truncated(ocr.truncated());
        ExtractionResult result =
            new ExtractionResult(
                merged, baseline.title(), baseline.mimeType(), baseline.author(), baseline.frontmatterMetadata());
        StructuredDocumentSummary summary =
            new StructuredDocumentSummary(
                pages,
                merged.length(),
                pages,
                0,
                baselineSummary == null ? 0 : baselineSummary.headingCount(),
                baselineSummary == null ? 0 : baselineSummary.paragraphCount(),
                baselineSummary == null ? 0 : baselineSummary.tableCount(),
                baselineSummary == null ? 0 : baselineSummary.listCount(),
                baselineSummary == null ? 0 : baselineSummary.imagePageCount());
        summary = ocrCoveredPdfSummary(file, summary, merged);
        return withVisualEvidence(
            ExtractionArtifact.full(result, policy, OcrRoutingConfig.PARSER_ID, ocr.truncated()),
            summary,
            "ocr_full",
            true,
            ocr.confidence(),
            false,
            ocrEvidence);
      }
    }
    // Single terminal classifier for the PDF OCR path (tempdoc 671 discipline; skip() is
    // first-write-wins). A hard engine failure is a failure; every other no-improvement outcome
    // (timeout with zero pages, blank-with-no-text) is a skip classified exactly once here.
    return classifyNonImprovement(file, ocr.failureReason(), baselineQuality, ocrEvidence);
  }

  private ExtractionArtifact tryImageOcr(
      Path file, ExtractionResult baseline, OcrEvidenceBuilder ocrEvidence) {
    long startedAtNanos = System.nanoTime();
    PdfOcrEngine.OcrEngineResult ocr = ocrEngine.ocrImage(file, policy.maxExtractedChars());
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    ocrMetricCatalog.timeMs.record(elapsedMs, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));

    double baselineQuality = TextQualityAnalyzer.computeQualityScore(baseline.content());
    String directText = ocr.text();
    if (directText != null && !directText.isBlank()) {
      double directQuality = TextQualityAnalyzer.computeQualityScore(directText, 1);
      if (directQuality >= baselineQuality) {
        ocrMetricCatalog.succeededTotal.increment(OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
        ocrEvidence.fallback(OCR_FALLBACK_DIRECT_TESSERACT);
        ocrEvidence.truncated(ocr.truncated());
        ExtractionResult result =
            new ExtractionResult(
                directText,
                baseline.title(),
                baseline.mimeType(),
                baseline.author(),
                baseline.frontmatterMetadata());
        StructuredDocumentSummary summary =
            new StructuredDocumentSummary(
                1, directText.length(), 1, 0, 0, Math.max(1, directText.split("\\R+").length), 0, 0, 0);
        return withVisualEvidence(
            ExtractionArtifact.full(result, policy, OcrRoutingConfig.PARSER_ID, ocr.truncated()),
            summary,
            "ocr_full",
            true,
            ocr.confidence(),
            false,
            ocrEvidence);
      }
    }
    // Single terminal classifier for the raster-image OCR path.
    return classifyNonImprovement(file, ocr.failureReason(), baselineQuality, ocrEvidence);
  }

  private ExtractionArtifact classifyNonImprovement(
      Path file, OcrSkipReason engineFailure, double baselineQuality, OcrEvidenceBuilder ocrEvidence) {
    if (engineFailure == OcrSkipReason.UNKNOWN) {
      ocrEvidence.skip(OcrSkipReason.UNKNOWN);
      ocrMetricCatalog.failedTotal.increment(
          OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, "OcrEngineFailure"));
      log.debug("OCR engine failed for {}", file.getFileName());
      return null;
    }
    OcrSkipReason reason =
        engineFailure != null ? engineFailure : OcrOutcomeClassifier.classifyNoImprovement(baselineQuality);
    ocrEvidence.skip(reason);
    ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(reason));
    log.debug("OCR did not improve extraction for {} (reason={})", file.getFileName(), reason);
    return null;
  }

  private ExtractionArtifact trySelectivePdfOcr(
      Path file,
      ExtractionResult baseline,
      StructuredDocumentSummary baselineSummary,
      OcrEvidenceBuilder ocrEvidence) {
    PdfVisualAnalyzer.PdfPageEvidence pageEvidence = PdfVisualAnalyzer.analyze(file);
    if (pageEvidence == null || !pageEvidence.mixed()) {
      return tryOcr(file, baseline, baselineSummary, ocrEvidence);
    }
    long startedAtNanos = System.nanoTime();
    String baselineText = baseline.content() == null ? "" : baseline.content().stripTrailing();
    int maxOcrChars = Math.max(0, policy.maxExtractedChars() - baselineText.length());
    List<Integer> missingPages = new ArrayList<>(pageEvidence.missingReadableTextPages());
    java.util.Collections.sort(missingPages);
    PdfOcrEngine.OcrEngineResult ocr = ocrEngine.ocrPdfPages(file, missingPages, maxOcrChars);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    ocrMetricCatalog.timeMs.record(elapsedMs, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));

    double baselineQuality =
        TextQualityAnalyzer.computeQualityScore(baseline.content(), baselineSummary.pageCount());
    String engineText = ocr.text();
    if (engineText != null && !engineText.isBlank()) {
      ocrEvidence.fallback(OCR_FALLBACK_RENDERED_PDF);
      ocrEvidence.truncated(ocr.truncated());
      String merged = baselineText + engineText;
      double mergedQuality =
          TextQualityAnalyzer.computeQualityScore(merged, baselineSummary.pageCount());
      if (mergedQuality >= baselineQuality) {
        ocrMetricCatalog.succeededTotal.increment(OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
        ExtractionResult mergedResult =
            new ExtractionResult(
                merged, baseline.title(), baseline.mimeType(), baseline.author(), baseline.frontmatterMetadata());
        StructuredDocumentSummary mergedSummary =
            new StructuredDocumentSummary(
                baselineSummary.pageCount(),
                merged.length(),
                baselineSummary.pageCount(),
                0,
                baselineSummary.headingCount(),
                baselineSummary.paragraphCount(),
                baselineSummary.tableCount(),
                baselineSummary.listCount(),
                baselineSummary.imagePageCount());
        return withVisualEvidence(
            ExtractionArtifact.full(
                mergedResult, policy, OcrRoutingConfig.PARSER_ID, ocr.truncated()),
            mergedSummary,
            "ocr_selective",
            true,
            ocr.confidence(),
            true,
            ocrEvidence);
      }
    }
    // Single terminal classifier for the selective PDF OCR path (a mixed PDF always has real
    // baseline text, so classifyNoImprovement here stays TEXTUAL — tempdoc 671).
    return classifyNonImprovement(file, ocr.failureReason(), baselineQuality, ocrEvidence);
  }

  private static StructuredDocumentSummary ocrCoveredPdfSummary(
      Path file, StructuredDocumentSummary base, String content) {
    StructuredDocumentSummary summary = base == null ? StructuredDocumentSummary.empty() : base;
    PdfVisualAnalyzer.PdfPageEvidence evidence = PdfVisualAnalyzer.analyze(file);
    int pages = Math.max(1, Math.max(summary.pageCount(), evidence == null ? 0 : evidence.pageCount()));
    int imagePages = evidence == null ? summary.imagePageCount() : evidence.imagePages().size();
    return new StructuredDocumentSummary(
        pages,
        content == null ? summary.textCharCount() : content.length(),
        pages,
        0,
        summary.headingCount(),
        summary.paragraphCount(),
        summary.tableCount(),
        summary.listCount(),
        Math.max(summary.imagePageCount(), imagePages));
  }

  private static boolean isOcrEligibleFile(Path file, String mimeType) {
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if ("application/pdf".equals(mime) || mime.startsWith("image/")) {
      return true;
    }
    String name =
        file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".pdf")
        || name.endsWith(".png")
        || name.endsWith(".jpg")
        || name.endsWith(".jpeg")
        || name.endsWith(".tif")
        || name.endsWith(".tiff")
        || name.endsWith(".bmp");
  }

  private static boolean isPdfFile(Path file, String mimeType) {
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if ("application/pdf".equals(mime)) {
      return true;
    }
    String name =
        file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".pdf");
  }

  private static boolean isRasterImageFile(Path file, String mimeType) {
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if (mime.startsWith("image/")) {
      return true;
    }
    String name =
        file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".png")
        || name.endsWith(".jpg")
        || name.endsWith(".jpeg")
        || name.endsWith(".tif")
        || name.endsWith(".tiff")
        || name.endsWith(".bmp");
  }

  private record ImageSize(int width, int height) {}

  record OcrAttemptDecision(boolean shouldAttempt, OcrSkipReason skipReason) {
    static OcrAttemptDecision yes() {
      return new OcrAttemptDecision(true, null);
    }

    static OcrAttemptDecision skip(OcrSkipReason reason) {
      return new OcrAttemptDecision(false, reason);
    }
  }

  private static final class OcrEvidenceBuilder {
    private boolean truncated;
    private String fallbackRoute;
    private OcrSkipReason skipReason;

    void truncated(boolean value) {
      truncated = truncated || value;
    }

    void fallback(String route) {
      if (route != null && !route.isBlank()) {
        fallbackRoute = route;
      }
    }

    void skip(OcrSkipReason reason) {
      if (reason != null && skipReason == null) {
        skipReason = reason;
      }
    }

    VisualExtractionEvidence.RoutingFacts facts(boolean artifactTruncated) {
      return VisualExtractionEvidence.RoutingFacts.of(
          truncated || artifactTruncated, fallbackRoute, skipReason);
    }
  }

  @Override
  public String detectMimeType(Path file) {
    try {
      return tika.detect(file);
    } catch (IOException e) {
      log.debug("MIME detection failed for {}: {}", file, e.getMessage());
      return "application/octet-stream";
    }
  }

}
