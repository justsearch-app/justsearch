package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.EventDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 865 §7.7 / §8.3 item 6 — the record projection's vocabulary must be EXHAUSTIVE over the
 * wire vocabulary.
 *
 * <p>The asymmetry this closes: every {@code AgentRunShape} kind is born durable on the wire, where
 * {@code AgentEventPayloads}' switches are over a SEALED interface and the compiler forces a
 * decision about each one — and born NON-durable in the record, where {@code
 * AgentInteractionMapper.fromRunEvent} switches over a {@code String} with a {@code default}, so
 * silence is the fallback. {@code budget_raised} was that asymmetry's output, not an oversight.
 *
 * <p>The compiler cannot help on the String switch, so this test is the enforcement: a kind added to
 * {@code AgentRunShape} and claimed by NEITHER list below fails the build, which is the same
 * two-site-agreement shape {@code AgentInteractionMapperTest.everyDeclaredPhaseConstantIsClassified}
 * already uses for the durable-progress phases. Deciding is cheap; the defect this prevents is a
 * kind whose durability nobody ever chose.
 *
 * <p><b>HONEST BOUNDARY — what this does NOT close.</b> It closes "a kind nobody wrote down
 * anywhere": every name {@code AgentRunShape} declares must appear in one of the two lists here, and
 * {@link #declaredNonProjectingNamesAreRealCasesInTheMapper} additionally binds the second list to
 * literal case labels in the mapper's source, so a name cannot be parked here while silently
 * reaching {@code default}. It does NOT verify the mapper's PROJECTING arm case-by-case (that is
 * {@code AgentInteractionMapperTest}'s job, per kind), and the {@code default} arm remains live and
 * necessary — this mapper also serves vocabularies {@code AgentRunShape} does not declare (the
 * workflow node journal, {@code search_executed}), so exhaustiveness here is exhaustiveness over ONE
 * vocabulary, not over the switch.
 */
final class AgentRunDurabilityClosureTest {

  /** Kinds {@code fromRunEvent} projects onto the thread record. */
  private static final Set<String> PROJECTING =
      Set.of(
          "tool_call_proposed",
          "tool_call_pending",
          "tool_exec_started",
          "tool_exec_completed",
          "tool_call_rejected",
          "done",
          "error",
          "progress",
          "handoff_executed",
          "directive_acknowledged");

  /**
   * Kinds {@code fromRunEvent} declares non-projecting, written there as explicit cases. See that
   * switch for the per-group reasons — run plumbing, streamed text the terminal already persists,
   * proposals whose outcome is durable, and live meters whose accountability outcome rides {@code
   * progress} or the terminal disposition.
   */
  private static final Set<String> DECLARED_NON_PROJECTING =
      Set.of(
          "session_started",
          "chunk",
          "reasoning_chunk",
          "tool_batch_proposed",
          "tool_call_approved",
          "tool_call_virtual",
          "handoff_proposed",
          "budget_update",
          "budget_gate",
          "context_gate",
          "context_compacted",
          "state_snapshot",
          "intent.resolution");

  @Test
  @DisplayName("865 §7.7: every AgentRunShape event kind is classified — projected or declared non-projecting")
  void everyWireKindIsClassified() {
    List<EventDescriptor> schema = AgentRunShape.definition().eventSchema();
    assertTrue(schema.size() >= 20, "sanity: the schema was found and is the agent run's");

    var unclassified = new LinkedHashSet<String>();
    for (EventDescriptor descriptor : schema) {
      String name = descriptor.name();
      if (!PROJECTING.contains(name) && !DECLARED_NON_PROJECTING.contains(name)) {
        unclassified.add(name);
      }
    }

    assertEquals(
        Set.of(),
        unclassified,
        "a new agent event kind must DECIDE its record durability. Add a projecting case to"
            + " AgentInteractionMapper.fromRunEvent and list it in PROJECTING, or add it to that"
            + " switch's explicit non-projecting arm and to DECLARED_NON_PROJECTING. Letting it"
            + " reach `default` makes 'nobody decided' indistinguishable from 'someone decided no'"
            + " — the asymmetry that produced budget_raised.");
  }

  @Test
  @DisplayName("865 §7.7: the two classification lists stay disjoint")
  void theListsDoNotOverlap() {
    var overlap = new LinkedHashSet<>(PROJECTING);
    overlap.retainAll(DECLARED_NON_PROJECTING);
    assertEquals(Set.of(), overlap, "a kind cannot be both projected and declared non-projecting");
  }

  /**
   * Tempdoc 865 §7.7 (review F5) — BIND the list to the switch.
   *
   * <p>Without this, {@link #DECLARED_NON_PROJECTING} is a list a future author could extend to
   * silence {@link #everyWireKindIsClassified} while the kind still reached {@code default} — the
   * gate would then assert "someone decided" about a decision nobody wrote down, which is the exact
   * false authority §7.7 exists to remove, relocated one level up.
   *
   * <p>A source-text check because the switch has no runtime representation: {@code fromRunEvent}
   * returning empty is what a {@code default} fall-through looks like too, so behaviour cannot tell
   * the two apart. Only the source can.
   */
  @Test
  @DisplayName("865 §7.7: every declared non-projecting kind is a real case label in the mapper")
  void declaredNonProjectingNamesAreRealCasesInTheMapper() throws IOException {
    String mapper =
        Files.readString(
            repoRoot()
                .resolve(
                    "modules/app-agent/src/main/java/io/justsearch/agent/AgentInteractionMapper.java"),
            StandardCharsets.UTF_8);
    // Only the switch's own arms, scanned by LINE. A substring cut on the first "default ->" in the
    // file finds the one quoted in that switch's own explanatory comment, which silently truncates
    // the region to before the arm being checked — a green-for-the-wrong-reason this test would have
    // shipped with (it failed loudly instead, which is why the scan is by line and not by index).
    var cases = new StringBuilder();
    boolean inSwitch = false;
    for (String line : mapper.split("\r?\n")) {
      String trimmed = line.strip();
      if (trimmed.startsWith("return switch (eventType)")) {
        inSwitch = true;
        continue;
      }
      if (!inSwitch) {
        continue;
      }
      if (trimmed.startsWith("default ->")) {
        break;
      }
      if (!trimmed.startsWith("//") && !trimmed.startsWith("*")) {
        cases.append(line).append('\n');
      }
    }
    assertTrue(inSwitch, "the eventType switch was found");

    var missing = new LinkedHashSet<String>();
    for (String kind : DECLARED_NON_PROJECTING) {
      if (!cases.toString().contains("\"" + kind + "\"")) {
        missing.add(kind);
      }
    }
    assertEquals(
        Set.of(),
        missing,
        "each name here must be a literal case label in AgentInteractionMapper.fromRunEvent's"
            + " switch, ahead of its `default`. Listing a kind here without writing the case makes"
            + " this gate certify a decision nobody took.");
  }

  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve("governance/consult-register.v1.json"))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("repo root not found from " + Paths.get("").toAbsolutePath());
  }
}
