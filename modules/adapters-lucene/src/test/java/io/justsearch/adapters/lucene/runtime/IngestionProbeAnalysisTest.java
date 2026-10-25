/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every mapped text field is analyzed fully without changing the real writer's documents. */
final class IngestionProbeAnalysisTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"title", "content", "content_preview", "vdu_enrichment"})
  void probeConsumesEachMappedTextFieldWithoutWriting(String brokenField) throws Exception {
    var failures = new AtomicInteger();
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0));
    try (var analyzer = new Analyzer(Analyzer.PER_FIELD_REUSE_STRATEGY) {
      @Override protected TokenStreamComponents createComponents(String field) {
        var tokenizer = new StandardTokenizer();
        var tokens = new TokenFilter(tokenizer) {
          @Override public boolean incrementToken() throws IOException {
            if (field.equals(brokenField)) {
              failures.incrementAndGet();
              throw new IOException("unavailable shared tokenization component");
            }
            return input.incrementToken();
          }
        };
        return new TokenStreamComponents(tokenizer, tokens);
      }
    }) {
      var cacheField = schema.analyzerRegistry().getClass().getDeclaredField("analyzersById");
      cacheField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var cache = (Map<String, Analyzer>) cacheField.get(schema.analyzerRegistry());
      for (String id : schema.analyzerRegistry().analyzerIds()) cache.put(id, analyzer);
      try (var runtime = schema.atPath(temp.resolve("index"))
          .withExecutorRegistrations(testLuceneExecutors()).open()) {
        var writer = runtime.session().snapshot.writer();
        int documents = writer.getDocStats().maxDoc;
        long sequence = writer.getMaxCompletedSequenceNumber();
        boolean pendingCommit = writer.hasUncommittedChanges();
        assertThrows(IndexWriterUnavailableException.class,
            () -> runtime.indexingCoordinator().probeIngestion(),
            "the probe must exhaust the actual analyzer stream for " + brokenField);
        assertEquals(1, failures.get());
        assertTrue(writer.isOpen(), "analysis failure does not require the writer to close");
        assertEquals(documents, writer.getDocStats().maxDoc);
        assertEquals(sequence, writer.getMaxCompletedSequenceNumber(), "no document operation");
        assertEquals(pendingCommit, writer.hasUncommittedChanges(), "no implicit commit");
      }
    }
  }
}
