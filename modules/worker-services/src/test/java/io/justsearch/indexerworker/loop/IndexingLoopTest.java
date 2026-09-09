package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ContentExtractorProvider;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ExtractionStatus;
import io.justsearch.indexerworker.extract.SandboxExtractionException;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link IndexingLoop} and its extracted ops classes.
 *
 * <p>Uses ByteBuddy experimental mode (-Dnet.bytebuddy.experimental=true) for JDK 25 compatibility.
 *
 * <p>Pure function tests target the public static methods in {@link IndexingDocumentOps}. Business
 * logic tests (processJob, buildDocument) require a comprehensive test fixture due to many
 * dependencies (metrics, telemetry, content extractor, etc.) and are better suited for integration
 * tests.
 */
@ExtendWith(MockitoExtension.class)
class IndexingLoopTest {

  // ==================== Mockito Smoke Test ====================

  @Nested
  @DisplayName("Mockito compatibility")
  class MockitoSmokeTest {

    @Mock JobQueue mockJobQueue;

    @Test
    @DisplayName("Mockito works with JDK 25 experimental mode")
    void mockitoWorks() {
      when(mockJobQueue.queueDepth()).thenReturn(42L);
      // Direct invocation on mock is intentional for this smoke test
      @SuppressWarnings("DirectInvocationOnMock")
      long result = mockJobQueue.queueDepth();
      assertEquals(42L, result);
      verify(mockJobQueue).queueDepth();
    }
  }

  // ==================== Pure Function Tests (IndexingDocumentOps) ====================

  @Nested
  @DisplayName("normalizeMimeBase()")
  class NormalizeMimeBaseTests {

    @Test
    @DisplayName("null returns null")
    void nullReturnsNull() {
      assertNull(IndexingDocumentOps.normalizeMimeBase(null));
    }

    @Test
    @DisplayName("MIME with charset is stripped and lowercased")
    void charsetIsStripped() {
      assertEquals("text/plain", IndexingDocumentOps.normalizeMimeBase("text/plain; charset=UTF-8"));
      assertEquals("text/html", IndexingDocumentOps.normalizeMimeBase("TEXT/HTML; charset=ISO-8859-1"));
    }

    @Test
    @DisplayName("simple MIME is lowercased")
    void simpleMimeIsLowercased() {
      assertEquals("application/json", IndexingDocumentOps.normalizeMimeBase("APPLICATION/JSON"));
    }
  }

  @Nested
  @DisplayName("contentPreview()")
  class ContentPreviewTests {

    @Test
    @DisplayName("null returns empty string")
    void nullReturnsEmpty() {
      assertEquals("", IndexingDocumentOps.contentPreview(null));
    }

    @Test
    @DisplayName("short content is returned unchanged")
    void shortContentUnchanged() {
      String shortText = "Hello, World!";
      assertEquals(shortText, IndexingDocumentOps.contentPreview(shortText));
    }

    @Test
    @DisplayName("long content truncated to 4096 characters")
    void longContentTruncated() {
      String longText = "a".repeat(5000);
      String result = IndexingDocumentOps.contentPreview(longText);
      assertEquals(4096, result.length());
      assertEquals("a".repeat(4096), result);
    }
  }

  @Nested
  @DisplayName("classifyFileKind()")
  class ClassifyFileKindTests {

    @Test
    @DisplayName("application/pdf returns 'pdf'")
    void pdfReturns() {
      assertEquals("pdf", IndexingDocumentOps.classifyFileKind(Path.of("doc.pdf"), "application/pdf"));
    }

    @Test
    @DisplayName("image/* returns 'image'")
    void imageReturns() {
      assertEquals("image", IndexingDocumentOps.classifyFileKind(Path.of("pic.png"), "image/png"));
      assertEquals("image", IndexingDocumentOps.classifyFileKind(Path.of("photo.jpg"), "image/jpeg"));
    }

    @Test
    @DisplayName("text/x-java returns 'code'")
    void codeReturns() {
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("App.java"), "text/x-java"));
    }

    @Test
    @DisplayName("null MIME with .java extension returns 'code'")
    void nullMimeCodeExtension() {
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("main.java"), null));
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("main.go"), null));
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("script.py"), null));
    }

    @Test
    @DisplayName("null MIME with unknown extension returns 'unknown'")
    void nullMimeUnknown() {
      assertEquals("unknown", IndexingDocumentOps.classifyFileKind(Path.of("file.xyz"), null));
    }

    @Test
    @DisplayName("text/plain returns 'text'")
    void textReturns() {
      assertEquals("text", IndexingDocumentOps.classifyFileKind(Path.of("readme.txt"), "text/plain"));
    }
  }

  @Nested
  @DisplayName("detectLanguage()")
  class DetectLanguageTests {

    @Test
    @DisplayName("null returns null")
    void nullReturnsNull() {
      assertNull(IndexingDocumentOps.detectLanguage(null));
    }

    @Test
    @DisplayName("empty string returns null")
    void emptyReturnsNull() {
      assertNull(IndexingDocumentOps.detectLanguage(""));
    }

    @Test
    @DisplayName("Latin-only text returns null (uses default)")
    void latinReturnsNull() {
      assertNull(IndexingDocumentOps.detectLanguage("The quick brown fox jumps over the lazy dog."));
    }

    @Test
    @DisplayName("Chinese text returns 'zh'")
    void chineseReturnsZh() {
      assertEquals("zh", IndexingDocumentOps.detectLanguage("这是一段中文文本用于测试。"));
    }

    @Test
    @DisplayName("Japanese hiragana returns 'ja'")
    void japaneseReturnsJa() {
      assertEquals("ja", IndexingDocumentOps.detectLanguage("これは日本語のテキストです。"));
    }

    @Test
    @DisplayName("Korean text returns 'ko'")
    void koreanReturnsKo() {
      assertEquals("ko", IndexingDocumentOps.detectLanguage("이것은 한국어 텍스트입니다."));
    }

    @Test
    @DisplayName("Cyrillic (Russian) returns 'ru'")
    void russianReturnsRu() {
      assertEquals("ru", IndexingDocumentOps.detectLanguage("Это текст на русском языке."));
    }

    @Test
    @DisplayName("Arabic text returns 'ar'")
    void arabicReturnsAr() {
      assertEquals("ar", IndexingDocumentOps.detectLanguage("هذا نص باللغة العربية."));
    }
  }

  @Nested
  @DisplayName("isCodeExtension() - selected extensions")
  class IsCodeExtensionTests {

    @Test
    @DisplayName("common code extensions return true")
    void commonCodeExtensions() {
      assertTrue(IndexingDocumentOps.isCodeExtension(".java"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".py"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".js"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".ts"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".go"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".rs"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".c"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".cpp"));
    }

    @Test
    @DisplayName("non-code extensions return false")
    void nonCodeExtensions() {
      assertFalse(IndexingDocumentOps.isCodeExtension(".txt"));
      assertFalse(IndexingDocumentOps.isCodeExtension(".pdf"));
      assertFalse(IndexingDocumentOps.isCodeExtension(".jpg"));
      assertFalse(IndexingDocumentOps.isCodeExtension(".xyz"));
    }

    @Test
    @DisplayName("empty string returns false")
    void emptyReturnsFalse() {
      assertFalse(IndexingDocumentOps.isCodeExtension(""));
    }
  }

  @Nested
  @DisplayName("isMarkdownExtension()")
  class IsMarkdownExtensionTests {

    @Test
    @DisplayName(".md returns true")
    void mdReturnsTrue() {
      assertTrue(IndexingDocumentOps.isMarkdownExtension(".md"));
    }

    @Test
    @DisplayName(".markdown returns true")
    void markdownReturnsTrue() {
      assertTrue(IndexingDocumentOps.isMarkdownExtension(".markdown"));
    }

    @Test
    @DisplayName(".txt returns false")
    void txtReturnsFalse() {
      assertFalse(IndexingDocumentOps.isMarkdownExtension(".txt"));
    }

    @Test
    @DisplayName("empty string returns false")
    void emptyReturnsFalse() {
      assertFalse(IndexingDocumentOps.isMarkdownExtension(""));
    }
  }

  // ==================== buildDocument NER Status (C1 fix validation) ====================

  @Nested
  @DisplayName("buildDocument NER status")
  class BuildDocumentNerStatus {

    @Mock WorkerSignalBus mockSignalBus;

    @Test
    @DisplayName("sets NER_STATUS to PENDING for new documents")
    void buildDocument_setsNerStatusPending() {
      ExtractionResult extraction = new ExtractionResult("Test content", null, "text/plain");

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("test.txt"), extraction, null, mockSignalBus, null);

      assertEquals(
          SchemaFields.NER_STATUS_PENDING,
          doc.fields().get(SchemaFields.NER_STATUS),
          "New documents must have NER_STATUS=PENDING for backfill discovery");
    }
  }

  @Nested
  @DisplayName("parent_token_count plumbing")
  class ParentTokenCountTests {

    @Mock WorkerSignalBus mockSignalBus;

    @Test
    @DisplayName("deriveParentMetadata uses SPLADE token count when encoder is available")
    void deriveParentMetadataUsesSpladeTokenCount() {
      ExtractionResult extraction = new ExtractionResult("Alpha beta gamma", null, "text/plain");
      SpladeEncoder spladeEncoder = mock(SpladeEncoder.class);
      when(spladeEncoder.tokenCount("Alpha beta gamma")).thenReturn(321L);

      IndexingDocumentOps.ParentIndexMetadata metadata =
          IndexingDocumentOps.deriveParentMetadata(
              Path.of("notes.txt"),
              extraction,
              spladeEncoder,
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class));

      assertEquals(321L, metadata.parentTokenCount());
      assertEquals("text/plain", metadata.mimeBase());
      assertEquals("text", metadata.fileKind());
    }

    @Test
    @DisplayName(
        "deriveParentMetadata falls back to a token estimate when the SPLADE encoder is missing"
            + " (tempdoc 717: never null → no short-corpus mis-classification)")
    void deriveParentMetadataWithoutSpladeFallsBackToEstimate() {
      ExtractionResult extraction = new ExtractionResult("Alpha beta gamma", null, "text/plain");

      IndexingDocumentOps.ParentIndexMetadata metadata =
          IndexingDocumentOps.deriveParentMetadata(
              Path.of("notes.txt"),
              extraction,
              null,
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class));

      // Tempdoc 717: a missing SPLADE encoder (the fresh-build startup race) must NOT leave
      // parent_token_count null — that fed the corpus-profile classifier a 0 median and
      // mis-classified long corpora as "short", silently skipping the chunk_merge leg. It now falls
      // back to the always-available character estimate; the exact SPLADE count is still used when
      // the encoder is ready (see deriveParentMetadataUsesSpladeTokenCount above).
      assertNotNull(
          metadata.parentTokenCount(), "missing SPLADE must not omit parent_token_count");
      // "Alpha beta gamma" = 16 chars → chars/3 estimate = 5.
      assertEquals(5L, metadata.parentTokenCount());
    }

    @Test
    @DisplayName("buildDocument writes parent_token_count when provided in metadata")
    void buildDocumentWritesParentTokenCount() {
      ExtractionResult extraction = new ExtractionResult("Test content", null, "text/plain");
      IndexingDocumentOps.ParentIndexMetadata metadata =
          new IndexingDocumentOps.ParentIndexMetadata(
              "text/plain", "text/plain", "text", "en", 777L);

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("test.txt"), extraction, null, mockSignalBus, metadata);

      assertEquals(777L, doc.fields().get(SchemaFields.PARENT_TOKEN_COUNT));
    }

    @Test
    @DisplayName("buildDocument omits parent_token_count when metadata does not include it")
    void buildDocumentOmitsParentTokenCountWhenAbsent() {
      ExtractionResult extraction = new ExtractionResult("Test content", null, "text/plain");
      IndexingDocumentOps.ParentIndexMetadata metadata =
          new IndexingDocumentOps.ParentIndexMetadata(
              "text/plain", "text/plain", "text", "en", null);

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("test.txt"), extraction, null, mockSignalBus, metadata);

      assertFalse(doc.fields().containsKey(SchemaFields.PARENT_TOKEN_COUNT));
    }
  }

  // Tempdoc 516 Slice 4d (W6): "reflection compatibility contract" nested class removed.
  // The two preserved methods (handleEmbeddingFailure / handleChunkEmbeddingFailure) were
  // @SuppressWarnings("unused") wrappers in IndexingLoop with no production callers — the
  // production code calls EmbeddingBackfillOps.handle*Failure(...) statics directly. The
  // wrappers + this test + the UnreferencedCodeTest allowlist entries formed a self-
  // referential dead-code loop. All three removed as part of the W6 BackfillScheduler
  // extraction.

  @Nested
  @DisplayName("extraction provenance schema fields (tempdoc 410 §11)")
  class ExtractionProvenanceFieldTests {

    @Mock WorkerSignalBus mockSignalBus;

    @Test
    @DisplayName("buildDocument writes status, policy, parser, truncated from validated artifact")
    void buildDocumentWritesArtifactProvenance() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact artifact =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", true)
              .validate(policy, "deadbeef");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("provenance.txt"),
              artifact,
              null,
              mockSignalBus,
              /* embeddingProvider */ null,
              /* allowEmbeddingWrites */ false,
              /* spladeEncoder */ null,
              /* parentMetadata */ null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              /* precomputedEmbedding */ null,
              new IndexingDocumentOps.SourceFileMetadata(123L, 456L, "a".repeat(64)),
              "test-doc-uid");

      Map<String, Object> fields = doc.fields();
      assertEquals("SUCCESS_PARTIAL", fields.get(SchemaFields.EXTRACTION_STATUS));
      assertEquals(Boolean.TRUE, fields.get(SchemaFields.CONTENT_TRUNCATED));
      assertEquals(policy.policyId(), fields.get(SchemaFields.EXTRACTION_POLICY_ID));
      assertEquals("tika-policy-structured", fields.get(SchemaFields.EXTRACTION_PARSER_ID));
      assertEquals("a".repeat(64), fields.get(SchemaFields.SOURCE_SHA256));
    }

    @Test
    @DisplayName("buildDocument writes EXTRACTION_REASON_CODE=SUCCESS_PARTIAL when truncated")
    void buildDocumentWritesExtractionReasonCodeForSuccessPartial() throws Exception {
      // Slice A.2 — SUCCESS_PARTIAL artifacts (truncated extraction) surface the reason code so
      // search-side callers can distinguish "got everything" from "got the prefix Tika handed us
      // before we cut it off."
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact partial =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", true)
              .validate(policy, "partial-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("partial.txt"),
              partial,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L),
              "test-doc-uid");

      assertEquals(
          IngestionReasonCodes.SUCCESS_PARTIAL,
          doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
    }

    @Test
    @DisplayName("buildDocument omits EXTRACTION_REASON_CODE for SUCCESS_FULL (implicit success)")
    void buildDocumentOmitsExtractionReasonCodeForSuccessFull() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact full =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", false)
              .validate(policy, "full-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("full.txt"),
              full,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L),
              "test-doc-uid");

      assertFalse(doc.fields().containsKey(SchemaFields.EXTRACTION_REASON_CODE));
    }

    @Test
    @DisplayName("buildDocument writes parser_warnings_count from artifact warnings (Slice A.1)")
    void buildDocumentWritesParserWarningsCount() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ExtractionArtifact rawWithWarnings =
          new ExtractionArtifact(
              ExtractionStatus.SUCCESS_FULL,
              extraction,
              policy.policyId(),
              "tika-policy-structured",
              false,
              List.of("warn-1", "warn-2", "warn-3"));
      ValidatedExtractionArtifact artifact = rawWithWarnings.validate(policy, "warned-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("warnings.txt"),
              artifact,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L),
              "test-doc-uid");

      assertEquals(3L, doc.fields().get(SchemaFields.PARSER_WARNINGS_COUNT));
    }

    @Test
    @DisplayName("buildDocument omits parser_warnings_count when warnings list is empty")
    void buildDocumentOmitsParserWarningsCountWhenZero() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact artifact =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", false)
              .validate(policy, "no-warn-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("clean.txt"),
              artifact,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L),
              "test-doc-uid");

      assertFalse(doc.fields().containsKey(SchemaFields.PARSER_WARNINGS_COUNT));
    }

    @Test
    @DisplayName("buildDocument omits provenance fields when artifact is unavailable (legacy path)")
    void buildDocumentOmitsProvenanceWhenSourceMetadataIsLegacy() {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("legacy.txt"), extraction, null, mockSignalBus, null);

      // TestDocumentBuilder routes through the artifact path with a synthetic "test-fixture"
      // parser and default policy; the provenance fields ARE written, just with fixture values.
      assertEquals("SUCCESS_FULL", doc.fields().get(SchemaFields.EXTRACTION_STATUS));
      assertEquals(Boolean.FALSE, doc.fields().get(SchemaFields.CONTENT_TRUNCATED));
      assertEquals(
          TikaExtractionPolicy.defaults().policyId(),
          doc.fields().get(SchemaFields.EXTRACTION_POLICY_ID));
      assertEquals("test-fixture", doc.fields().get(SchemaFields.EXTRACTION_PARSER_ID));
    }
  }

  @Nested
  @DisplayName("typed ingestion outcomes")
  class TypedIngestionOutcomeTests {

    @Test
    void genericExtractionFailureDoesNotReturnPlaceholderDocument() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-extract-fail", ".txt"), "bad");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              providerThrowingWithoutDetect(
                  new ContentExtractor.ExtractionException("parser boom")));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.PARSER_FAILED, queue.lastOutcome.outcomeClass());
      assertTrue(queue.terminalFailed, "Parser failure should be terminal for v1");
      verify(queue.indexingCoordinator, never()).indexSingle(any());
    }

    @Test
    void sandboxFailureProducesSandboxFailedOutcome() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-sandbox-fail", ".txt"), "bad");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              providerThrowing(
                  new SandboxExtractionException(
                      "stdout polluted", null)));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.SANDBOX_FAILED, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.SANDBOX_FAILED, queue.lastOutcome.reasonCode());
      assertFalse(queue.terminalFailed, "Sandbox failure should be retryable, not terminal");
      assertFalse(queue.deferred, "Sandbox failure should not defer without attempt");
    }

    @Test
    void budgetExceededIsTerminalBudgetOutcome() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-budget", ".txt"), "bad");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              providerThrowing(
                  new ContentExtractor.BudgetExceededException("too large", "INPUT_TOO_LARGE")));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.BUDGET_EXCEEDED, queue.lastOutcome.outcomeClass());
      assertTrue(queue.terminalFailed);
    }

    @Test
    void missingFileProducesStaleSourceOutcome() throws Exception {
      Path file = Files.createTempDirectory("js-missing").resolve("gone.txt");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("unused"));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.STALE_SOURCE, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.DELETED_OR_MISSING, queue.lastOutcome.reasonCode());
      assertTrue(queue.done);
      verify(queue.indexingCoordinator).deleteByIdAndChunks(anyString());
    }

    @Test
    void nonRegularSourceIsSkippedBeforeExtraction() throws Exception {
      Path directory = Files.createTempDirectory("js-non-regular");
      RecordingQueue queue = new RecordingQueue();
      ContentExtractorProvider provider = mock(ContentExtractorProvider.class);
      IndexingLoop loop = newLoop(queue, provider);

      Object extracted = invokeExtractJob(loop, directory);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.SKIPPED_POLICY, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.NON_REGULAR_SOURCE, queue.lastOutcome.reasonCode());
      assertTrue(queue.done);
      verify(provider, never()).extract(any());
    }

    @Test
    void fileChangedAfterExtractionDefersForFreshRetry() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-stale", ".txt"), "before");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.writeString(path, "after-change");
                  return new ExtractionResult("before", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.STALE_SOURCE, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.SIZE_CHANGED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.deferred);
      assertFalse(queue.done);
    }

    @Test
    void modifiedTimeChangedAfterExtractionDefersForFreshRetry() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-mtime-stale", ".txt"), "same-size");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.setLastModifiedTime(
                      path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 2000L));
                  return new ExtractionResult("same-size", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionReasonCodes.MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.deferred);
    }

    @Test
    void sourceKindChangedAfterExtractionDefersForFreshRetry() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-kind-stale", ".txt"), "before");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.delete(path);
                  Files.createDirectory(path);
                  return new ExtractionResult("before", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionReasonCodes.SOURCE_KIND_CHANGED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.deferred);
    }

    @Test
    void fileDeletedAfterExtractionDeletesExistingIndexAndMarksDone() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-delete-stale", ".txt"), "before");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.delete(path);
                  return new ExtractionResult("before", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.STALE_SOURCE, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.DELETED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.done);
      verify(queue.indexingCoordinator).deleteByIdAndChunks(anyString());
    }

    @Test
    void writeFailureProducesWriteFailedOutcome() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-write-fail", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("body"));
      doThrow(new RuntimeException("write boom")).when(queue.indexingCoordinator).indexSingle(any());

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));

      assertEquals(IngestionOutcomeClass.WRITE_FAILED, queue.lastOutcome.outcomeClass());
      assertFalse(queue.done);
    }

    @Test
    void writeFeedsSuccessEvidenceToTheEccOnlyForCompletedEmbeddings() throws Exception {
      // #470 D1: the attestation's success evidence flows through JobBatchWriter.write() -> the
      // ECC seam on EmbeddingProviderLifecycle. This test exercises the PRODUCTION wiring (real
      // write path, controller injected through the real lifecycle seam) — a call-site deletion in
      // JobBatchWriter must redden it, which the controller's own unit tests cannot detect.
      Path file = Files.writeString(Files.createTempFile("js-ecc-wiring", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("body"));
      EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
      loop.getEmbeddingLifecycle().setEmbeddingCompatController(ecc);

      // Primary indexing defers the embedding to the backfill: the doc is PENDING, so the write
      // must contribute NO success evidence (a vector-less doc proves nothing).
      invokeWriteExtractedJob(loop, extractedJob(file, "body"));
      verify(ecc, never()).noteSuccessfulEmbeddingObserved();

      // The batch/migration path hands write() a precomputed embedding: the doc carries
      // EMBEDDING_STATUS_COMPLETED, which IS the evidence the stamp must be earned from.
      loop.getWriter()
          .write(
              (ExtractedJob) extractedJob(file, "body"),
              new float[] {0.1f, 0.2f});
      verify(ecc).noteSuccessfulEmbeddingObserved();
    }

    @Test
    void drainingWriteRejectionDefersWithoutFailure() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-draining", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("body"));
      doThrow(
              new IndexRuntimeIOException(
                  IndexRuntimeIOException.Reason.DRAINING, "runtime_draining", null))
          .when(queue.indexingCoordinator)
          .indexSingle(any());

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));

      assertEquals(IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING, queue.lastOutcome.outcomeClass());
      assertTrue(queue.deferred);
      assertFalse(queue.terminalFailed);
    }

    @Test
    void successfulWriteRecordsSuccessOnlyWhenMarkDoneDrains() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-success", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("body"));

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));
      assertNull(queue.lastOutcome, "Write should not mark DONE before commit/mark-done drain");

      // Tempdoc 516 Slice 4a.1: drain now lives on IngestionOutcomeJournal.
      loop.getJournal().drainPending();

      assertEquals(IngestionOutcomeClass.SUCCESS_FULL, queue.lastOutcome.outcomeClass());
      assertTrue(queue.done);
      assertNotNull(queue.lastEntry);
      assertEquals("SUCCESS_FULL", queue.lastEntry.artifactStatus());
      assertEquals("test-structured", queue.lastEntry.parserId());
    }

    @Test
    void drainKeepsPendingPathsWhenOutcomeWriteFails() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-drain-fail", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      queue.failOutcomeWrites = true;
      IndexingLoop loop = newLoop(queue, providerReturning("body"));

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));

      // Tempdoc 516 Slice 4a.1: pendingMarkDone now encapsulated in IngestionOutcomeJournal;
      // tests use the package-private snapshot accessor.
      var journal = loop.getJournal();
      assertEquals(
          1,
          journal.pendingTransitionsForTest().size(),
          "Write should enqueue a pending mark-done transition");

      journal.drainPending();

      assertEquals(
          1,
          journal.pendingTransitionsForTest().size(),
          "Failed outcome writes must keep the path in pendingMarkDone for next drain");
    }

    @Test
    void drainPendingMarkDoneRoutesSuccessPartialToMatchingLedgerOutcome() throws Exception {
      // Slice G.1 (H1 + M4) — pre-G.1 the entire pendingMarkDone batch was hardcoded to
      // SUCCESS_FULL/SUCCESS regardless of the artifact's actual status, so a SUCCESS_PARTIAL
      // document had a SUCCESS_FULL ledger row. This test mixes one full-success and one
      // partial-success extracted job in the same batch and asserts the recording queue saw
      // both outcome classes — proving drainPendingMarkDone partitions by artifact status.
      Path full = Files.writeString(Files.createTempFile("js-mixed-full", ".txt"), "fully extracted");
      Path partial =
          Files.writeString(Files.createTempFile("js-mixed-partial", ".txt"), "extracted but truncated");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("ignored"));

      invokeWriteExtractedJob(loop, extractedJob(full, "fully extracted"));
      invokeWriteExtractedJob(loop, extractedJobPartial(partial, "extracted but truncated"));

      // Tempdoc 516 Slice 4a.1: drain now lives on IngestionOutcomeJournal.
      loop.getJournal().drainPending();

      java.util.Set<IngestionOutcomeClass> seen = new java.util.HashSet<>();
      for (IngestionOutcome o : queue.capturedOutcomes) {
        seen.add(o.outcomeClass());
      }
      assertTrue(
          seen.contains(IngestionOutcomeClass.SUCCESS_FULL),
          "Mixed batch must produce a SUCCESS_FULL ledger outcome; saw: " + seen);
      assertTrue(
          seen.contains(IngestionOutcomeClass.SUCCESS_PARTIAL),
          "Mixed batch must produce a SUCCESS_PARTIAL ledger outcome; saw: " + seen);
      // Reason codes also align with the doc field (Slice A.2 wires SUCCESS_PARTIAL there).
      java.util.Set<String> reasons = new java.util.HashSet<>();
      for (IngestionOutcome o : queue.capturedOutcomes) {
        reasons.add(o.reasonCode());
      }
      assertTrue(reasons.contains(IngestionReasonCodes.SUCCESS));
      assertTrue(reasons.contains(IngestionReasonCodes.SUCCESS_PARTIAL));
    }

    @Test
    void misroutedOutcomeWriteDoesNotCrashBatch() throws Exception {
      // B-H.4 defect F — recordOutcomeSafely must catch RuntimeException, not just
      // OutcomeWriteException, so the SqliteJobQueue defer-policy guard
      // (which throws IllegalArgumentException on a misrouted call) doesn't crash
      // the batch loop. Pre-fix: a single misroute would propagate up and abort the batch.
      Path bad = Files.writeString(Files.createTempFile("js-misroute-bad", ".txt"), "bad");
      Path good = Files.writeString(Files.createTempFile("js-misroute-good", ".txt"), "good");
      RecordingQueue queue = new RecordingQueue();
      queue.failOutcomeWritesAsIllegalArgument = true;
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws ContentExtractor.ExtractionException {
                  if (path.equals(bad)) {
                    throw new ContentExtractor.ExtractionException("parser boom");
                  }
                  return new ExtractionResult("good", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });
      setRunning(loop, true);

      // The bad file's PARSER_FAILED outcome write throws IllegalArgumentException via the
      // RecordingQueue's misroute simulation. Pre-B-H.4 this would escape recordOutcomeSafely
      // (which only caught OutcomeWriteException) and crash invokeProcessBatch. Post-fix, the
      // good file still progresses to indexing.
      invokeProcessBatch(loop, List.of(new JobQueue.IndexJob(bad, null), new JobQueue.IndexJob(good, null)));

      verify(queue.indexingCoordinator).indexSingle(any());
    }

    @Test
    void badFileDoesNotBlockGoodFileInSameBatch() throws Exception {
      Path bad = Files.writeString(Files.createTempFile("js-bad-batch", ".txt"), "bad");
      Path good = Files.writeString(Files.createTempFile("js-good-batch", ".txt"), "good");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws ContentExtractor.ExtractionException {
                  if (path.equals(bad)) {
                    throw new ContentExtractor.ExtractionException("parser boom");
                  }
                  return new ExtractionResult("good", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });
      setRunning(loop, true);

      invokeProcessBatch(loop, List.of(new JobQueue.IndexJob(bad, null), new JobQueue.IndexJob(good, null)));

      assertEquals(IngestionOutcomeClass.PARSER_FAILED, queue.lastOutcome.outcomeClass());
      verify(queue.indexingCoordinator).indexSingle(any());
    }

    // Tempdoc 516 Slice 1 + W2.6 — regression net for upcoming P1 extractions:

    @Test
    @DisplayName(
        "StaleSourceHandler.deleteMissingSource returns 1 on coordinator success + 0 on "
            + "coordinator exception (W2.6: isolation test for the seam Appendix A.1 named)")
    void staleSourceHandlerReturnsOneOnSuccessZeroOnException() throws Exception {
      IndexingCoordinator successCoordinator = mock(IndexingCoordinator.class);
      StaleSourceHandler okHandler =
          new StaleSourceHandler(successCoordinator);
      Path file = Files.writeString(Files.createTempFile("js-stale-ok", ".txt"), "body");
      assertEquals(1, okHandler.deleteMissingSource(file),
          "Successful coordinator.deleteByIdAndChunks must surface as 1 so the caller bumps "
              + "indexedSinceCommit. The cross-seam contract Appendix A.1 named.");
      verify(successCoordinator).deleteByIdAndChunks(anyString());

      IndexingCoordinator throwingCoordinator = mock(IndexingCoordinator.class);
      doThrow(new RuntimeException("simulated lucene failure"))
          .when(throwingCoordinator).deleteByIdAndChunks(anyString());
      StaleSourceHandler failHandler =
          new StaleSourceHandler(throwingCoordinator);
      assertEquals(0, failHandler.deleteMissingSource(file),
          "Coordinator exception must be swallowed and surfaced as 0 so the caller does not "
              + "bump indexedSinceCommit on a no-op (best-effort delete semantics).");
    }

    @Test
    @DisplayName(
        "StaleSourceHandler marks identity deleted only when the source is really gone "
            + "(tempdoc 931 §C.6 — the DELETED classification also fires on an IOException)")
    void staleSourceHandlerMarksIdentityOnlyForAVerifiedAbsentSource() throws Exception {
      List<String> marked = new java.util.ArrayList<>();
      DocumentIdentityStore recorder =
          new DocumentIdentityStore() {
            @Override
            public void markDeleted(String pathHash, long nowMs) {
              marked.add(pathHash);
            }

            @Override
            public Identity resolve(String pathHash, long nowMs) {
              throw new UnsupportedOperationException();
            }

            @Override
            public Identity importExisting(String pathHash, String docUid, long nowMs) {
              throw new UnsupportedOperationException();
            }

            @Override
            public int importExisting(
                java.util.Collection<ImportedIdentity> identities, long nowMs) {
              throw new UnsupportedOperationException();
            }

            @Override
            public long identityCount() {
              throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasImportRecord(String generationId) {
              throw new UnsupportedOperationException();
            }

            @Override
            public void recordImport(ImportRecord record) {
              throw new UnsupportedOperationException();
            }

            @Override
            public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
              throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<Identity> lookup(String pathHash) {
              throw new UnsupportedOperationException();
            }
          };
      StaleSourceHandler handler =
          new StaleSourceHandler(
              mock(IndexingCoordinator.class), recorder);

      Path stillThere = Files.writeString(Files.createTempFile("js-stale-present", ".txt"), "body");
      assertEquals(1, handler.deleteMissingSource(stillThere));
      assertTrue(
          marked.isEmpty(),
          "the source is present, so the index delete is not evidence of a deletion");

      Path gone = Files.createTempDirectory("js-stale-gone").resolve("gone.txt");
      assertEquals(1, handler.deleteMissingSource(gone));
      assertEquals(
          List.of(
              DocumentIdentityStore.pathHash(
                  io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(gone))),
          marked);
    }


    @Test
    @DisplayName(
        "processBatch maintains the batchIndexed+Skipped+Failed counter invariant across "
            + "mixed outcomes (regression net for Slice 4a Extractor/Writer split)")
    void processBatchMaintainsCounterInvariantAcrossMixedOutcomes() throws Exception {
      Path good1 = Files.writeString(Files.createTempFile("js-inv-good1", ".txt"), "alpha");
      Path good2 = Files.writeString(Files.createTempFile("js-inv-good2", ".txt"), "beta");
      Path bad = Files.writeString(Files.createTempFile("js-inv-bad", ".txt"), "gamma");
      Path missing = Files.createTempDirectory("js-inv-missing").resolve("gone.txt");
      Path nonRegular = Files.createTempDirectory("js-inv-dir");

      RecordingQueue queue = new RecordingQueue();
      // ContentExtractor: good paths succeed; bad throws parser exception; missing/nonRegular
      // are rejected before the extractor (filesystem checks).
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path)
                    throws ContentExtractor.ExtractionException {
                  if (path.equals(bad)) {
                    throw new ContentExtractor.ExtractionException("parser boom");
                  }
                  return new ExtractionResult("content", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });
      setRunning(loop, true);

      List<JobQueue.IndexJob> jobs =
          List.of(
              new JobQueue.IndexJob(good1, null),
              new JobQueue.IndexJob(good2, null),
              new JobQueue.IndexJob(bad, null),
              new JobQueue.IndexJob(missing, null),
              new JobQueue.IndexJob(nonRegular, null));
      invokeProcessBatch(loop, jobs);

      // Tempdoc 516 Slice 3: the 4 batch counter fields were encapsulated in BatchStats.
      var batchStatsField = IndexingLoop.class.getDeclaredField("batchStats");
      batchStatsField.setAccessible(true);
      var batchStats =
          (io.justsearch.indexerworker.loop.ops.BatchStats) batchStatsField.get(loop);
      long indexed = batchStats.indexed();
      long skipped = batchStats.skipped();
      long failed = batchStats.failed();

      assertEquals(
          jobs.size(),
          indexed + skipped + failed,
          "Counter invariant must hold: indexed("
              + indexed
              + ") + skipped("
              + skipped
              + ") + failed("
              + failed
              + ") = jobs("
              + jobs.size()
              + "). Slice 4a's Extractor/Writer split must thread these counters through "
              + "BatchStats so the invariant survives extraction.");
    }

    @Test
    @DisplayName(
        "maybeFinalizeEmbeddingRebuildIfNeeded commits + stamps fingerprint + resets counters "
            + "when ECC reports rebuild complete (regression net for Slice 4c Embedding "
            + "lifecycle extraction)")
    void maybeFinalizeEmbeddingRebuildIfNeededCommitsAndResetsCounters() throws Exception {
      RecordingQueue queue = new RecordingQueue();
      CommitOps commitOps = mock(CommitOps.class);
      DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
      IndexCountOps indexCountOps = mock(IndexCountOps.class);
      WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
      queue.indexingCoordinator = mock(IndexingCoordinator.class);
      when(indexCountOps.countByField(any(), any())).thenReturn(0);

      IndexingLoop loop =
          new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
              queue,
              queue.indexingCoordinator,
              commitOps,
              documentFieldOps,
              indexCountOps,
              () -> null,
              signalBus,
              IndexingPacing.unthrottled(),
              null,
              null,
              null,
              null,
              new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
                  providerReturning("unused"),
                  Duration.ofSeconds(5),
                  (io.justsearch.indexerworker.extract.ExtractionMetricCatalog) null),
              null, // W7.2 — default EncoderBindings
              null); // W7.2 followup — default IndexingLoopOptions

      // ECC reports REBUILDING + completion confirmed.
      EmbeddingCompatibilityController ecc =
          mock(EmbeddingCompatibilityController.class);
      when(ecc.state())
          .thenReturn(
              EmbeddingCompatibilityController.State.REBUILDING);
      // Tempdoc 726 F1: queueDepth no longer gates certification, so match any depth.
      when(ecc.checkRebuildCompletion(anyLong(), eq(0))).thenReturn(true);
      loop.getEmbeddingLifecycle().setEmbeddingCompatController(ecc);

      // Pre-set indexedSinceCommit > 0 so we can assert it resets.
      var indexedField = IndexingLoop.class.getDeclaredField("indexedSinceCommit");
      indexedField.setAccessible(true);
      indexedField.setLong(loop, 7L);

      // Tempdoc 516 Slice 4c: maybeFinalize is now a wrapper that delegates to the
      // lifecycle's tryFinalizeRebuild() and resets the commit-driver counters on true.
      // Tempdoc 726 F1: the lifecycle debounces certification with a two-consecutive-reads guard
      // (pendingEmbeddings==0 twice — indexCountOps is a mock defaulting countByField()→0), so the
      // first invocation confirms and the second certifies + commits.
      Method finalize =
          IndexingLoop.class.getDeclaredMethod("tryFinalizeEmbeddingRebuild");
      finalize.setAccessible(true);
      finalize.invoke(loop); // first pending==0 read — no commit yet
      finalize.invoke(loop); // second consecutive read — certifies

      verify(commitOps)
          .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.INDEXING_LOOP_REBUILD_STAMP);
      verify(ecc).onFingerprintStamped();
      assertEquals(
          0L,
          indexedField.getLong(loop),
          "indexedSinceCommit must reset after the rebuild-stamp commit. Slice 4c's "
              + "EmbeddingProviderLifecycle extraction must preserve this cross-cutting reset "
              + "via the shouldStampRebuild/onFingerprintStamped hook described in Appendix "
              + "A.6 constraint #11.");
    }

    @Test
    @DisplayName(
        "finalizeShutdownCommit() guarantees the rebuild-completion stamp commit fires at "
            + "shutdown even with indexedSinceCommit == 0 (tempdoc 730 review item 2: the "
            + "'no subsequent commit' ratchet hole — a plain worker restart right after rebuild "
            + "completion previously skipped the shutdown commit entirely because it was gated "
            + "on indexedSinceCommit > 0, a check irrelevant to whether the ECC completion needs "
            + "a forced empty stamp commit)")
    void finalizeShutdownCommitFiresRebuildStampWithNoIndexedSinceCommit() throws Exception {
      RecordingQueue queue = new RecordingQueue();
      CommitOps commitOps = mock(CommitOps.class);
      DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
      IndexCountOps indexCountOps = mock(IndexCountOps.class);
      WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
      queue.indexingCoordinator = mock(IndexingCoordinator.class);
      when(indexCountOps.countByField(any(), any())).thenReturn(0);

      IndexingLoop loop =
          new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
              queue,
              queue.indexingCoordinator,
              commitOps,
              documentFieldOps,
              indexCountOps,
              () -> null,
              signalBus,
              IndexingPacing.unthrottled(),
              null,
              null,
              null,
              null,
              new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
                  providerReturning("unused"),
                  Duration.ofSeconds(5),
                  (io.justsearch.indexerworker.extract.ExtractionMetricCatalog) null),
              null,
              null);

      // Rebuild has completed (queue drained, no pending embeddings) but — modelling a worker
      // that stops right after completion, before any further loop iteration — nothing else has
      // called tryFinalizeEmbeddingRebuild() yet.
      EmbeddingCompatibilityController ecc =
          mock(EmbeddingCompatibilityController.class);
      when(ecc.state())
          .thenReturn(
              EmbeddingCompatibilityController.State.REBUILDING);
      when(ecc.checkRebuildCompletion(0L, 0)).thenReturn(true);
      loop.getEmbeddingLifecycle().setEmbeddingCompatController(ecc);

      // indexedSinceCommit stays at its default 0 — the pre-fix shutdown block's
      // `if (indexedSinceCommit > 0)` guard would have skipped its commit entirely here.
      var indexedField = IndexingLoop.class.getDeclaredField("indexedSinceCommit");
      indexedField.setAccessible(true);
      assertEquals(0L, indexedField.getLong(loop), "sanity: nothing indexed since the last commit");

      Method finalizeShutdown = IndexingLoop.class.getDeclaredMethod("finalizeShutdownCommit");
      finalizeShutdown.setAccessible(true);
      finalizeShutdown.invoke(loop);

      verify(commitOps)
          .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.INDEXING_LOOP_REBUILD_STAMP);
      verify(ecc).onFingerprintStamped();
      // The indexedSinceCommit == 0 branch of the shutdown block must NOT also fire a redundant
      // INDEXING_LOOP_SHUTDOWN commit.
      verify(commitOps, never())
          .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.INDEXING_LOOP_SHUTDOWN);
    }

    private IndexingLoop newLoop(RecordingQueue queue, ContentExtractorProvider provider) {
      DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
      IndexCountOps indexCountOps = mock(IndexCountOps.class);
      WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
      DocumentIdentityStore identityStore = mock(DocumentIdentityStore.class);
      lenient()
          .when(identityStore.resolve(anyString(), anyLong()))
          .thenAnswer(
              invocation -> {
                String hash = invocation.getArgument(0);
                long now = invocation.getArgument(1);
                return new DocumentIdentityStore.Identity(hash, "test-uid-" + hash, now, now);
              });
      queue.indexingCoordinator = mock(IndexingCoordinator.class);
      return new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
          queue,
          queue.indexingCoordinator,
          mock(CommitOps.class),
          documentFieldOps,
          indexCountOps,
          () -> null,
          signalBus,
          IndexingPacing.unthrottled(),
          null,
          null,
          null,
          null,
          new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
              provider,
              Duration.ofSeconds(5),
              (io.justsearch.indexerworker.extract.ExtractionMetricCatalog) null),
          null, // W7.2 — default EncoderBindings
          new IndexingLoopOptions(false, null, identityStore, null, null, null));
    }

    private Object invokeExtractJob(IndexingLoop loop, Path file) throws Exception {
      // W5.2: extractJob moved to JobBatchExtractor. Call via the package-private accessor
      // + reflection on the now-private extractJob method on the extractor.
      Method method =
          JobBatchExtractor.class.getDeclaredMethod("extractJob", Path.class, String.class,
              JobQueue.EnqueueProvenance.class);
      method.setAccessible(true);
      return method.invoke(loop.getExtractor(), file, null, null);
    }

    private void invokeWriteExtractedJob(IndexingLoop loop, Object extractedJob) throws Exception {
      // W5.1: writeExtractedJob moved to JobBatchWriter.write.
      loop.getWriter().write((ExtractedJob) extractedJob, null);
    }

    private void invokeProcessBatch(IndexingLoop loop, List<JobQueue.IndexJob> jobs) throws Exception {
      Method method = IndexingLoop.class.getDeclaredMethod("processBatch", List.class);
      method.setAccessible(true);
      method.invoke(loop, jobs);
    }

    private void setRunning(IndexingLoop loop, boolean value) throws Exception {
      var field = IndexingLoop.class.getDeclaredField("running");
      field.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicBoolean) field.get(loop)).set(value);
    }

    private Object extractedJob(Path file, String content) throws Exception {
      return buildExtractedJob(file, content, false);
    }

    /**
     * Slice G.1 — variant of {@link #extractedJob} that constructs a SUCCESS_PARTIAL artifact
     * (truncated=true). Used by the mixed-batch drain test to verify outcome partitioning.
     */
    private Object extractedJobPartial(Path file, String content) throws Exception {
      return buildExtractedJob(file, content, true);
    }

    private Object buildExtractedJob(Path file, String content, boolean truncated) throws Exception {
      // Tempdoc 516 Slice 3: ExtractedJob moved from IndexingLoop$ExtractedJob (private nested)
      // to io.justsearch.indexerworker.loop.ops.ExtractedJob (package-public record) so the
      // upcoming Slice 4a JobBatchExtractor can return it without a circular package reference.
      ExtractionResult extraction = new ExtractionResult(content, null, "text/plain");
      FileEnvelope envelope = FileEnvelope.fromSnapshot(FileFreshnessSnapshot.capture(file));
      ValidatedExtractionArtifact artifact =
          ExtractionArtifact.full(extraction, TikaExtractionPolicy.defaults(), "test-structured", truncated)
              .validate(TikaExtractionPolicy.defaults(), envelope.pathHash());
      return new ExtractedJob(
          file,
          null,
          artifact,
          System.currentTimeMillis(),
          envelope,
          "00000000-0000-4000-8000-000000000001");
    }

    private ContentExtractorProvider providerReturning(String content) {
      return new ContentExtractorProvider() {
        @Override
        public ExtractionResult extract(Path file) {
          return new ExtractionResult(content, null, "text/plain");
        }

        @Override
        public String detectMimeType(Path file) {
          return "text/plain";
        }
      };
    }

    private ContentExtractorProvider providerThrowing(ContentExtractor.ExtractionException exception) {
      return new ContentExtractorProvider() {
        @Override
        public ExtractionResult extract(Path file) throws ContentExtractor.ExtractionException {
          throw exception;
        }

        @Override
        public String detectMimeType(Path file) {
          return "text/plain";
        }
      };
    }

    private ContentExtractorProvider providerThrowingWithoutDetect(
        ContentExtractor.ExtractionException exception) {
      return new ContentExtractorProvider() {
        @Override
        public ExtractionResult extract(Path file) throws ContentExtractor.ExtractionException {
          throw exception;
        }

        @Override
        public String detectMimeType(Path file) {
          throw new AssertionError("Failure metrics must not re-enter MIME detection");
        }
      };
    }
  }

  // ==================== Ingest starvation containment (tempdoc 798) ====================

  @Nested
  @DisplayName("background enrichment must never starve the ingest poll (tempdoc 798)")
  class IngestStarvationTests {

    /**
     * The field incident: the single {@code indexing-loop} thread entered the combined-enrichment
     * tight loop on 2 documents that were rewritten every batch but could never advance a stage,
     * and its continue-condition was "the write touched a doc" — so it never returned to {@code
     * pollPending}. Every user ingest after the first sat in the queue for 20+ minutes with zero
     * errors and every health surface green.
     *
     * <p>This is the end-to-end property {@code BackfillSchedulerTightLoopTest} cannot reach: it
     * drives the REAL {@link IndexingLoop} against the REAL {@link BackfillScheduler} wiring,
     * including the pending-ingest probe the loop publishes onto the signal bus. Batch A is
     * drained, batch B arrives while the loop is inside the non-converging backfill, and batch B
     * must still get claimed.
     *
     * <p>What it pins (tempdoc 798 review TQ2): the END-TO-END containment — that control does come
     * back to {@code pollPending} and a queued batch is claimed — not any one of the three
     * mechanisms that make it come back. Its 20s bound is deliberately generous enough that ANY of
     * them satisfies it. The per-mechanism isolation lives in {@code
     * BackfillSchedulerTightLoopTest}, where each case's bounds exclude the other two.
     */
    @Test
    @DisplayName(
        "non-converging enrichment population: the next ingest batch is still claimed")
    void nonConvergingBackfill_stillClaimsNextIngestBatch(@org.junit.jupiter.api.io.TempDir Path tmp)
        throws Exception {
      Path fileA = Files.writeString(tmp.resolve("a.txt"), "alpha content");
      Path fileB = Files.writeString(tmp.resolve("b.txt"), "beta content");
      List<JobQueue.IndexJob> batchB =
          List.of(new JobQueue.IndexJob(fileA, null), new JobQueue.IndexJob(fileB, null));

      java.util.concurrent.atomic.AtomicInteger pending =
          new java.util.concurrent.atomic.AtomicInteger(0);
      java.util.concurrent.atomic.AtomicInteger polls =
          new java.util.concurrent.atomic.AtomicInteger(0);
      java.util.concurrent.CountDownLatch batchBClaimed =
          new java.util.concurrent.CountDownLatch(1);

      JobQueue queue = mock(JobQueue.class);
      lenient()
          .when(queue.pollPending(anyInt()))
          .thenAnswer(
              inv -> {
                if (polls.incrementAndGet() == 1) {
                  // Batch A is drained. Batch B is enqueued right as the loop hands control to
                  // background enrichment — the exact ordering that livelocked in the field.
                  pending.set(batchB.size());
                  return List.of();
                }
                if (pending.getAndSet(0) > 0) {
                  batchBClaimed.countDown();
                  return batchB;
                }
                return List.of();
              });
      lenient()
          .when(queue.jobStateCounts())
          .thenAnswer(
              inv -> new JobQueue.JobStateCounts(pending.get(), pending.get(), 0L, 0L, 0L));
      lenient().when(queue.queueDepth()).thenAnswer(inv -> (long) pending.get());

      ProbeSignalBus signalBus = new ProbeSignalBus();
      IndexingLoop loop = nonConvergingBackfillLoop(queue, signalBus);
      loop.start();
      try {
        assertTrue(
            batchBClaimed.await(20, java.util.concurrent.TimeUnit.SECONDS),
            "the indexing loop must return from background enrichment and claim batch B."
                + " Pre-fix the combined tight loop continued on `written > 0` — activity, not"
                + " progress — so pollPending was never reached again (tempdoc 798). Observed"
                + " polls: "
                + polls.get());
        assertTrue(
            signalBus.probeRegistered(),
            "IndexingLoop must publish the pending-ingest probe onto the signal bus, otherwise"
                + " BackfillScheduler's third yield-signal is permanently blind");
      } finally {
        loop.close();
      }
    }

    /**
     * Builds an {@link IndexingLoop} whose enrichment backfill can never converge: the doc-id
     * query returns the same two ids on every call, every batch write reports both docs updated,
     * and no stage can advance (blank content, so the SPLADE and NER phases skip the docs while
     * the write still lands their status fields). SPLADE + NER bound (embedding unavailable) puts
     * the scheduler in combined mode.
     */
    private IndexingLoop nonConvergingBackfillLoop(JobQueue queue, WorkerSignalBus signalBus) {
      List<String> stuckIds = List.of("stuck-doc-1", "stuck-doc-2");

      DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
      lenient()
          .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
          .thenReturn(stuckIds);
      lenient().when(documentFieldOps.getDocumentContentBatch(anyList())).thenReturn(Map.of());
      lenient()
          .when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet()))
          .thenAnswer(
              inv -> {
                List<String> ids = inv.getArgument(0);
                Map<String, Map<String, String>> result = new java.util.HashMap<>();
                for (String id : ids) {
                  result.put(
                      id,
                      Map.of(
                          SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
                          SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
                }
                return result;
              });

      IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
      lenient()
          .when(coordinator.updateDocumentsBatch(anyList()))
          .thenAnswer(
              inv -> {
                List<?> batch = inv.getArgument(0);
                return new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes
                    .BatchUpdateResult(batch.size(), 0);
              });

      CommitOps commitOps = mock(CommitOps.class);
      lenient()
          .doAnswer(
              inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
              })
          .when(commitOps)
          .withNrtSuspended(any());

      IndexCountOps indexCountOps = mock(IndexCountOps.class);
      lenient().when(indexCountOps.countByField(anyString(), anyString())).thenReturn(0);

      io.justsearch.indexerworker.ner.NerService nerService =
          mock(io.justsearch.indexerworker.ner.NerService.class);
      lenient().when(nerService.isAvailable()).thenReturn(true);
      var encoderBindings = new io.justsearch.indexerworker.server.EncoderBindings();
      encoderBindings.bindSpladeEncoder(mock(SpladeEncoder.class));
      encoderBindings.bindNerService(nerService);

      return new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
          queue,
          coordinator,
          commitOps,
          documentFieldOps,
          indexCountOps,
          this::resolvedConfig,
          signalBus,
          IndexingPacing.unthrottled(),
          null,
          null,
          null,
          null,
          new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path file) {
                  return new ExtractionResult("content of " + file.getFileName(), null, "text/plain");
                }

                @Override
                public String detectMimeType(Path file) {
                  return "text/plain";
                }
              },
              Duration.ofSeconds(5),
              (io.justsearch.indexerworker.extract.ExtractionMetricCatalog) null),
          encoderBindings,
          null);
    }

    private io.justsearch.configuration.resolved.ResolvedConfig resolvedConfig() {
      var config = mock(io.justsearch.configuration.resolved.ResolvedConfig.class);
      var rag = mock(io.justsearch.configuration.resolved.ResolvedConfig.Rag.class);
      lenient().when(rag.chunkVectorsEnabled()).thenReturn(false);
      lenient().when(rag.chunkSpladeEnabled()).thenReturn(false);
      lenient().when(config.rag()).thenReturn(rag);
      var ai = mock(io.justsearch.configuration.resolved.ResolvedConfig.Ai.class);
      var embedding = mock(io.justsearch.configuration.resolved.ResolvedConfig.Ai.Embedding.class);
      lenient().when(embedding.lateChunkingEnabled()).thenReturn(false);
      lenient().when(ai.embedding()).thenReturn(embedding);
      lenient()
          .when(ai.backfillPacing())
          .thenReturn(
              io.justsearch.configuration.resolved.ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
      lenient().when(config.ai()).thenReturn(ai);
      return config;
    }
  }

  /**
   * Minimal {@link WorkerSignalBus} that honours the tempdoc 798 pending-ingest probe. A Mockito
   * mock cannot stand in here: its {@code setPendingIngestProbe} would no-op and {@code
   * hasPendingIngest()} would answer a constant false, so the wiring under test would be stubbed
   * out of existence.
   */
  private static final class ProbeSignalBus implements WorkerSignalBus {
    private volatile java.util.function.BooleanSupplier probe;
    private volatile boolean everRegistered;

    boolean probeRegistered() {
      return everRegistered;
    }

    @Override
    public void setPendingIngestProbe(java.util.function.BooleanSupplier probe) {
      this.probe = probe;
      if (probe != null) {
        this.everRegistered = true;
      }
    }

    @Override
    public boolean hasPendingIngest() {
      var p = probe;
      return p != null && p.getAsBoolean();
    }

    @Override
    public void open() {}





    @Override
    public boolean isMainGpuActive() {
      return false;
    }

    @Override
    public long startupTime() {
      return 0L;
    }

    @Override
    public void close() {}
  }

  private static final class RecordingQueue implements JobQueue {
    IngestionOutcome lastOutcome;
    IngestionLedgerEntry lastEntry;
    /**
     * Slice G.1 — every outcome-bearing markDone* call is appended here so tests can assert on
     * the full sequence (the prior single-{@code lastOutcome} field collapsed multiple calls
     * into the last one, which masked the LEDGER ↔ DOCUMENT inconsistency the slice fixes).
     */
    final List<IngestionOutcome> capturedOutcomes = new java.util.ArrayList<>();

    boolean done;
    boolean terminalFailed;
    boolean deferred;
    boolean failOutcomeWrites;
    boolean failOutcomeWritesAsIllegalArgument;
    IndexingCoordinator indexingCoordinator;

    private void maybeFail(String op) {
      if (failOutcomeWritesAsIllegalArgument) {
        // B-H.4 defect F — simulate the SqliteJobQueue defer-policy guard misroute.
        throw new IllegalArgumentException("simulated defer-policy misroute during " + op);
      }
      if (failOutcomeWrites) {
        throw new io.justsearch.indexerworker.queue.OutcomeWriteException(
            "simulated rollback during " + op, null);
      }
    }

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return paths == null ? 0 : paths.size();
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {
      done = true;
    }

    private void record(IngestionOutcome outcome) {
      lastOutcome = outcome;
      if (outcome != null) {
        capturedOutcomes.add(outcome);
      }
    }

    @Override
    public void markDone(Path path, IngestionOutcome outcome) {
      maybeFail("markDone");
      record(outcome);
      done = true;
    }

    @Override
    public void markDone(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      maybeFail("markDone");
      record(outcome);
      lastEntry = entry;
      done = true;
    }

    @Override
    public void markDoneTransitions(
        java.util.Collection<IngestionLedgerTransition> transitions, IngestionOutcome outcome) {
      maybeFail("markDoneTransitions");
      record(outcome);
      lastEntry =
          transitions == null || transitions.isEmpty()
              ? null
              : transitions.iterator().next().entry();
      done = true;
    }

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public void markFailed(Path path, IngestionOutcome outcome) {
      maybeFail("markFailed");
      record(outcome);
      if (outcome != null
          && outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE) {
        terminalFailed = true;
      }
    }

    @Override
    public void markFailed(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      maybeFail("markFailed");
      record(outcome);
      lastEntry = entry;
      if (outcome != null
          && outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE) {
        terminalFailed = true;
      }
    }

    @Override
    public void defer(Path path, IngestionOutcome outcome) {
      maybeFail("defer");
      record(outcome);
      deferred = true;
    }

    @Override
    public void defer(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      maybeFail("defer");
      record(outcome);
      lastEntry = entry;
      deferred = true;
    }

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long queueDepth() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
