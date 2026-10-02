/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.OperationResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 580 §17 P4 (Fix B) — wires the agent feedback contributors as a live listener on the agent
 * run-event stream. Two reactions, correlated by the run's {@code sessionId} (stamped on every event
 * payload by {@code AgentRunStore.appendEvent}):
 *
 * <ul>
 *   <li>On each {@code tool_exec_completed} carrying search {@code feedbackFeatures}, capture a
 *       {@link FeatureSnapshot} keyed by {@code sessionId} — the per-search ranking features (the §17.4
 *       join input the agent path previously lacked).
 *   <li>On {@code done}, project the answer's grounding sources + citations into {@link ResultDisposition}s
 *       keyed by the SAME {@code sessionId}, so a CITED/SHOWN disposition joins its FeatureSnapshot and
 *       becomes a real training label (the join the original P4 left unwired — agent dispositions used a
 *       fresh {@code agent-<UUID>} with no snapshot, so they were all dropped by {@code LabelProjection}).
 * </ul>
 *
 * <p>Takes a listener-registrar function rather than the agent store type, so the feedback package stays
 * decoupled from app-agent (only the caller, HeadAssembly, holds both). Best-effort: the underlying
 * {@link NdjsonAppendStore#append} swallows failures, so feedback never affects the loop.
 */
public final class AgentDispositionWiring {

  private static final Logger log = LoggerFactory.getLogger(AgentDispositionWiring.class);

  private AgentDispositionWiring() {}

  /**
   * Registers the contributors on the agent run-event stream.
   * The registrar delivers {@code (sessionId, recordEnvelope)} with a nested {@code payload}.
   *
   * @param addEventListener the store's {@code addEventListener} (e.g. {@code agentRunStore::addEventListener})
   * @param dataDir the resolved data directory
   * @param cipher the AUTHORED feedback-store cipher (tempdoc 778) — seals each ndjson line; must be
   *     the SAME key the other writers/readers use, or the LabelProjection join breaks
   */
  public static void register(
      Consumer<BiConsumer<String, Map<String, Object>>> addEventListener,
      Path dataDir, StoreCipher cipher, FeedbackCaptureSettings captureSettings,
      FeedbackObserver observer) {
    register(addEventListener, dataDir, cipher, captureSettings,
        observation -> observer.observe(observation), true);
  }

  public static void register(
      Consumer<BiConsumer<String, Map<String, Object>>> addEventListener,
      Path dataDir,
      StoreCipher cipher,
      FeedbackCaptureSettings captureSettings) {
    register(addEventListener, dataDir, cipher, captureSettings, Runnable::run, false);
  }

  private static void register(
      Consumer<BiConsumer<String, Map<String, Object>>> addEventListener,
      Path dataDir, StoreCipher cipher, FeedbackCaptureSettings captureSettings,
      Consumer<Runnable> observations, boolean managedLookup) {
    Path feedback = dataDir.resolve("feedback");
    var dispositions = new NdjsonAppendStore<>(
        feedback.resolve("result-dispositions.ndjson"), ResultDisposition.class, cipher);
    var snapshots = managedLookup
        ? NdjsonAppendStore.observedFeatureSnapshots(feedback.resolve("feature-snapshots.ndjson"), cipher)
        : new NdjsonAppendStore<>(feedback.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class, cipher);
    addEventListener.accept((sessionId, envelope) -> {
      if (sessionId == null || sessionId.isBlank() || envelope == null
          || !"core.agent-run".equals(envelope.get("shapeId"))) return;
      String eventType = str(envelope.get("eventType"));
      if (!"tool_exec_completed".equals(eventType) && !"done".equals(eventType)) return;
      if (!(envelope.get("payload") instanceof Map<?, ?> payload)) return;
      if ("tool_exec_completed".equals(eventType)) {
        if (!(payload.get("structuredData") instanceof Map<?, ?> structured)
            || !(structured.get(OperationResult.FEEDBACK_FEATURES_KEY) instanceof List<?> features)
            || features.isEmpty()) return;
      } else if (!captureSettings.isEnabled()
          || !(payload.get("sources") instanceof List<?> sources) || sources.isEmpty()) {
        return;
      }
      Map<String, Object> captured = new java.util.HashMap<>();
      for (var entry : payload.entrySet()) {
        if (entry.getKey() instanceof String key) captured.put(key, entry.getValue());
      }
      // The durable listener's session argument owns correlation, including legacy payloads.
      captured.put("sessionId", sessionId);
      long occurredAtMs = Instant.now().toEpochMilli();
      observations.accept(() -> {
        if ("tool_exec_completed".equals(eventType)) {
          // Score vectors are captured even when behavioural capture is disabled.
          captureAgentSnapshot(snapshots, sessionId, captured, occurredAtMs);
        } else if (captureSettings.isEnabled()) {
          persistUidDispositions(dispositions, snapshots, sessionId, captured, occurredAtMs);
        }
      });
    });
  }

  /**
   * Capture the per-search {@link FeatureSnapshot} from a {@code tool_exec_completed} event's
   * {@code feedbackFeatures} (the §17 P4 feedback channel emitted by {@code SearchTool.buildSearchEvidence},
   * keyed by {@code parentDocId} — the same id-space agent dispositions reference). Multiple searches in
   * one run emit multiple snapshots under the same {@code sessionId}; {@code LabelProjection} unions them.
   */
  private static void captureAgentSnapshot(
      NdjsonAppendStore<FeatureSnapshot> store,
      String sessionId,
      Map<String, Object> payload,
      long now) {
    if (sessionId == null || sessionId.isBlank()) {
      return;
    }
    if (!(payload.get("structuredData") instanceof Map<?, ?> sd)) {
      return;
    }
    if (!(sd.get(OperationResult.FEEDBACK_FEATURES_KEY) instanceof List<?> feats)
        || feats.isEmpty()) {
      return; // not a search tool result
    }
    List<FeatureSnapshot.HitFeatures> hits = new ArrayList<>();
    for (Object o : feats) {
      if (!(o instanceof Map<?, ?> f)) {
        continue;
      }
      String docId = str(f.get("docId"));
      String docUid = str(f.get("docUid"));
      if (docId == null || docId.isBlank() || docUid == null || docUid.isBlank()) {
        continue;
      }
      hits.add(
          new FeatureSnapshot.HitFeatures(
              docUid,
              docId,
              intOf(f.get("rank")),
              floatOf(f.get("sparse")),
              floatOf(f.get("dense")),
              floatOf(f.get("splade")),
              floatOf(f.get("fused")),
              null,
              str(f.get("contentRevision"))));
    }
    if (!hits.isEmpty()) {
      store.append(new FeatureSnapshot(sessionId, "agent-search", now, hits));
    }
  }

  private static void persistUidDispositions(
      NdjsonAppendStore<ResultDisposition> dispositions,
      NdjsonAppendStore<FeatureSnapshot> snapshots,
      String sessionId,
      Map<String, Object> payload,
      long now) {
    if (sessionId == null || sessionId.isBlank()) {
      return;
    }
    int unresolved = 0;
    for (ResultDisposition disposition :
        AgentCitationContributor.fromDoneEvent(sessionId, payload, now)) {
      java.util.Optional<String> stableDocId;
      try {
        stableDocId = snapshots.resolveStableDocId(disposition.interactionId(), disposition.docId());
      } catch (Exception failure) {
        log.debug("agent feedback UID resolution failed (non-fatal): {}", failure.toString());
        continue;
      }
      if (stableDocId.isEmpty()) {
        unresolved++;
        continue;
      }
      dispositions.append(
          new ResultDisposition(
              disposition.interactionId(),
              stableDocId.get(),
              disposition.kind(),
              disposition.contributor(),
              disposition.occurredAtMs()));
    }
    if (unresolved > 0) {
      log.debug("omitted {} agent feedback rows without stable document UID", unresolved);
    }
  }

  private static String str(Object o) {
    return o instanceof String s ? s : null;
  }

  private static int intOf(Object o) {
    return o instanceof Number n ? n.intValue() : 0;
  }

  private static float floatOf(Object o) {
    return o instanceof Number n ? n.floatValue() : 0f;
  }
}
