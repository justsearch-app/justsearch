package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 628 Stage A (G1): unit tests for {@link ComponentsFactory#checkIndexIntegrity}.
 *
 * <p>The point of the integrity check is that Lucene's normal {@code DirectoryReader.open} validates
 * segment <em>headers</em> but does not recompute file <em>checksums</em>, so silent body bit-rot in a
 * crash-damaged segment can be served as wrong results with no signal. These tests prove the bounded
 * verification turns that silently-wrong class into a detected, classified {@code CORRUPT_INDEX}.
 */
class IndexIntegrityCheckTest extends RuntimeTestBase {

  private Path seedIndex(String name) throws Exception {
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve(name);
    Files.createDirectories(indexPath);
    IndexSchema schema = buildSchemaWithDim(4);
    RunningRuntime seed = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    for (int i = 0; i < 50; i++) {
      seed.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "doc-" + i,
                      SchemaFields.DOC_UID, "doc-" + i + "#0",
                      SchemaFields.CONTENT,
                          "content body number " + i + " with enough text to populate a segment file")));
    }
    seed.commitOps().commitAndTrack();
    seed.close();
    return indexPath;
  }

  @Test
  void cleanIndexPassesBothTiers() throws Exception {
    Path indexPath = seedIndex("clean-idx");
    try (Directory dir = new MMapDirectory(indexPath)) {
      assertDoesNotThrow(() -> ComponentsFactory.checkIndexIntegrity(dir, indexPath, /*fullScan=*/ true));
      assertDoesNotThrow(() -> ComponentsFactory.checkIndexIntegrity(dir, indexPath, /*fullScan=*/ false));
    }
  }

  @Test
  void fullScanDetectsSilentDataFileBodyCorruption() throws Exception {
    Path indexPath = seedIndex("corrupt-body-idx");
    Path dataFile = pickLargestDataFile(indexPath);
    flipMiddleByte(dataFile);

    try (Directory dir = new MMapDirectory(indexPath)) {
      IndexRuntimeIOException ex =
          assertThrows(
              IndexRuntimeIOException.class,
              () -> ComponentsFactory.checkIndexIntegrity(dir, indexPath, /*fullScan=*/ true),
              "FULL integrity scan must detect body bit-rot as CORRUPT_INDEX");
      assertEquals(IndexRuntimeIOException.Reason.CORRUPT_INDEX, ex.reason());
    }
  }

  @Test
  void structuralScanDetectsCommitFileCorruption() throws Exception {
    Path indexPath = seedIndex("corrupt-seg-idx");
    flipMiddleByte(segmentsFile(indexPath));

    try (Directory dir = new MMapDirectory(indexPath)) {
      IndexRuntimeIOException ex =
          assertThrows(
              IndexRuntimeIOException.class,
              () -> ComponentsFactory.checkIndexIntegrity(dir, indexPath, /*fullScan=*/ false),
              "STRUCTURAL scan must detect segments_N corruption as CORRUPT_INDEX");
      assertEquals(IndexRuntimeIOException.Reason.CORRUPT_INDEX, ex.reason());
    }
  }

  // ----- helpers -----

  private static Path segmentsFile(Path indexPath) throws IOException {
    try (Stream<Path> stream = Files.list(indexPath)) {
      return stream
          .filter(p -> p.getFileName().toString().startsWith("segments_"))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no segments_N file in " + indexPath));
    }
  }

  /** Largest non-{@code segments_N} file — the bulk data/compound file with a real body. */
  private static Path pickLargestDataFile(Path indexPath) throws IOException {
    try (Stream<Path> stream = Files.list(indexPath)) {
      return stream
          .filter(p -> !p.getFileName().toString().startsWith("segments_"))
          .max(Comparator.comparingLong(IndexIntegrityCheckTest::sizeOf))
          .orElseThrow(() -> new AssertionError("no data files in " + indexPath));
    }
  }

  private static long sizeOf(Path p) {
    try {
      return Files.size(p);
    } catch (IOException e) {
      return 0L;
    }
  }

  /**
   * Flips one bit of a byte in the middle of the file body (well away from the codec footer's stored
   * CRC), so {@code CodecUtil.checksumEntireFile} recomputes a mismatching CRC. Lucene's normal open
   * does not recompute this, which is exactly the silent-wrong gap the integrity check closes.
   */
  private static void flipMiddleByte(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    if (bytes.length < 32) {
      throw new AssertionError("file too small to corrupt safely: " + file + " (" + bytes.length + "B)");
    }
    int idx = bytes.length / 2;
    bytes[idx] = (byte) (bytes[idx] ^ 0xFF);
    Files.write(file, bytes);
  }
}
