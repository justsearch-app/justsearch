/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.services.LanguageUtils;
import io.justsearch.indexing.extraction.StructuredDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Extracts structured text content from files using Tika's SAX event stream.
 *
 * <p>Unlike {@link ContentExtractor} which uses {@code Tika.parseToString()} (discarding all
 * document structure), this extractor captures headings, tables, page boundaries, and lists via a
 * custom SAX handler, then serializes to search-optimized annotated text.
 *
 * <p>Falls back to {@link ContentExtractor} on any structured parsing failure, ensuring zero
 * regression risk.
 *
 * <p>This class is thread-safe — the {@link AutoDetectParser} and {@link Tika} instances are
 * thread-safe, and the SAX handler is created per-parse.
 */
public final class StructuredContentExtractor implements ContentExtractorProvider, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(StructuredContentExtractor.class);

  /** Default maximum content length (10MB of text). */
  private static final int DEFAULT_MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

  /** Maximum file size to attempt extraction (100MB). */
  private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

  /** Maximum file size for Office documents (30MB). POI OOM risk. */
  private static final long MAX_OFFICE_FILE_SIZE = 30 * 1024 * 1024;

  private final PreparedExtractionInput.Factory inputFactory;
  private final AutoDetectParser parser;
  private final Tika tika; // for detectMimeType only
  private final int maxContentLength;
  private final TikaExtractionPolicy policy;

  public StructuredContentExtractor() {
    this(DEFAULT_MAX_CONTENT_LENGTH);
  }

  public StructuredContentExtractor(int maxContentLength) {
    this(maxContentLength, TikaExtractionPolicy.defaults());
  }

  StructuredContentExtractor(int maxContentLength, TikaExtractionPolicy policy) {
    this(maxContentLength, policy, new PreparedExtractionInput.Factory());
  }

  StructuredContentExtractor(int maxContentLength, TikaExtractionPolicy policy,
      PreparedExtractionInput.Factory inputFactory) {
    this.inputFactory = inputFactory;
    // Text-named, text-byte files must not be routed to a binary parser by a magic-number
    // collision — see TextNameMagicConflictDetector (tempdoc 803).
    org.apache.tika.detect.Detector detector = TextNameMagicConflictDetector.wrapDefault();
    this.parser = new AutoDetectParser(detector);
    this.tika = new Tika(detector);
    this.maxContentLength = maxContentLength;
    this.policy = policy;
  }

  @Override
  public ContentExtractor.ExtractionResult extract(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    return extractWithStatus(file).result();
  }

  /**
   * Extraction result paired with the SAX handler's authoritative truncation flag. Callers that
   * need to surface truncation as an outcome (policy-driven extraction → SUCCESS_PARTIAL ledger
   * events) must use this method rather than post-checking {@code result.content().length()}; the
   * length-based check is structurally unable to fire when the input chunk boundary aligns exactly
   * with the cap.
   */
  public StructuredExtractionResult extractWithStatus(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    Objects.requireNonNull(file, "file");
    try (PreparedExtractionInput input = inputFactory.prepare(file, policy)) {
      return extractWithStatus(input);
    }
  }

  @Override
  public void close() throws IOException {
    inputFactory.close();
  }

  StructuredExtractionResult extractWithStatus(PreparedExtractionInput input)
      throws IOException, ContentExtractor.ExtractionException {
    Path file = input.source();
    validateFileForExtraction(input);

    if (input.size() == 0) {
      return new StructuredExtractionResult(
          new ContentExtractor.ExtractionResult("", null, "text/plain"),
          false,
          StructuredDocumentSummary.empty());
    }

    ParseContext context = parseContextWithMarkedPdfContent();
    EmbeddedResourceBudget budget =
        new EmbeddedResourceBudget(policy, input.size(), context, input.expansion());
    context.set(org.apache.tika.parser.Parser.class, parser);
    try {
      StructuredExtractionResult result = extractStructured(input, context);
      budget.check();
      return new StructuredExtractionResult(result.result(), result.truncated(), result.summary(),
          budget.resources(), budget.maxDepth());
    } catch (Exception e) {
      budget.check();
      if (e instanceof ContentExtractor.BudgetExceededException limit) throw limit;
      log.warn(
          "Structured extraction failed for {}, falling back to flat extraction",
          file.getFileName(),
          e);
      return new StructuredExtractionResult(
          new ContentExtractor(maxContentLength).extract(input, context, budget),
          false,
          StructuredDocumentSummary.empty(), budget.resources(), budget.maxDepth());
    }
  }

  private void validateFileForExtraction(PreparedExtractionInput input)
      throws IOException, ContentExtractor.ExtractionException {
    Path file = input.source();
    long fileSize = input.size();
    if (fileSize > MAX_FILE_SIZE) {
      log.warn("File too large for extraction: {} ({} bytes)", file, fileSize);
      throw new ContentExtractor.ExtractionException(
          "File too large: " + fileSize + " bytes (max: " + MAX_FILE_SIZE + ")");
    }

    if (fileSize > MAX_OFFICE_FILE_SIZE && input.isOfficeForLimits(tika)) {
      log.warn("Office file too large for extraction: {} ({} bytes)", file, fileSize);
      throw new ContentExtractor.ExtractionException(
          "Office file too large: " + fileSize + " bytes (max: " + MAX_OFFICE_FILE_SIZE + ")");
    }
  }

  /** Pairs an {@link ContentExtractor.ExtractionResult} with the SAX handler's truncation flag. */
  public record StructuredExtractionResult(
      ContentExtractor.ExtractionResult result,
      boolean truncated,
      StructuredDocumentSummary summary,
      int embeddedResourceCount,
      int maxEmbeddedDepth) {
    public StructuredExtractionResult(ContentExtractor.ExtractionResult result,
        boolean truncated, StructuredDocumentSummary summary) {
      this(result, truncated, summary, 0, 0);
    }
    public int pageCount() {
      return summary == null ? 0 : summary.pageCount();
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

  private StructuredExtractionResult extractStructured(PreparedExtractionInput input, ParseContext parseContext)
      throws IOException, TikaException, SAXException {
    Path file = input.source();
    log.debug("Structured extraction from: {} ({} bytes)", file.getFileName(), input.size());

    StructuredContentHandler handler = new StructuredContentHandler(maxContentLength);
    Metadata metadata = input.metadata();

    // Enable marked content extraction for tagged PDFs — this enables table, heading,
    // and list extraction for the subset of PDFs that have accessibility tags.
    // Falls back gracefully for untagged PDFs (no additional cost).
    // PDFParserConfig is in tika-parsers-standard (runtimeOnly), so we configure via reflection
    // to avoid a compile-time dependency.
    try (InputStream is = input.openParserStream(tika)) {
      parser.parse(is, handler, metadata, parseContext);
    }

    StructuredDocument doc = handler.getDocument();

    // Remove repeated headers/footers for multi-page documents
    doc = doc.removeHeadersFooters();

    StructuredDocument.AnnotatedText annotated = doc.toAnnotatedText(maxContentLength);
    String content = annotated.text();
    String mimeType = metadata.get(Metadata.CONTENT_TYPE);
    String title = metadata.get(TikaCoreProperties.TITLE);

    // Fallback: YAML frontmatter title (same as ContentExtractor)
    Map<String, String> frontmatterMeta = Map.of();
    if (content != null && content.startsWith("---")) {
      if (title == null) {
        title = ContentExtractor.extractFrontmatterTitle(content);
      }
      frontmatterMeta = LanguageUtils.extractFrontmatterMetadata(content);
    }

    log.debug(
        "Structured extraction: {} chars, {} elements, {} pages from {} (type: {})",
        content.length(),
        doc.elements().size(),
        doc.pageCount(),
        file.getFileName(),
        mimeType);

    return new StructuredExtractionResult(
        new ContentExtractor.ExtractionResult(content, title, mimeType, null, frontmatterMeta),
        handler.isLimitReached() || annotated.truncated(),
        StructuredDocumentSummary.fromDocument(doc));
  }

  private static ParseContext parseContextWithMarkedPdfContent() {
    ParseContext parseContext = new ParseContext();
    configurePdfMarkedContent(parseContext);
    configureTesseractSkipOcr(parseContext);
    return parseContext;
  }

  /**
   * Configures PDF marked content extraction via reflection. PDFParserConfig is in
   * tika-parsers-standard (runtimeOnly dependency), so we avoid a compile-time import.
   */
  @SuppressWarnings("unchecked")
  private static void configurePdfMarkedContent(ParseContext parseContext) {
    try {
      Class<?> configClass = Class.forName("org.apache.tika.parser.pdf.PDFParserConfig");
      Object config = configClass.getDeclaredConstructor().newInstance();
      configClass
          .getMethod("setExtractMarkedContent", boolean.class)
          .invoke(config, true);
      configurePdfOcrStrategy(configClass, config, "NO_OCR");
      invokeIfPresent(configClass, config, "setExtractInlineImages", boolean.class, false);
      // ParseContext.set(Class<T>, T) — use raw types to match
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, configClass, config);
      log.debug("PDF marked content extraction enabled");
    } catch (Exception e) {
      log.debug("Could not enable PDF marked content extraction: {}", e.getMessage());
      // Non-fatal: structured extraction still works for page boundaries and paragraphs
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void configurePdfOcrStrategy(
      Class<?> configClass, Object config, String strategyName) {
    try {
      Class<?> strategyClass =
          Class.forName("org.apache.tika.parser.pdf.PDFParserConfig$OCR_STRATEGY");
      Object strategy =
          Enum.valueOf((Class<Enum>) strategyClass.asSubclass(Enum.class), strategyName);
      configClass.getMethod("setOcrStrategy", strategyClass).invoke(config, strategy);
    } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
      // Tika version differences are handled by configuring only supported setters.
    }
  }

  private static void configureTesseractSkipOcr(ParseContext parseContext) {
    try {
      Class<?> configClass = Class.forName("org.apache.tika.parser.ocr.TesseractOCRConfig");
      Object tesseract = configClass.getDeclaredConstructor().newInstance();
      invokeIfPresent(configClass, tesseract, "setSkipOcr", boolean.class, true);
      invokeIfPresent(configClass, tesseract, "setSkipOCR", boolean.class, true);
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, configClass, tesseract);
      Class<?> parserClass = Class.forName("org.apache.tika.parser.ocr.TesseractOCRParser");
      Object parser = parserClass.getDeclaredConstructor().newInstance();
      invokeIfPresent(parserClass, parser, "setSkipOCR", boolean.class, true);
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, parserClass, parser);
    } catch (Exception e) {
      log.debug("Could not disable first-pass Tesseract OCR: {}", e.getMessage());
    }
  }

  private static void invokeIfPresent(
      Class<?> targetClass, Object target, String method, Class<?> parameterType, Object value) {
    try {
      targetClass.getMethod(method, parameterType).invoke(target, value);
    } catch (ReflectiveOperationException ignored) {
      // Tika version differences are handled by configuring only supported setters.
    }
  }
}
