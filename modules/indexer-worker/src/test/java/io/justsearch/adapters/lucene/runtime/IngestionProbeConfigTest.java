/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockConstruction;

import io.justsearch.configuration.FieldCatalogDef;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LiveIndexWriterConfig;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pin every public config property, so new Lucene settings cannot silently escape the probe. */
final class IngestionProbeConfigTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void disposableConfigMatchesEveryLiveSettingExceptMergeAndCommitBehavior() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0))
        .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      var live = runtime.session().snapshot.writer().getConfig();
      // Non-default live settings exercise propagation rather than coincidentally equal defaults.
      set(live, "indexSort", new Sort(new SortField("sort-key", SortField.Type.LONG)));
      set(live, "indexSortFields", Set.of("sort-key"));
      set(live, "parentField", "_parent");
      set(live, "perThreadHardLimitMB", 512);
      set(live, "readerPooling", false);
      set(live, "useCompoundFile", false);
      set(live, "checkPendingFlushOnUpdate", false);
      var captured = new AtomicReference<IndexWriterConfig>();
      try (var construction = mockConstruction(IndexWriter.class, (writer, context) -> {
        assertInstanceOf(ByteBuffersDirectory.class, context.arguments().get(0));
        captured.set((IndexWriterConfig) context.arguments().get(1));
      })) {
        runtime.indexingCoordinator().probeIngestion();
        assertEquals(1, construction.constructed().size(), "one disposable writer");
      }
      var canary = captured.get();
      assertNotNull(canary);
      var mergeAndCommit = Set.of("getMergePolicy", "getMergeScheduler", "getCommitOnClose",
          "getIndexCommit", "getIndexDeletionPolicy", "getOpenMode", "getMaxFullFlushMergeWaitMillis");
      var checked = Set.of("getAnalyzer", "getMaxBufferedDocs", "getRAMBufferSizeMB",
          "getMergedSegmentWarmer", "getIndexCreatedVersionMajor", "getSimilarity", "getCodec",
          "getReaderPooling", "getRAMPerThreadHardLimitMB", "getInfoStream", "getUseCompoundFile",
          "getIndexSort", "getIndexSortFields", "getLeafSorter", "isCheckPendingFlushOnUpdate",
          "getSoftDeletesField", "getIndexWriterEventListener", "getParentField");
      var getters = Arrays.stream(LiveIndexWriterConfig.class.getDeclaredMethods())
          .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 0)
          .filter(m -> m.getName().startsWith("get") || m.getName().startsWith("is")).toList();
      assertAll(getters.stream().map(method -> () -> {
        String name = method.getName();
        assertTrue(checked.contains(name) || mergeAndCommit.contains(name),
            "New Lucene setting needs an explicit parity decision: " + name);
        if (checked.contains(name)) assertEquals(method.invoke(live), method.invoke(canary), name);
      }));
      assertFalse(canary.getCommitOnClose(), "disposable writer never commits");
      assertNotSame(live.getMergeScheduler(), canary.getMergeScheduler(), "independent lifecycle");
    }
  }

  private static void set(LiveIndexWriterConfig config, String name, Object value) throws Exception {
    var field = LiveIndexWriterConfig.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(config, value);
  }
}
