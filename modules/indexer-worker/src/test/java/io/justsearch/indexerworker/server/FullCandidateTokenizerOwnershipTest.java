/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FullCandidateTokenizerOwnershipTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedUnpublishedPartitionKeepsOwnershipUntilRetirementSucceeds(boolean refuse,
      @TempDir Path dir) throws Exception {
    var session = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    var refusal = new AtomicBoolean(refuse);
    when(session.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      status.set(refusal.get() ? SessionHandle.RetirementStatus.REFUSED
          : SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(session).close();
    var tokenizer = mock(HuggingFaceTokenizer.class);
    doAnswer(ignored -> {
      assertEquals(SessionHandle.RetirementStatus.RETIRED, status.get());
      return null;
    }).when(tokenizer).close();
    var surface = new InferenceSurface(
        Optional.of(new EmbeddingAssembly(session, null, tokenizer, null)), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        new PolicySnapshot(RuntimePolicy.defaults(), new TreeMap<>()), List.of(session),
        new InferenceSurface.ComponentObservation(Optional.of("wrong-projection"),
            Set.of(EncoderRole.EMBEDDING), Set.of()));
    var config = TestResolvedConfigHelper.fromEntries(Map.of("justsearch.data.dir", dir.toString()));
    var projection = EncoderConfigurationProjection.from(config);
    var absent = io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.notConfigured();
    var identity = new EncoderSet.ModelIdentity(absent, absent, absent, false, 768);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(config));
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(dir));
      var build = KnowledgeServer.class.getDeclaredMethod("buildCandidateModels", InferenceSurface.class,
          EncoderSet.ModelIdentity.class, EncoderConfigurationProjection.class, IndexCompositionPlan.class);
      build.setAccessible(true);
      try {
        var failure = assertThrows(InvocationTargetException.class,
            () -> build.invoke(server, surface, identity, projection, null));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        if (refuse) {
          verify(tokenizer, never()).close();
          assertEquals(NativeQuiescence.UNQUIESCED, server.nativeQuiescence());
          refusal.set(false);
        } else verify(tokenizer).close();
        server.close();
        assertEquals(NativeQuiescence.QUIESCED, server.nativeQuiescence());
        verify(tokenizer).close();
      } finally {
        refusal.set(false);
        server.close();
      }
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }
}
