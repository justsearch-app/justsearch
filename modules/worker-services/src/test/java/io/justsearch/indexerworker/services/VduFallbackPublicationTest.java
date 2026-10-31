/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.app.api.*;
import io.justsearch.app.services.vdu.*;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Local PDFs -> real VDU processor -> worker publication -> committed parent/chunk readers. */
class VduFallbackPublicationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private final String baseline = "Readable baseline document content without vision output. ".repeat(220)
      + "uniquepage51 omittedpagetoken truncatedtailtoken";
  private final String transcription = "acceptedvisiontoken readable document content ".repeat(64).stripTrailing();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger metadataCalls = new AtomicInteger();
  private final AtomicBoolean failVision = new AtomicBoolean();
  private final AtomicBoolean cancelMetadata = new AtomicBoolean();
  private final AtomicBoolean metadataFails = new AtomicBoolean();
  private final AtomicBoolean eligible = new AtomicBoolean(true);
  private final List<UpdateVduResultRequest> writes = new ArrayList<>();
  private CallContext ctx;
  private String id;

  @ParameterizedTest
  @ValueSource(strings = {"cap", "skipped", "length", "empty-baseline"})
  void incompleteTranscriptionAddsWithoutLosingBaselineAcrossReopen(String kind) throws Exception {
    Path source = pdf(kind.equals("cap") ? 51 : 2, kind.equals("skipped"));
    String original = kind.equals("empty-baseline") ? "" : baseline;
    String retainedToken = kind.equals("skipped") ? "omittedpagetoken"
        : kind.equals("length") ? "truncatedtailtoken" : "uniquepage51";
    String accepted;
    try (var runtime = open()) {
      seed(runtime, source, original);
      var service = service(runtime);
      try (var files = new TempFileManager(directory.resolve("images"))) {
        var processor = processor(files, kind.equals("length") || kind.equals("empty-baseline") ? "length" : "stop", transcription);
        assertEquals(1, batch(service, processor).processPendingFiles(ctx.engineContext(), ignored -> {}).processed());
      }
      accepted = runtime.documentFieldOps().getDocumentContent(id);
      assertTrue(accepted.contains(transcription));
      assertTrue(accepted.startsWith(original), "S-D76/C5/C6/C7: baseline must precede accepted pages");
      assertEquals("COMPLETED", field(runtime, SchemaFields.VDU_STATUS));
      assertSearchable("acceptedvisiontoken");
      if (!original.isBlank()) assertSearchable(retainedToken);
      assertCoherent(runtime, accepted);
    }
    try (var runtime = open()) {
      assertEquals(accepted, runtime.documentFieldOps().getDocumentContent(id));
      assertCoherent(runtime, accepted);
      if (!original.isBlank()) assertSearchable(retainedToken);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"short-to-long", "long-to-short"})
  void completeCoverageReplacesBaselineAndRegeneratesReaders(String direction) throws Exception {
    Path source = pdf(1, false);
    String before = direction.equals("short-to-long") ? "bad" : baseline;
    String after = direction.equals("short-to-long") ? transcription.repeat(30) : transcription;
    try (var runtime = open()) {
      seed(runtime, source, before);
      try (var files = new TempFileManager(directory.resolve("images"))) {
        assertEquals(1, batch(service(runtime), processor(files, "stop", after))
            .processPendingFiles(ctx.engineContext(), ignored -> {}).processed());
      }
      assertEquals(after, runtime.documentFieldOps().getDocumentContent(id), "P1/C10");
      assertCoherent(runtime, after);
    }
    try (var runtime = open()) { assertCoherent(runtime, after); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "rejected"})
  void nonTextResultsPreserveEveryBaselineReaderAndArtifact(String kind) throws Exception {
    Path source = pdf(2, kind.equals("rejected"));
    Map<String, String> before;
    Map<String, Map<String, String>> chunks;
    try (var runtime = open()) {
      seed(runtime, source, baseline);
      before = descriptors(runtime);
      chunks = chunks(runtime);
      try (var files = new TempFileManager(directory.resolve("images"))) {
        // Reject every page for Stage 0; empty output is accepted but has no searchable payload.
        if (kind.equals("rejected")) pdf(2, true, true);
        if (kind.equals("rejected")) {
          runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(source)));
          refresh(runtime);
        }
        batch(service(runtime), processor(files, "stop", " \n ")).processPendingFiles(ctx.engineContext(), ignored -> {});
      }
      assertEquals(kind.equals("empty") ? "COMPLETED_EMPTY" : "REJECTED", field(runtime, SchemaFields.VDU_STATUS), "S-D75/C11");
      assertEquals(0, metadataCalls.get(), "C24: metadata fallback cannot override empty or abstained output");
      assertEquals(before, descriptors(runtime), "C13: content-derived fields must be unchanged");
      assertEquals(chunks, chunks(runtime));
      assertSearchable("uniquepage51");
    }
    try (var runtime = open()) {
      assertEquals(before, descriptors(runtime));
      assertEquals(chunks, chunks(runtime));
      assertSearchable("uniquepage51");
    }
  }

  @Test
  void diskChangeAfterClaimWithoutIndexChangeRefusesActualProcessorResult() throws Exception {
    Path source = pdf(1, false);
    try (var runtime = open()) {
      seed(runtime, source, baseline);
      var before = descriptors(runtime); var chunks = chunks(runtime);
      var service = service(runtime);
      String attempt = claim(service);
      // Rewrite only the source, retaining the claimed index revision and identity.
      pdf(2, false);
      VduProcessor.VduResult result;
      try (var files = new TempFileManager(directory.resolve("images"))) {
        result = processor(files, "stop", transcription).process(source, ctx.engineContext());
      }
      var response = service.updateVduResult(request(attempt, result), ctx);
      assertFalse(response.getSuccess(), "S-D19/C1: a current index revision cannot authorize different disk bytes");
      assertEquals(before, descriptors(runtime));
      assertEquals(chunks, chunks(runtime));
      assertEquals("PENDING", field(runtime, SchemaFields.VDU_STATUS));
      assertEquals("0", field(runtime, SchemaFields.VDU_RETRY_COUNT));
    }
    try (var runtime = open()) {
      assertEquals(baseline, runtime.documentFieldOps().getDocumentContent(id));
      assertSearchable("uniquepage51");
    }
  }

  @Test
  void missingObservedHashCannotAuthorizePublication() throws Exception {
    Path source = pdf(1, false);
    try (var runtime = open()) {
      seed(runtime, source, baseline); var service = service(runtime);
      var result = service.updateVduResult(UpdateVduResultRequest.newBuilder().setDocId(id)
          .setAttemptId(claim(service)).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(transcription).build(), ctx);
      assertFalse(result.getSuccess(), "C4: unverifiable result must retain baseline");
      assertEquals(baseline, runtime.documentFieldOps().getDocumentContent(id));
    }
  }

  @Test
  void ordinaryFailureRemainsSelectableAcrossReopenThenSuccessfulRetryClearsOldMetadata() throws Exception {
    Path source = pdf(1, false);
    failVision.set(true);
    metadataFails.set(true);
    try (var runtime = open()) {
      seed(runtime, source, baseline);
      try (var files = new TempFileManager(directory.resolve("images"))) {
        assertEquals(1, batch(service(runtime), processor(files, "stop", transcription))
            .processPendingFiles(ctx.engineContext(), ignored -> {}).failed());
      }
      assertEquals("PENDING", field(runtime, SchemaFields.VDU_STATUS), "S-D66/C14: ordinary selection must see remaining allowance");
      assertEquals("1", field(runtime, SchemaFields.VDU_RETRY_COUNT));
      assertTrue(field(runtime, SchemaFields.VDU_ENRICHMENT).contains("error"));
    }
    failVision.set(false);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      assertEquals(1, batch(service(runtime), processor(files, "stop", transcription))
          .processPendingFiles(ctx.engineContext(), ignored -> {}).processed());
      assertEquals(2, calls.get());
      assertEquals("2", field(runtime, SchemaFields.VDU_RETRY_COUNT));
      assertEquals(transcription, runtime.documentFieldOps().getDocumentContent(id));
      assertNull(field(runtime, SchemaFields.VDU_ENRICHMENT), "S-D80/C25: unavailable metadata must clear an earlier error");
    }
    try (var runtime = open()) {
      assertNull(field(runtime, SchemaFields.VDU_ENRICHMENT));
      assertSearchable("acceptedvisiontoken");
    }
  }

  @Test
  void repeatedFailuresExhaustAllowanceAndLeaveNextDocumentProcessable() throws Exception {
    Path source = pdf(1, false);
    failVision.set(true);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      seed(runtime, source, baseline);
      var service = service(runtime);
      var batch = batch(service, processor(files, "stop", transcription));
      for (int i = 1; i <= SchemaFields.VDU_MAX_RETRIES; i++) {
        assertEquals(1, batch.processPendingFiles(ctx.engineContext(), ignored -> {}).failed());
        assertEquals(Integer.toString(i), field(runtime, SchemaFields.VDU_RETRY_COUNT));
      }
      assertEquals("FAILED", field(runtime, SchemaFields.VDU_STATUS), "C15");
      assertEquals(0, batch.processPendingFiles(ctx.engineContext(), ignored -> {}).selected());
      assertEquals(SchemaFields.VDU_MAX_RETRIES, calls.get());
      failVision.set(false);
      Path other = directory.resolve("other.pdf"); Files.copy(source, other);
      seed(runtime, other, baseline);
      assertEquals(1, batch.processPendingFiles(ctx.engineContext(), ignored -> {}).processed());
    }
  }

  @Test
  void metadataFallbackDoesNotConcealSourceOrPublicationRefusal() throws Exception {
    Path source = pdf(1, false);
    metadataFails.set(true);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      seed(runtime, source, baseline);
      var service = service(runtime);
      var ai = model("stop", transcription);
      when(ai.chatCompletion(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
        runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.SOURCE_SHA256, "b".repeat(64)));
        refresh(runtime);
        return CompletableFuture.failedFuture(new TimeoutException("metadata"));
      });
      assertThrows(IllegalStateException.class, () -> batch(service, processor(files, ai))
          .processPendingFiles(ctx.engineContext(), ignored -> {}), "C24: source refusal remains visible");
      assertEquals(baseline, runtime.documentFieldOps().getDocumentContent(id));
      assertEquals(1, writes.size(), "publication refusal must never trigger a second failure write");
    }
  }

  @Test
  void cancellationOnFinalAttemptRefundsClaimAndDoesNotPublishAcceptedText() throws Exception {
    Path source = pdf(1, false);
    cancelMetadata.set(true);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      seed(runtime, source, baseline);
      runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.VDU_RETRY_COUNT, Integer.toString(SchemaFields.VDU_MAX_RETRIES - 1)));
      refresh(runtime);
      var service = service(runtime);
      assertThrows(CancellationException.class, () -> batch(service, processor(files, "stop", transcription))
          .processPendingFiles(ctx.engineContext(), ignored -> {}));
      assertTrue(writes.isEmpty(), "C16/C24: metadata fallback cannot swallow terminal cancellation");
      var recovery = RecoverVduProcessingRequest.newBuilder().setProcedureOwner(ctx.engineContext().workId().orElseThrow().toString());
      recovery.addAttemptIds(lastAttempt);
      assertEquals(1, service.recoverVduProcessing(recovery.build(), ctx).getRecoveredCount());
      assertEquals(Integer.toString(SchemaFields.VDU_MAX_RETRIES - 1), field(runtime, SchemaFields.VDU_RETRY_COUNT));
      assertEquals("PENDING", field(runtime, SchemaFields.VDU_STATUS));
      assertEquals(baseline, runtime.documentFieldOps().getDocumentContent(id));
    }
  }

  @Test
  void generationRefusalOnFinalAttemptReturnsAllowance() throws Exception {
    Path source = pdf(1, false);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      seed(runtime, source, baseline);
      runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.VDU_RETRY_COUNT,
          Integer.toString(SchemaFields.VDU_MAX_RETRIES - 1)));
      refresh(runtime);
      var ai = model("stop", transcription);
      when(ai.visionCompletionDetailed(anyString(), any(byte[].class), anyInt(), any(), any(), any()))
          .thenReturn(CompletableFuture.failedFuture(new io.justsearch.app.api.chat.GenerationUnavailableException("draining")));
      var result = batch(service(runtime), processor(files, ai)).processPendingFiles(ctx.engineContext(), ignored -> {});
      assertEquals(0, result.failed(), "P3/C16: refused generation is not a document failure");
      assertEquals("PENDING", field(runtime, SchemaFields.VDU_STATUS));
      assertEquals(Integer.toString(SchemaFields.VDU_MAX_RETRIES - 1), field(runtime, SchemaFields.VDU_RETRY_COUNT));
      assertTrue(writes.isEmpty());
    }
  }

  @Test
  void refusedFailureWriteDoesNotAcknowledgeProgressOrWriteAgain() throws Exception {
    Path source = pdf(1, false);
    failVision.set(true);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      seed(runtime, source, baseline);
      var service = spy(service(runtime));
      doReturn(UpdateVduResultResponse.newBuilder().setSuccess(false).setError("injected write refusal").build())
          .when(service).updateVduResult(any(), any());
      var progress = new ArrayList<OfflineProcessingOutcome>();
      assertThrows(IllegalStateException.class, () -> batch(service, processor(files, "stop", transcription))
          .processPendingFiles(ctx.engineContext(), progress::add), "C17");
      assertEquals(1, writes.size(), "a refused FAILED write cannot trigger another FAILED write");
      assertEquals(0, progress.getLast().failed());
      assertEquals("PROCESSING", field(runtime, SchemaFields.VDU_STATUS));
      assertTrue(service.releaseVduProcessing(id, lastAttempt, ctx));
      String newerAttempt = claim(service);
      var oldRecovery = RecoverVduProcessingRequest.newBuilder().setProcedureOwner(ctx.engineContext().workId().orElseThrow().toString())
          .addAttemptIds(lastAttempt).build();
      assertEquals(0, service.recoverVduProcessing(oldRecovery, ctx).getRecoveredCount());
      assertEquals("PROCESSING", field(runtime, SchemaFields.VDU_STATUS));
      assertTrue(service.releaseVduProcessing(id, newerAttempt, ctx));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "exceptional", "synchronous"})
  void auxiliaryFailureStillPublishesSearchableTranscriptionAfterReopen(String failure) throws Exception {
    Path source = pdf(1, false);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      seed(runtime, source, baseline);
      var ai = model("stop", transcription);
      when(ai.chatCompletion(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
        if (failure.equals("synchronous")) throw new IllegalStateException("metadata unavailable");
        return CompletableFuture.failedFuture(failure.equals("timeout")
            ? new TimeoutException("metadata timeout") : new IOException("metadata failed"));
      });
      assertEquals(1, batch(service(runtime), processor(files, ai))
          .processPendingFiles(ctx.engineContext(), ignored -> {}).processed(), "S-D80/C23");
      assertEquals(transcription, runtime.documentFieldOps().getDocumentContent(id));
      assertSearchable("acceptedvisiontoken");
    }
    try (var runtime = open()) {
      assertEquals(transcription, runtime.documentFieldOps().getDocumentContent(id));
      assertSearchable("acceptedvisiontoken");
    }
  }

  @Test
  void auxiliaryFallbackDoesNotConcealPublicationWriteFailure() throws Exception {
    Path source = pdf(1, false);
    metadataFails.set(true);
    try (var runtime = open(); var files = new TempFileManager(directory.resolve("images"))) {
      seed(runtime, source, baseline);
      var service = spy(service(runtime));
      doReturn(UpdateVduResultResponse.newBuilder().setSuccess(false).setError("injected publication refusal").build())
          .when(service).updateVduResult(any(), any());
      assertThrows(IllegalStateException.class, () -> batch(service, processor(files, "stop", transcription))
          .processPendingFiles(ctx.engineContext(), ignored -> {}), "C24");
      assertEquals(1, writes.size());
      assertEquals(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT, writes.getFirst().getOutcome());
      assertEquals(baseline, runtime.documentFieldOps().getDocumentContent(id));
    }
  }

  @Test
  void incompletePublicationCannotTreatBaselineReadFailureAsEmptyText() throws Exception {
    Path source = pdf(1, false);
    try (var runtime = open()) {
      seed(runtime, source, baseline);
      var before = descriptors(runtime); var chunks = chunks(runtime);
      var fields = spy(runtime.documentFieldOps());
      doThrow(new IOException("controlled baseline read failure"))
          .when(fields).getDocumentFieldOrThrow(id, SchemaFields.CONTENT);
      var facade = mock(RunningRuntime.class);
      when(facade.documentFieldOps()).thenReturn(fields);
      when(facade.indexingCoordinator()).thenReturn(runtime.indexingCoordinator());
      var request = UpdateVduResultRequest.newBuilder().setDocId(id).setExtractedContent(transcription)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);
      optional(request, "setIncompleteCoverage", boolean.class, true);
      IOException failure = assertThrows(IOException.class, () -> applyWriter(facade, request.build(), Map.of(SchemaFields.CONTENT, baseline)), "C13: missing baseline authority must fail before chunk mutation");
      assertEquals("controlled baseline read failure", failure.getMessage());
      refresh(runtime);
      assertEquals(before, descriptors(runtime));
      assertEquals(chunks, chunks(runtime));
    }
  }

  private static void applyWriter(RunningRuntime runtime, UpdateVduResultRequest request,
      Map<String, Object> expected) throws Throwable {
    java.lang.reflect.Method method;
    try {
      method = VduResultWriter.class.getDeclaredMethod("apply", RunningRuntime.class,
          UpdateVduResultRequest.class, boolean.class, Map.class, int.class);
    } catch (NoSuchMethodException baselineVersion) {
      method = VduResultWriter.class.getDeclaredMethod("apply", RunningRuntime.class,
          UpdateVduResultRequest.class, boolean.class, Map.class);
    }
    Object[] args = method.getParameterCount() == 5
        ? new Object[] {runtime, request, false, expected, SchemaFields.VDU_MAX_RETRIES}
        : new Object[] {runtime, request, false, expected};
    try { method.invoke(null, args); }
    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
  }

  private String lastAttempt;
  private VduBatchProcessor batch(WorkerIngestService service, VduProcessor processor) {
    KnowledgeClient client = mock(KnowledgeClient.class, call -> {
      var args = call.getArguments();
      return switch (call.getMethod().getName()) {
        case "queryPendingVduDocIds" -> service.queryPendingVdu(QueryPendingVduRequest.newBuilder().setLimit(100).build(), ctx).getDocIdsList();
        case "markVduProcessing" -> {
          lastAttempt = args.length == 4 ? (String) args[2] : UUID.randomUUID().toString();
          var marked = service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId((String) args[0])
              .setMaxRetries((Integer) args[1]).setAttemptId(lastAttempt).build(), ctx);
          yield marked.getSuccess() ? marked.getRetryCount() : -1;
        }
        case "releaseVduProcessing" -> service.releaseVduProcessing((String) args[0], (String) args[1], ctx);
        case "updateVduResult" -> {
          var request = UpdateVduResultRequest.newBuilder().setDocId((String) args[0]).setAttemptId((String) args[5])
              .setOutcome((VduUpdateOutcome) args[2]).setPageCount((Integer) args[4])
              .setVduEnrichment(args[3] == null ? "" : (String) args[3]);
          if (args[1] != null) request.setExtractedContent((String) args[1]);
          if (args.length == 9) {
            optional(request, "setSourceSha256", String.class, args[6]);
            optional(request, "setIncompleteCoverage", boolean.class, args[7]);
          }
          writes.add(request.build());
          yield service.updateVduResult(request.build(), ctx).getSuccess();
        }
        default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      };
    });
    var gpu = mock(GpuCapabilitiesService.class);
    var effective = new GpuCapabilities.Effective(true, "test", GpuCapabilities.Confidence.HIGH,
        "1.0", 1, 0, 1, 24_000_000_000L, 24_000_000_000L, 0L, GpuCapabilities.Cuda.unknown());
    var snapshot = new GpuCapabilities(null, null, effective);
    when(gpu.snapshot()).thenReturn(snapshot);
    return new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(), new VduCapabilityState(), () -> !eligible.get());
  }

  private VduProcessor processor(TempFileManager files, String finish, String text) {
    return processor(files, model(finish, text));
  }
  private VduProcessor processor(TempFileManager files, OnlineAiService ai) {
    var introspection = mock(OnlineAiRuntimeIntrospection.class);
    when(introspection.hasVisionCapability()).thenReturn(true);
    return new VduProcessor(introspection, mock(OnlineAiLifecycleControl.class), ai, files, new ImagePreparer());
  }
  private OnlineAiService model(String finish, String text) {
    var ai = mock(OnlineAiService.class);
    when(ai.visionCompletionDetailed(anyString(), any(byte[].class), anyInt(), any(), any(), any())).thenAnswer(call -> {
      calls.incrementAndGet();
      if (failVision.get()) return CompletableFuture.failedFuture(new IOException("transient unclassified failure"));
      return CompletableFuture.completedFuture(new OnlineAiService.VisionCompletionResult(text, finish, 20, -0.02, 0.0));
    });
    when(ai.chatCompletion(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
      metadataCalls.incrementAndGet();
      if (cancelMetadata.get()) return CompletableFuture.failedFuture(new CancellationException("terminal"));
      if (metadataFails.get()) return CompletableFuture.failedFuture(new TimeoutException("metadata"));
      return CompletableFuture.completedFuture("{\"summary\":\"current\"}");
    });
    return ai;
  }
  private UpdateVduResultRequest request(String attempt, VduProcessor.VduResult result) throws Exception {
    var request = UpdateVduResultRequest.newBuilder().setDocId(id).setAttemptId(attempt)
        .setExtractedContent(result.extractedText()).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);
    try {
      optional(request, "setSourceSha256", String.class, result.getClass().getMethod("sourceSha256").invoke(result));
      optional(request, "setIncompleteCoverage", boolean.class, !(boolean) result.getClass().getMethod("completeCoverage").invoke(result));
    } catch (NoSuchMethodException baselineVersion) { /* baseline publishes without a source witness */ }
    return request.build();
  }
  private static void optional(Object target, String name, Class<?> type, Object value) throws Exception {
    try { target.getClass().getMethod(name, type).invoke(target, value); }
    catch (NoSuchMethodException baselineVersion) { /* run against the baseline wire contract too */ }
  }
  private String claim(WorkerIngestService service) {
    String attempt = UUID.randomUUID().toString();
    var response = service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(id).setAttemptId(attempt).build(), ctx);
    assertTrue(response.getSuccess(), response.getError()); return attempt;
  }
  private WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
  }
  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer(); return runtime;
  }
  private void seed(RunningRuntime runtime, Path source, String content) throws Exception {
    id = source.toString();
    if (ctx == null) {
      var base = CallContext.none();
      ctx = new CallContext(null, null, null, base.engineContext().withWorkId(UUID.randomUUID()), base.provenance(), base.childLifetime());
    }
    var fields = new HashMap<String, Object>();
    fields.put(SchemaFields.DOC_ID, id); fields.put(SchemaFields.DOC_UID, "stable-uid");
    fields.put(SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(source));
    fields.put(SchemaFields.CONTENT, content); fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.PARENT_TOKEN_COUNT, (long) content.length() / 3);
    fields.put(SchemaFields.CONTENT_PREVIEW, content.substring(0, Math.min(content.length(), 500)));
    fields.put(SchemaFields.EMBEDDING_STATUS, "FAILED"); fields.put(SchemaFields.NER_STATUS, "FAILED");
    fields.put(SchemaFields.VDU_STATUS, "PENDING"); fields.put(SchemaFields.VDU_RETRY_COUNT, "0");
    var plan = ChunkDocumentWriter.plan(content, "application/pdf", "pdf");
    ChunkDocumentWriter.declare(fields, plan);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields)); refresh(runtime);
    ChunkDocumentWriter.replaceDeclaredChunks(runtime.indexingCoordinator(), id, content,
        new ChunkDocumentWriter.ParentChunkMetadata("application/pdf", "application/pdf", "pdf", "en", (long) content.length() / 3,
            "documents", "stable-uid"), plan, false);
    refresh(runtime);
  }
  private static void refresh(RunningRuntime runtime) { runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking(); }
  private String field(RunningRuntime runtime, String key) { return runtime.documentFieldOps().getDocumentField(id, key); }
  private Map<String, String> descriptors(RunningRuntime runtime) {
    return runtime.documentFieldOps().getDocumentFieldsBatch(List.of(id), Set.of(SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256,
        SchemaFields.CONTENT_PREVIEW, SchemaFields.PARENT_TOKEN_COUNT, SchemaFields.EMBEDDING_STATUS, SchemaFields.NER_STATUS)).get(id);
  }
  private Map<String, Map<String, String>> chunks(RunningRuntime runtime) {
    var ids = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
    return runtime.documentFieldOps().getDocumentFieldsBatch(ids,
        Set.of(SchemaFields.CHUNK_CONTENT, SchemaFields.CHUNK_PARENT_CONTENT_SHA256, SchemaFields.PARENT_TOKEN_COUNT));
  }
  private void assertCoherent(RunningRuntime runtime, String content) {
    assertEquals(content, runtime.documentFieldOps().getDocumentContent(id));
    assertEquals(ChunkParentRevision.sha256Hex(content), field(runtime, SchemaFields.CONTENT_SHA256));
    assertEquals("PENDING", field(runtime, SchemaFields.EMBEDDING_STATUS));
    for (var chunk : chunks(runtime).values()) {
      assertEquals(ChunkParentRevision.sha256Hex(content), chunk.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
      assertEquals(field(runtime, SchemaFields.PARENT_TOKEN_COUNT), chunk.get(SchemaFields.PARENT_TOKEN_COUNT));
    }
  }
  private void assertSearchable(String token) throws Exception {
    try (var fs = FSDirectory.open(directory.resolve("index")); var reader = DirectoryReader.open(fs)) {
      var searcher = new IndexSearcher(reader);
      var parent = new BooleanQuery.Builder().add(new TermQuery(new Term(SchemaFields.DOC_ID, id)), BooleanClause.Occur.FILTER)
          .add(new TermQuery(new Term(SchemaFields.CONTENT, token)), BooleanClause.Occur.MUST).build();
      assertEquals(1, searcher.count(parent), "parent search: " + token);
      var children = new BooleanQuery.Builder().add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, id)), BooleanClause.Occur.FILTER)
          .add(new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, token)), BooleanClause.Occur.MUST).build();
      assertTrue(searcher.count(children) > 0, "chunk search: " + token);
    }
  }
  private Path pdf(int pages, boolean blankLast) throws Exception { return pdf(pages, blankLast, false); }
  private Path pdf(int pages, boolean blankLast, boolean blankAll) throws Exception {
    Path source = directory.resolve("source.pdf");
    try (var doc = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        var page = new PDPage(new PDRectangle(120, 120)); doc.addPage(page);
        try (var content = new PDPageContentStream(doc, page)) {
          if (i == 50 || blankLast && i == pages - 1) {
            content.beginText();
            content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 8);
            content.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
            content.newLineAtOffset(10, 10);
            content.showText(i == 50 ? "uniquepage51" : "omittedpagetoken");
            content.endText();
          }
          if (blankAll || blankLast && i == pages - 1) continue;
          for (int x = 0; x < 120; x += 8) for (int y = 0; y < 120; y += 8) {
            if ((x / 8 + y / 8) % 2 == 0) { content.addRect(x, y, 8, 8); content.fill(); }
          }
        }
      }
      doc.save(source.toFile());
    }
    return source;
  }
}
