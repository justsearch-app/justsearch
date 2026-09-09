package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HierarchicalShapeRunner} (tempdoc 491 §C2.3). */
final class HierarchicalShapeRunnerTest {

  @Test
  @DisplayName("Missing docId emits NO_DOC_ID error and returns")
  void noDocId() {
    var runner = new HierarchicalShapeRunner(() -> new StubAi(List.of()), () -> new StubDocs(Map.of()));
    var events = new ArrayList<SseEvent>();
    runner.run(Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent err = events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("NO_DOC_ID", err.payload().get("errorCode"));
  }

  @Test
  @DisplayName("Small document uses single-pass synthesis (no sectioning)")
  void smallDocSinglePass() {
    var ai = new StubAi(List.of("the summary"));
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", "tiny content", Map.of())));
    var runner = new HierarchicalShapeRunner(() -> ai, () -> docs);

    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("docId", "doc"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    // progress: loading, standard, then chunk + done.
    assertTrue(
        events.stream().anyMatch(e -> "progress".equals(e.name())
            && "loading".equals(e.payload().get("phase"))));
    assertTrue(
        events.stream().anyMatch(e -> "progress".equals(e.name())
            && "standard".equals(e.payload().get("phase"))));
    SseEvent done = events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals(false, done.payload().get("hierarchical"));
    assertEquals("doc", done.payload().get("docId"));
    // No sectioning progress events.
    assertFalse(
        events.stream().anyMatch(e -> "progress".equals(e.name())
            && "splitting".equals(e.payload().get("phase"))));
  }

  @Test
  @DisplayName("Large document triggers hierarchical: split + per-section + synthesis")
  void largeDocHierarchical() {
    // Comfortably over the derived hierarchical threshold (tempdoc 883: the input budget, which is
    // 2304 tokens at this stub's 4096-token window). This filler has NO whitespace, so
    // estimateTokens takes its dense arm (chars/3, not chars/4): 30k chars is ~10000 tokens.
    String huge = "x".repeat(30_000);
    var ai = new StubAi(List.of("s1", "s2", "final synthesis"));
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", huge, Map.of())));
    var runner = new HierarchicalShapeRunner(() -> ai, () -> docs);

    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("docId", "doc"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    // Must emit splitting + sections + at least one summarizing + synthesis phases.
    assertTrue(events.stream().anyMatch(
        e -> "progress".equals(e.name()) && "splitting".equals(e.payload().get("phase"))));
    assertTrue(events.stream().anyMatch(
        e -> "progress".equals(e.name()) && "sections".equals(e.payload().get("phase"))));
    assertTrue(events.stream().anyMatch(
        e -> "progress".equals(e.name()) && "summarizing".equals(e.payload().get("phase"))));
    assertTrue(events.stream().anyMatch(
        e -> "progress".equals(e.name()) && "synthesis".equals(e.payload().get("phase"))));

    SseEvent done = events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals(true, done.payload().get("hierarchical"));
    assertNotNull(done.payload().get("sections"));
    assertEquals(0, done.payload().get("failedSections"));
  }

  @Test
  @DisplayName("AI unavailable → AI_OFFLINE error and no document fetch")
  void aiOffline() {
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", "content", Map.of())));
    var ai = new UnavailableAi();
    var runner = new HierarchicalShapeRunner(() -> ai, () -> docs);

    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("docId", "doc"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent err =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("AI_OFFLINE", err.payload().get("errorCode"));
  }

  @Test
  @DisplayName("AI starting up → AI_STARTING error code (distinct from offline)")
  void aiStartingUp() {
    var docs = new StubDocs(Map.of());
    var ai = new StartingUpAi();
    var runner = new HierarchicalShapeRunner(() -> ai, () -> docs);

    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("docId", "doc"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent err =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("AI_STARTING", err.payload().get("errorCode"));
  }

  @Test
  @DisplayName("Empty document content → NO_CONTENT error")
  void emptyContent() {
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", "", Map.of())));
    var ai = new StubAi(List.of());
    var runner = new HierarchicalShapeRunner(() -> ai, () -> docs);

    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("docId", "doc"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent err =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("NO_CONTENT", err.payload().get("errorCode"));
  }

  @Test
  @DisplayName("Per-section LLM failure surfaces in done.failedSections (does not abort)")
  void sectionFailureFallback() {
    String huge = "x".repeat(30_000); // ≥5K tokens → sectioning
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", huge, Map.of())));
    // FlakeyAi fails section calls but succeeds synthesis.
    var ai = new FlakeyAi();
    var runner = new HierarchicalShapeRunner(() -> ai, () -> docs);

    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("docId", "doc"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent done =
        events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    int failedSections = (int) done.payload().get("failedSections");
    assertTrue(failedSections > 0, "section failures must be surfaced in done payload");
    assertEquals(true, done.payload().get("hierarchical"));
  }

  @Test
  @DisplayName("Body content override falls back when no docId — uses inline content")
  void inlineContentFallback() {
    var docs = new StubDocs(Map.of()); // no docs registered
    var ai = new StubAi(List.of("synthesis"));
    var runner = new HierarchicalShapeRunner(() -> ai, () -> docs);

    var events = new ArrayList<SseEvent>();
    runner.run(
        Map.of("docId", "missing-doc", "content", "inline fallback content"),
        Audience.USER,
        events::add, io.justsearch.app.services.TestEngineContexts.internal());

    // docId is set so missing-doc routes through fetch first → returns null → falls back to
    // inline content → goes through small-doc single-pass.
    SseEvent done =
        events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals("missing-doc", done.payload().get("docId"));
    assertEquals(false, done.payload().get("hierarchical"));
  }

  // ---- fixtures ----

  private static final class StubDocs implements DocumentService {
    private final Map<String, DocumentRecord> docs;

    StubDocs(Map<String, DocumentRecord> docs) {
      this.docs = docs;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(docs.get(docId));
    }
  }

  /** Stub OnlineAiService — answers each streamChat call with the next scripted response. */
  private static final class StubAi implements OnlineAiService {
    private final List<String> responses;
    private final AtomicInteger idx = new AtomicInteger(0);

    StubAi(List<String> responses) {
      this.responses = responses;
    }

    private String nextResponse() {
      int i = idx.getAndIncrement();
      if (i < responses.size()) return responses.get(i);
      return "fallback-" + i;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    // Tempdoc 491 §C5: streamSummary + streamAnswer overrides removed.

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public void streamChat(
        List<Map<String, Object>> messages, int maxTokens,
        Consumer<String> onChunk, Consumer<String> onComplete, Consumer<Throwable> onError) {
      String text = nextResponse();
      onChunk.accept(text);
      onComplete.accept(text);
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      streamChat(request.messages(), request.maxTokens(), sink.onContent(), sink.onComplete(),
          sink.onError(), request.sampling(), request.requireSentinel());
    }

    @Override
    public void streamChat(
        List<Map<String, Object>> messages, int maxTokens,
        Consumer<String> onChunk, Consumer<String> onComplete, Consumer<Throwable> onError,
        SamplingParams sampling, boolean requireSentinel) {
      streamChat(messages, maxTokens, onChunk, onComplete, onError);
    }
  }

  /** AI service that reports unavailable (no startup either). */
  private static final class UnavailableAi implements OnlineAiService {
    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unavailable"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unavailable"));
    }
  }

  /** AI service in startup phase (distinct error code from plain offline). */
  private static final class StartingUpAi implements OnlineAiService {
    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public boolean isStartingUp() {
      return true;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("starting"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("starting"));
    }
  }

  /**
   * AI service whose per-section calls fail; synthesis succeeds. Distinguishes section
   * messages from synthesis by inspecting the system prompt — sections use the
   * SECTION_SUMMARY prompt; synthesis uses the SYNTHESIS prompt.
   */
  private static final class FlakeyAi implements OnlineAiService {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public void streamChat(
        List<Map<String, Object>> messages, int maxTokens,
        Consumer<String> onChunk, Consumer<String> onComplete, Consumer<Throwable> onError) {
      String systemContent =
          messages.isEmpty() ? "" : String.valueOf(messages.get(0).getOrDefault("content", ""));
      if (systemContent.contains("synthesize") || systemContent.contains("section summaries")) {
        // Synthesis call — succeeds.
        onChunk.accept("synthesis output");
        onComplete.accept("synthesis output");
      } else {
        // Section call — fail.
        onError.accept(new RuntimeException("section LLM failure"));
      }
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      streamChat(request.messages(), request.maxTokens(), sink.onContent(), sink.onComplete(),
          sink.onError(), request.sampling(), request.requireSentinel());
    }

    @Override
    public void streamChat(
        List<Map<String, Object>> messages, int maxTokens,
        Consumer<String> onChunk, Consumer<String> onComplete, Consumer<Throwable> onError,
        SamplingParams sampling, boolean requireSentinel) {
      streamChat(messages, maxTokens, onChunk, onComplete, onError);
    }
  }
}
