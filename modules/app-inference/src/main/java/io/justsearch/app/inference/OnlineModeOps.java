/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static io.justsearch.app.inference.InferenceHttpHelpers.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiService.AiUsage;
import io.justsearch.app.api.OnlineAiService.VisionCompletionResult;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.Mode;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Online mode HTTP operations for {@link InferenceLifecycleManager}.
 *
 * <p>Encapsulates chat completion, streaming, summarization, Q&A, and vision operations that
 * communicate with the llama-server HTTP API. Owns the online request lock and VDU executor.
 * Extracted to reduce the size of the lifecycle manager class.
 */
final class OnlineModeOps {
  private static final Logger LOG = LoggerFactory.getLogger(OnlineModeOps.class);

  private static final String PATH_CHAT_COMPLETIONS = "/v1/chat/completions";
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(2);

  /**
   * Strips leaked {@code <think>} tags from model output (llama.cpp #13189 defense).
   *
   * <p>When {@code --reasoning-format deepseek} is enabled, reasoning content should arrive via
   * the {@code reasoning_content} SSE field. However, some edge cases (context exhaustion, model
   * quirks) can cause {@code <think>} tags to leak into the main content field. This pattern
   * strips them as a safety net.
   */
  private static final Pattern THINK_TAGS = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
  private static final long VISION_LOCK_POLL_MS = 50;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Supplier<Mode> currentMode;
  private final Supplier<Integer> serverPort;
  private final Supplier<String> lastKnownModelId;
  private final Supplier<String> configModelFileName;
  // Tempdoc 412 follow-up: request-event sink. Defaults to noop when no telemetry is wired.
  private final InferenceTelemetryEvents events;

  // Package-private for test override (avoids 2-minute waits in unit tests).
  Duration visionLockDeadline = HTTP_TIMEOUT;

  /**
   * Idle read deadline for a streaming response body. {@code HTTP_TIMEOUT} on the request bounds
   * only header arrival; without this, a server that answers 200 and then goes silent parks the
   * single streaming thread — and the online-request lock — indefinitely. Package-private for test
   * override.
   */
  Duration streamIdleDeadline = HTTP_TIMEOUT;

  // Priority queue for Online Mode (Chat > VDU)
  private final ReentrantLock onlineRequestLock = new ReentrantLock();
  private final ExecutorService vduExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "VDU-Background");
            t.setDaemon(true);
            return t;
          });

  /**
   * Consumer callbacks run here, off the online-request lock, one thread per in-flight stream (see
   * {@link StreamCallbackPump}). A blocked consumer therefore delays only its own stream instead of
   * every inference request in the process.
   */
  private final ExecutorService callbackExecutor =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "VDU-Callbacks");
            t.setDaemon(true);
            return t;
          });

  /** Ticks the per-stream {@link StreamIdleWatchdog}s. */
  private final java.util.concurrent.ScheduledExecutorService streamWatchdogScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "VDU-StreamWatchdog");
            t.setDaemon(true);
            return t;
          });

  OnlineModeOps(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Supplier<Mode> currentMode,
      Supplier<Integer> serverPort,
      Supplier<String> lastKnownModelId,
      Supplier<String> configModelFileName) {
    this(
        httpClient,
        objectMapper,
        currentMode,
        serverPort,
        lastKnownModelId,
        configModelFileName,
        InferenceTelemetryEvents.noop());
  }

  /**
   * Tempdoc 412 follow-up: events-aware overload. Production callers pass the wired
   * {@link InferenceTelemetryEvents}; tests use the prior constructor (delegates to no-op).
   */
  OnlineModeOps(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Supplier<Mode> currentMode,
      Supplier<Integer> serverPort,
      Supplier<String> lastKnownModelId,
      Supplier<String> configModelFileName,
      InferenceTelemetryEvents events) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.currentMode = currentMode;
    this.serverPort = serverPort;
    this.lastKnownModelId = lastKnownModelId;
    this.configModelFileName = configModelFileName;
    this.events = events == null ? InferenceTelemetryEvents.noop() : events;
  }

  /**
   * Tempdoc 412 follow-up: emits {@code onRequestEnqueued}. Best-effort; exceptions logged.
   */
  private void emitRequestEnqueued(RequestKind kind) {
    try {
      events.onRequestEnqueued(kind);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onRequestEnqueued threw: {}", ex.getMessage());
    }
  }

  /**
   * Tempdoc 412 follow-up: records the moment between enqueue and lock-acquisition. Called by
   * lock-acquiring methods immediately after {@code onlineRequestLock.lock()} returns.
   */
  private void emitRequestStarted(RequestKind kind, long enqueueNanos) {
    try {
      events.onRequestStarted(
          kind, Duration.ofNanos(System.nanoTime() - enqueueNanos));
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onRequestStarted threw: {}", ex.getMessage());
    }
  }

  /**
   * Tempdoc 412 follow-up: emits {@code onRequestCompleted} with outcome and total elapsed
   * time. Best-effort; exceptions logged.
   */
  private void emitRequestCompleted(
      RequestKind kind, long enqueueNanos, RequestOutcome outcome) {
    try {
      events.onRequestCompleted(
          kind, Duration.ofNanos(System.nanoTime() - enqueueNanos), outcome);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onRequestCompleted threw: {}", ex.getMessage());
    }
  }

  /**
   * Tempdoc 412 follow-up: maps a throwable from a request work block to the right
   * {@link RequestOutcome}. {@code InterruptedException} (direct or wrapped) maps to
   * {@link RequestOutcome#CANCELLED}; everything else to {@link RequestOutcome#ERROR}.
   */
  private static RequestOutcome outcomeFromThrowable(Throwable t) {
    if (t == null) return RequestOutcome.OK;
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof InterruptedException) return RequestOutcome.CANCELLED;
      cur = cur.getCause();
    }
    return RequestOutcome.ERROR;
  }

  // ==================== Chat Completion ====================

  CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens) {
    return chatCompletion(messages, maxTokens, null);
  }

  CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    requireOnline("Chat");

    return CompletableFuture.supplyAsync(
        () -> {
          long enqueueNanos = System.nanoTime();
          emitRequestEnqueued(RequestKind.CHAT);
          onlineRequestLock.lock();
          emitRequestStarted(RequestKind.CHAT, enqueueNanos);
          RequestOutcome outcome = RequestOutcome.ERROR;
          try {
            String result = sendChatRequest(messages, maxTokens, sampling);
            outcome = RequestOutcome.OK;
            return result;
          } catch (RuntimeException re) {
            outcome = outcomeFromThrowable(re);
            throw re;
          } finally {
            onlineRequestLock.unlock();
            emitRequestCompleted(RequestKind.CHAT, enqueueNanos, outcome);
          }
        });
  }

  CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens) {
    return visionCompletionDetailed(prompt, imageBytes, maxTokens, SamplingParams.VDU, null);
  }

  /**
   * Tempdoc 677 Stage 2: sampling/seed-override overload — the agreement probe calls this with
   * {@link SamplingParams#VDU_PROBE} and a fixed seed instead of the deterministic {@link
   * SamplingParams#VDU} the 3-arg overload above always uses.
   */
  CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens, SamplingParams sampling, Long seed) {
    requireOnline("Vision");

    String base64Image = Base64.getEncoder().encodeToString(imageBytes);

    return CompletableFuture.supplyAsync(
        () -> {
          long enqueueNanos = System.nanoTime();
          emitRequestEnqueued(RequestKind.VISION);
          RequestOutcome outcome = RequestOutcome.ERROR;
          try {
            long deadlineNanos = enqueueNanos + visionLockDeadline.toNanos();
            while (!onlineRequestLock.tryLock(VISION_LOCK_POLL_MS, TimeUnit.MILLISECONDS)) {
              if (System.nanoTime() > deadlineNanos) {
                outcome = RequestOutcome.TIMEOUT;
                throw new RuntimeException(
                    "Vision request timed out waiting for chat lock after " + HTTP_TIMEOUT);
              }
            }
            emitRequestStarted(RequestKind.VISION, enqueueNanos);
            try {
              VisionCompletionResult result =
                  sendVisionRequestDetailed(prompt, base64Image, maxTokens, sampling, seed);
              outcome = RequestOutcome.OK;
              return result;
            } finally {
              onlineRequestLock.unlock();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = RequestOutcome.CANCELLED;
            throw new RuntimeException("VDU interrupted", e);
          } catch (RuntimeException re) {
            if (outcome == RequestOutcome.ERROR) outcome = outcomeFromThrowable(re);
            throw re;
          } finally {
            emitRequestCompleted(RequestKind.VISION, enqueueNanos, outcome);
          }
        },
        vduExecutor);
  }

  CompletableFuture<String> visionCompletion(
      String prompt, byte[] imageBytes, int maxTokens) {
    return visionCompletionDetailed(prompt, imageBytes, maxTokens)
        .thenApply(VisionCompletionResult::content);
  }

  CompletableFuture<String> summarize(String content, int maxTokens) {
    List<Map<String, Object>> messages = buildSummarizationMessages(content);
    return chatCompletion(messages, maxTokens, SamplingParams.DETERMINISTIC);
  }

  CompletableFuture<String> askQuestion(String context, String question, int maxTokens) {
    List<Map<String, Object>> messages =
        List.of(
            Map.of(
                "role",
                "system",
                "content",
                "You are a helpful assistant. Answer questions based on the provided context."),
            Map.of(
                "role", "user", "content", "Context:\n" + context + "\n\nQuestion: " + question));
    return chatCompletion(messages, maxTokens, SamplingParams.DETERMINISTIC);
  }

  // ==================== Streaming ====================

  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    streamChat(messages, maxTokens, onChunk, null, onComplete, onError);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    streamChat(messages, maxTokens, onChunk, onUsage, onComplete, onError, null);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling) {
    streamChat(messages, maxTokens, onChunk, onUsage, onComplete, onError, sampling, true);
  }

  /**
   * Stream a chat completion with explicit sentinel enforcement control.
   *
   * @param requireSentinel if true, the stream must end with {@code data: [DONE]} or {@code
   *     onError} fires with {@link StreamTruncatedException}. If false, a missing sentinel logs at
   *     DEBUG and calls {@code onComplete} (lenient mode for internal accumulation like
   *     map-reduce).
   */
  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {

    // Tempdoc 412 follow-up: wrap user callbacks to fire onRequestCompleted exactly once.
    long enqueueNanos = System.nanoTime();
    emitRequestEnqueued(RequestKind.STREAM);
    boolean[] emitted = {false};
    Consumer<String> trackedOnComplete =
        finishReason -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(RequestKind.STREAM, enqueueNanos, RequestOutcome.OK);
          }
          onComplete.accept(finishReason);
        };
    Consumer<Throwable> trackedOnError =
        t -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(
                RequestKind.STREAM, enqueueNanos, outcomeFromThrowable(t));
          }
          onError.accept(t);
        };

    if (currentMode.get() != Mode.ONLINE) {
      trackedOnError.accept(
          new IllegalStateException(
              "Not in Online Mode, current mode is " + currentMode.get()));
      return;
    }

    var unused =
        CompletableFuture.runAsync(
            () -> {
              // The lock covers the llama-server exchange only; consumer callbacks are pumped off
              // it, and the body read carries an idle deadline so it can never park forever.
              StreamCallbackPump pump = new StreamCallbackPump(callbackExecutor);
              // Tempdoc 835 §5.3: one stateful, frame-straddle-safe think-tag filter for every
              // streaming shape. This path has no reasoning handler, so captured thinking is
              // discarded — exactly what it already does with reasoning_content below.
              ThinkTagStreamFilter thinkFilter = new ThinkTagStreamFilter(null);
              boolean[] sawDone = {false};
              String[] lastFinishReason = {null};
              Throwable failure = null;

              onlineRequestLock.lock();
              emitRequestStarted(RequestKind.STREAM, enqueueNanos);
              try {
                Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", resolveModelIdForRequests());
                body.put("messages", messages);
                body.put("max_tokens", maxTokens);
                body.put("stream", true);
                if (onUsage != null) {
                  body.put("stream_options", Map.of("include_usage", true));
                }
                if (sampling != null) {
                  body.put("temperature", sampling.temperature());
                  body.put("top_p", sampling.topP());
                  // Lane F PR 0b: the RNG seed, when the caller pinned one. Null (every preset, and
                  // every request that sends no `sampling` override) omits the field entirely, so
                  // the body llama-server sees is byte-identical to before the component existed.
                  if (sampling.seed() != null) {
                    body.put("seed", sampling.seed());
                  }
                  // Tempdoc 835 §10f: this transport dropped enableThinking entirely — the only one
                  // of the three that did — so a caller's suppression was silently discarded and
                  // the server-wide budget applied anyway. Query expansion and section summarize
                  // both arrive here.
                  if (sampling.enableThinking() != null) {
                    body.put(
                        "chat_template_kwargs",
                        Map.of("enable_thinking", sampling.enableThinking()));
                  }
                }

                String json = objectMapper.writeValueAsString(body);

                LOG.debug(
                    "LLM Stream Request: endpoint=/v1/chat/completions, messages={}, max_tokens={}, body_size={}",
                    messages.size(),
                    maxTokens,
                    json.length());

                HttpRequest request =
                    buildJsonPostRequest(
                        serverPort.get(), PATH_CHAT_COMPLETIONS, json, HTTP_TIMEOUT);

                HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                LOG.debug("LLM Stream Response: status={}", response.statusCode());

                if (response.statusCode() != 200) {
                  String errorBody = readErrorBody(response);
                  LOG.warn("LLM Error: status={} body={}", response.statusCode(), errorBody);
                  failure = new LlmServerException(response.statusCode(), errorBody);
                } else {
                  boolean bodyEnded =
                      consumeStreamBody(
                          response,
                          line -> {
                            RuntimeException callbackFailure = pump.failure();
                            if (callbackFailure != null) {
                              throw callbackFailure;
                            }
                            if (line.equals("data: [DONE]")) {
                              sawDone[0] = true;
                            } else if (line.startsWith("data: ")) {
                              try {
                                String jsonData = line.substring(6);
                                JsonNode node = objectMapper.readTree(jsonData);
                                String fr = finishReasonOf(node.path("choices").path(0));
                                if (fr != null) {
                                  lastFinishReason[0] = fr;
                                }
                                if (onUsage != null) {
                                  AiUsage usage = extractUsageFromChatChunk(node);
                                  if (usage != null) {
                                    pump.dispatch(() -> acceptUsageBestEffort(onUsage, usage));
                                  }
                                }
                                JsonNode delta = node.path("choices").path(0).path("delta");

                                String content = delta.path("content").asText("");
                                if (!content.isEmpty()) {
                                  String visible = thinkFilter.accept(content);
                                  if (!visible.isEmpty()) {
                                    pump.dispatch(() -> onChunk.accept(visible));
                                  }
                                }

                                // Consume reasoning_content so it isn't silently lost.
                                // streamChat callers (summary, Q&A, map-reduce) don't need
                                // reasoning — only streamChatWithTools exposes it.
                                String reasoning = delta.path("reasoning_content").asText("");
                                if (!reasoning.isEmpty() && LOG.isDebugEnabled()) {
                                  LOG.debug(
                                      "streamChat: discarding {} reasoning chars (no handler)",
                                      reasoning.length());
                                }
                              } catch (java.util.concurrent.CancellationException cancelled) {
                                throw cancelled;
                              } catch (Exception e) {
                                LOG.debug("Failed to parse SSE chunk: {}", line);
                              }
                            }
                          });
                  if (!bodyEnded) {
                    failure = new StreamStalledException(streamIdleDeadline);
                  } else {
                    String tail = thinkFilter.flush();
                    if (!tail.isEmpty()) {
                      pump.dispatch(() -> onChunk.accept(tail));
                    }
                  }
                }
              } catch (Exception e) {
                failure = e;
              } finally {
                onlineRequestLock.unlock();
              }

              // Outside the lock: deliver every queued callback, then exactly one terminal event.
              failure = finishStream(pump, failure);
              if (failure != null) {
                reportStreamFailure("Stream chat", failure, trackedOnError);
              } else if (sawDone[0]) {
                trackedOnComplete.accept(lastFinishReason[0]);
              } else if (requireSentinel) {
                LOG.warn(
                    "LLM stream ended without [DONE] sentinel (finish_reason={})",
                    lastFinishReason[0]);
                trackedOnError.accept(new StreamTruncatedException(lastFinishReason[0]));
              } else {
                LOG.debug(
                    "LLM stream ended without [DONE] (lenient mode, finish_reason={})",
                    lastFinishReason[0]);
                trackedOnComplete.accept(lastFinishReason[0]);
              }
            },
            vduExecutor);
    unused.isDone(); // mark as observed; fire-and-forget
  }

  /**
   * Consumes a streaming response body line by line under {@link #streamIdleDeadline}.
   *
   * @return {@code true} if the body ended on its own, {@code false} if the idle deadline elapsed
   *     and the read was abandoned.
   */
  private boolean consumeStreamBody(
      HttpResponse<java.io.InputStream> response, Consumer<String> onLine) throws java.io.IOException {
    try (java.io.InputStream body = response.body();
        java.io.BufferedReader reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
      StreamIdleWatchdog watchdog =
          new StreamIdleWatchdog(streamWatchdogScheduler, streamIdleDeadline, () -> closeQuietly(body));
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          watchdog.touch();
          onLine.accept(line);
        }
      } catch (java.io.IOException | RuntimeException e) {
        // A read that failed *because* the watchdog aborted the body is a stall, not a transport
        // fault — the caller reports it as one.
        if (!watchdog.fired()) {
          throw e;
        }
      } finally {
        watchdog.close();
      }
      return !watchdog.fired();
    }
  }

  /**
   * Drains the pump (so no chunk can arrive after the terminal event) and folds in a failure a
   * consumer callback raised. Returns the failure to report, or {@code null} if the stream is clean.
   */
  private static Throwable finishStream(StreamCallbackPump pump, Throwable failure) {
    try {
      pump.awaitDrain();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failure != null ? failure : e;
    }
    if (failure != null) {
      return failure;
    }
    return pump.failure();
  }

  /**
   * Usage callbacks stay best-effort — a caller's accounting must never break streaming — while a
   * cancellation still aborts the stream, exactly as when this ran inline in the read loop.
   */
  private static void acceptUsageBestEffort(Consumer<AiUsage> onUsage, AiUsage usage) {
    try {
      onUsage.accept(usage);
    } catch (java.util.concurrent.CancellationException cancelled) {
      throw cancelled;
    } catch (RuntimeException ignored) {
      // best-effort: never let usage accounting break streaming
    }
  }

  private static void reportStreamFailure(
      String what, Throwable failure, Consumer<Throwable> trackedOnError) {
    if (failure instanceof java.util.concurrent.CancellationException) {
      LOG.debug("{} cancelled: {}", what, failure.getMessage());
    } else {
      LOG.error("{} failed", what, failure);
    }
    try {
      trackedOnError.accept(failure);
    } catch (RuntimeException ignored) {
      // best-effort: the caller's error handler must not resurrect the stream
    }
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    try {
      closeable.close();
    } catch (java.io.IOException | RuntimeException e) {
      LOG.debug("Closing a stalled stream body threw: {}", e.toString());
    }
  }

  // ==================== Tool-Aware Streaming ====================

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    streamChatWithTools(
        messages, tools, maxTokens, onChunk, onToolCallDelta, onReasoningChunk,
        onUsage, onComplete, onError, null);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling) {
    streamChatWithTools(messages, tools, maxTokens, onChunk, onToolCallDelta,
        onReasoningChunk, onUsage, onComplete, onError, sampling, true);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {

    // Tempdoc 412 follow-up: wrap user callbacks to fire onRequestCompleted exactly once.
    long enqueueNanos = System.nanoTime();
    emitRequestEnqueued(RequestKind.STREAM);
    boolean[] emitted = {false};
    Consumer<String> trackedOnComplete =
        finishReason -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(RequestKind.STREAM, enqueueNanos, RequestOutcome.OK);
          }
          onComplete.accept(finishReason);
        };
    Consumer<Throwable> trackedOnError =
        t -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(
                RequestKind.STREAM, enqueueNanos, outcomeFromThrowable(t));
          }
          onError.accept(t);
        };

    if (currentMode.get() != Mode.ONLINE) {
      trackedOnError.accept(
          new IllegalStateException(
              "Not in Online Mode, current mode is " + currentMode.get()));
      return;
    }

    var unused =
        CompletableFuture.runAsync(
            () -> {
              // The lock covers the llama-server exchange only; consumer callbacks are pumped off
              // it, and the body read carries an idle deadline so it can never park forever.
              StreamCallbackPump pump = new StreamCallbackPump(callbackExecutor);
              // Tempdoc 835 §5.3: the same stateful filter, here with the reasoning channel
              // wired — inline <think> markup from a leaking build is rerouted to the reasoning
              // sink, so the product behaves identically on both build families.
              ThinkTagStreamFilter thinkFilter =
                  new ThinkTagStreamFilter(
                      onReasoningChunk == null
                          ? null
                          : reasoning -> pump.dispatch(() -> onReasoningChunk.accept(reasoning)));
              boolean[] sawDone = {false};
              String[] lastFinishReason = {null};
              Throwable failure = null;

              onlineRequestLock.lock();
              emitRequestStarted(RequestKind.STREAM, enqueueNanos);
              try {
                Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", resolveModelIdForRequests());
                body.put("messages", messages);
                body.put("max_tokens", maxTokens);
                body.put("stream", true);
                if (tools != null && !tools.isEmpty()) {
                  body.put("tools", tools);
                }
                if (onUsage != null) {
                  body.put("stream_options", Map.of("include_usage", true));
                }
                if (sampling != null) {
                  body.put("temperature", sampling.temperature());
                  body.put("top_p", sampling.topP());
                  // Lane F PR 0b: see streamChat above — omitted unless the caller pinned a seed.
                  if (sampling.seed() != null) {
                    body.put("seed", sampling.seed());
                  }
                  if (sampling.toolChoice() != null) {
                    body.put("tool_choice", sampling.toolChoice());
                  }
                  // response_format (JSON schema → server-side GBNF) takes precedence over a raw
                  // grammar, mirroring the non-streaming sendChatRequest path (tempdoc 569 Phase 5:
                  // the conversation engine streams, so the schema constraint MUST be applied here or
                  // it is silently dropped — found by live verification). Both are guarded by the
                  // no-tools condition: llama-server rejects tools + grammar/response_format with
                  // HTTP 400 (it builds its own tool-call grammar from the tools list).
                  if (sampling.responseFormat() != null && (tools == null || tools.isEmpty())) {
                    body.put("response_format", sampling.responseFormat());
                  } else if (sampling.grammar() != null && (tools == null || tools.isEmpty())) {
                    body.put("grammar", sampling.grammar());
                  }
                  // Direction D: per-request thinking-prompt control via chat_template_kwargs.
                  // false = suppress <think> tag in prompt template (E0a, DECIDING turns);
                  // null = omit field (server default applies — typically true for Qwen3).
                  if (sampling.enableThinking() != null) {
                    body.put(
                        "chat_template_kwargs",
                        Map.of("enable_thinking", sampling.enableThinking()));
                  }
                }

                String json = objectMapper.writeValueAsString(body);

                LOG.debug(
                    "LLM Tool Stream Request: endpoint=/v1/chat/completions, messages={}, tools={}, max_tokens={}, body_size={}",
                    messages.size(),
                    tools == null ? 0 : tools.size(),
                    maxTokens,
                    json.length());

                HttpRequest request =
                    buildJsonPostRequest(
                        serverPort.get(), PATH_CHAT_COMPLETIONS, json, HTTP_TIMEOUT);

                HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                LOG.debug("LLM Tool Stream Response: status={}", response.statusCode());

                if (response.statusCode() != 200) {
                  String errorBody = readErrorBody(response);
                  LOG.warn("LLM Error: status={} body={}", response.statusCode(), errorBody);
                  failure = new LlmServerException(response.statusCode(), errorBody);
                } else {
                  boolean bodyEnded =
                      consumeStreamBody(
                          response,
                          line -> {
                            RuntimeException callbackFailure = pump.failure();
                            if (callbackFailure != null) {
                              throw callbackFailure;
                            }
                            if (line.equals("data: [DONE]")) {
                              sawDone[0] = true;
                            } else if (line.startsWith("data: ")) {
                              try {
                                String jsonData = line.substring(6);
                                JsonNode node = objectMapper.readTree(jsonData);
                                String fr = finishReasonOf(node.path("choices").path(0));
                                if (fr != null) {
                                  lastFinishReason[0] = fr;
                                }

                                if (onUsage != null) {
                                  AiUsage usage = extractUsageFromChatChunk(node);
                                  if (usage != null) {
                                    pump.dispatch(() -> acceptUsageBestEffort(onUsage, usage));
                                  }
                                }

                                JsonNode delta = node.path("choices").path(0).path("delta");

                                // Text content
                                String content = delta.path("content").asText("");
                                if (!content.isEmpty()) {
                                  String visible = thinkFilter.accept(content);
                                  if (!visible.isEmpty()) {
                                    pump.dispatch(() -> onChunk.accept(visible));
                                  }
                                }

                                // Reasoning content (emitted by --reasoning-format deepseek)
                                if (onReasoningChunk != null) {
                                  String reasoning = delta.path("reasoning_content").asText("");
                                  if (!reasoning.isEmpty()) {
                                    pump.dispatch(() -> onReasoningChunk.accept(reasoning));
                                  }
                                }

                                // Tool call deltas
                                JsonNode toolCalls = delta.path("tool_calls");
                                if (!toolCalls.isMissingNode() && toolCalls.isArray()) {
                                  pump.dispatch(() -> onToolCallDelta.accept(node));
                                }
                              } catch (java.util.concurrent.CancellationException cancelled) {
                                throw cancelled;
                              } catch (Exception e) {
                                LOG.debug("Failed to parse SSE chunk: {}", line);
                              }
                            }
                          });
                  if (!bodyEnded) {
                    failure = new StreamStalledException(streamIdleDeadline);
                  } else {
                    String tail = thinkFilter.flush();
                    if (!tail.isEmpty()) {
                      pump.dispatch(() -> onChunk.accept(tail));
                    }
                  }
                }
              } catch (Exception e) {
                failure = e;
              } finally {
                onlineRequestLock.unlock();
              }

              // Outside the lock: deliver every queued callback, then exactly one terminal event.
              failure = finishStream(pump, failure);
              if (failure != null) {
                reportStreamFailure("Stream chat with tools", failure, trackedOnError);
              } else if (sawDone[0]) {
                trackedOnComplete.accept(lastFinishReason[0]);
              } else if (requireSentinel) {
                LOG.warn(
                    "LLM tool stream ended without [DONE] sentinel (finish_reason={})",
                    lastFinishReason[0]);
                trackedOnError.accept(new StreamTruncatedException(lastFinishReason[0]));
              } else {
                LOG.debug(
                    "LLM stream ended without [DONE] (lenient mode, finish_reason={})",
                    lastFinishReason[0]);
                trackedOnComplete.accept(lastFinishReason[0]);
              }
            },
            vduExecutor);
    unused.isDone(); // mark as observed; fire-and-forget
  }

  // ==================== Unified Streaming (Tempdoc 499) ====================

  /**
   * Unified streaming method. Supports all channels, optional tools, and lenient sentinel mode.
   * Delegates to {@link #streamChatWithTools} with the requireSentinel parameter.
   */
  void stream(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onContent,
      Consumer<String> onReasoning,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {
    streamChatWithTools(messages, tools, maxTokens, onContent, onToolCallDelta,
        onReasoning, onUsage, onComplete, onError, sampling, requireSentinel);
  }

  // Tempdoc 491 §C5 follow-up: streamSummary + streamAnswer forwarders deleted. The shape
  // SPIs (SummarizationStyle PromptContributor + DocAccess ContextInjector for summarize;
  // RAGQAStyle + RAGContext for ask) call streamChat directly with shape-specific message
  // lists. buildSummarizationMessages is retained because the sync `summarize()` call above
  // still uses it; buildAnswerMessages is pruned below (no remaining consumer).

  // ==================== Internal Helpers ====================

  private void requireOnline(String operation) {
    if (currentMode.get() != Mode.ONLINE) {
      throw new IllegalStateException(
          operation + " requires ONLINE mode, but current mode is " + currentMode.get());
    }
  }

  private String resolveModelIdForRequests() {
    String id = lastKnownModelId.get();
    if (id != null && !id.isBlank()) {
      return id;
    }
    try {
      return configModelFileName.get();
    } catch (Exception e) {
      return "default";
    }
  }

  private String sendChatRequest(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    return sendChatRequestDetailed(messages, maxTokens, sampling, false, null).content();
  }

  /**
   * Sends a non-streaming chat completion request, optionally requesting per-token logprobs and/or
   * an explicit seed.
   *
   * <p>Tempdoc 677 S1 (plumbing only, no gating): {@code requestLogprobs} is opt-in. Today only
   * {@link #sendVisionRequestDetailed} passes {@code true} — {@link #sendChatRequest} (used by
   * interactive chat, summarize, Q&amp;A, and the VDU pass-2 enrichment call, all of which are
   * out of this slice's scope) always passes {@code false}, so this change is behavior-neutral
   * for every non-vision caller. {@code finish_reason} is extracted unconditionally (cheap);
   * the logprob-derived scalars ({@code meanLogprob}, {@code lowConfidenceFraction}) are null
   * when the server did not return {@code logprobs} on the response.
   *
   * <p>Tempdoc 677 Stage 2: {@code seed}, when non-null, is sent as llama-server's {@code seed}
   * request field. Only the Stage-2 agreement probe (via {@link #sendVisionRequestDetailed})
   * passes a non-null seed today — {@link #sendChatRequest} always passes {@code null}, so this
   * is likewise behavior-neutral for every existing caller.
   */
  private VisionCompletionResult sendChatRequestDetailed(
      List<Map<String, Object>> messages,
      int maxTokens,
      SamplingParams sampling,
      boolean requestLogprobs,
      Long seed) {
    try {
      Map<String, Object> body = new java.util.HashMap<>();
      body.put("model", resolveModelIdForRequests());
      body.put("messages", messages);
      body.put("max_tokens", maxTokens);
      if (requestLogprobs) {
        body.put("logprobs", true);
      }
      if (seed != null) {
        body.put("seed", seed);
      }
      if (sampling != null) {
        body.put("temperature", sampling.temperature());
        body.put("top_p", sampling.topP());
        // Lane F PR 0b: SamplingParams now carries an optional seed of its own. The explicit `seed`
        // parameter above is the vision probe's, and stays authoritative when both are set; this
        // branch keeps the record's meaning uniform across all three transports instead of leaving
        // one that silently drops it. No shipped preset sets it, so nothing changes today.
        if (seed == null && sampling.seed() != null) {
          body.put("seed", sampling.seed());
        }
        if (sampling.toolChoice() != null) {
          body.put("tool_choice", sampling.toolChoice());
        }
        // response_format (JSON schema) takes precedence over GBNF grammar — both constrain
        // output, but response_format lets llama-server handle schema→GBNF conversion internally.
        if (sampling.responseFormat() != null) {
          body.put("response_format", sampling.responseFormat());
        } else if (sampling.grammar() != null) {
          body.put("grammar", sampling.grammar());
        }
        // Per-request thinking-prompt control via chat_template_kwargs (parity with streaming path).
        // false = suppress <think> tag in prompt template; null = omit (server default applies).
        if (sampling.enableThinking() != null) {
          body.put(
              "chat_template_kwargs",
              Map.of("enable_thinking", sampling.enableThinking()));
        }
      }

      String json = objectMapper.writeValueAsString(body);

      LOG.debug(
          "LLM Request: endpoint=/v1/chat/completions, messages={}, max_tokens={}, body_size={}",
          messages.size(),
          maxTokens,
          json.length());

      HttpRequest request =
          buildJsonPostRequest(serverPort.get(), PATH_CHAT_COMPLETIONS, json, HTTP_TIMEOUT);

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      LOG.debug(
          "LLM Response: status={}, body_size={}",
          response.statusCode(),
          response.body().length());

      if (response.statusCode() != 200) {
        LOG.warn(
            "LLM Error Response: status={}, body={}",
            response.statusCode(),
            response.body().substring(0, Math.min(500, response.body().length())));
        throw new LlmServerException(response.statusCode(), response.body());
      }

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode choice = root.path("choices").path(0);
      String result = choice.path("message").path("content").asText();
      String finishReason = finishReasonOf(choice);

      // Strip leaked <think> tags (llama.cpp #13189 defense)
      String stripped = THINK_TAGS.matcher(result).replaceAll("").strip();
      if (stripped.length() < result.length()) {
        LOG.warn(
            "Stripped <think> tags from non-streaming response ({} -> {} chars)",
            result.length(),
            stripped.length());
        result = stripped;
      }

      LOG.debug("LLM Result: length={}", result.length());

      // Tempdoc 677 S1: reduce per-token logprobs to three scalars on the way through — the
      // full per-token array is never retained beyond this block.
      int tokenCount = 0;
      Double meanLogprob = null;
      Double lowConfidenceFraction = null;
      JsonNode logprobContent = choice.path("logprobs").path("content");
      if (logprobContent.isArray() && !logprobContent.isEmpty()) {
        double sum = 0.0;
        int lowConfidenceCount = 0;
        for (JsonNode tokenNode : logprobContent) {
          double logprob = tokenNode.path("logprob").asDouble();
          sum += logprob;
          if (logprob < -1.0) {
            lowConfidenceCount++;
          }
        }
        tokenCount = logprobContent.size();
        meanLogprob = sum / tokenCount;
        lowConfidenceFraction = (double) lowConfidenceCount / tokenCount;
      }

      return new VisionCompletionResult(
          result, finishReason, tokenCount, meanLogprob, lowConfidenceFraction);

    } catch (Exception e) {
      throw new RuntimeException("Chat request failed", e);
    }
  }

  private VisionCompletionResult sendVisionRequestDetailed(
      String prompt, String base64Image, int maxTokens, SamplingParams sampling, Long seed) {
    try {
      List<Map<String, Object>> content = new ArrayList<>();
      content.add(Map.of("type", "text", "text", prompt));
      content.add(
          Map.of(
              "type",
              "image_url",
              "image_url",
              Map.of("url", "data:image/jpeg;base64," + base64Image)));

      List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", content));

      return sendChatRequestDetailed(
          messages, maxTokens, sampling != null ? sampling : SamplingParams.VDU, true, seed);

    } catch (RuntimeException e) {
      throw e; // avoid double-wrapping RuntimeExceptions from sendChatRequestDetailed
    } catch (Exception e) {
      throw new RuntimeException("Vision request failed", e);
    }
  }

  private static List<Map<String, Object>> buildSummarizationMessages(String content) {
    return List.of(
        Map.of(
            "role",
            "system",
            "content",
            "You are a helpful assistant that summarizes documents concisely. "
                + "Focus on key information: dates, amounts, parties, and main purpose. "
                + "ONLY summarize what is explicitly stated in the provided text. "
                + "Do not add information from outside knowledge."),
        Map.of(
            "role",
            "user",
            "content",
            "Summarize the following document(s):\n\n"
                + content
                + "\n\nProvide a clear, organized summary based ONLY on the text above."));
  }


  /**
   * Re-tags the assembled RAG context as {@code <passage id="n" source="label">} blocks for the
   * online path.
   *
   * <p>The passage id is the number the context header already carries ({@code "[n] label\n"},
   * ContextBudgeter.sectionHeader), not a running counter of this loop: that ordinal is what the
   * prompt asks the model to cite and what the FE resolves against {@code sources[n - 1]}, so a
   * second, independently-derived numbering here could silently disagree with it (tempdoc 822
   * §3a). A section whose header does not parse falls back to the running counter.
   *
   * <p>Reachable from outside this package via
   * {@code InferenceLifecycleManager#formatContextAsNumberedPassages} (tempdoc 849): the head's
   * section-aware cut exists partly to keep these headers parseable when the budget bites, and that
   * claim is only worth asserting against THIS parser rather than a re-implementation of it.
   */
  static String formatContextAsNumberedPassages(String rawContext) {
    if (rawContext == null || rawContext.isBlank()) {
      return "";
    }
    String[] sections = rawContext.split(DocumentService.SECTION_SEPARATOR);
    StringBuilder sb = new StringBuilder();
    int sectionOrdinal = 0;
    for (String section : sections) {
      String trimmed = section.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      sectionOrdinal++;
      int passageNum = sectionOrdinal;
      String source = "unknown";
      String content = trimmed;
      int headerEnd = trimmed.indexOf('\n');
      if (trimmed.startsWith("[") && headerEnd > 0) {
        int close = trimmed.indexOf("] ");
        if (close > 1 && close < headerEnd) {
          String digits = trimmed.substring(1, close);
          int parsed = parsePositiveIntOrZero(digits);
          if (parsed > 0) {
            passageNum = parsed;
            source = trimmed.substring(close + 2, headerEnd);
            content = trimmed.substring(headerEnd + 1);
          }
        }
      }
      if (sb.length() > 0) {
        sb.append("\n\n");
      }
      sb.append("<passage id=\"")
          .append(passageNum)
          .append("\" source=\"")
          .append(source)
          .append("\">\n");
      sb.append(content.trim());
      sb.append("\n</passage>");
    }
    return sb.toString();
  }

  /** Returns the value of an all-digit string, or 0 when it is empty, non-numeric or overflows. */
  private static int parsePositiveIntOrZero(String digits) {
    if (digits.isEmpty() || digits.length() > 9) {
      return 0;
    }
    for (int i = 0; i < digits.length(); i++) {
      if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
        return 0;
      }
    }
    return Integer.parseInt(digits);
  }

  /**
   * Extracts OpenAI-compatible usage information from a streamed chat completion SSE chunk.
   *
   * <p>llama-server emits a final chunk with {@code choices: []} and a {@code usage} object when
   * {@code stream_options.include_usage=true}. Other chunks typically omit {@code usage}.
   */
  /**
   * Reads a failed streaming response's body so the reason survives to the caller (tempdoc 835
   * §10f). The streaming paths request {@code BodyHandlers.ofLines()} and previously passed
   * {@code null} as the body, which turned llama-server's own explanation — e.g. {@code request
   * (5878 tokens) exceeds the available context size (4096 tokens), try increasing it} — into a
   * bare "Server returned status 400" on the surface, with "check llama-server logs" as the only
   * lead. Consuming the stream here also closes it instead of leaking it on the error path.
   */
  private static String readErrorBody(HttpResponse<java.io.InputStream> response) {
    try (java.io.BufferedReader reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
      String body =
          reader.lines().limit(20).collect(java.util.stream.Collectors.joining("\n")).strip();
      return body.isEmpty() ? null : body.substring(0, Math.min(1000, body.length()));
    } catch (Exception e) {
      LOG.debug("Failed to read LLM error body: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The {@code finish_reason} of a chat choice, or {@code null} when the runtime did not report one.
   *
   * <p>Tempdoc 881 (independent review §G finding 12): {@code path("finish_reason").asText(null)}
   * does NOT yield {@code null} for an explicit JSON {@code null} — {@code NullNode.asText()}
   * returns the four-character string {@code "null"}, and every non-terminal streaming chunk carries
   * {@code "finish_reason": null}. That was inert while nothing read the value; 881 puts it in a
   * user-facing sentence, where a stream that ends without a terminal reason would say
   * "finish_reason=null" instead of admitting the runtime reported nothing.
   */
  static String finishReasonOf(JsonNode choice) {
    JsonNode node = choice == null ? null : choice.path("finish_reason");
    return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
  }

  static AiUsage extractUsageFromChatChunk(JsonNode root) {
    if (root == null) return null;
    JsonNode usage = root.get("usage");
    if (usage == null || usage.isNull() || !usage.isObject()) return null;
    Integer prompt = asIntOrNull(usage.get("prompt_tokens"));
    Integer completion = asIntOrNull(usage.get("completion_tokens"));
    Integer total = asIntOrNull(usage.get("total_tokens"));
    if (prompt == null && completion == null && total == null) return null;
    return new AiUsage(prompt, completion, total);
  }

  // ==================== Lifecycle ====================

  void shutdown() {
    vduExecutor.shutdownNow();
    callbackExecutor.shutdownNow();
    streamWatchdogScheduler.shutdownNow();
  }
}
