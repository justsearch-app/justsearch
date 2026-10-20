/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.agent.AgentLoopServiceTest.ScriptedAiService;
import io.justsearch.agent.AgentLoopServiceTest.ScriptedResponse;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model boundary for WP6.1/WP6.2 tests: what an agent request must look like to be accepted by
 * a strict chat template (Qwen3.5's, as llama-server applies it), and helpers that read the user's
 * directions and the resume warning out of a sent request.
 */
final class ModelRequestFixtures {
  private ModelRequestFixtures() {}

  /** How a direction reaches the model: a labelled user turn at its place in the history. */
  static final String DIRECTION_LABEL = "Direction from the user, sent while you were working: ";

  static final String DIRECTION_TAIL = " Adjust your plan accordingly for the remaining steps.";

  /** The resume warning as saved in history (a system message, unchanged by WP6.2). */
  static final String RESUME_NOTE =
      "Resume note: previous pending approvals must be re-requested after restart before any write"
          + " or destructive action.";

  /** The resume warning as the model receives it. */
  static final String RESUME_NOTE_SENT =
      "Note from JustSearch (this run was resumed after an interruption): previous pending"
          + " approvals must be re-requested after restart before any write or destructive action.";

  /** A direction as saved in history. */
  static String steering(String text) {
    return "Mid-run steering directive from the user: " + text + DIRECTION_TAIL;
  }

  private static final Pattern SENT_DIRECTION =
      Pattern.compile(Pattern.quote(DIRECTION_LABEL) + "(.*)" + Pattern.quote(DIRECTION_TAIL));

  /** The directions in a sent request, in the order the model reads them. */
  static List<String> directionsIn(List<Map<String, Object>> request) {
    var found = new ArrayList<String>();
    for (var message : request) {
      if (!"user".equals(message.get("role"))) continue;
      Matcher matcher = SENT_DIRECTION.matcher(String.valueOf(message.get("content")));
      if (matcher.matches()) found.add(matcher.group(1));
    }
    return found;
  }

  /** How many times the resume warning reaches the model in this request. */
  static long resumeNotesIn(List<Map<String, Object>> request) {
    return request.stream()
        .filter(m -> "user".equals(m.get("role")) && RESUME_NOTE_SENT.equals(m.get("content")))
        .count();
  }

  static String system(List<Map<String, Object>> request) {
    return (String) request.getFirst().get("content");
  }

  /** tool_call_id → content, in request order. */
  static Map<String, String> toolResults(List<Map<String, Object>> request) {
    var results = new LinkedHashMap<String, String>();
    for (var message : request) {
      if ("tool".equals(message.get("role"))) {
        results.put(String.valueOf(message.get("tool_call_id")), String.valueOf(message.get("content")));
      }
    }
    return results;
  }

  static void assertNotExecuted(String content) {
    assertNotNull(content, "every assistant tool call must be answered");
    assertTrue(content.startsWith("Not executed:"), content);
  }

  static void assertValidRequest(List<Map<String, Object>> request) {
    String violation = templateViolation(request);
    if (violation != null) fail(violation + ": " + request);
  }

  /**
   * What a strict template and llama-server reject, or null. Qwen3.5's template raises "System
   * message must be at the beginning" and "No user query found in messages"; llama-server treats a
   * trailing assistant message as a prefill and rejects it with thinking enabled; strict templates
   * (and OpenAI-style APIs) reject a tool call without its result or a result without its call.
   */
  static String templateViolation(List<Map<String, Object>> request) {
    if (request.isEmpty()) return "no messages";
    for (int i = 1; i < request.size(); i++) {
      if ("system".equals(request.get(i).get("role"))) {
        return "System message must be at the beginning (index " + i + ")";
      }
    }
    boolean userQuery = request.stream().anyMatch(m -> "user".equals(m.get("role")));
    if (!userQuery) return "No user query found in messages";
    if ("assistant".equals(request.getLast().get("role"))) {
      return "Assistant response prefill is incompatible with enable_thinking";
    }
    for (int i = 0; i < request.size(); i++) {
      var message = request.get(i);
      if ("tool".equals(message.get("role"))) {
        return "tool message without a preceding assistant call at " + i;
      }
      if (!"assistant".equals(message.get("role"))
          || !(message.get("tool_calls") instanceof List<?> calls)) {
        continue;
      }
      var expected = new LinkedHashSet<String>();
      for (Object call : calls) expected.add(String.valueOf(((Map<?, ?>) call).get("id")));
      var answered = new ArrayList<String>();
      while (i + 1 < request.size() && "tool".equals(request.get(i + 1).get("role"))) {
        answered.add(String.valueOf(request.get(++i).get("tool_call_id")));
      }
      if (!expected.equals(new LinkedHashSet<>(answered)) || expected.size() != answered.size()) {
        return "tool calls " + expected + " answered by " + answered;
      }
    }
    return null;
  }

  static Map<String, Object> message(String role, String content) {
    return Map.of("role", role, "content", content);
  }

  static Map<String, Object> toolResult(String id, String content) {
    return Map.of("role", "tool", "tool_call_id", id, "content", content);
  }

  /** An assistant message calling (id, toolName) pairs in one batch. */
  static Map<String, Object> assistantCalls(String... idAndName) {
    var calls = new ArrayList<ToolCallRequest>();
    for (int i = 0; i < idAndName.length; i += 2) {
      calls.add(new ToolCallRequest(idAndName[i], idAndName[i + 1], "{}"));
    }
    return AgentLlmCaller.buildAssistantToolCallMessage(new LlmCallResult(null, List.of(), null), calls);
  }

  static ArrayList<Map<String, Object>> history() {
    return new ArrayList<>(List.of(message("system", "base"), message("user", "task")));
  }

  static void assertDone(List<AgentEvent> events) {
    assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.AgentError), events.toString());
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.AgentDone), events.toString());
  }

  static String outcome(AgentEvent.DirectiveAcknowledged ack) {
    return (String) AgentEventPayloads.base(ack).get("outcome");
  }

  static String reasonOf(AgentEvent.DirectiveAcknowledged ack) {
    return (String) AgentEventPayloads.base(ack).get("reason");
  }

  static String idOf(AgentEvent.DirectiveAcknowledged ack) {
    return (String) AgentEventPayloads.base(ack).get("directiveId");
  }

  /** Collects a run's events and remembers its session id; {@link #onEvent} runs as each arrives. */
  static final class Run implements Consumer<AgentEvent> {
    final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    final AtomicReference<String> sessionId = new AtomicReference<>();
    volatile Consumer<AgentEvent> onEvent = event -> {};

    @Override
    public void accept(AgentEvent event) {
      events.add(event);
      if (event instanceof AgentEvent.SessionStarted started) sessionId.set(started.sessionId());
      onEvent.accept(event);
    }

    String id() {
      return sessionId.get();
    }

    List<AgentEvent.DirectiveAcknowledged> acks() {
      return events.stream()
          .filter(e -> e instanceof AgentEvent.DirectiveAcknowledged)
          .map(e -> (AgentEvent.DirectiveAcknowledged) e)
          .toList();
    }

    List<String> outcomes() {
      return acks().stream().map(ModelRequestFixtures::outcome).toList();
    }

    List<String> texts(String wanted) {
      return acks().stream()
          .filter(a -> wanted.equals(outcome(a)))
          .map(AgentEvent.DirectiveAcknowledged::directiveText)
          .toList();
    }
  }

  /**
   * A scripted model that records every request sent and every list counted, and refuses a request
   * a strict template would refuse (the stream errors, as llama-server's HTTP 400/500 does).
   */
  static final class CapturingAi implements OnlineAiService {
    final ScriptedAiService delegate;
    final List<List<Map<String, Object>>> counted = new CopyOnWriteArrayList<>();
    final List<String> rejected = new CopyOnWriteArrayList<>();
    List<Integer> counts = List.of();
    volatile IntConsumer beforeStream = index -> {};
    private int streams;

    CapturingAi(ScriptedResponse... responses) {
      delegate = new ScriptedAiService(responses);
    }

    /** Every request that reached the model, accepted or refused. */
    List<List<Map<String, Object>>> sent() {
      return delegate.recordedMessages;
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      beforeStream.accept(streams++);
      String violation = templateViolation(request.messages());
      if (violation != null) {
        delegate.recordedMessages.add(List.copyOf(request.messages()));
        rejected.add(violation);
        sink.onError().accept(new IllegalStateException("template rejected: " + violation));
        return;
      }
      delegate.stream(request, sink);
    }

    @Override
    public Optional<Integer> countPromptTokens(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools, EngineContext context) {
      counted.add(List.copyOf(messages));
      int index = counted.size() - 1;
      return Optional.of(index < counts.size() ? counts.get(index) : 100);
    }

    @Override public Integer llmContextTokens() { return 4096; }
    @Override public boolean isAvailable() { return true; }
    @Override public boolean isStartingUp() { return false; }
    @Override public CompletableFuture<String> summarize(String content) {
      throw new UnsupportedOperationException();
    }
    @Override public CompletableFuture<String> askQuestion(String question, String context) {
      throw new UnsupportedOperationException();
    }
  }
}
