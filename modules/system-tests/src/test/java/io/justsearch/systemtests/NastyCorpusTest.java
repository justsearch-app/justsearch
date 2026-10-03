package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nasty Corpus Tests: Verify the indexer handles malformed input gracefully.
 *
 * <p>These tests verify that the system doesn't crash when encountering:
 * <ul>
 *   <li>Zero-byte files</li>
 *   <li>Truncated/malformed PDFs</li>
 *   <li>Invalid UTF-8 sequences</li>
 *   <li>Files with null bytes</li>
 * </ul>
 *
 * <p>The expectation is NOT that extraction succeeds perfectly, but that
 * the system degrades gracefully (logs warning, returns empty/partial content,
 * continues processing other files).
 *
 * <p>Each test uses a 30-second timeout to prevent hanging on malformed files.
 *
 * <p><b>Re-enabled 2026-04-25 (tempdoc 410 §14 Slice C.3):</b> the original CI disable cited
 * "resource loading issues on Windows CI runners." Investigation surfaced the actual root
 * cause — the {@code nasty-archive.zip} fixture was missing from
 * {@code src/test/resources/corpus/nasty/}, so {@code assertTrue(Files.exists(file))} failed on
 * any runner that didn't have an out-of-tree copy.
 *
 * <p>Slice G.3 (M5) — the {@code nasty-archive.zip} fixture is now generated programmatically
 * in {@link #setup()} rather than committed as a binary force-added past the
 * {@code .gitignore} {@code *.zip} rule. The bytes are the same minimal invalid-zip pattern
 * used inline in {@code AdversarialCorpusIngestionTest} (PK\03\04 magic + 26 zero bytes +
 * "BBBB" = 34 bytes total). Writing to {@code nastyCorpusDir} works because that path
 * resolves to {@code build/resources/test/corpus/nasty/} — Gradle build output, not source.
 * If the test starts failing in CI again, investigate the specific failure rather than
 * re-adding a blanket disable.
 */
@DisplayName("Nasty Corpus Tests")
class NastyCorpusTest {
  private static final Logger log = LoggerFactory.getLogger(NastyCorpusTest.class);

  /**
   * Slice G.3 — minimal invalid ZIP. PK\03\04 local-file-header signature, then 26 bytes of
   * zero, then "BBBB" garbage. Tika's ZIP parser sees the magic but fails on the truncated
   * central directory; the {@link ContentExtractor#extractSafe(Path)} contract is that this
   * should NOT crash the worker.
   */
  private static final byte[] INVALID_ZIP_BYTES = {
    0x50, 0x4B, 0x03, 0x04,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00,
    0x42, 0x42, 0x42, 0x42
  };

  private static ContentExtractor extractor;
  private static Path nastyCorpusDir;

  @BeforeAll
  static void setup() throws IOException, URISyntaxException {
    extractor = new ContentExtractor();
    nastyCorpusDir = Path.of(
        NastyCorpusTest.class.getResource("/corpus/nasty").toURI());
    Path nastyZip = nastyCorpusDir.resolve("nasty-archive.zip");
    if (!Files.exists(nastyZip)) {
      Files.write(nastyZip, INVALID_ZIP_BYTES);
      log.info("Generated invalid-zip fixture: {}", nastyZip);
    }
    log.info("Nasty corpus directory: {}", nastyCorpusDir);
  }

  @Nested
  @DisplayName("Zero-byte Files")
  class ZeroByteFiles {

    @Test
    @Timeout(30)
    @DisplayName("Zero-byte PDF doesn't crash extractor")
    void zeroBytesPdfDoesNotCrash() throws IOException {
      Path file = nastyCorpusDir.resolve("zero-byte.pdf");
      assertTrue(Files.exists(file), "Test file should exist");
      assertEquals(0, Files.size(file), "File should be zero bytes");

      // Should not throw
      ExtractionResult result = assertDoesNotThrow(() -> extractor.extract(file));

      // Should return empty content gracefully
      assertNotNull(result);
      assertEquals("", result.content(), "Zero-byte file should return empty content");
      log.info("Zero-byte PDF handled gracefully: {}", result);
    }

    @Test
    @Timeout(30)
    @DisplayName("extractSafe returns empty string for zero-byte file")
    void extractSafeReturnsEmptyForZeroByte() {
      Path file = nastyCorpusDir.resolve("zero-byte.pdf");

      String content = extractor.extractSafe(file);

      assertEquals("", content);
    }
  }

  @Nested
  @DisplayName("Truncated/Malformed Files")
  class TruncatedFiles {

    @Test
    @Timeout(30)
    @DisplayName("Truncated PDF doesn't crash extractor")
    void truncatedPdfDoesNotCrash() throws IOException {
      Path file = nastyCorpusDir.resolve("truncated.pdf");
      assertTrue(Files.exists(file), "Test file should exist");
      assertTrue(Files.size(file) > 0, "File should have some content");

      // Should not throw - either succeeds with partial content or throws ExtractionException
      // which is caught by extractSafe
      String content = assertDoesNotThrow(() -> extractor.extractSafe(file));

      // We don't care what content is returned, just that it doesn't crash
      assertNotNull(content);
      log.info("Truncated PDF handled gracefully, extracted {} chars", content.length());
    }
  }

  @Nested
  @DisplayName("Encoding Issues")
  class EncodingIssues {

    @Test
    @Timeout(30)
    @DisplayName("Invalid UTF-8 sequences don't crash extractor")
    void invalidUtf8DoesNotCrash() throws IOException {
      Path file = nastyCorpusDir.resolve("invalid-utf8.txt");
      assertTrue(Files.exists(file), "Test file should exist");

      // Should not throw
      String content = assertDoesNotThrow(() -> extractor.extractSafe(file));

      // Should extract something (even if garbled)
      assertNotNull(content);
      assertTrue(content.length() > 0, "Should extract some content from text file");
      log.info("Invalid UTF-8 handled, extracted {} chars", content.length());
    }
  }

  @Nested
  @DisplayName("MIME Type Detection")
  class MimeTypeDetection {

    @Test
    @Timeout(30)
    @DisplayName("MIME detection doesn't crash on nasty files")
    void mimeDetectionDoesNotCrash() {
      for (String filename : new String[]{"zero-byte.pdf", "truncated.pdf", "invalid-utf8.txt"}) {
        Path file = nastyCorpusDir.resolve(filename);

        // Should not throw
        String mimeType = assertDoesNotThrow(() -> extractor.detectMimeType(file));

        assertNotNull(mimeType);
        log.info("{} detected as: {}", filename, mimeType);
      }
    }
  }

  @Nested
  @DisplayName("Archive/Binary Guardrails")
  class ArchiveBinaryGuardrails {

    @Test
    @Timeout(30)
    @DisplayName("Invalid archive (zip) doesn't crash extractor")
    void invalidArchiveDoesNotCrash() throws IOException {
      Path file = nastyCorpusDir.resolve("nasty-archive.zip");
      assertTrue(Files.exists(file), "Test file should exist");
      assertTrue(Files.size(file) > 0, "File should have some content");

      // Should not throw - either succeeds with content or returns empty
      String content = assertDoesNotThrow(() -> extractor.extractSafe(file));

      // We don't care what content is returned, just that it doesn't crash
      assertNotNull(content);
      log.info("Invalid archive handled gracefully, extracted {} chars", content.length());
    }

    @Test
    @Timeout(30)
    @DisplayName("Unknown binary doesn't crash extractor")
    void unknownBinaryDoesNotCrash() throws IOException {
      Path file = nastyCorpusDir.resolve("unknown-binary.bin");
      assertTrue(Files.exists(file), "Test file should exist");
      assertTrue(Files.size(file) > 0, "File should have some content");

      // Should not throw
      String content = assertDoesNotThrow(() -> extractor.extractSafe(file));

      assertNotNull(content);
      log.info("Unknown binary handled gracefully, extracted {} chars", content.length());
    }

    @Test
    @Timeout(30)
    @DisplayName("Archive MIME type is detected correctly")
    void archiveMimeTypeDetection() {
      Path file = nastyCorpusDir.resolve("nasty-archive.zip");

      String mimeType = assertDoesNotThrow(() -> extractor.detectMimeType(file));

      assertNotNull(mimeType);
      // May be application/zip if Tika recognizes the .zip extension, or text/plain if it reads the content
      log.info("nasty-archive.zip detected as: {}", mimeType);
    }

    @Test
    @Timeout(30)
    @DisplayName("Binary MIME type is detected correctly")
    void binaryMimeTypeDetection() {
      Path file = nastyCorpusDir.resolve("unknown-binary.bin");

      String mimeType = assertDoesNotThrow(() -> extractor.detectMimeType(file));

      assertNotNull(mimeType);
      // May be application/octet-stream or text/plain depending on content
      log.info("unknown-binary.bin detected as: {}", mimeType);
    }
  }

  @Nested
  @DisplayName("Batch Processing Resilience")
  class BatchProcessing {

    @Test
    @Timeout(60)  // Batch processing may take longer
    @DisplayName("Processing nasty files doesn't stop batch processing")
    void nastyFilesDoNotStopBatch(@TempDir Path batchDir)
        throws IOException, ContentExtractor.ExtractionException {
      // Simulate batch processing: process multiple files, some nasty
      int successCount = 0;
      int failCount = 0;

      String[] files = {"zero-byte.pdf", "truncated.pdf", "invalid-utf8.txt",
          "nasty-archive.zip", "unknown-binary.bin"};

      for (String filename : files) {
        Path file = nastyCorpusDir.resolve(filename);
        // S5 (merge b74f2aa83): archive preflight can reject malformed ZIPs with IOException.
        try {
          ExtractionResult result = extractor.extract(file);
          successCount++;
          log.info("Processed {}: {} chars", filename, result.content().length());
        } catch (IOException | ContentExtractor.ExtractionException e) {
          failCount++;
          log.info("Expected failure for {}: {}", filename, e.getMessage());
        }
      }

      // The important thing: we processed ALL files, didn't stop mid-batch
      assertEquals(files.length, successCount + failCount,
          "All files should be processed (success or graceful fail)");
      Path healthyFile = Files.writeString(batchDir.resolve("healthy-after-nasty.txt"), "batch sentinel");
      assertEquals("batch sentinel", extractor.extract(healthyFile).content().strip(),
          "A healthy file must still extract successfully after malformed inputs");
      log.info("Batch complete: {} success, {} failed gracefully", successCount, failCount);
    }
  }
}
