/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 880 §T.6 — the oracle for the agent-tool re-homing.
 *
 * <p>This is the baseline the shipped, model-facing tool surface never had. {@code
 * AgentOperationEmitterRegressionTest} deep-equals a baseline built from four HAND-WRITTEN stub
 * operations, so it pins the emitter's projection ALGORITHM and nothing about the real catalog —
 * tempdoc 868 §D routed exactly that gap. This test pins the real {@link
 * AgentToolsOperationCatalog}, which is what the delegate is actually offered.
 *
 * <p>Two halves, because the wire projection is lossy and the lost half is the behavioural half:
 *
 * <ul>
 *   <li>{@link #wireProjectionMatchesBaseline()} pins what the MODEL sees — wire name, description
 *       key, and the full input schema, byte-for-byte against a checked-in baseline. Key order is
 *       NOT normalized: the emitter builds a {@code LinkedHashMap} and the baseline pins that
 *       order too.
 *   <li>{@link #declaredPolicyIsUnchanged()} pins what the EXECUTOR acts on — risk tier, confirm
 *       strategy, audit policy, retry policy, undo support, capability family, advisory class,
 *       lineage, availability, executors, audience. None of that reaches the OpenAI projection, so
 *       a wire baseline alone would not notice a dropped {@code withCapabilityFamily} or a risk
 *       tier silently relaxed during a move.
 * </ul>
 *
 * <p>Deliberate limit: this pins the DECLARATION, not the tool's runtime behaviour. It is the right
 * net for a re-homing and the wrong net for a rewrite.
 */
@DisplayName("Agent-tool catalog baseline (declaration + wire projection)")
final class AgentToolCatalogBaselineTest {

  private static final String BASELINE_RESOURCE = "/agent-tools-wire-baseline.json";

  /**
   * Insertion order is preserved on purpose — see the class javadoc. Pretty-printed so the checked
   * in baseline is reviewable in a diff.
   */
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Test
  @DisplayName("wire projection of the real catalog is byte-identical to the baseline")
  void wireProjectionMatchesBaseline() throws IOException {
    // Identity message resolver, no virtual store, no availability probe: the deterministic
    // projection of the catalog as declared, independent of live condition state.
    List<Map<String, Object>> tools =
        new AgentOperationEmitter().emit(new AgentToolsOperationCatalog(), List.of());

    // Line endings are normalized on BOTH sides. Jackson's pretty printer emits the platform
    // separator, so on Windows the comparison would be CRLF-vs-LF against a baseline git checks out
    // with LF — a test that passes on the machine that generated the file and fails on every other.
    String actual = normalizeEol(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
    String baseline = normalizeEol(readBaseline());

    if (!baseline.equals(actual)) {
      Path dump = writeActual(actual);
      assertEquals(
          baseline,
          actual,
          "The model-facing agent-tool surface changed: a wire name, an input schema, or a\n"
              + "description KEY (this emitter uses the identity resolver, so the prose itself is\n"
              + "resolved elsewhere and checked by AgentOfferingProseTest). If the change is\n"
              + "DELIBERATE, copy\n  "
              + dump
              + "\nover src/test/resources"
              + BASELINE_RESOURCE
              + " and say why in the PR. If it is not deliberate,\n"
              + "the delegate is being offered a different tool catalog than it was before.");
    }
  }

  @Test
  @DisplayName("declared policy, lineage, availability and executors are unchanged")
  void declaredPolicyIsUnchanged() {
    Map<String, Operation> byId = new LinkedHashMap<>();
    for (Operation op : new AgentToolsOperationCatalog().definitions()) {
      byId.put(op.id().value(), op);
    }

    assertEquals(
        List.of(
            "core.search-index",
            "core.read-document",
            "core.browse-folders",
            "core.ingest-files",
            "core.file-operations",
            "core.navigate-to-surface",
            "core.remember"),
        List.copyOf(byId.keySet()),
        "catalog entry set or order changed");

    // core.search-index — LOW, no confirm, auto-retry, gated on the index serving.
    assertPolicy(
        byId,
        "core.search-index",
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.autoRetry(2, "core.search-index"),
            Set.of(),
            false),
        Set.of(ExecutorTag.AGENT),
        Audience.USER,
        OperationLineage.empty(),
        GATED_ON_INDEX_SERVING,
        Presentation.forId(new OperationRef("core.search-index")));

    // core.read-document — LOW, no confirm, NO retry (a paged read must not be replayed behind a
    // different offset), same index-serving gate.
    assertPolicy(
        byId,
        "core.read-document",
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        Set.of(ExecutorTag.AGENT),
        Audience.USER,
        OperationLineage.empty(),
        GATED_ON_INDEX_SERVING,
        Presentation.forId(new OperationRef("core.read-document")));

    // core.browse-folders — LOW, auto-retry (a listing is idempotent), always available.
    assertPolicy(
        byId,
        "core.browse-folders",
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.autoRetry(2, "core.browse-folders"),
            Set.of(),
            false),
        Set.of(ExecutorTag.AGENT),
        Audience.USER,
        OperationLineage.empty(),
        OperationAvailability.empty(),
        Presentation.forId(new OperationRef("core.browse-folders")));

    // core.ingest-files — MEDIUM, inline confirm, metadata audit, "file-operations" family,
    // affects the indexing-jobs Resource.
    assertPolicy(
        byId,
        "core.ingest-files",
        new OperationPolicy(
                RiskTier.MEDIUM,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Set.of(),
                false)
            .withCapabilityFamily("file-operations")
            .withRecordKind(io.justsearch.agent.api.registry.OperationKind.INGEST),
        Set.of(ExecutorTag.AGENT),
        Audience.USER,
        new OperationLineage(Set.of(new ResourceRef("core.indexing-jobs")), Set.of()),
        OperationAvailability.empty(),
        Presentation.forId(new OperationRef("core.ingest-files")));

    // core.file-operations — HIGH, inline confirm, undo SUPPORTED, advisory class set, same family.
    assertPolicy(
        byId,
        "core.file-operations",
        new OperationPolicy(
                RiskTier.HIGH,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Set.of(),
                true,
                Optional.of(new ResourceRef("core.advisory-operation-completed")))
            .withCapabilityFamily("file-operations"),
        Set.of(ExecutorTag.AGENT),
        Audience.USER,
        OperationLineage.empty(),
        OperationAvailability.empty(),
        Presentation.forId(
            new OperationRef("core.file-operations"),
            Optional.of("warning"),
            Optional.of("destructive")));

    // core.navigate-to-surface — the one entry carrying the UI executor as well as AGENT.
    assertPolicy(
        byId,
        "core.navigate-to-surface",
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT),
        Audience.USER,
        OperationLineage.empty(),
        OperationAvailability.empty(),
        Presentation.forId(new OperationRef("core.navigate-to-surface")));

    // core.remember — LOW, no confirm, no lineage (memory has no ResourceRef).
    assertPolicy(
        byId,
        "core.remember",
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        Set.of(ExecutorTag.AGENT),
        Audience.USER,
        OperationLineage.empty(),
        OperationAvailability.empty(),
        new Presentation(
            new I18nKey("ops.remember.label"),
            new I18nKey("ops.remember.description"),
            Optional.empty(),
            Optional.empty()));
  }

  /**
   * Availability gated on the index serving: {@code Not(ConditionMatches("index.unavailable"))}.
   *
   * <p>Asserted as a VALUE, not as {@code expression().isPresent()}. Presence is one boolean short of
   * covering the axis that decides whether the delegate is offered its two read tools: dropping the
   * {@code Not} keeps presence true and inverts the meaning — search and read-document would be
   * offered only while the index is DOWN and withheld while it serves. {@code AvailabilityExpression}
   * variants are records, so equality here pins the whole tree.
   */
  private static final OperationAvailability GATED_ON_INDEX_SERVING =
      new OperationAvailability(
          Optional.of(
              new AvailabilityExpression.Not(
                  new AvailabilityExpression.ConditionMatches("index.unavailable"))),
          Optional.empty());

  private static void assertPolicy(
      Map<String, Operation> byId,
      String id,
      OperationPolicy expectedPolicy,
      Set<ExecutorTag> expectedExecutors,
      Audience expectedAudience,
      OperationLineage expectedLineage,
      OperationAvailability expectedAvailability,
      Presentation expectedPresentation) {
    Operation op = byId.get(id);
    Objects.requireNonNull(op, id + " missing from AgentToolsOperationCatalog");
    assertEquals(expectedPolicy, op.policy(), id + ": declared policy changed");
    assertEquals(expectedExecutors, op.executors(), id + ": executor tags changed");
    assertEquals(expectedAudience, op.audience(), id + ": audience changed");
    assertEquals(expectedLineage, op.lineage(), id + ": lineage changed");
    assertEquals(expectedAvailability, op.availability(), id + ": declared availability changed");
    // The wire projection carries only the description KEY, so the label key and the confirm/danger
    // presentation hints reach no other assertion in the tree. Losing `warning`/`destructive` off the
    // one HIGH-risk destructive tool would otherwise be invisible.
    assertEquals(expectedPresentation, op.presentation(), id + ": presentation changed");
    assertEquals(
        Binding.of(new OperationRef(id)), op.binding(), id + ": binding changed");
    assertEquals(Provenance.core("1.0"), op.provenance(), id + ": provenance changed");
  }

  /** LF line endings, exactly one trailing newline — so the baseline is a platform-neutral file. */
  private static String normalizeEol(String s) {
    return s.replace("\r\n", "\n").stripTrailing() + "\n";
  }

  private static String readBaseline() throws IOException {
    try (InputStream is =
        AgentToolCatalogBaselineTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
      Objects.requireNonNull(is, BASELINE_RESOURCE + " not on test classpath");
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Writes the current projection next to the build reports so a deliberate update is a copy. */
  private static Path writeActual(String actual) throws IOException {
    Path dir = Path.of("build", "reports", "agent-tool-baseline");
    Files.createDirectories(dir);
    Path dump = dir.resolve("agent-tools-wire-baseline.actual.json");
    Files.writeString(dump, actual, StandardCharsets.UTF_8);
    return dump.toAbsolutePath();
  }
}
