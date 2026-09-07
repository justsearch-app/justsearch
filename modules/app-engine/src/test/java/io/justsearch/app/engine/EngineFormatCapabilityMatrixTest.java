/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState;
import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState.ExpectedState;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.process.FormatCapabilityMatrixE2ETest}.
 *
 * <p><b>What the retired test asserted, and why it survives the collapse.</b> It is
 * deterministic-by-fixture production-path acceptance: {@code FormatCapabilityFixtureFactory}
 * generates one byte-identical file per matrix row (EML, MBOX, RTF, EPUB, ODT, three XLSX
 * shapes, PPTX-with-notes, ZIP-containing-XLSX), and every row must survive admission,
 * extraction, indexing, search, fetch and ledger reconciliation with the end state
 * {@code FormatCapabilityExpectedState} declares for it — exact extracted text, stored path,
 * MIME, structured-element counts in the visual-extraction evidence, and one privacy-safe
 * ingestion-ledger event carrying the extraction policy and parser adapter that produced it.
 * Not one of those is a property of the second process. Admission, extraction, the index and
 * the ledger are all the index half, which {@link EngineRoot} now composes in this JVM, so the
 * whole matrix carries over with its subject intact.
 *
 * <p><b>What was dropped, and why.</b> Only the process scaffolding: the {@code @BeforeAll}
 * spawn / {@code awaitPort} / {@code isHealthy} handshake (now {@link EngineTestHarness#start},
 * which either returns a working client or throws), the {@code mmf.keepAlive()} calls and the
 * {@code ScheduledExecutorService} that drove them, and {@code assertHeartbeatHealthy()} with
 * its {@code HEARTBEAT_FAILURE} reference. Those fed the memory-mapped suicide-pact heartbeat
 * that stopped a spawned Worker self-terminating when its parent vanished. There is no second
 * process, no signal file and no watchdog, so there is nothing for them to assert.
 *
 * <p><b>The ledger changed shape, not meaning.</b> The retired test read the ledger as protos
 * ({@code RecentIngestionEventsResponse} / {@code IngestionEvent}). The in-process facade
 * returns {@code List<Map<String,Object>>} — the same {@code IngestionEvent} fields, projected
 * key-by-key at KnowledgeClient.java:1298-1317. Every field the retired assertions used has a
 * counterpart there: {@code getPathHash()} → {@code "pathHash"}, {@code getOutcomeClass()} →
 * {@code "outcomeClass"}, {@code getArtifactStatus()} → {@code "artifactStatus"},
 * {@code getPolicyId()} → {@code "policyId"}, {@code getParserId()} → {@code "parserId"}.
 * Nothing was dropped for want of a counterpart.
 *
 * <p><b>Two places where this is deliberately more precise than the original, stated rather
 * than smoothed over.</b>
 *
 * <ol>
 *   <li><b>The row's search hit is selected by result id, not by the {@code path} field.</b>
 *       The retired test picked its hit with
 *       {@code result.getFieldsOrDefault(SchemaFields.PATH, "")}. That selector is only safe
 *       for whole-document hits: a <em>chunk</em> hit's projected fields come from the chunk
 *       stored-field allowlist, which does not contain {@code path}
 *       (ChunkSearchOps.java:339-354), and the parent-metadata enrichment that follows lifts
 *       only {@code doc_uid} / {@code content_sha256} / {@code title} / {@code filename}
 *       (SearchResponseBuilder.java:647-671). So a row that surfaced as a chunk hit would have
 *       been filtered out and reported as "not returned for its unique marker" — a red for
 *       entirely the wrong reason. The result id is populated on both branches and is the same
 *       identity either way: a chunk hit reports its parent's id
 *       (SearchResponseBuilder.java:483-490), and a parent document's {@code doc_id} is exactly
 *       the normalized absolute path this test already computes
 *       (IndexingDocumentOps.java:162/172). Selecting on it is the same selection, read from a
 *       field that is always there.
 *   <li><b>The ledger snapshot and each row's hit are polled for, not read once.</b>
 *       {@code awaitIndexed} converges on queue-drain plus doc count, which does not by itself
 *       order the ledger write or the searcher refresh against the count. The retired test read
 *       the ledger immediately afterwards and searched immediately afterwards; in-process those
 *       become bounded polls so a slow refresh is a wait rather than a flake. The assertions
 *       they gate are unchanged — the ledger poll waits for at least ten matrix events and the
 *       test still asserts <em>exactly</em> ten, so a timed-out poll fails rather than passes.
 * </ol>
 *
 * <p><b>Why the marker query is genuinely selective, and where it is not.</b> The analysis
 * chain is {@code ICUTokenizer} → {@code ICUNormalizer2Filter} → {@code LowerCaseFilter}
 * (SsotAnalyzerRegistry.java:148-150). UAX-29 treats {@code _} as {@code ExtendNumLet}, which
 * joins the segments either side of it, so {@code JUSTSEARCH_EML_SUBJECT_MARKER} analyses to
 * one token rather than four shared ones — the markers do not bleed across rows through a
 * split. They do bleed one legitimate way: {@code ZIP_WITH_XLSX} embeds the workbook whose
 * marker {@code XLSX} searches for, so that one query matches two documents by design. That is
 * why the hit is selected by identity rather than taken from rank, and it is also why hit
 * <em>presence</em> alone is not the proof that the marker landed in the right document — the
 * exact-content assertion against {@code expected.exactAnnotatedText()} is.
 *
 * <p><b>One shape change with no assertion behind it.</b> The retired test was a single
 * {@code @Test} looping over {@code FormatId.values()}, so the first failing row hid the other
 * nine. The per-row body is a {@code @ParameterizedTest} here; every assertion is the same, but
 * a red now names the rows that actually failed.
 */
@DisplayName("Engine format capability matrix production path (in-process)")
@Timeout(600)
final class EngineFormatCapabilityMatrixTest {

  private static final int SEARCH_LIMIT = 20;
  private static final int SLICE_CHARS = 64;
  private static final int LEDGER_LIMIT = 100;
  private static final long INDEX_TIMEOUT_MS = 120_000L;
  private static final long SEARCH_TIMEOUT_MS = 60_000L;
  private static final long LEDGER_TIMEOUT_MS = 60_000L;
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir static Path tempDir;

  private static final Map<FormatId, Path> FIXTURE_PATHS = new EnumMap<>(FormatId.class);
  private static final Map<FormatId, String> NORMALIZED_PATHS = new EnumMap<>(FormatId.class);
  private static final Set<String> MATRIX_PATH_HASHES = new HashSet<>();

  private static EngineTestHarness harness;

  /** The matrix rows' ledger events, snapshotted once after indexing converged. */
  private static List<Map<String, Object>> ledger;

  @BeforeAll
  static void indexTheMatrix() throws Exception {
    // A sibling of the Engine's data directory, never a parent of it: nothing here should be
    // able to enqueue the Lucene index into itself.
    Path fixtureDirectory = tempDir.resolve("format-capability");
    List<Path> fixtures = new ArrayList<>();
    Set<String> searchMarkers = new HashSet<>();
    for (FormatId id : FormatId.values()) {
      Path path =
          FormatCapabilityFixtureFactory.write(fixtureDirectory, id).toAbsolutePath().normalize();
      String normalizedPath = PathNormalizer.normalizePath(path.toString());
      FIXTURE_PATHS.put(id, path);
      NORMALIZED_PATHS.put(id, normalizedPath);
      MATRIX_PATH_HASHES.add(DocumentIdentityStore.pathHash(normalizedPath));
      fixtures.add(path);
      assertTrue(
          searchMarkers.add(searchMarker(FormatCapabilityExpectedState.forFormat(id))),
          id + " must have a unique JUSTSEARCH_ marker for exact result selection");
    }

    assertEquals(
        FormatId.values().length, FIXTURE_PATHS.size(), "Every matrix row needs a fixture");
    assertEquals(
        FormatId.values().length,
        MATRIX_PATH_HASHES.size(),
        "Every matrix row needs its own path hash for ledger reconciliation");

    harness = EngineTestHarness.start(tempDir.resolve("data"));
    assertTrue(harness.client().isHealthy(), "the engine must be healthy before indexing");
    assertEquals(
        FormatId.values().length,
        harness.client().submitBatch(fixtures).getAcceptedCount(),
        "the engine should admit every generated fixture");
    assertTrue(
        harness.awaitIndexed(FormatId.values().length, INDEX_TIMEOUT_MS),
        "All format fixtures should converge in the index");

    ledger = awaitMatrixLedger(FormatId.values().length, LEDGER_TIMEOUT_MS);
  }

  @AfterAll
  static void stopEngine() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  @Test
  @DisplayName("each matrix row produces one privacy-safe ledger event")
  void everyRowProducesOnePrivacySafeLedgerEvent() {
    assertEquals(
        FormatId.values().length,
        ledger.size(),
        "Each matrix row should produce one privacy-safe ledger event");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(FormatId.class)
  @DisplayName("a row survives admission, extraction, indexing, search, fetch and the ledger")
  void rowSurvivesTheProductionPath(FormatId id) throws Exception {
    ExpectedState expected = FormatCapabilityExpectedState.forFormat(id);
    assertNotNull(expected, id + " has no expected-state oracle entry");
    String normalizedPath = NORMALIZED_PATHS.get(id);

    SearchResult hit = awaitRowHit(id, searchMarker(expected), normalizedPath);

    CompleteSlice fetched = fetchCompleteSlice(hit.getId());
    assertEquals(expected.exactAnnotatedText(), fetched.content(), id + " stored content drifted");
    assertEquals(
        normalizedPath, fetched.metadata().get(SchemaFields.PATH), id + " stored path drifted");
    assertEquals(
        expected.mimeType(), fetched.metadata().get(SchemaFields.MIME), id + " MIME drifted");
    assertVisualEvidence(
        id, expected, fetched.metadata().get(SchemaFields.VISUAL_EXTRACTION_EVIDENCE));

    String pathHash = DocumentIdentityStore.pathHash(normalizedPath);
    List<Map<String, Object>> matchingEvents =
        ledger.stream().filter(event -> pathHash.equals(event.get("pathHash"))).toList();
    assertEquals(1, matchingEvents.size(), id + " should have exactly one ledger event");
    Map<String, Object> event = matchingEvents.getFirst();
    assertEquals("SUCCESS_FULL", event.get("outcomeClass"), id + " outcome drifted");
    assertEquals("SUCCESS_FULL", event.get("artifactStatus"), id + " artifact status drifted");
    assertEquals(expected.policyId(), event.get("policyId"), id + " extraction policy drifted");
    assertEquals(expected.parserAdapterId(), event.get("parserId"), id + " parser adapter drifted");
    assertLedgerEventIsPrivacySafe(id, event, normalizedPath);
  }

  /**
   * Polls the ledger until it holds at least {@code expectedRows} matrix events, then returns
   * whatever it holds — the caller asserts the exact count, so a timed-out poll is a failure and
   * not a pass.
   */
  private static List<Map<String, Object>> awaitMatrixLedger(int expectedRows, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (true) {
      List<Map<String, Object>> matrixEvents =
          harness.client().recentIngestionEvents(LEDGER_LIMIT).stream()
              .filter(event -> MATRIX_PATH_HASHES.contains(event.get("pathHash")))
              .toList();
      if (matrixEvents.size() >= expectedRows || System.currentTimeMillis() >= deadline) {
        return matrixEvents;
      }
      Thread.sleep(250);
    }
  }

  /**
   * The retired test's "was this row returned for its unique marker?", with the selector moved
   * from the {@code path} field to the result id (see the class javadoc) and the single read
   * turned into a bounded poll so an asynchronous searcher refresh is a wait, not a flake.
   */
  private static SearchResult awaitRowHit(FormatId id, String marker, String normalizedPath)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS;
    SearchResponse search = harness.client().search(marker, SEARCH_LIMIT);
    while (true) {
      for (SearchResult result : search.getResultsList()) {
        if (normalizedPath.equals(result.getId())) {
          return result;
        }
      }
      if (System.currentTimeMillis() >= deadline) {
        break;
      }
      Thread.sleep(250);
      search = harness.client().search(marker, SEARCH_LIMIT);
    }
    return fail(
        id
            + " was not returned for its unique marker "
            + marker
            + "; hits="
            + search.getResultsCount());
  }

  private static CompleteSlice fetchCompleteSlice(String docId) {
    StringBuilder content = new StringBuilder();
    Map<String, String> metadata = Map.of();
    Integer totalChars = null;
    int offset = 0;

    for (int page = 0; page < 100; page++) {
      FetchDocumentSliceResponse response =
          harness.client().fetchDocumentSlice(docId, offset, SLICE_CHARS);
      assertTrue(response.getFound(), "Stored document should exist: " + docId);
      assertEquals(docId, response.getDocId(), "Slice should retain the requested document id");

      if (totalChars == null) {
        totalChars = response.getTotalChars();
        metadata = Map.copyOf(response.getMetadataMap());
      } else {
        assertEquals(
            totalChars.intValue(), response.getTotalChars(), "Slice total changed while paging");
        assertEquals(metadata, response.getMetadataMap(), "Slice metadata changed while paging");
      }

      content.append(response.getContent());
      assertEquals(
          offset + response.getContent().length(),
          response.getNextOffsetChars(),
          "Slice next offset must equal consumed UTF-16 characters");
      assertTrue(
          response.getNextOffsetChars() > offset || !response.getTruncated(),
          "A truncated slice must make forward progress");
      offset = response.getNextOffsetChars();

      if (!response.getTruncated()) {
        assertEquals(totalChars.intValue(), offset, "Final slice must end at the declared total");
        assertEquals(
            totalChars.intValue(),
            content.length(),
            "Fetched content must cover the declared total");
        return new CompleteSlice(content.toString(), metadata);
      }
    }
    throw new AssertionError("Stored content exceeded the 100-page safety bound: " + docId);
  }

  private static void assertVisualEvidence(
      FormatId id, ExpectedState expected, String visualEvidence) throws Exception {
    assertNotNull(visualEvidence, id + " visual extraction evidence is missing");
    assertFalse(visualEvidence.isBlank(), id + " visual extraction evidence is blank");
    JsonNode evidence = JSON.readTree(visualEvidence);
    // asString() is Jackson 3's rename of the retired test's asText(), which is deprecated —
    // same coercion, no change of meaning (cf. IndexedRootViewSchemaTest.java:90).
    assertEquals("structured", evidence.path("route").asString(), id + " extraction route drifted");
    JsonNode counts = evidence.path("structuredElementCounts");
    assertTrue(counts.isObject(), id + " structured element counts must be an object");
    assertEquals(
        Set.of("tables", "headings", "lists"),
        Set.copyOf(counts.propertyNames()),
        id + " structured element count fields drifted");
    assertStructuredCount(id, counts, "tables", expected.structuredCounts().tables());
    assertStructuredCount(id, counts, "headings", expected.structuredCounts().headings());
    assertStructuredCount(id, counts, "lists", expected.structuredCounts().lists());
  }

  private static void assertStructuredCount(
      FormatId id, JsonNode counts, String field, int expected) {
    JsonNode actual = counts.get(field);
    assertTrue(actual.isIntegralNumber(), id + " " + field + " count must be an integer");
    assertEquals(expected, actual.intValue(), id + " " + field + " count drifted");
  }

  /**
   * The retired test called the ledger events "privacy-safe" but asserted only their cardinality.
   * The projected map is exactly where a raw path could leak (a diagnostic summary is a free
   * string), so the claim is checked rather than asserted by naming: no field of the event may
   * carry the row's absolute path. Compared case-insensitively because the stored key is
   * lowercased on Windows while a leak would not be.
   */
  private static void assertLedgerEventIsPrivacySafe(
      FormatId id, Map<String, Object> event, String normalizedPath) {
    String needle = normalizedPath.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, Object> entry : event.entrySet()) {
      if (entry.getValue() instanceof String text) {
        assertFalse(
            text.toLowerCase(Locale.ROOT).contains(needle),
            id + " ledger field " + entry.getKey() + " leaked the raw path");
      }
    }
  }

  private static String searchMarker(ExpectedState expected) {
    return expected.requiredMarkers().stream()
        .filter(marker -> marker.startsWith("JUSTSEARCH_"))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(expected.recipeId() + " has no owned JUSTSEARCH_ marker"));
  }

  private record CompleteSlice(String content, Map<String, String> metadata) {}
}
