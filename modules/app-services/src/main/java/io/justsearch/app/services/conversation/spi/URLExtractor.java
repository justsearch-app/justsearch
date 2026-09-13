/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentDispatchResult;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.RecoveryAction;
import io.justsearch.agent.api.registry.ResolutionRecoveryPolicy;
import io.justsearch.agent.api.registry.ResolutionResult;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.api.registry.TrustGateDeniedException;
import io.justsearch.app.services.intent.MarkdownUrlExtractor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-stream {@link StreamConsumer} that parses {@code justsearch://} URLs from the
 * model's full response and dispatches each via the {@link BackendIntentRouter}.
 *
 * <p>Per tempdoc 487 §3.4 / §4.3 / §6 step 12: the consumer half of the
 * URL-emission SPI pair. Composes with {@link URLEmissionGrammar} (the
 * {@link io.justsearch.agent.api.conversation.PromptContributor} half)
 * inline in {@code NavigateChatShape.definition()} to make a complete
 * end-to-end LLM-URL-emission feature. The tempdoc names this pairing
 * "{@code URLEmissionCapability}" but no Capability class ships — slice
 * 491 ConversationShapes compose SPIs by id directly, with no Capability
 * precedent in the codebase.
 *
 * <p>Pipeline (in {@link #onDone}):
 *
 * <ol>
 *   <li>Run {@link MarkdownUrlExtractor#extract} on the assistant's full response text
 *       to get a Stream of {@link Intent} envelopes (in document order).
 *   <li>For each envelope, emit a {@code navigate.url_extracted} SSE event with the
 *       parsed address shape for observability.
 *   <li>Dispatch via {@link BackendIntentRouter} — Invocation envelopes hit the
 *       trust lattice in {@code OperationExecutorImpl} (UNTRUSTED × ... per
 *       slice 487 §4.4), Navigation envelopes ride the
 *       {@code SseIntentForwardingTransport} to the FE router.
 *   <li>Emit {@code navigate.url_dispatched} on success (with the dispatch result),
 *       or {@code navigate.url_rejected} on failure (with reason).
 * </ol>
 *
 * <p><strong>Trust lattice interaction.</strong> The transport is
 * {@link TransportTag#LLM_EMISSION} which maps to {@code SourceTier.UNTRUSTED} via the
 * {@code IntentSourceCatalog}. The lattice produces:
 *
 * <ul>
 *   <li>UNTRUSTED × LOW → AUTO: dispatch proceeds; result emitted.
 *   <li>UNTRUSTED × MEDIUM/HIGH → TYPED_CONFIRM: dispatcher throws
 *       {@code ConfirmationRequiredException} (no confirmation token supplied
 *       — LLM emission carries no user-approval context); {@code navigate.url_rejected}
 *       fires with reason {@code confirmation-required}. The FE elicitation UX
 *       takes over from there (a future UI slice will render the gate; today the
 *       SSE event is the wire signal).
 * </ul>
 *
 * <p>Stateless construction (parser/router supplied as deps). Reentrant; safe to
 * use across concurrent requests.
 */
public final class URLExtractor implements StreamConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(URLExtractor.class);

  /** Stable id used by {@code ConversationShape.streamConsumerIds}. */
  public static final String ID = "core.url-extractor";

  private final MarkdownUrlExtractor parser;
  private final BackendIntentRouter router;
  private final Clock clock;
  private final List<OperationCatalog> operationCatalogs;

  public URLExtractor(BackendIntentRouter router) {
    this(router, MarkdownUrlExtractor.llmChatEmission(), Clock.systemUTC(), List.of());
  }

  public URLExtractor(
      BackendIntentRouter router,
      MarkdownUrlExtractor parser,
      Clock clock,
      List<OperationCatalog> operationCatalogs) {
    this.router = Objects.requireNonNull(router, "router");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.operationCatalogs = operationCatalogs == null ? List.of() : List.copyOf(operationCatalogs);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    // Post-stream consumer: nothing to do mid-stream. Per-URL streaming dispatch is
    // tempdoc Appendix A.1 (deferred). Per-message dispatch in onDone is the MVP.
    return StreamConsumerResult.empty();
  }

  @Override
  public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    var incomingContext = ctx.engineContext();
    var engineContext = io.justsearch.app.services.intent.EngineProvenance.rebase(
        incomingContext, incomingContext.sessionId(), TransportTag.LLM_EMISSION,
        incomingContext.survival(), incomingContext.urgency());
    List<SseEvent> events = new ArrayList<>();
    List<Map<String, Object>> sideEffects = new ArrayList<>();
    int urlIndex = 0;
    for (Intent intent : (Iterable<Intent>) parser.extract(fullText)::iterator) {
      events.add(extractedEvent(intent, urlIndex));

      // Resolution + recovery policy for Invocation intents (tempdoc 499).
      // Navigation intents skip BE resolution — surfaces are FE-resolved.
      if (intent.address() instanceof ShellAddress.Invocation inv && !operationCatalogs.isEmpty()) {
        @SuppressWarnings("unchecked")
        ResolutionResult<Object> resolution = (ResolutionResult<Object>) resolveOperation(inv.target());
        var action = ResolutionRecoveryPolicy.<Object>sseStream().decide(resolution);
        events.add(resolutionSseEvent(urlIndex, inv.target().value(), resolution));
        if (!(action instanceof RecoveryAction.Proceed<?>)) {
          sideEffects.add(sideEffectRecord(intent, urlIndex, null, null));
          urlIndex++;
          continue;
        }
      }

      try {
        IntentDispatchResult result =
            router.dispatch(intent, io.justsearch.app.services.intent.EngineProvenance.invocation(
                engineContext, io.justsearch.agent.api.registry.ExecutorTag.AGENT, clock.instant(), Optional.empty()), engineContext);
        events.add(dispatchedEvent(intent, urlIndex, result));
        sideEffects.add(sideEffectRecord(intent, urlIndex, result, null));
      } catch (RuntimeException e) {
        LOG.debug(
            "URLExtractor: dispatch failed for intent {} — {}",
            intent.address(),
            e.getMessage());
        events.add(rejectedEvent(intent, urlIndex, e));
        sideEffects.add(sideEffectRecord(intent, urlIndex, null, e));
      }
      urlIndex++;
    }
    return new StreamConsumerResult(events, sideEffects, List.of(), Map.of());
  }

  private ResolutionResult<?> resolveOperation(OperationRef ref) {
    for (OperationCatalog catalog : operationCatalogs) {
      ResolutionResult<?> result = catalog.resolve(ref);
      if (result instanceof ResolutionResult.Resolved<?>) return result;
      if (result instanceof ResolutionResult.Redirected<?>) return result;
    }
    // None resolved — return the first catalog's unresolved (with alternatives)
    if (!operationCatalogs.isEmpty()) {
      return operationCatalogs.get(0).resolve(ref);
    }
    return new ResolutionResult.Unresolved<>(ref.value(),
        new ResolutionResult.UnresolvedDiagnosis(
            ResolutionResult.FailureMode.UNKNOWN,
            "No operation catalog available"),
        List.of());
  }

  // ===== SSE event constructors =====

  private static SseEvent resolutionSseEvent(int index, String target, ResolutionResult<?> result) {
    return switch (result) {
      case ResolutionResult.Resolved<?> r -> resolvedResolutionEvent(index, target);
      case ResolutionResult.Redirected<?> r -> redirectedResolutionEvent(index, target, r);
      case ResolutionResult.Unresolved<?> r -> unresolvedResolutionEvent(index, r);
    };
  }

  private static SseEvent resolvedResolutionEvent(int index, String target) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("index", index);
    payload.put("target", target);
    payload.put("resolution", Map.of("status", "resolved"));
    return new SseEvent("intent.resolution", payload);
  }

  private static SseEvent redirectedResolutionEvent(
      int index, String target, ResolutionResult.Redirected<?> redirected) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("index", index);
    payload.put("target", target);
    payload.put("resolution", Map.of(
        "status", "redirected",
        "originalId", redirected.originalId(),
        "reason", redirected.reason().name().toLowerCase(java.util.Locale.ROOT)));
    return new SseEvent("intent.resolution", payload);
  }

  private static SseEvent unresolvedResolutionEvent(
      int index, ResolutionResult.Unresolved<?> unresolved) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("index", index);
    payload.put("target", unresolved.attemptedId());
    Map<String, Object> resolution = new LinkedHashMap<>();
    resolution.put("status", "unresolved");
    Map<String, Object> diagnosis = new LinkedHashMap<>();
    diagnosis.put("mode", unresolved.diagnosis().mode().name().toLowerCase(java.util.Locale.ROOT));
    diagnosis.put("detail", unresolved.diagnosis().detail());
    resolution.put("diagnosis", diagnosis);
    List<Map<String, Object>> alts = new ArrayList<>();
    for (var s : unresolved.alternatives()) {
      Map<String, Object> alt = new LinkedHashMap<>();
      alt.put("id", s.refId());
      alt.put("confidence", s.confidence());
      alt.put("rationale", s.rationale());
      alts.add(alt);
    }
    resolution.put("alternatives", alts);
    payload.put("resolution", resolution);
    return new SseEvent("intent.resolution", payload);
  }

  private static SseEvent extractedEvent(Intent intent, int index) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("index", index);
    payload.put("addressKind", intent.address() instanceof ShellAddress.Navigation ? "navigate" : "invoke");
    payload.put("target", intent.address() instanceof ShellAddress.Navigation nav
        ? nav.target().value()
        : ((ShellAddress.Invocation) intent.address()).target().value());
    payload.put("transport", intent.transport().name());
    return new SseEvent("navigate.url_extracted", payload);
  }

  private static SseEvent dispatchedEvent(Intent intent, int index, IntentDispatchResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("index", index);
    payload.put("addressKind", intent.address() instanceof ShellAddress.Navigation ? "navigate" : "invoke");
    payload.put("target", intent.address() instanceof ShellAddress.Navigation nav
        ? nav.target().value()
        : ((ShellAddress.Invocation) intent.address()).target().value());
    switch (result) {
      case IntentDispatchResult.Dispatched d -> {
        payload.put("outcome", "dispatched");
        payload.put("success", d.result().success());
        payload.put("message", d.result().message());
      }
      case IntentDispatchResult.Forwarded f -> {
        payload.put("outcome", "forwarded");
        payload.put("envelopeId", f.envelopeId());
      }
    }
    return new SseEvent("navigate.url_dispatched", payload);
  }

  private static SseEvent rejectedEvent(Intent intent, int index, RuntimeException e) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("index", index);
    payload.put("addressKind", intent.address() instanceof ShellAddress.Navigation ? "navigate" : "invoke");
    payload.put("target", intent.address() instanceof ShellAddress.Navigation nav
        ? nav.target().value()
        : ((ShellAddress.Invocation) intent.address()).target().value());
    payload.put("reason", reasonCode(e));
    payload.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    return new SseEvent("navigate.url_rejected", payload);
  }

  /**
   * Categorize the dispatch failure into a stable wire reason code. The three
   * trust-lattice exceptions (slice 487 §4.4) get specific codes; everything else
   * is "dispatch-failed" (catalog miss, handler throw, etc.).
   */
  private static String reasonCode(RuntimeException e) {
    if (e instanceof ConfirmationRequiredException) {
      return "confirmation-required";
    }
    if (e instanceof TrustGateDeniedException) {
      return "trust-denied";
    }
    return "dispatch-failed";
  }

  private static Map<String, Object> sideEffectRecord(
      Intent intent, int index, IntentDispatchResult result, RuntimeException error) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("kind", "url-extracted");
    record.put("index", index);
    record.put(
        "target",
        intent.address() instanceof ShellAddress.Navigation nav
            ? nav.target().value()
            : ((ShellAddress.Invocation) intent.address()).target().value());
    record.put("outcome", error == null ? "dispatched" : "rejected");
    if (result != null) {
      record.put("result", result.getClass().getSimpleName());
    }
    if (error != null) {
      record.put("error", error.getMessage());
    }
    return record;
  }
}
