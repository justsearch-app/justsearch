/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** D11: neither embedding catch can turn cancellation or executor refusal into positions. */
final class RagMmrRequestEndTest {
  static Stream<Arguments> failures() {
    return Stream.of("query", "firstPassage", "laterPassage")
        .flatMap(path -> Stream.of(false, true).flatMap(refusal -> Stream.of("direct", "completion", "execution")
            .map(wrapper -> Arguments.of(path, refusal, wrapper))));
  }

  @ParameterizedTest @MethodSource("failures")
  void embeddingRequestEndsPropagate(String path, boolean refusal, String wrapper) {
    RuntimeException end = refusal
        ? new EngineExecutorRejectedException(EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "embedding", 3)
        : new CancellationException();
    RuntimeException failure = switch (wrapper) {
      case "completion" -> new CompletionException(end);
      case "execution" -> new CompletionException(new ExecutionException(end));
      default -> end;
    };
    var provider = mock(EmbeddingProvider.class);
    when(provider.dimension()).thenReturn(2);
    when(provider.availability()).thenReturn(EmbeddingProvider.Availability.READY);
    when(provider.embedQuery(anyString(), any())).thenAnswer(ignored -> { throw failure; });
    var passages = new AtomicInteger();
    when(provider.embedDocument(anyString(), any())).thenAnswer(ignored -> {
      if (!path.equals("laterPassage") || passages.getAndIncrement() == 2) throw failure;
      return new float[] {1, 0};
    });
    var config = new ResolvedConfigBuilder();
    config.putDefault("rag.diversify.mode", "mmr");
    var settings = config.build();
    var ops = new RagContextOps(null, null, null, () -> settings, provider);
    var hits = IntStream.range(0, 5).mapToObj(i -> new LuceneRuntimeTypes.SearchHit("h" + i, 1f,
        Map.of(SchemaFields.CHUNK_CONTENT, "passage " + i, SchemaFields.CHUNK_INDEX, "0", SchemaFields.CHUNK_TOTAL, "1"))).toList();
    assertSame(end, assertThrows(RuntimeException.class, () -> ops.diversifyChunks("q",
        path.equals("query") ? null : new float[] {1, 0}, hits, 2, true, InferenceRequest.foreground())));
    if (path.equals("query")) verify(provider, never()).embedDocument(anyString(), any());
    if (path.equals("laterPassage")) assertEquals(3, passages.get());
  }
}
