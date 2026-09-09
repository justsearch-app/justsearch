package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentDispatchResult;
import io.justsearch.agent.api.registry.IntentSource;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.IntentSourceRef;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.StateSnapshot;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.agent.api.registry.SurfaceConsumes;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.core.context.EngineContext;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.intent.IntentEnvelopeEvent;
import io.justsearch.app.observability.navigation.NavigationHistoryEntry;
import io.justsearch.app.observability.navigation.NavigationHistoryStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link BackendIntentRouterImpl} per tempdoc 487 §4.3.
 *
 * <p>Verifies the two dispatch paths: Invocation envelopes dispatch via
 * {@link OperationDispatcher} and return {@link IntentDispatchResult.Dispatched};
 * Navigation envelopes broadcast on the intent stream and return
 * {@link IntentDispatchResult.Forwarded} carrying the envelope's stable id.
 */
final class BackendIntentRouterImplTest {

  private static final EngineContext UI_CONTEXT =
      io.justsearch.app.services.TestEngineContexts.ui();
  private static final InvocationProvenance UI_PROV =
      InvocationProvenance.fromEngineContext(
          UI_CONTEXT,
          ExecutorTag.UI,
          Instant.parse("2026-05-13T10:00:00.000Z"),
          Optional.empty());

  private static Operation makeOp(String id) {
    return new Operation(
        new OperationRef(id),
        Presentation.of(new I18nKey("test." + id), new I18nKey("test." + id + ".desc")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(new OperationRef(id)),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        Audience.USER);
  }

  private static IntentSource makeSource(String id, TransportTag transport, SourceTier tier) {
    return new IntentSource(
        new IntentSourceRef(id),
        Presentation.of(new I18nKey("intent-source." + id), new I18nKey("intent-source." + id)),
        Provenance.core("1.0"),
        tier,
        "extractor-" + id,
        false,
        Optional.of(transport));
  }

  /** Stub dispatcher capturing its arguments. */
  private static final class CapturingDispatcher implements OperationDispatcher {
    Operation lastOp;
    String lastArgs;
    InvocationProvenance lastProvenance;
    OperationResult next =
        new OperationResult(
            true,
            "ok",
            Optional.empty(),
            Map.of(),
            Optional.empty(),
            Map.of(),
            Optional.empty());

    @Override
    public OperationResult dispatch(
        Operation op, String argumentsJson, EngineContext engineContext) {
      throw new UnsupportedOperationException("Use provenance overload");
    }

    @Override
    public OperationResult dispatch(
        Operation op,
        String argumentsJson,
        InvocationProvenance provenance,
        Optional<String> confirmationToken,
        EngineContext engineContext) {
      this.lastOp = op;
      this.lastArgs = argumentsJson;
      this.lastProvenance = provenance;
      return next;
    }

    @Override
    public OperationResult undo(
        Operation op, String executionId, EngineContext engineContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OperationResult undo(
        Operation op,
        String executionId,
        InvocationProvenance provenance,
        Optional<String> confirmationToken,
        EngineContext engineContext) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void invocationDispatchesAndWrapsResult() {
    OperationCatalog ops = OperationCatalog.of("core", List.of(makeOp("core.ping-backend")));
    IntentSourceCatalog sources = IntentSourceCatalog.of("core", List.of());
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    CapturingDispatcher dispatcher = new CapturingDispatcher();
    BackendIntentRouterImpl router =
        new BackendIntentRouterImpl(ops, dispatcher, sources, registry, () -> "ie-test-001");

    Intent intent =
        new Intent(
            ShellAddress.Invocation.of(new OperationRef("core.ping-backend"), "{}"),
            TransportTag.AGENT_LOOP);

    IntentDispatchResult result = router.dispatch(intent, UI_PROV, UI_CONTEXT);

    var dispatched = assertInstanceOf(IntentDispatchResult.Dispatched.class, result);
    assertTrue(dispatched.result().success(), "underlying dispatcher result is propagated");
    assertSame(UI_PROV, dispatcher.lastProvenance, "provenance is threaded through");
    assertEquals("core.ping-backend", dispatcher.lastOp.id().value());
    assertEquals("{}", dispatcher.lastArgs);
  }

  @Test
  void invocationWithUnknownOperationRefThrows() {
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    IntentSourceCatalog sources = IntentSourceCatalog.of("core", List.of());
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    BackendIntentRouterImpl router =
        new BackendIntentRouterImpl(
            ops, new CapturingDispatcher(), sources, registry, () -> "ie-test-002");

    Intent intent =
        new Intent(
            ShellAddress.Invocation.of(new OperationRef("core.does-not-exist"), "{}"),
            TransportTag.AGENT_LOOP);

    assertThrows(IllegalArgumentException.class, () -> router.dispatch(intent, UI_PROV, UI_CONTEXT));
  }

  @Test
  void navigationBroadcastsEnvelopeAndReturnsForwardedId() {
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    IntentSourceCatalog sources =
        IntentSourceCatalog.of(
            "core",
            List.of(
                makeSource("core.llm-chat-emission", TransportTag.LLM_EMISSION, SourceTier.UNTRUSTED)));
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    AtomicReference<SseEnvelope> captured = new AtomicReference<>();
    registry.subscribe(captured::set);

    BackendIntentRouterImpl router =
        new BackendIntentRouterImpl(
            ops, new CapturingDispatcher(), sources, registry, () -> "ie-test-nav-001");

    Map<String, Object> state = new HashMap<>();
    state.put("folder", "docs");
    Intent intent =
        new Intent(
            new ShellAddress.Navigation(new SurfaceRef("core.library"), new StateSnapshot(state)),
            TransportTag.LLM_EMISSION);

    IntentDispatchResult result = router.dispatch(intent, UI_PROV, UI_CONTEXT);

    var forwarded = assertInstanceOf(IntentDispatchResult.Forwarded.class, result);
    assertEquals("ie-test-nav-001", forwarded.envelopeId());

    SseEnvelope env = captured.get();
    assertEquals(IntentEnvelopeChangeRegistry.STREAM_ID, env.streamId());
    assertEquals(SseFrameKind.UPDATE, env.frameKind());
    var payload = assertInstanceOf(IntentEnvelopeEvent.class, env.payload());
    assertEquals("ie-test-nav-001", payload.id());
    assertEquals(IntentEnvelopeEvent.KIND, payload.kind());
    assertEquals("core.llm-chat-emission", payload.sourceId().value());
    assertEquals(TransportTag.LLM_EMISSION, payload.intent().transport());
  }

  /**
   * Tempdoc 550 Slice F1 (Outcome face): a forwarded Navigation is recorded as an
   * attributed {@link NavigationHistoryEntry} when the router is wired with a
   * {@link NavigationHistoryStore}. Navigation previously left no record at all.
   */
  @Test
  void navigationIsRecordedInHistoryLedger() {
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    IntentSourceCatalog sources =
        IntentSourceCatalog.of(
            "core",
            List.of(
                makeSource(
                    "core.llm-chat-emission", TransportTag.LLM_EMISSION, SourceTier.UNTRUSTED)));
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    NavigationHistoryStore navHistory = new NavigationHistoryStore();

    BackendIntentRouterImpl router =
        new BackendIntentRouterImpl(
            ops,
            new CapturingDispatcher(),
            sources,
            registry,
            navHistory,
            () -> "ie-test-nav-rec");

    Intent intent =
        new Intent(
            new ShellAddress.Navigation(new SurfaceRef("core.library"), StateSnapshot.empty()),
            TransportTag.LLM_EMISSION);

    router.dispatch(intent, UI_PROV, UI_CONTEXT);

    List<NavigationHistoryEntry> recorded = navHistory.recent();
    assertEquals(1, recorded.size(), "one navigation entry recorded");
    NavigationHistoryEntry entry = recorded.get(0);
    assertEquals("ie-test-nav-rec", entry.envelopeId());
    assertEquals("core.library", entry.targetSurface());
    assertEquals("core.llm-chat-emission", entry.sourceId());
    assertEquals(UI_PROV.occurredAt(), entry.occurredAt());
    assertSame(UI_PROV, entry.provenance(), "full provenance is retained on the record");
  }

  /**
   * Tempdoc 550 Slice F1: the back-compat constructor (no navigation ledger) still
   * forwards navigations without recording — recording is opt-in via the store-bearing
   * constructor, mirroring the trust-deps-absent escape hatch.
   */
  @Test
  void navigationWithoutLedgerStillForwards() {
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    IntentSourceCatalog sources =
        IntentSourceCatalog.of(
            "core",
            List.of(
                makeSource(
                    "core.llm-chat-emission", TransportTag.LLM_EMISSION, SourceTier.UNTRUSTED)));
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();

    // 5-arg test constructor: no NavigationHistoryStore.
    BackendIntentRouterImpl router =
        new BackendIntentRouterImpl(
            ops, new CapturingDispatcher(), sources, registry, () -> "ie-test-nav-noledger");

    Intent intent =
        new Intent(
            new ShellAddress.Navigation(new SurfaceRef("core.library"), StateSnapshot.empty()),
            TransportTag.LLM_EMISSION);

    IntentDispatchResult result = router.dispatch(intent, UI_PROV, UI_CONTEXT);
    assertInstanceOf(IntentDispatchResult.Forwarded.class, result);
  }

  @Test
  void navigationWithUnregisteredTransportFallsBackToSentinelSource() {
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    // Empty source catalog — no source registered for any transport.
    IntentSourceCatalog sources = IntentSourceCatalog.of("core", List.of());
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    AtomicReference<SseEnvelope> captured = new AtomicReference<>();
    registry.subscribe(captured::set);

    BackendIntentRouterImpl router =
        new BackendIntentRouterImpl(
            ops, new CapturingDispatcher(), sources, registry, () -> "ie-test-nav-002");

    Intent intent =
        new Intent(
            new ShellAddress.Navigation(new SurfaceRef("core.library"), StateSnapshot.empty()),
            TransportTag.LLM_EMISSION);

    IntentDispatchResult result = router.dispatch(intent, UI_PROV, UI_CONTEXT);

    assertInstanceOf(IntentDispatchResult.Forwarded.class, result);
    var payload = assertInstanceOf(IntentEnvelopeEvent.class, captured.get().payload());
    assertEquals(
        "core.unregistered-transport",
        payload.sourceId().value(),
        "fallback sentinel sourceId when transport has no registered source");
  }

  // ── Tempdoc 550 WA-4: Navigation gating via the (SourceTier × Surface.RiskTier) lattice ──

  private static Surface surfaceWithRisk(String idValue, RiskTier riskTier) {
    return new Surface(
        new SurfaceRef(idValue),
        Presentation.of(
            new I18nKey("registry-surface.example.label"),
            new I18nKey("registry-surface.example.description")),
        Audience.USER,
        Placement.RAIL,
        SurfaceConsumes.empty(),
        "jf-example-surface",
        Provenance.core("1.0"),
        Optional.empty(),
        riskTier);
  }

  private static BackendIntentRouterImpl gatingRouter(
      IntentEnvelopeChangeRegistry registry, SurfaceCatalog surfaces) {
    // URL_BAR → MEDIUM tier. MEDIUM × HIGH-surface = TYPED_CONFIRM (non-AUTO) → gated;
    // MEDIUM × LOW-surface = AUTO → broadcast.
    IntentSourceCatalog sources =
        IntentSourceCatalog.of(
            "core",
            List.of(makeSource("core.url-bar", TransportTag.URL_BAR, SourceTier.MEDIUM)));
    return new BackendIntentRouterImpl(
        OperationCatalog.of("core", List.of()),
        new CapturingDispatcher(),
        sources,
        registry,
        null, // no navigation ledger needed for these assertions
        () -> "ie-test-wa4",
        new CoreTrustEvaluator(),
        surfaces);
  }

  @Test
  void navigationToHighRiskSurfaceIsAuditedButStillBroadcast() {
    // Tempdoc 550 WA-4 (audit-only): the (SourceTier × Surface.RiskTier) gate is COMPUTED for
    // navigation (a non-AUTO result logs a forward-compat breadcrumb), but enforcement is not
    // wired — so even a HIGH-risk surface nav is still broadcast. (Suppressing it while
    // returning Forwarded would falsely report a navigation that didn't happen; real
    // enforcement lands with the first higher-risk surface + its FE recovery.)
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    AtomicReference<SseEnvelope> captured = new AtomicReference<>();
    registry.subscribe(captured::set);
    SurfaceCatalog surfaces =
        SurfaceCatalog.of("core", List.of(surfaceWithRisk("core.danger-surface", RiskTier.HIGH)));
    BackendIntentRouterImpl router = gatingRouter(registry, surfaces);

    Intent intent =
        new Intent(
            new ShellAddress.Navigation(
                new SurfaceRef("core.danger-surface"), StateSnapshot.empty()),
            TransportTag.URL_BAR);
    IntentDispatchResult result = router.dispatch(intent, UI_PROV, UI_CONTEXT);

    assertInstanceOf(IntentDispatchResult.Forwarded.class, result);
    assertNotNull(
        captured.get(),
        "navigation gating is audit-only — the envelope is still broadcast (no false suppress)");
  }

  @Test
  void navigationToLowRiskSurfaceIsBroadcastNormally() {
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    AtomicReference<SseEnvelope> captured = new AtomicReference<>();
    registry.subscribe(captured::set);
    SurfaceCatalog surfaces =
        SurfaceCatalog.of("core", List.of(surfaceWithRisk("core.library", RiskTier.LOW)));
    BackendIntentRouterImpl router = gatingRouter(registry, surfaces);

    Intent intent =
        new Intent(
            new ShellAddress.Navigation(new SurfaceRef("core.library"), StateSnapshot.empty()),
            TransportTag.URL_BAR);
    IntentDispatchResult result = router.dispatch(intent, UI_PROV, UI_CONTEXT);

    assertInstanceOf(IntentDispatchResult.Forwarded.class, result);
    assertNotNull(
        captured.get(),
        "a LOW-risk surface yields an AUTO gate → the navigation is broadcast as normal (the"
            + " gate is the determinant, proving the HIGH case is gated for the right reason)");
  }
}
