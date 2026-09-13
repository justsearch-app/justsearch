package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies the contribution-registry mechanism + transactional composer (tempdoc 560 §4.2 / 507 KCS). */
class ContributionRegistryTest {

  private static Operation op(String id) {
    OperationRef ref = new OperationRef(id);
    return new Operation(
        ref,
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        Interface.of("{}", "{}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ref),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Plugin plugin(String id, Set<OperationRef> ops) {
    return new Plugin(
        new PluginRef(id),
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.x", "1.0"),
        Audience.AGENT,
        PluginContributions.ofOperations(ops),
        List.of(new ConsumerHook.Realized("agent-loop", Audience.AGENT)));
  }

  private static ContributionRegistry.Installation install(String pluginId, String... opIds) {
    var ops = java.util.Arrays.stream(opIds).map(ContributionRegistryTest::op).toList();
    var refs = ops.stream().map(Operation::id).collect(java.util.stream.Collectors.toSet());
    var handlers =
        ops.stream()
            .collect(
                java.util.stream.Collectors.<Operation, OperationRef, OperationHandler>toMap(
                    Operation::id, o -> (args, context) -> OperationResult.success("ok")));
    return new ContributionRegistry.Installation(plugin(pluginId, refs), ops, handlers);
  }

  /** A LANGUAGE_MEDIATED contribution (Prompt) — proves the composer is axis-agnostic, not Operation-only. */
  private static Prompt prompt(String id) {
    return new Prompt(
        new PromptRef(id),
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        "templates/" + id,
        List.of(),
        Provenance.core("1.0"),
        Audience.AGENT,
        List.of(new ConsumerHook.Realized("prompt-renderer", Audience.AGENT)));
  }

  /** An installation spanning two axis projections — EXECUTABLE (operations) + LANGUAGE_MEDIATED (prompts). */
  private static ContributionRegistry.Installation multiAxis(
      String pluginId, List<Operation> ops, List<Prompt> prompts) {
    var opRefs = ops.stream().map(Operation::id).collect(java.util.stream.Collectors.toSet());
    var handlers =
        ops.stream()
            .collect(
                java.util.stream.Collectors.<Operation, OperationRef, OperationHandler>toMap(
                    Operation::id, o -> (args, context) -> OperationResult.success("ok")));
    return new ContributionRegistry.Installation(
        plugin(pluginId, opRefs), ops, List.of(), prompts, List.of(), List.of(), List.of(), handlers);
  }

  @Test
  void installExposesOperationsPluginsAndHandlers() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(install("vendor.a.pack", "vendor.a.one", "vendor.a.two"));

    assertEquals(1, reg.plugins().size());
    assertEquals(2, reg.operations().size());
    assertEquals(2, reg.handlers().size());
    assertTrue(reg.isInstalled(new PluginRef("vendor.a.pack")));
  }

  @Test
  void operationCollisionIsRejectedAtomically() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(install("vendor.a.pack", "vendor.a.shared"));

    // A second plugin contributing the same op ref must be rejected whole — no partial state.
    assertThrows(
        IllegalStateException.class, () -> reg.install(install("vendor.b.pack", "vendor.a.shared", "vendor.b.extra")));

    assertEquals(1, reg.plugins().size(), "rejected install must leave the registry unchanged");
    assertEquals(1, reg.operations().size());
    assertFalse(reg.isInstalled(new PluginRef("vendor.b.pack")));
  }

  @Test
  void doubleInstallSamePluginRejected() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(install("vendor.a.pack", "vendor.a.one"));
    assertThrows(IllegalStateException.class, () -> reg.install(install("vendor.a.pack", "vendor.a.two")));
    assertEquals(1, reg.operations().size());
  }

  @Test
  void uninstallRemovesPluginAndItsContributions() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(install("vendor.a.pack", "vendor.a.one", "vendor.a.two"));
    reg.install(install("vendor.b.pack", "vendor.b.one"));

    assertTrue(reg.uninstall(new PluginRef("vendor.a.pack")));

    assertEquals(1, reg.plugins().size());
    assertEquals(List.of("vendor.b.one"), reg.operations().stream().map(o -> o.id().value()).toList());
    assertEquals(1, reg.handlers().size());
    assertFalse(reg.isInstalled(new PluginRef("vendor.a.pack")));
  }

  @Test
  void ownerOfTracksContributor() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(install("vendor.a.pack", "vendor.a.one"));
    assertEquals(
        Optional.of(new PluginRef("vendor.a.pack")), reg.ownerOf(new OperationRef("vendor.a.one")));
    assertEquals(Optional.empty(), reg.ownerOf(new OperationRef("vendor.z.none")));
  }

  @Test
  void uninstallUnknownReturnsFalse() {
    ContributionRegistry reg = new ContributionRegistry();
    assertFalse(reg.uninstall(new PluginRef("vendor.absent.pack")));
  }

  @Test
  void untrustedPluginDeniedWhenSandboxRuntimeUnavailable() {
    ContributionRegistry reg = new ContributionRegistry();
    Plugin untrusted =
        new Plugin(
            new PluginRef("vendor.untrusted.pack"),
            Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
            new Provenance(TrustTier.UNTRUSTED_PLUGIN, "vendor.untrusted", "1.0"),
            Audience.AGENT,
            PluginContributions.ofOperations(Set.of(new OperationRef("vendor.untrusted.run"))),
            List.of(new ConsumerHook.Realized("agent-loop", Audience.AGENT)));
    var inst =
        new ContributionRegistry.Installation(untrusted, List.of(op("vendor.untrusted.run")), Map.of());

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reg.install(inst));
    // The Boundary substrate now lives in the shared composer; the Head's UNTRUSTED tier projects to
    // boundaryAdmissible=false, so the composer refuses the install.
    assertTrue(ex.getMessage().contains("Boundary refused"));
    assertEquals(0, reg.operations().size(), "denied UNTRUSTED install must leave the registry unchanged");
  }

  @Test
  void hostOwnsTruthRejectsNonCorePluginMintingCoreRef() {
    ContributionRegistry reg = new ContributionRegistry();
    // The install helper builds TRUSTED_PLUGIN plugins; a core.* contribution forks core truth (§4.5).
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> reg.install(install("vendor.evil.pack", "core.search-index")));
    assertTrue(ex.getMessage().contains("Host owns truth"));
    assertEquals(0, reg.operations().size(), "rejected violation must leave the registry unchanged");
    assertFalse(reg.isInstalled(new PluginRef("vendor.evil.pack")));
  }

  // ---- §4.4 one composer, all four axis projections (zero per-axis re-derivation) ----

  @Test
  void installComposesMultipleAxesThroughOneMechanism() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(
        multiAxis(
            "vendor.multi.pack",
            List.of(op("vendor.multi.run")),
            List.of(prompt("vendor.multi.greeting"))));

    assertEquals(List.of("vendor.multi.run"), reg.operations().stream().map(o -> o.id().value()).toList());
    assertEquals(
        List.of("vendor.multi.greeting"), reg.prompts().stream().map(p -> p.id().value()).toList());
    assertEquals(0, reg.resources().size());
    assertEquals(0, reg.surfaces().size());
    // EXECUTABLE handler is wired; the LANGUAGE_MEDIATED axis carries no executable body.
    assertEquals(1, reg.handlers().size());
  }

  @Test
  void uninstallRevokesEveryAxisNotJustOperations() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(
        multiAxis(
            "vendor.multi.pack",
            List.of(op("vendor.multi.run")),
            List.of(prompt("vendor.multi.greeting"))));

    assertTrue(reg.uninstall(new PluginRef("vendor.multi.pack")));

    assertEquals(0, reg.operations().size());
    assertEquals(
        0, reg.prompts().size(), "uninstall must revoke the LANGUAGE_MEDIATED axis via the one ownership index");
    assertEquals(0, reg.handlers().size());
  }

  @Test
  void hostOwnsTruthAppliesToEveryAxisNotJustOperations() {
    ContributionRegistry reg = new ContributionRegistry();
    // A non-core plugin minting a core.* PROMPT (not an operation) forks core truth — the §4.5 check
    // runs on every axis through the one validate pass, not only the EXECUTABLE axis.
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> reg.install(multiAxis("vendor.evil.pack", List.of(), List.of(prompt("core.system-greeting")))));

    assertTrue(ex.getMessage().contains("Host owns truth"));
    assertTrue(ex.getMessage().contains("core.system-greeting"));
    assertEquals(0, reg.prompts().size(), "rejected violation must leave the registry unchanged");
    assertFalse(reg.isInstalled(new PluginRef("vendor.evil.pack")));
  }

  // ---- §10.4 declaration completeness: the two trailing axes (DiagnosticChannel, ConversationShape) ----

  /** A vendor OBSERVABLE-stream contribution (DiagnosticChannel). */
  private static DiagnosticChannel channel(String id) {
    return new DiagnosticChannel(
        new DiagnosticChannelRef(id),
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        Set.of(DataClass.CONFIG_VALUES),
        ProducerKind.IN_PROCESS_LOGBACK,
        DeliveryMode.SSE_STREAM,
        LoggerNamespaceSelector.of(Map.of()),
        "/api/diagnostic-channels/" + id.replace('.', '-') + "/stream",
        ConsumerPermission.OPERATOR_OVERRIDE,
        Provenance.core("1.0"));
  }

  /** A vendor ConversationShape (SHAPE_DRIVEN + ONE_SHOT → no iterationControllerId required). */
  private static ConversationShape shape(String id) {
    return new ConversationShape(
        new ConversationShapeRef(id),
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        Audience.AGENT,
        Provenance.core("1.0"),
        ExecutionMode.SHAPE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of(),
        false);
  }

  /** An installation carrying only the two trailing axes (no executable body). */
  private static ContributionRegistry.Installation installStreams(
      String pluginId, List<DiagnosticChannel> channels, List<ConversationShape> shapes) {
    Set<DiagnosticChannelRef> chRefs =
        channels.stream().map(DiagnosticChannel::id).collect(java.util.stream.Collectors.toSet());
    Set<ConversationShapeRef> shRefs =
        shapes.stream().map(ConversationShape::id).collect(java.util.stream.Collectors.toSet());
    Plugin p =
        new Plugin(
            new PluginRef(pluginId),
            Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
            new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.x", "1.0"),
            Audience.OPERATOR,
            new PluginContributions(Set.of(), Set.of(), Set.of(), Set.of(), chRefs, shRefs),
            List.of(new ConsumerHook.Realized("registry", Audience.OPERATOR)));
    return new ContributionRegistry.Installation(
        p, List.of(), List.of(), List.of(), List.of(), channels, shapes, Map.of());
  }

  @Test
  void installComposesDiagnosticChannelAndConversationShapeAxes() {
    ContributionRegistry reg = new ContributionRegistry();
    reg.install(
        installStreams(
            "vendor.streams.pack",
            List.of(channel("vendor.streams.log")),
            List.of(shape("vendor.streams.flow"))));

    assertEquals(
        List.of("vendor.streams.log"),
        reg.diagnosticChannels().stream().map(c -> c.id().value()).toList());
    assertEquals(
        List.of("vendor.streams.flow"),
        reg.conversationShapes().stream().map(s -> s.id().value()).toList());
    // The two trailing axes carry no executable body and don't perturb the others.
    assertEquals(0, reg.operations().size());
    assertEquals(0, reg.handlers().size());
  }

  @Test
  void hostOwnsTruthAppliesToTheDiagnosticChannelAxis() {
    ContributionRegistry reg = new ContributionRegistry();
    // A non-core plugin minting a core.* DiagnosticChannel forks core truth — proves the generic
    // trust boundary covers the NEW axis with zero per-axis code (the "it's free" claim, tested).
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> reg.install(installStreams("vendor.evil.pack", List.of(channel("core.engine-log")), List.of())));

    assertTrue(ex.getMessage().contains("Host owns truth"));
    assertTrue(ex.getMessage().contains("core.engine-log"));
    assertEquals(0, reg.diagnosticChannels().size(), "rejected violation must leave the registry unchanged");
    assertFalse(reg.isInstalled(new PluginRef("vendor.evil.pack")));
  }
}
