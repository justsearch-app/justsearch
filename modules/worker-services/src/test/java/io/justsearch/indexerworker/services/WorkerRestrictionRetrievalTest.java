/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Message;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.TimeRangeMs;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F3/F13/F14: actual Worker service and Lucene retrieval, including capped parent resolution. */
class WorkerRestrictionRetrievalTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path directory;
  private RunningRuntime runtime;
  private WorkerSearchService service;

  @BeforeEach
  void open() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(runtime);
  }

  @AfterEach
  void close() throws Exception {
    if (runtime != null) runtime.close();
  }

  @Test
  void chunksChunklessUnionAndFullDocumentPathsIntersectAllBounds() throws Exception {
    for (boolean chunks : List.of(false, true)) {
      String suffix = chunks ? "chunked" : "short";
      index("/inside/eligible-" + suffix, 100L, 50L, "needle ELIGIBLE", chunks);
      index("/outside/folder-" + suffix, 100L, 50L, "needle FOLDERDECOY", chunks);
      index("/inside/date-" + suffix, 200L, 50L, "needle DATEDECOY", chunks);
      index("/inside/size-" + suffix, 100L, 100L, "needle SIZEDECOY", chunks);
      index("/inside/unknown-" + suffix, null, null, "needle UNKNOWNDECOY", chunks);
    }
    refresh();
    for (boolean full : List.of(false, true)) {
      var result = service.retrieveContext(request().setReturnFullDocuments(full).build(), CallContext.none());
      assertTrue(result.getContext().contains("ELIGIBLE"), result.toString());
      assertFalse(result.getContext().contains("DECOY"), result.toString());
      assertTrue(result.getChunksList().stream().allMatch(c -> c.getParentDocId().startsWith("/inside/eligible-")));
      if (!full) assertTrue(result.getUsedChunks());
      else assertEquals("FULL_DOCUMENT_REQUESTED", result.getRetrievalModeReason());
    }
  }

  @Test
  void fulltextFallbackAndEmptyIntersectionDoNotBroaden() throws Exception {
    // A non-matching chunk stops the chunkless union; only fulltext fallback can supply this parent.
    index("/inside/eligible", 100L, 50L, "needle ELIGIBLE appendix", true, "appendix");
    index("/inside/decoy", 100L, 100L, "needle SIZEDECOY appendix", true, "appendix");
    refresh();
    var result = service.retrieveContext(request().build(), CallContext.none());
    assertEquals("FULLTEXT_FALLBACK", result.getRetrievalMode());
    assertTrue(result.getContext().contains("ELIGIBLE"));
    assertFalse(result.getContext().contains("DECOY"));
    var empty = service.retrieveContext(request().setPathPrefix("/missing").build(), CallContext.none());
    assertTrue(empty.getContext().isBlank());
    assertEquals(0, empty.getChunksCount());
  }

  @Test
  void knownZeroMatchesAndUnknownSizeDoesNot() throws Exception {
    index("/inside/zero", 100L, 0L, "needle ZERO", false);
    index("/inside/unknown", 100L, null, "needle UNKNOWN", false);
    refresh();
    var request = request();
    size(request, 0L, 0L);
    var result = service.retrieveContext(request.build(), CallContext.none());
    assertTrue(result.getContext().contains("ZERO"));
    assertFalse(result.getContext().contains("UNKNOWN"));
  }

  @Test
  void exactlyTenThousandSucceedsOverflowIsTypedAndExplicitIdsAvoidIt() throws Exception {
    for (int i = 0; i < 9999; i++) index("/inside/metadata-" + i, 100L, 50L, "", false);
    index("/inside/target", 100L, 50L, "needle TARGET", false);
    refresh();
    assertTrue(service.retrieveContext(request().build(), CallContext.none()).getContext().contains("TARGET"));
    // Replacing the first target makes the sole query hit the 10,001st parent.
    index("/inside/target", 100L, 50L, "", false);
    index("/inside/zzzz-target", 100L, 50L, "needle TARGET", false);
    refresh();
    var error = assertThrows(WorkerServiceException.class,
        () -> service.retrieveContext(request().build(), CallContext.none()));
    assertEquals(WorkerServiceException.Status.RESOURCE_EXHAUSTED, error.status());
    assertTrue(error.getMessage().contains("10000"));
    var scoped = service.retrieveContext(request().addDocIds("/inside/zzzz-target").build(), CallContext.none());
    assertTrue(scoped.getContext().contains("TARGET"));
    assertEquals(Set.of("/inside/zzzz-target"), scoped.getChunksList().stream()
        .map(c -> c.getParentDocId()).collect(java.util.stream.Collectors.toSet()));
  }

  private static RetrieveContextRequest.Builder request() {
    var builder = RetrieveContextRequest.newBuilder().setQuestion("needle").setTopK(10)
        .setPathPrefix("/inside").setModifiedAt(TimeRangeMs.newBuilder().setFromMs(100).setToMs(100));
    size(builder, 50L, 50L);
    return builder;
  }

  private static void size(Message.Builder builder, Long from, Long to) {
    var field = builder.getDescriptorForType().findFieldByName("size_bytes");
    if (field == null) return; // Run baseline behavior, so exclusion assertions fail, not compilation.
    var range = builder.newBuilderForField(field);
    if (from != null) range.setField(range.getDescriptorForType().findFieldByName("from_bytes"), from);
    if (to != null) range.setField(range.getDescriptorForType().findFieldByName("to_bytes"), to);
    builder.setField(field, range.build());
  }

  private void index(String id, Long date, Long size, String text, boolean chunked) throws Exception {
    index(id, date, size, text, chunked, text);
  }

  private void index(String id, Long date, Long size, String text, boolean chunked, String chunkText) throws Exception {
    var parent = new HashMap<String, Object>();
    parent.put(SchemaFields.DOC_ID, id);
    parent.put(SchemaFields.DOC_UID, id + "#0");
    parent.put(SchemaFields.PATH, id);
    parent.put(SchemaFields.CONTENT, text);
    parent.put(SchemaFields.MIME, "text/plain");
    if (date != null) parent.put(SchemaFields.MODIFIED_AT, date);
    if (size != null) parent.put(SchemaFields.SIZE_BYTES, size);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(parent));
    if (chunked) {
      var chunk = new HashMap<String, Object>();
      chunk.put(SchemaFields.DOC_ID, "chunk:" + id);
      chunk.put(SchemaFields.DOC_UID, "chunk:" + id + "#0");
      chunk.put(SchemaFields.PATH, id);
      chunk.put(SchemaFields.PARENT_DOC_ID, id);
      chunk.put(SchemaFields.IS_CHUNK, "true");
      chunk.put(SchemaFields.CHUNK_INDEX, 0);
      chunk.put(SchemaFields.CHUNK_TOTAL, 1);
      chunk.put(SchemaFields.CHUNK_CONTENT, chunkText);
      chunk.put(SchemaFields.CHUNK_START_CHAR, text.indexOf(chunkText));
      chunk.put(SchemaFields.CHUNK_END_CHAR, text.indexOf(chunkText) + chunkText.length());
      chunk.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(text));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
    }
  }

  private void refresh() throws Exception {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
