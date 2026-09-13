/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.List;
import java.util.Objects;

/** Worker-side OCR routing configuration derived from the resolved runtime config. */
public record OcrRoutingConfig(
    boolean enabled,
    List<String> languages,
    Integer perFileTimeoutMs,
    Integer maxPages,
    Integer maxImageDimension,
    Integer maxImagePixels,
    Integer renderDpi,
    Integer ocrWorkers) {
  public static final String PARSER_ID = "tika-policy-ocr";
  public static final String ENGINE = "tesseract";
  private static final List<String> DEFAULT_LANGUAGES = List.of("eng");
  private static final int DEFAULT_PER_FILE_TIMEOUT_MS = 30_000;
  // 706 founder-delegated decision (2026-07-10): raised 50 -> 200. The aggregate per-document
  // budget (DEFAULT_PER_FILE_TIMEOUT_MS) is the cost bound now that budget expiry returns
  // partial page-ordered text; this cap is only a sanity ceiling for pathological documents.
  // Corpus evidence (mixed/realdocs-v1): 14% of real PDFs exceed 50 pages; <2% exceed 200.
  private static final int DEFAULT_MAX_PAGES = 200;
  private static final int DEFAULT_MAX_IMAGE_DIMENSION = 4096;
  private static final int DEFAULT_MAX_IMAGE_PIXELS = 40_000_000;
  private static final int DEFAULT_RENDER_DPI = 300;
  /** 0 means "auto" — derive from available cores, see {@link PdfOcrEngine#defaultPoolSize()}. */
  private static final int AUTO_OCR_WORKERS = 0;

  public OcrRoutingConfig {
    languages =
        languages == null || languages.isEmpty()
            ? DEFAULT_LANGUAGES
            : languages.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    if (languages.isEmpty()) {
      languages = DEFAULT_LANGUAGES;
    }
  }

  public static OcrRoutingConfig disabled() {
    return new OcrRoutingConfig(false, DEFAULT_LANGUAGES, null, null, null, null, null, null);
  }

  public static OcrRoutingConfig defaults() {
    return new OcrRoutingConfig(
        true,
        DEFAULT_LANGUAGES,
        DEFAULT_PER_FILE_TIMEOUT_MS,
        DEFAULT_MAX_PAGES,
        DEFAULT_MAX_IMAGE_DIMENSION,
        DEFAULT_MAX_IMAGE_PIXELS,
        DEFAULT_RENDER_DPI,
        AUTO_OCR_WORKERS);
  }

  /**
   * Fills absent (null or non-positive) limits with the same safe defaults {@link #defaults()}
   * uses. Config-absent must never mean "no limits" — an all-null {@code ocr} section (as shipped
   * by the eval/headless config before this fix) previously let the page cap and image guards
   * never fire.
   */
  public static OcrRoutingConfig from(ResolvedConfig.Ocr ocr) {
    if (ocr == null) {
      return defaults();
    }
    boolean enabled = !Boolean.FALSE.equals(ocr.enabled());
    return new OcrRoutingConfig(
        enabled,
        ocr.languages(),
        positiveOrDefault(ocr.perFileTimeoutMs(), DEFAULT_PER_FILE_TIMEOUT_MS),
        positiveOrDefault(ocr.maxPages(), DEFAULT_MAX_PAGES),
        positiveOrDefault(ocr.maxImageDimension(), DEFAULT_MAX_IMAGE_DIMENSION),
        positiveOrDefault(ocr.maxImagePixels(), DEFAULT_MAX_IMAGE_PIXELS),
        positiveOrDefault(ocr.renderDpi(), DEFAULT_RENDER_DPI),
        positiveOrDefault(ocr.workers(), AUTO_OCR_WORKERS));
  }

  private static Integer positiveOrDefault(Integer value, int fallback) {
    return value == null || value <= 0 ? fallback : value;
  }

  public int tikaTimeoutSeconds() {
    int timeoutMs =
        perFileTimeoutMs == null || perFileTimeoutMs <= 0
            ? DEFAULT_PER_FILE_TIMEOUT_MS
            : perFileTimeoutMs;
    return Math.max(1, (int) Math.ceil(timeoutMs / 1000.0d));
  }

  public String tikaLanguage() {
    return String.join("+", languages);
  }

  /** Effective PDF render DPI: the configured value, or {@link #DEFAULT_RENDER_DPI}. */
  public int effectiveRenderDpi() {
    return renderDpi == null || renderDpi <= 0 ? DEFAULT_RENDER_DPI : renderDpi;
  }

  /**
   * Effective OCR worker pool size: the configured value, or the auto-derived
   * {@link PdfOcrEngine#defaultPoolSize()} when absent/0 ("auto").
   */
  public int effectiveOcrWorkers() {
    return ocrWorkers == null || ocrWorkers <= 0 ? PdfOcrEngine.defaultPoolSize() : ocrWorkers;
  }

  /**
   * Resolves OCR parallelism against the process-wide worker limit.
   *
   * <p>An explicit positive setting that exceeds the limit is a configuration error. Auto sizing
   * is clamped to the limit and always returns a positive worker count.
   */
  public OcrRoutingConfig withWorkerLimit(int maxWorkers) {
    if (maxWorkers <= 0) {
      throw new IllegalArgumentException("ocr maxWorkers must be positive");
    }
    int configured = ocrWorkers == null ? AUTO_OCR_WORKERS : ocrWorkers;
    if (configured > 0) {
      if (configured > maxWorkers) {
        throw new IllegalArgumentException(
            "ocr.workers=" + configured + " exceeds max worker limit " + maxWorkers);
      }
      return copyWithWorkers(configured);
    }
    return copyWithWorkers(Math.min(PdfOcrEngine.defaultPoolSize(), maxWorkers));
  }

  private OcrRoutingConfig copyWithWorkers(int workers) {
    return new OcrRoutingConfig(
        enabled,
        languages,
        perFileTimeoutMs,
        maxPages,
        maxImageDimension,
        maxImagePixels,
        renderDpi,
        workers);
  }
}
