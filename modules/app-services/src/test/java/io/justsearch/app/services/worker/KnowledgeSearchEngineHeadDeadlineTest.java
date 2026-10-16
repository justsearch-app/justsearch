/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static io.justsearch.app.services.worker.KnowledgeSearchEngineExpansionBudgetTest.engineOver;
import static io.justsearch.app.services.worker.KnowledgeSearchEngineExpansionBudgetTest.expandingRequest;
import static io.justsearch.app.services.worker.KnowledgeSearchEngineExpansionBudgetTest.hits;
import static io.justsearch.app.services.worker.KnowledgeSearchEngineExpansionBudgetTest.isExpanded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.SearchRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D §1.2 and §1.6 at the head seam: every worker search of one request runs inside the single
 * deadline {@code D} the head fixed for it ({@link HeadSearchDeadline}), the expansion re-search
 * inside {@code min(now + EXPANSION_BUDGET_MS, D)}, and each source of a fan-out inside the same
 * {@code D} on its own thread.
 */
final class KnowledgeSearchEngineHeadDeadlineTest {

  @TempDir Path modelsDir;
  private ConfigStore previousConfig;

  @BeforeEach
  void installConfig() {
    previousConfig = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", modelsDir.toString(),
        "justsearch.search.query_classification.enabled", "false"))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void everyWorkerSearchOfTheRequestRunsInsideOneHeadDeadline() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(4_000L);
    Map<String, long[]> seen = new ConcurrentHashMap<>();
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      OptionalLong head = HeadSearchDeadline.current();
      if (head.isPresent()) {
        seen.put(isExpanded(request) ? "expanded" : "base",
            new long[] {head.getAsLong(), System.nanoTime()});
      }
      return hits(isExpanded(request) ? "/expanded.txt" : "/base.txt");
    });

    long before = System.nanoTime();
    engineOver(client).search(expandingRequest(), TestEngineContexts.internal());
    long after = System.nanoTime();

    long[] base = seen.get("base");
    long[] expanded = seen.get("expanded");
    assertTrue(base != null && expanded != null, "both searches ran inside a head deadline");
    long fourSeconds = TimeUnit.MILLISECONDS.toNanos(4_000);
    assertTrue(base[0] - (before + fourSeconds) >= 0 && (after + fourSeconds) - base[0] >= 0,
        "the base search runs inside D = the search's start + requestDeadlineMs");
    assertTrue(base[0] - expanded[0] >= 0, "the re-search never runs past D");
    assertTrue(expanded[0] - (expanded[1] + TimeUnit.MILLISECONDS.toNanos(1_500)) <= 0,
        "the re-search gets at most EXPANSION_BUDGET_MS from when it starts");
  }

  @Test
  void aCallersNarrowerDeadlineIsTheSearchDeadline() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(15_000L);
    long[] seen = new long[1];
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      if (!isExpanded(invocation.getArgument(0))) seen[0] = HeadSearchDeadline.current().orElse(0);
      return hits("/base.txt");
    });

    long callerDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000);
    HeadSearchDeadline.within(callerDeadline,
        () -> engineOver(client).search(expandingRequest(), TestEngineContexts.internal()));

    assertEquals(callerDeadline, seen[0]);
  }

  @Test
  void eachSourceOfAFanOutRunsInsideTheSameHeadDeadline() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    Map<String, Long> deadlineBySource = new ConcurrentHashMap<>();
    Map<String, Thread> threadBySource = new ConcurrentHashMap<>();
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      String source = request.getFilters().getMetaSourceCount() == 0
          ? "unfiltered" : request.getFilters().getMetaSource(0);
      deadlineBySource.put(source, HeadSearchDeadline.current().orElse(0));
      threadBySource.put(source, Thread.currentThread());
      return hits("/" + source + ".txt");
    });
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    EngineWorkHandle work = mock(EngineWorkHandle.class);
    EngineContext context = TestEngineContexts.internal();
    when(work.context()).thenReturn(context);
    when(work.retain()).thenReturn(work);
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    when(admission.attach(any())).thenReturn(work);

    long d = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
    try (var executors = new TestEngineExecutors();
        var perSource = new SearchPerSourceExecutor(executors, admission)) {
      HeadSearchDeadline.within(d, () -> perSource.execute(client,
          SearchRequest.newBuilder().setQuery("fan out").setLimit(4).build(),
          List.of("one", "two"), 4, context));
    }

    assertTrue(threadBySource.get("one") != Thread.currentThread()
            && threadBySource.get("two") != Thread.currentThread(),
        "the sources ran on fan-out threads, not the caller's");
    assertEquals(d, deadlineBySource.get("one"), "source one sees the caller's D");
    assertEquals(d, deadlineBySource.get("two"), "source two sees the caller's D");
  }
}
