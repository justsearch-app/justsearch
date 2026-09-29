package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.WorkflowRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 560 WS5 — the workflow → agent-tool Operation projection + ref bijection; tempdoc 876 B.4 —
 * a projected operation inherits the availability of what it composes (and is not projected at all
 * when what it composes is absent).
 */
final class WorkflowOperationProjectionTest {

  private static final OperationRef MCP_ADD = new OperationRef("vendor.mcphost.reference-add");
  private static final OperationRef MCP_GET_IMAGE =
      new OperationRef("vendor.mcphost.reference-get-image");
  private static final OperationRef RESEARCH_BRIEF_OP =
      new OperationRef("core.workflow-research-brief");
  private static final OperationRef DEMO_OP = new OperationRef("core.workflow-demo-compose");

  /** A minimal referenced operation carrying {@code expression} (null → no expression declared). */
  private static Operation stubOp(OperationRef id, AvailabilityExpression expression) {
    return new Operation(
        id,
        Presentation.of(new I18nKey("stub.label"), new I18nKey("stub.description")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        new OperationAvailability(Optional.ofNullable(expression), Optional.empty()),
        OperationLineage.empty(),
        Binding.of(id),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  /**
   * A referenced operation that declares a {@link RequiredCapability} but NO availability
   * expression — the shape {@code CapabilityAvailability.withCapabilityDerivedAvailability} would
   * normally fill in later, over the composed set.
   */
  private static Operation stubOpRequiring(OperationRef id, RequiredCapability capability) {
    return new Operation(
        id,
        Presentation.of(new I18nKey("stub.label"), new I18nKey("stub.description")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(capability),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Optional<Operation> byId(List<Operation> ops, OperationRef id) {
    return ops.stream().filter(op -> op.id().equals(id)).findFirst();
  }

  @Test
  void aWorkflowComposingAnAbsentOperationIsNotProjected() {
    // The default install: no MCP reference server, so vendor.mcphost.* is not in the registry.
    List<Operation> ops =
        WorkflowOperationProjection.project(CoreWorkflowCatalog.catalog(), List.of());

    assertTrue(
        byId(ops, RESEARCH_BRIEF_OP).isPresent(),
        "research-brief is two LlmSteps — no operation dependency, so it is always projected");
    assertTrue(
        byId(ops, DEMO_OP).isEmpty(),
        "demo-compose composes vendor.mcphost.* ToolSteps that do not exist here — it must not be"
            + " offered as an agent tool");
  }

  @Test
  void aWorkflowWithNoToolStepsKeepsTheEmptyAvailability() {
    List<Operation> ops =
        WorkflowOperationProjection.project(CoreWorkflowCatalog.catalog(), List.of());
    Operation researchBrief = byId(ops, RESEARCH_BRIEF_OP).orElseThrow();
    assertEquals(OperationAvailability.empty(), researchBrief.availability());
  }

  @Test
  void projectsEachWorkflowToAnAgentFacingOperationWhenTheComposedOperationsExist() {
    List<Operation> ops =
        WorkflowOperationProjection.project(
            CoreWorkflowCatalog.catalog(),
            List.of(stubOp(MCP_ADD, null), stubOp(MCP_GET_IMAGE, null)));

    assertTrue(byId(ops, RESEARCH_BRIEF_OP).isPresent());
    Operation demo =
        byId(ops, DEMO_OP)
            .orElseThrow(() -> new AssertionError("demo-compose workflow was not projected"));
    // Agent-only: never reaches the UI registry path; AGENT audience so the agent loop sees it.
    assertTrue(demo.executors().contains(ExecutorTag.AGENT));
    assertFalse(demo.executors().contains(ExecutorTag.UI), "workflow tools are agent-only");
    assertEquals(Audience.AGENT, demo.audience());
    // NonEmpty<ConsumerHook> carried over from the Workflow (no synthetic hook needed).
    assertFalse(demo.consumers().isEmpty());
    // Neither composed op declares an expression, so the projection stays unconditionally available.
    assertEquals(OperationAvailability.empty(), demo.availability());
  }

  @Test
  void projectedAvailabilityIsTheConjunctionOfTheComposedOperations() {
    AvailabilityExpression addExpr =
        new AvailabilityExpression.Not(new AvailabilityExpression.ConditionMatches("x"));
    AvailabilityExpression imageExpr = new AvailabilityExpression.ConditionMatches("y");
    List<Operation> ops =
        WorkflowOperationProjection.project(
            CoreWorkflowCatalog.catalog(),
            List.of(stubOp(MCP_ADD, addExpr), stubOp(MCP_GET_IMAGE, imageExpr)));

    Operation demo = byId(ops, DEMO_OP).orElseThrow();
    assertEquals(
        Optional.of(new AvailabilityExpression.AllOf(List.of(addExpr, imageExpr))),
        demo.availability().expression(),
        "the projected op conjoins its ToolStep operations' availability, in node order");
  }

  @Test
  void aComposedOperationWithoutAnExpressionContributesNothingToTheConjunction() {
    AvailabilityExpression addExpr =
        new AvailabilityExpression.Not(new AvailabilityExpression.ConditionMatches("x"));
    List<Operation> ops =
        WorkflowOperationProjection.project(
            CoreWorkflowCatalog.catalog(),
            // get-image declares no availability expression — the conjunction is the add op's alone.
            List.of(stubOp(MCP_ADD, addExpr), stubOp(MCP_GET_IMAGE, null)));

    Operation demo = byId(ops, DEMO_OP).orElseThrow();
    assertEquals(
        Optional.of(addExpr),
        demo.availability().expression(),
        "a single surviving expression is carried through as-is, not wrapped in a 1-element AllOf");
  }

  @Test
  void opRefAndWorkflowRefAreABijection() {
    WorkflowRef workflow = new WorkflowRef("core.demo-compose");
    OperationRef op = WorkflowOperationProjection.opRefFor(workflow);
    assertEquals(new OperationRef("core.workflow-demo-compose"), op);
    assertEquals(workflow, WorkflowOperationProjection.workflowRefFor(op).orElseThrow());
  }

  @Test
  void workflowRefForReturnsEmptyOnANonWorkflowOperation() {
    assertTrue(
        WorkflowOperationProjection.workflowRefFor(new OperationRef("core.recover-component")).isEmpty(),
        "a normal operation ref is not a projected workflow");
  }

  @Test
  void aComposedOperationsRequiredCapabilityIsInheritedAsAvailability() {
    // Tempdoc 876 C.4 finding 3. A composed op may gate on a CAPABILITY rather than on a hand-written
    // expression. Relying on the later withCapabilityDerivedAvailability pass cannot cover this: that
    // pass fills only ops with NO expression, so a projection already carrying a sibling's explicit
    // expression would silently never receive this one's capability gate — and the workflow would be
    // offered while the operation it runs is capability-blocked.
    List<Operation> known =
        List.of(
            stubOp(MCP_ADD, new AvailabilityExpression.ConditionMatches("index.unavailable")),
            stubOpRequiring(MCP_GET_IMAGE, new RequiredCapability.WorkerOnline()));

    List<Operation> projected =
        WorkflowOperationProjection.project(CoreWorkflowCatalog.catalog(), known);

    Operation demo =
        byId(projected, DEMO_OP).orElseThrow(() -> new AssertionError("demo-compose must project"));
    AvailabilityExpression expression =
        demo.availability()
            .expression()
            .orElseThrow(() -> new AssertionError("expected a conjoined availability expression"));

    assertTrue(
        expression instanceof AvailabilityExpression.AllOf,
        "two gated steps must conjoin into an AllOf; got " + expression);
    List<AvailabilityExpression> terms = ((AvailabilityExpression.AllOf) expression).children();
    assertEquals(2, terms.size(), "both the explicit and the capability-derived term must survive");
    assertTrue(
        terms.contains(new AvailabilityExpression.ConditionMatches("index.unavailable")),
        "the sibling's EXPLICIT expression must survive; got " + terms);
    assertTrue(
        terms.contains(
            new AvailabilityExpression.Not(
                new AvailabilityExpression.ConditionMatches("worker.capability"))),
        "the CAPABILITY-derived term must be inherited too — this is the term that was dropped"
            + " before 876 C.4 finding 3; got "
            + terms);
  }
}
