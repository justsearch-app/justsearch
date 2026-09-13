/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState;
import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState.Classification;
import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState.EmbeddedIdentityExpectation;
import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState.ExpectedState;
import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState.FailureClass;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.GeneratedFixture;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.core.StreamReadFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class PolicyDrivenFormatCapabilityTest {

  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Set<String> PROJECTION_FIELDS =
      Set.of(
          "formatId",
          "recipeId",
          "sha256",
          "mimeType",
          "policyId",
          "parserAdapterId",
          "requiredMarkers",
          "expectedAbsentMarkers",
          "structuredCounts",
          "embeddedResourceCount",
          "maxEmbeddedDepth",
          "embeddedIdentity",
          "classification",
          "failureClasses",
          "exactAnnotatedText");

  @TempDir Path tempDir;

  static Stream<FormatId> formats() {
    return Stream.of(FormatId.values());
  }

  @Test
  void ownedRecipesAreByteDeterministic() {
    assertEquals("format-capability-v1", FormatCapabilityExpectedState.ORACLE_VERSION);
    for (FormatId id : FormatId.values()) {
      assertArrayEquals(
          FormatCapabilityFixtureFactory.generate(id).bytes(),
          FormatCapabilityFixtureFactory.generate(id).bytes(),
          () -> id + " recipe must be byte-identical across generations");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LF", "CRLF"})
  void pptxRecipeHasPinnedBytesAcrossJvmLineSeparators(String separatorName) throws Exception {
    Path fixture = tempDir.resolve(separatorName + ".pptx");
    Path log = tempDir.resolve(separatorName + ".log");
    List<String> command = new ArrayList<>(
        PersistentExtractionSandboxTest.javaCommand(PptxFixtureProbe.class));
    command.add(fixture.toString());
    command.add(separatorName);
    Process child = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "PPTX fixture probe did not exit");
      assertEquals(0, child.exitValue(), Files.readString(log));
      assertEquals(
          FormatCapabilityExpectedState.forFormat(FormatId.PPTX_WITH_NOTES).sha256(),
          sha256(Files.readAllBytes(fixture)),
          () -> separatorName + " must preserve the pinned PPTX fixture bytes");
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  public static final class PptxFixtureProbe {
    private PptxFixtureProbe() {}

    public static void main(String[] args) throws Exception {
      System.setProperty("line.separator", "LF".equals(args[1]) ? "\n" : "\r\n");
      Files.write(
          Path.of(args[0]),
          FormatCapabilityFixtureFactory.generate(FormatId.PPTX_WITH_NOTES).bytes());
    }
  }

  @Test
  void checkedJsonProjectionMatchesEveryFieldOfTheTypedJavaOracle() throws Exception {
    JsonNode root;
    try (InputStream input =
        PolicyDrivenFormatCapabilityTest.class.getResourceAsStream(
            "/fixtures/format-capability/expected-state.v1.json")) {
      assertNotNull(input, "checked expected-state projection must be present");
      root = JSON.readTree(input);
    }

    assertTrue(root.isObject(), "projection root must be an object");
    assertEquals(
        Set.of("oracleVersion", "authority", "formats"), Set.copyOf(root.propertyNames()));
    assertEquals(FormatCapabilityExpectedState.ORACLE_VERSION, root.get("oracleVersion").textValue());
    assertEquals("checked-projection-of-typed-java-oracle", root.get("authority").textValue());

    JsonNode formats = root.get("formats");
    assertTrue(formats.isArray(), "formats must be an array");
    Map<FormatId, ExpectedState> projected = new EnumMap<>(FormatId.class);
    for (JsonNode row : formats.values()) {
      assertTrue(row.isObject(), "projection rows must be objects");
      assertEquals(
          PROJECTION_FIELDS,
          Set.copyOf(row.propertyNames()),
          "projection rows must contain all fields");
      JsonNode counts = row.get("structuredCounts");
      assertTrue(counts.isObject(), "structuredCounts must be an object");
      assertEquals(
          Set.of("tables", "headings", "lists"),
          Set.copyOf(counts.propertyNames()),
          "structured counts must be a complete projection");
      FormatId id = FormatId.valueOf(row.get("formatId").textValue());
      assertFalse(projected.containsKey(id), () -> "duplicate projection row for " + id);
      List<String> requiredMarkers = stringList(row.get("requiredMarkers"), "requiredMarkers");
      List<String> expectedAbsentMarkers =
          stringList(row.get("expectedAbsentMarkers"), "expectedAbsentMarkers");
      List<String> projectedFailureClasses =
          stringList(row.get("failureClasses"), "failureClasses");
      assertEquals(
          projectedFailureClasses.size(),
          Set.copyOf(projectedFailureClasses).size(),
          () -> id + " failureClasses must not contain duplicates");
      Set<FailureClass> failureClasses = EnumSet.noneOf(FailureClass.class);
      projectedFailureClasses
          .forEach(value -> failureClasses.add(FailureClass.valueOf(value)));
      projected.put(
          id,
          new ExpectedState(
              row.get("recipeId").textValue(),
              row.get("sha256").textValue(),
              row.get("mimeType").textValue(),
              row.get("policyId").textValue(),
              row.get("parserAdapterId").textValue(),
              requiredMarkers,
              expectedAbsentMarkers,
              new FormatCapabilityExpectedState.StructuredCounts(
                  counts.get("tables").intValue(),
                  counts.get("headings").intValue(),
                  counts.get("lists").intValue()),
              row.get("embeddedResourceCount").intValue(),
              row.get("maxEmbeddedDepth").intValue(),
              EmbeddedIdentityExpectation.valueOf(row.get("embeddedIdentity").textValue()),
              Classification.valueOf(row.get("classification").textValue()),
              failureClasses,
              row.get("exactAnnotatedText").textValue()));
    }

    assertEquals(EnumSet.allOf(FormatId.class), projected.keySet());
    for (FormatId id : FormatId.values()) {
      assertEquals(
          FormatCapabilityExpectedState.forFormat(id),
          projected.get(id),
          () -> id + " JSON row drifted from the authoritative typed Java oracle");
    }
  }

  @Test
  void archiveRecipesPinStoredEntriesAndRequiredPackageOrdering() throws Exception {
    Set<FormatId> archives =
        EnumSet.of(
            FormatId.EPUB,
            FormatId.ODT,
            FormatId.XLSX,
            FormatId.XLSX_MERGED_HEADERS,
            FormatId.XLSX_TYPED_CELLS,
            FormatId.PPTX_WITH_NOTES,
            FormatId.ZIP_WITH_XLSX);
    for (FormatId id : archives) {
      byte[] fixtureBytes = FormatCapabilityFixtureFactory.generate(id).bytes();
      List<String> entryNames = new ArrayList<>();
      try (ZipInputStream zip =
          new ZipInputStream(new ByteArrayInputStream(fixtureBytes))) {
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          entryNames.add(entry.getName());
          assertEquals(ZipEntry.STORED, entry.getMethod(), () -> id + " entry must be STORED");
          assertEquals(
              LocalDateTime.of(1980, 1, 1, 0, 0, 2),
              entry.getTimeLocal(),
              () -> id + " entry timestamp must use the fixed representable DOS epoch");
          assertEquals(
              entry.getSize(),
              entry.getCompressedSize(),
              () -> id + " STORED entry sizes must match");
          assertTrue(entry.getCrc() >= 0, () -> id + " entry must carry an explicit CRC");
        }
      }
      assertTrue(!entryNames.isEmpty(), () -> id + " package must contain entries");
      if (id == FormatId.EPUB || id == FormatId.ODT) {
        assertEquals("mimetype", entryNames.getFirst(), () -> id + " mimetype must be first");
      }
      if (id == FormatId.EPUB) {
        assertEquals(0x04034b50, littleEndianInt(fixtureBytes, 0), "EPUB must start with a local header");
        assertEquals(ZipEntry.STORED, littleEndianUnsignedShort(fixtureBytes, 8));
        int nameLength = littleEndianUnsignedShort(fixtureBytes, 26);
        assertEquals(0, littleEndianUnsignedShort(fixtureBytes, 28), "mimetype local header must have no extra field");
        assertEquals(
            "mimetype",
            new String(fixtureBytes, 30, nameLength, StandardCharsets.US_ASCII),
            "mimetype must be the first local-file entry");
        String packageBytes = new String(fixtureBytes, StandardCharsets.ISO_8859_1);
        assertEquals(1, occurrences(packageBytes, "dcterms:modified"));
        assertTrue(entryNames.contains("OEBPS/nav.xhtml"), "EPUB 3 navigation document is required");
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formats")
  @Timeout(30)
  void productionExtractorMatchesVersionedCapabilityOracle(FormatId id) throws Exception {
    GeneratedFixture generated = FormatCapabilityFixtureFactory.generate(id);
    ExpectedState expected = FormatCapabilityExpectedState.forFormat(id);
    Path fixture = FormatCapabilityFixtureFactory.write(tempDir, id);

    assertEquals(expected.recipeId(), generated.recipeId());
    assertEquals(expected.sha256(), sha256(generated.bytes()), () -> id + " fixture hash drifted");

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory()).extractArtifact(fixture);

    assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
    assertEquals(expected.mimeType(), artifact.result().mimeType());
    assertEquals(expected.policyId(), artifact.policyId());
    assertEquals(
        expected.parserAdapterId(),
        artifact.parserId(),
        () -> id + " must exercise the pinned production structured-extraction adapter");
    for (String marker : expected.requiredMarkers()) {
      assertTrue(
          artifact.result().content().contains(marker),
          () -> id + " did not extract required marker " + marker + ": " + artifact.result().content());
    }
    for (String marker : expected.expectedAbsentMarkers()) {
      assertFalse(
          artifact.result().content().contains(marker),
          () -> id + " unexpectedly gained a marker pinned as absent: " + marker);
    }
    assertEquals(expected.embeddedResourceCount(), artifact.embeddedResourceCount());
    assertEquals(expected.maxEmbeddedDepth(), artifact.maxEmbeddedDepth());
    String structuredCounts =
        "\"structuredElementCounts\":{\"tables\":"
            + expected.structuredCounts().tables()
            + ",\"headings\":"
            + expected.structuredCounts().headings()
            + ",\"lists\":"
            + expected.structuredCounts().lists()
            + "}";
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains(structuredCounts),
        () -> id + " structured counts drifted: " + artifact.visualExtractionEvidenceJson());
    if (expected.classification() == Classification.PASS) {
      assertEquals(EmbeddedIdentityExpectation.NOT_APPLICABLE, expected.embeddedIdentity());
      assertEquals(Set.of(FailureClass.NONE), expected.failureClasses());
    } else {
      assertTrue(
          !expected.failureClasses().isEmpty()
              && !expected.failureClasses().contains(FailureClass.NONE),
          () -> id + " known gap must name one or more machine-readable failure classes");
    }
    assertEquals(expected.exactAnnotatedText(), artifact.result().content());
  }

  @Test
  @ResourceLock(value = Resources.LOCALE, mode = ResourceAccessMode.READ_WRITE)
  void typedCellsHaveIdenticalAnnotatedTextUnderGermanFormatLocale() throws Exception {
    Locale original = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
      Path fixture = FormatCapabilityFixtureFactory.write(tempDir, FormatId.XLSX_TYPED_CELLS);
      ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory()).extractArtifact(fixture);
      assertEquals(
          FormatCapabilityExpectedState.forFormat(FormatId.XLSX_TYPED_CELLS)
              .exactAnnotatedText(),
          artifact.result().content());
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, original);
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static List<String> stringList(JsonNode node, String fieldName) {
    assertTrue(node.isArray(), () -> fieldName + " must be an array");
    return node.values().stream().map(JsonNode::textValue).toList();
  }

  private static int littleEndianUnsignedShort(byte[] bytes, int offset) {
    return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
  }

  private static int littleEndianInt(byte[] bytes, int offset) {
    return littleEndianUnsignedShort(bytes, offset)
        | (littleEndianUnsignedShort(bytes, offset + 2) << 16);
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
      count++;
    }
    return count;
  }
}
