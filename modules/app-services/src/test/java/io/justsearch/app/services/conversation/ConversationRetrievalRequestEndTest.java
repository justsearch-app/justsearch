/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.*;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.*;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.conversation.spi.RAGContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** D1/D2a/D6/D9/D12 and P7: run the substrate and reopen its actual saved record. */
final class ConversationRetrievalRequestEndTest {
  @TempDir Path temp;
  private static final ConversationShapeRef ID = new ConversationShapeRef("core.test-request-end");

  @org.junit.jupiter.api.AfterEach
  void clearInterrupt() { Thread.interrupted(); }

  static Stream<Arguments> endings() {
    return Stream.of("raw", "messageLess", "blank", "reason", "port", "interrupted", "deadline", "fallbackDeadline", "fallbackRaw",
        "fallbackReason", "fallbackPort", "stop", "fallbackStop")
        .flatMap(kind -> Stream.of(false, true).flatMap(chosen -> Stream.of("direct", "completion", "execution")
            .filter(wrapper -> !(kind.equals("deadline") && chosen || kind.startsWith("fallback") && !chosen))
            .map(wrapper -> Arguments.of(kind, chosen, wrapper))));
  }

  @ParameterizedTest @MethodSource("endings")
  void terminalRetrievalOutcomeIsSavedOnceAndSurvivesReload(String kind, boolean chosen, String wrapper) {
    Exception end = switch (kind) {
      case "interrupted" -> new InterruptedException();
      case "messageLess" -> new CancellationException();
      case "blank" -> new CancellationException(" \t");
      case "raw", "fallbackRaw" -> new CancellationException("cancelled");
      case "reason", "stop", "fallbackReason", "fallbackStop" -> new EngineWorkCancelledException("CONTEXT_CLOSED");
      case "port", "fallbackPort" -> new KnowledgeClientException(KnowledgeClientException.Status.CANCELLED, "cancelled");
      default -> new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, "neutral");
    };
    Exception failure = switch (wrapper) {
      case "completion" -> new CompletionException(end);
      case "execution" -> new CompletionException(new ExecutionException(end));
      default -> end;
    };
    var reason = new AtomicReference<String>();
    var work = mock(EngineWorkHandle.class);
    when(work.cancellationReason()).thenAnswer(ignored -> Optional.ofNullable(reason.get()));
    when(work.context()).thenReturn(TestEngineContexts.internal());
    doAnswer(call -> { reason.compareAndSet(null, call.getArgument(0)); return null; }).when(work).cancel(anyString());
    var admission = mock(EngineAdmissionService.class);
    when(admission.attach(any())).thenReturn(work);
    var docs = mock(DocumentService.class);
    var engineRef = new AtomicReference<ConversationEngine>();
    when(docs.retrieveContext(any(), any())).thenAnswer(ignored -> {
      if (kind.equals("stop")) engineRef.get().stopTurn("thread", "turn");
      if (kind.startsWith("fallback")) return CompletableFuture.completedFuture(
          new DocumentService.ContextResult("", 0, 0, 0, List.of(), "", "", false, List.of()));
      throw failure;
    });
    when(docs.fetchBatch(anyList(), any())).thenAnswer(ignored -> {
      if (kind.equals("fallbackStop")) engineRef.get().stopTurn("thread", "turn");
      throw failure;
    });
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    doAnswer(call -> {
      call.getArgument(1, OnlineAiService.StreamSink.class).onComplete().accept("stop");
      return null;
    }).when(ai).stream(any(), any());
    var shape = new ConversationShape(ID, new Presentation(new I18nKey("test.label"),
        new I18nKey("test.desc"), Optional.empty(), Optional.empty()), Audience.USER, Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN, IterationMode.ONE_SHOT, PersistenceMode.EPHEMERAL,
        List.of(), List.of(RAGContext.ID), List.of(), SingleHopController.ID, List.of(), true);
    var store = new FileConversationStore(temp);
    var engine = new ConversationEngine(ConversationShapeCatalog.of("core", List.of(shape)), List.of(),
        PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of(new RAGContext(docs))),
        StreamConsumerRegistry.of(List.of()), IterationControllerRegistry.of(List.of(SingleHopController.INSTANCE)),
        () -> ai, store, admission);
    engineRef.set(engine);
    var events = new ArrayList<SseEvent>();
    Map<String, Object> body = chosen
        ? Map.of("question", "q", "docIds", List.of("doc"), "conversationId", "thread", "turnId", "turn")
        : Map.of("question", "q", "conversationId", "thread", "turnId", "turn");
    assertDoesNotThrow(() -> engine.run(ID, body, Audience.USER, events::add, TestEngineContexts.internal()));
    if (kind.equals("interrupted")) assertTrue(Thread.currentThread().isInterrupted());
    var terminals = events.stream().filter(event -> event.name().equals("error") || event.name().equals("done")).toList();
    assertEquals(1, terminals.size(), events.toString());
    var history = new FileConversationStore(temp).loadHistory("thread");
    assertEquals(2, history.size(), history.toString());
    var saved = history.get(1);
    if (kind.equals("stop") || kind.equals("fallbackStop")) {
      assertEquals("CANCELLED", saved.get("disposition"));
      assertNotEquals(true, saved.get("terminalError"));
      assertEquals("Stopped by the user", terminals.getFirst().payload().get("error"));
    } else {
      String code = kind.endsWith("Deadline") || kind.equals("deadline") ? "RETRIEVAL_TIMEOUT" : "SERVICE_UNAVAILABLE";
      assertEquals(code, terminals.getFirst().payload().get("errorCode"));
      assertEquals(code, saved.get("errorCode"));
      assertEquals(true, saved.get("terminalError"));
      assertEquals(terminals.getFirst().payload().get("error"), saved.get("content"));
      assertFalse(saved.containsKey("disposition"));
      if (kind.equals("messageLess") || kind.equals("blank")) {
        assertEquals("Engine work cancelled", terminals.getFirst().payload().get("message"));
        assertEquals("Engine work cancelled", terminals.getFirst().payload().get("error"));
        assertEquals("Engine work cancelled", saved.get("content"));
      }
      if (code.equals("SERVICE_UNAVAILABLE")) {
        assertEquals("errors.SERVICE_UNAVAILABLE", terminals.getFirst().payload().get("i18nKey"));
      }
      if (kind.equals("interrupted")) {
        assertEquals("caller_interrupted", terminals.getFirst().payload().get("reasonCode"));
      }
      if (kind.equals("reason") || kind.equals("fallbackReason")) {
        assertEquals("CONTEXT_CLOSED", terminals.getFirst().payload().get("reasonCode"));
      }
    }
    verify(ai, never()).stream(any(), any());
    assertTrue(events.stream().noneMatch(event -> event.name().equals("rag.citations")));
    if (!kind.startsWith("fallback") && !kind.equals("stop")) {
      verify(docs, never()).fetchBatch(anyList(), any());
    }
    assertEquals(history, new FileConversationStore(temp).loadHistory("thread"));
  }
}
