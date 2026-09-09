package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Test;
import io.justsearch.indexing.api.IndexDocument;

class ComponentsInjectionTest extends LuceneExecutorTestBase {

  private static ResolvedConfig resolveForTest() {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry();
    return builder.build();
  }

  @Test
  void usesInjectedComponentsWithoutDisk() throws Exception {
    AtomicBoolean refreshed = new AtomicBoolean(false);

    Directory dir = new ByteBuffersDirectory();
    IndexWriterConfig cfg = new IndexWriterConfig(new WhitespaceAnalyzer());
    IndexWriter writer = new IndexWriter(dir, cfg);
    SearcherManager sm =
        new SearcherManager(writer, true, true, new SearcherFactory());
    sm.addListener(
        new org.apache.lucene.search.ReferenceManager.RefreshListener() {
          @Override
          public void beforeRefresh() {}

          @Override
          public void afterRefresh(boolean didRefresh) {
            if (didRefresh) refreshed.set(true);
          }
        });

    Components components =
        new Components(
            /*commitMetadataEnabled*/ true,
            dir,
            writer,
            sm,
            null,
            Path.of("mem"),
            true,
            SchemaFields.SOFT_DELETE,
            SchemaFields.DOC_ID,
            cfg.getCodec().knnVectorsFormat(),
            cfg.getCodec().knnVectorsFormat() == null ? 0 : 10,
            cfg.getAnalyzer(),
            resolveForTest(),
            500L,
            1_000L,
            NrtMode.CONTINUOUS,
            1_000L,
            500L,
            1_000L);

    // Tempdoc 406 Phase 4b: injection via builder.withPrebuiltComponentsForTests
    // (replaces the legacy LuceneLifecycleManager + LifecycleTestAccessor.setComponentsForTests
    // path). RuntimeSession.openComponents reads ctx.prebuiltComponents (set from the
    // builder) and uses it instead of calling ComponentsFactory.build.
    IndexSchema schema =
        new IndexSchema(
            new FieldMapper(FieldCatalogDef.forTesting(4)),
            new SsotAnalyzerRegistry(),
            () -> (CommitMetadataSource) Map::of,
            new CommitMetadataValidator() {
              @Override
              public void validate(Map<String, Object> metadata) {}
            },
            cfg.getCodec().knnVectorsFormat());

    RunningRuntime runtime =
        schema
            .ephemeral().withExecutorRegistrations(testLuceneExecutors())
            .withPrebuiltComponentsForTests(components)
            .open();

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "1", SchemaFields.DOC_UID, "1")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefresh();

    assertTrue(runtime.commitOps() != null, "commitOps should be available");
    assertTrue(refreshed.get(), "maybeRefresh should trigger refresh listener on injected components");
  }
}
