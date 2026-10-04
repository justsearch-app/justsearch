package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.context.EngineContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryUnderstandingServiceTest {
  private static final EngineContext TEST_CONTEXT =
      io.justsearch.app.services.intent.EngineProvenance.internal(
          "query-understanding-test",
          EngineContext.Survival.INTERACTIVE,
          EngineContext.Urgency.FOREGROUND);

  @Mock OnlineAiService aiService;

  @Test
  void extractIfAvailableSamplesOncePerOperationAndCallsAiOnlyWhenEnabled() throws Exception {
    AtomicInteger samples = new AtomicInteger();
    QueryUnderstandingService service =
        new QueryUnderstandingService(aiService, () -> samples.getAndIncrement() == 0);
    when(aiService.isAvailable()).thenReturn(true);
    when(aiService.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture("{\"query\":\"budget report\"}"));

    CompletableFuture<QueryUnderstandingService.QuResult> enabled =
        service.extractIfAvailable("budget report", null, TEST_CONTEXT);
    CompletableFuture<QueryUnderstandingService.QuResult> disabled =
        service.extractIfAvailable("second query", null, TEST_CONTEXT);

    assertNotNull(enabled);
    assertEquals("budget report", enabled.get().refinedQuery());
    assertNull(disabled);
    assertEquals(2, samples.get());
    verify(aiService, times(1)).isAvailable();
    verify(aiService, times(1)).chatCompletion(any(), anyInt(), any(), any());
  }

  @Test
  void extractRetainsCompletedNullCompatibilityWhenDisabled() throws Exception {
    QueryUnderstandingService service = new QueryUnderstandingService(aiService, () -> false);

    CompletableFuture<QueryUnderstandingService.QuResult> result =
        service.extract("budget report", null, TEST_CONTEXT);

    assertNotNull(result);
    assertNull(result.get());
    verify(aiService, never()).isAvailable();
    verify(aiService, never()).chatCompletion(any(), anyInt(), any(), any());
  }
}
