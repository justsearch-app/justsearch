package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Default routing (tempdoc 885 item 14, design decision 2): PDF / Office / archives / images out
 * of process, plain text / markdown / code / CSV / JSON in process, and one mode switch that
 * forces either side.
 *
 * <p>The routing is asserted against <b>real files</b> through the real MIME detector, not against
 * the extension strings, because the classification the router consumes
 * ({@code IndexingDocumentOps.classifyFileKind}) keys on the detected MIME first.
 */
final class ExtractionRoutingTest {

  @TempDir Path tempDir;

  @Test
  void pdfAndOfficeAndArchivesGoOutOfProcessAndTextStaysInProcess() throws Exception {
    RecordingSandbox inProcess = new RecordingSandbox("in");
    RecordingSandbox outOfProcess = new RecordingSandbox("out");
    RoutingExtractionSandbox router =
        new RoutingExtractionSandbox(inProcess, outOfProcess, new ContentExtractor());

    assertEquals(inProcess.policy(), router.policy());
    assertEquals("out", router.extract(copyFixture("/fixtures/pdf/pdf-text-layer.pdf", "doc.pdf")).parserId());
    router.extract(copyFixture("/fixtures/office/office-marker.docx", "doc.docx"));
    router.extract(copyFixture("/fixtures/office/office-marker.pptx", "deck.pptx"));
    for (FormatId id : FormatId.values()) {
      router.extract(FormatCapabilityFixtureFactory.write(tempDir, id));
    }

    assertEquals(
        List.of(
            "doc.pdf",
            "doc.docx",
            "deck.pptx",
            "format-capability.eml",
            "format-capability.mbox",
            "format-capability.rtf",
            "format-capability.epub",
            "format-capability.odt",
            "format-capability.xlsx",
            "format-capability-merged.xlsx",
            "format-capability-typed.xlsx",
            "format-capability-notes.pptx",
            "format-capability.zip"),
        outOfProcess.seen,
        "wedge-prone families must be parsed out of process");

    assertEquals("in", router.extract(write("notes.txt", "plain text")).parserId());
    router.extract(write("readme.md", "# heading"));
    router.extract(write("App.java", "class App {}"));
    router.extract(write("rows.csv", "a,b\n1,2\n"));
    router.extract(write("data.json", "{\"a\":1}"));

    assertEquals(
        List.of("notes.txt", "readme.md", "App.java", "rows.csv", "data.json"),
        inProcess.seen,
        "decoder-only families must stay in process");
    assertEquals(13, outOfProcess.seen.size(), "no text file may have crossed the process boundary");
  }

  @Test
  void routingTableIsExplicitAboutEveryClassifiedKind() {
    for (String kind : List.of("pdf", "office", "archive", "image", "binary")) {
      assertTrue(RoutingExtractionSandbox.requiresProcessIsolation(kind), kind);
    }
    for (String kind : List.of("text", "markdown", "code", "unknown")) {
      assertFalse(RoutingExtractionSandbox.requiresProcessIsolation(kind), kind);
    }
    assertFalse(RoutingExtractionSandbox.requiresProcessIsolation(null));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "false,false,false,false", "true,false,false,false", "false,true,false,false",
      "true,true,false,false", "true,true,true,false", "true,true,false,true"
  })
  void closeAttemptsBothOwnersAndPreservesFailureIdentity(
      boolean firstThrows, boolean secondThrows, boolean fatal, boolean sharedFailure) {
    var inProcess = mock(ExtractionSandbox.class);
    var outOfProcess = mock(ExtractionSandbox.class);
    var router = new RoutingExtractionSandbox(inProcess, outOfProcess, mock(ContentExtractorProvider.class));
    Throwable first = fatal ? new AssertionError("first close") : new IllegalStateException("first close");
    Throwable second = sharedFailure ? first : new IllegalStateException("second close");
    if (firstThrows) doThrow(first).when(inProcess).close();
    if (secondThrows) doThrow(second).when(outOfProcess).close();
    if (firstThrows || secondThrows) {
      Throwable expected = firstThrows ? first : second;
      assertSame(expected, assertThrows(expected.getClass(), router::close));
      org.junit.jupiter.api.Assertions.assertArrayEquals(
          firstThrows && secondThrows && !sharedFailure ? new Throwable[] {second} : new Throwable[0],
          expected.getSuppressed());
    } else {
      router.close();
    }
    verify(inProcess).close();
    verify(outOfProcess).close();
  }

  /**
   * The mode switch, asserted on an observable difference rather than on a getter: only the
   * in-process mode tolerates an empty child command, because the other two build a child pool.
   */
  @Test
  void modeSwitchSelectsTheSandbox() {
    try (TimeboxedContentExtractor inProcess =
        ExtractionSandboxFactory.create(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            ExtractionSandboxFactory.Mode.IN_PROCESS,
            TikaExtractionPolicy.defaults(),
            Duration.ofSeconds(5),
            null,
            List.of())) {
      assertEquals("tika-default-v1", inProcess.extractionPolicy().policyId());
    }

    for (ExtractionSandboxFactory.Mode mode :
        List.of(ExtractionSandboxFactory.Mode.PROCESS, ExtractionSandboxFactory.Mode.AUTO)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ExtractionSandboxFactory.create(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
                  mode,
                  TikaExtractionPolicy.defaults(),
                  Duration.ofSeconds(5),
                  null,
                  List.of()),
          mode + " must build a child pool and therefore require a command");
    }
  }

  /**
   * The startup probe, both ways. Spawning is lazy, so without it a broken child command is
   * invisible until the first routed file. A failed probe is reported at startup; routed families
   * remain confined and fail with SANDBOX_FAILED while decoder-only formats remain usable.
   */
  @Test
  void startupProbeAnswersForAWorkingChildAndNamesTheFailureForABrokenOne() {
    assertEquals(
        java.util.Optional.empty(),
        ExtractionSandboxFactory.probeChildCommand(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(30)),
        "the shipped child command must pass its own probe");

    java.util.Optional<String> broken =
        ExtractionSandboxFactory.probeChildCommand(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            List.of("this-binary-does-not-exist", "--serve"),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(10));
    assertTrue(broken.isPresent(), "a command that cannot launch must fail the probe");
    assertFalse(broken.get().isBlank(), "the probe must name why it failed");
  }

  private Path copyFixture(String resource, String name) throws IOException {
    Path target = tempDir.resolve(name);
    try (InputStream in = ExtractionRoutingTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Missing test fixture: " + resource);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  private Path write(String name, String content) throws IOException {
    Path target = tempDir.resolve(name);
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return target;
  }

  private static final class RecordingSandbox implements ExtractionSandbox {
    private final String id;
    private final List<String> seen = new ArrayList<>();

    RecordingSandbox(String id) {
      this.id = id;
    }

    @Override
    public ExtractionArtifact extract(Path file) {
      seen.add(file.getFileName().toString());
      return ExtractionArtifact.full(
          new ContentExtractor.ExtractionResult(id, null, "text/plain"),
          TikaExtractionPolicy.defaults(),
          id,
          false);
    }
  }
}
