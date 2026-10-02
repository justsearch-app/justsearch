/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts text content from files using Apache Tika.
 *
 * <p>Supports a wide range of document formats including:
 * <ul>
 *   <li>Plain text (txt, md, java, py, etc.)</li>
 *   <li>PDF documents</li>
 *   <li>Microsoft Office (docx, xlsx, pptx)</li>
 *   <li>OpenDocument (odt, ods, odp)</li>
 *   <li>HTML and XML</li>
 *   <li>And many more via Tika's auto-detection</li>
 * </ul>
 *
 * <p>This class is thread-safe as {@link Tika} instances are thread-safe.
 */
public final class ContentExtractor implements ContentExtractorProvider {
  private static final Logger log = LoggerFactory.getLogger(ContentExtractor.class);

  /** Default maximum content length (10MB of text). */
  private static final int DEFAULT_MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

  /** Maximum file size to attempt extraction (100MB). */
  private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

  /**
   * Maximum file size for Office documents (30MB). POI requires 10-20x the file size in heap
   * memory during parsing. With a 512MB Worker heap, files above this threshold risk OOM. See
   * TIKA-2109, TIKA-4474.
   */
  private static final long MAX_OFFICE_FILE_SIZE = 30 * 1024 * 1024;

  private final Tika tika;
  private final int maxContentLength;

  /** Creates a new ContentExtractor with default settings. */
  public ContentExtractor() {
    this(DEFAULT_MAX_CONTENT_LENGTH);
  }

  /**
   * Creates a new ContentExtractor with specified max content length.
   *
   * @param maxContentLength Maximum characters to extract from any document
   */
  public ContentExtractor(int maxContentLength) {
    // Same detector as the structured extractor, so this flat fallback cannot disagree with it
    // about what is text (tempdoc 803).
    this.tika = new Tika(TextNameMagicConflictDetector.wrapDefault());
    this.maxContentLength = maxContentLength;
    // Set default max string length for Tika parser
    this.tika.setMaxStringLength(maxContentLength);
  }

  /**
   * Extracts text content from a file.
   *
   * @param file The file to extract content from
   * @return Extracted text content, or empty string if extraction fails
   * @throws IOException if the file cannot be read
   * @throws ExtractionException if Tika parsing fails
   */
  @Override
  public ExtractionResult extract(Path file) throws IOException, ExtractionException {
    org.apache.tika.parser.ParseContext context = new org.apache.tika.parser.ParseContext();
    EmbeddedResourceBudget budget = new EmbeddedResourceBudget(
        TikaExtractionPolicy.defaults(), Files.size(file), context);
    return extract(file, context, budget);
  }

  ExtractionResult extract(Path file, org.apache.tika.parser.ParseContext context,
      EmbeddedResourceBudget budget) throws IOException, ExtractionException {
    Objects.requireNonNull(file, "file");

    if (!Files.exists(file)) {
      throw new IOException("File does not exist: " + file);
    }

    if (!Files.isReadable(file)) {
      throw new IOException("File is not readable: " + file);
    }

    long fileSize = Files.size(file);
    if (fileSize > MAX_FILE_SIZE) {
      log.warn("File too large for extraction: {} ({} bytes)", file, fileSize);
      throw new BudgetExceededException(
          "File too large: " + fileSize + " bytes (max: " + MAX_FILE_SIZE + ")",
          "INPUT_TOO_LARGE");
    }

    // Stricter limit for Office documents — POI expands them 10-20x in memory.
    // Only pay the tika.detect() cost for files that exceed the Office threshold.
    if (fileSize > MAX_OFFICE_FILE_SIZE && isOfficeMimeType(tika.detect(file))) {
      log.warn("Office file too large for extraction: {} ({} bytes)", file, fileSize);
      throw new BudgetExceededException(
          "Office file too large: "
              + fileSize
              + " bytes (max: "
              + MAX_OFFICE_FILE_SIZE
              + ")",
          "OFFICE_INPUT_TOO_LARGE");
    }

    if (fileSize == 0) {
      return new ExtractionResult("", null, "text/plain");
    }

    log.debug("Extracting content from: {} ({} bytes)", file.getFileName(), fileSize);

    Metadata metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());

    try (InputStream is = Files.newInputStream(file)) {
      org.apache.tika.sax.BodyContentHandler handler =
          new org.apache.tika.sax.BodyContentHandler(maxContentLength);
      context.set(org.apache.tika.parser.Parser.class, tika.getParser());
      try {
        tika.getParser().parse(is, handler, metadata, context);
      } catch (org.xml.sax.SAXException e) {
        if (!org.apache.tika.exception.WriteLimitReachedException.isWriteLimitReached(e)) {
          throw new TikaException("Flat extraction failed", e);
        }
      }
      budget.check();
      String content = handler.toString();
      String mimeType = metadata.get(Metadata.CONTENT_TYPE);
      String title = metadata.get(TikaCoreProperties.TITLE);
      // Fallback: extract title from YAML frontmatter (Tika doesn't parse markdown frontmatter)
      java.util.Map<String, String> frontmatterMeta = java.util.Map.of();
      if (content != null && content.startsWith("---")) {
        if (title == null) {
          title = extractFrontmatterTitle(content);
        }
        frontmatterMeta = io.justsearch.indexerworker.services.LanguageUtils.extractFrontmatterMetadata(content);
      }

      // Author/sender: Tika sets dc:creator for emails (From header) and Office docs (Author).
      String author = metadata.get(TikaCoreProperties.CREATOR);

      log.debug("Extracted {} chars from {} (type: {})",
          content.length(), file.getFileName(), mimeType);

      return new ExtractionResult(content, title, mimeType, author, frontmatterMeta);

    } catch (TikaException e) {
      budget.check();
      log.warn("Tika extraction failed for {}", file.getFileName(), e);
      throw new ExtractionException("Failed to extract content: " + e.getMessage(), e);
    } catch (RuntimeException | IOException e) {
      budget.check();
      throw e;
    }
  }

  /**
   * Attempts to extract content, returning empty string on failure.
   *
   * <p>This is a convenience method that catches exceptions and logs warnings.
   * Use {@link #extract(Path)} for more control over error handling.
   *
   * @param file The file to extract content from
   * @return Extracted text content, or empty string if extraction fails
   */
  public String extractSafe(Path file) {
    try {
      return extract(file).content();
    } catch (IOException e) {
      log.warn("IO error extracting content from {}", file, e);
      return "";
    } catch (ExtractionException e) {
      log.warn("Extraction failed for {}", file, e);
      return "";
    }
  }

  /**
   * Detects the MIME type of a file without fully parsing it.
   *
   * @param file The file to detect
   * @return The detected MIME type, or "application/octet-stream" if unknown
   */
  @Override
  public String detectMimeType(Path file) {
    try {
      return tika.detect(file);
    } catch (IOException e) {
      log.debug("MIME detection failed for {}: {}", file, e.getMessage());
      return "application/octet-stream";
    }
  }

  /**
   * Extracts the title from YAML frontmatter in markdown content. Expects format: {@code
   * ---\ntitle: My Title\n...\n---}
   *
   * @return the title string, or null if no frontmatter or no title key found
   */
  static String extractFrontmatterTitle(String content) {
    if (content == null || !content.startsWith("---")) {
      return null;
    }
    int endIdx = content.indexOf("\n---", 3);
    if (endIdx < 0) {
      return null;
    }
    String frontmatter = content.substring(3, endIdx);
    for (String line : frontmatter.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("title:")) {
        String value = trimmed.substring(6).trim();
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
          value = value.substring(1, value.length() - 1);
        }
        return value.isBlank() ? null : value;
      }
    }
    return null;
  }

  /**
   * Result of content extraction.
   *
   * @param content The extracted text content
   * @param title The document title (may be null)
   * @param mimeType The detected MIME type
   * @param author The document author/sender (may be null; populated from Tika dc:creator)
   * @param frontmatterMetadata Structured metadata from YAML frontmatter (empty map if none)
   */
  public record ExtractionResult(String content, String title, String mimeType, String author,
                                  java.util.Map<String, String> frontmatterMetadata) {
    /** Backward-compatible constructor without frontmatter metadata. */
    public ExtractionResult(String content, String title, String mimeType, String author) {
      this(content, title, mimeType, author, java.util.Map.of());
    }

    /** Backward-compatible constructor without author or frontmatter metadata. */
    public ExtractionResult(String content, String title, String mimeType) {
      this(content, title, mimeType, null, java.util.Map.of());
    }

    public ExtractionResult {
      content = content == null ? "" : content;
      mimeType = mimeType == null ? "application/octet-stream" : mimeType;
      frontmatterMetadata = frontmatterMetadata == null ? java.util.Map.of() : frontmatterMetadata;
      title = boundTitle(title);
    }

    /**
     * A sane cap for the indexed title (observation #379 follow-up). Chosen well ABOVE the ~370-char
     * normal-title range (per observation #380's tempdoc-title survey) yet far UNDER the
     * {@link ExtractionArtifact} scalar-metadata validation backstop (4096): a giant frontmatter
     * {@code title:} is truncated to a readable length rather than indexed as a multi-thousand-char
     * wall of text — and the artifact still validates, so the document indexes instead of
     * permanently failing and looping on every re-sync.
     */
    static final int MAX_INDEXED_TITLE_CHARS = 512;

    /**
     * Bounds an over-long title to {@link #MAX_INDEXED_TITLE_CHARS}. Titles within the cap are
     * returned unchanged; {@code null} stays {@code null}. The truncation point never splits a UTF-16
     * surrogate pair (titles may contain emoji), and a trailing ellipsis marks the truncation.
     */
    private static String boundTitle(String title) {
      if (title == null || title.length() <= MAX_INDEXED_TITLE_CHARS) {
        return title;
      }
      int cut = MAX_INDEXED_TITLE_CHARS - 1; // leave room for the ellipsis
      if (cut > 0 && Character.isHighSurrogate(title.charAt(cut - 1))) {
        cut -= 1; // don't strand a high surrogate without its low half
      }
      return title.substring(0, cut) + "…";
    }

    /** Returns true if this is a text-based document. */
    public boolean isTextBased() {
      return mimeType != null && (
          mimeType.startsWith("text/") ||
          mimeType.contains("json") ||
          mimeType.contains("xml") ||
          mimeType.contains("javascript")
      );
    }

    /** Returns true if this is a PDF document. */
    public boolean isPdf() {
      return "application/pdf".equals(mimeType);
    }

    /** Returns true if this is a Microsoft Office document. */
    public boolean isOffice() {
      return isOfficeMimeType(mimeType);
    }
  }

  static boolean isOfficeMimeType(String mimeType) {
    return mimeType != null
        && (mimeType.contains("officedocument")
            || mimeType.contains("msword")
            || mimeType.contains("ms-excel")
            || mimeType.contains("ms-powerpoint"));
  }

  /** Exception thrown when content extraction fails. */
  public static class ExtractionException extends Exception {
    public ExtractionException(String message) {
      super(message);
    }

    public ExtractionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Extraction failure caused by a declared ingestion budget. */
  public static final class BudgetExceededException extends ExtractionException {
    private final String reasonCode;

    public BudgetExceededException(String message, String reasonCode) {
      super(message);
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }
  }
}
