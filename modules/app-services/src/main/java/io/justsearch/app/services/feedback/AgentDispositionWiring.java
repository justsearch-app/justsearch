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
    register(listener -> addEventListener.accept((type, payload) -> {
      Map<String, Object> captured = new java.util.HashMap<>(payload);
      observer.observe(() -> listener.accept(type, captured));
    }), dataDir, cipher, captureSettings);
  }

  public static void register(
      Consumer<BiConsumer<String, Map<String, Object>>> addEventListener,
      Path dataDir,
      StoreCipher cipher,
      FeedbackCaptureSettings captureSettings) {
    Path feedback = dataDir.resolve("feedback");
    NdjsonAppendStore<ResultDisposition> dispositions =
        new NdjsonAppendStore<>(
            feedback.resolve("result-dispositions.ndjson"), ResultDisposition.class, cipher);
    NdjsonAppendStore<FeatureSnapshot> snapshots =
        new NdjsonAppendStore<>(
            feedback.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class, cipher);
    addEventListener.accept(
        (eventType, payload) -> {
          long now = Instant.now().toEpochMilli();
          String sessionId = str(payload.get("sessionId"));
          if ("tool_exec_completed".equals(eventType)) {
            // Feature snapshots are engine score-vectors (not user behaviour), and the join needs them
            // even when a disposition is later re-enabled — so they are NOT gated by the capture flag.
            captureAgentSnapshot(snapshots, sessionId, payload, now);
          } else if ("done".equals(eventType) && captureSettings.isEnabled()) {
            // Tempdoc 778 — the disposition (the behavioural signal) is gated by the default-on local
            // capture flag; off ⇒ the answer's citations are not recorded as dispositions.
            // Persist only dispositions whose unchanged path-oriented citation id resolves through
            // a captured snapshot to a stable UID. Falling back to the path would create a new
            // path-keyed row and violate tempdoc 915 B5.
            persistUidDispositions(dispositions, snapshots, sessionId, payload, now);
          }
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
