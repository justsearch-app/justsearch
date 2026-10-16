/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.ChildSpec.CooperativeStop;
import io.justsearch.app.api.runtime.child.ChildSpec.Stdio;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The process-ownership vocabulary is the single wire authority (reconciliation section 3): exact
 * members, exact wire casing, and the invariants of its records.
 */
@DisplayName("runtime.child vocabulary")
final class ChildControlVocabularyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("every enum has exactly the reconciled members, in order")
  void exactMembers() {
    assertEquals(
        List.of("QUIT", "UPGRADE", "ENGINE_RESTART", "DEVICE_LOST", "RECONFIGURE", "MODEL_SWITCH",
            "DEACTIVATE", "RESIDENCY_PRESSURE", "IDLE_UNLOAD", "VDU_ENTER", "VDU_EXIT",
            "SELF_TEST", "TIMEOUT", "ROLLBACK", "CRASH_RECOVERY", "RECYCLE", "CANCELLED"),
        names(StopReason.class));
    assertEquals(List.of("COOPERATIVE", "NOW"), names(StopMode.class));
    assertEquals(List.of("REQUESTED", "UNEXPECTED", "OWNER_DIED", "UNKNOWN"),
        names(ExitClass.class));
    assertEquals(List.of("OWNER", "SELF"), names(RequestedBy.class));
    assertEquals(List.of("LLAMA_SERVER", "LLAMA_SELF_TEST", "EXTRACTION"),
        names(ChildKind.class));
    assertEquals(
        List.of("request_budget", "protocol", "hang", "start", "request", "escalated",
            "bulk_window", "gpu_busy", "game_mode", "session_end", "takeover", "interrupted",
            "unknown"),
        Arrays.stream(StopDetail.values()).map(StopDetail::token).toList());
  }

  @Test
  @DisplayName("wire casing: UPPER names, lower-case detail tokens, round-trip")
  void wireCasing() throws Exception {
    assertEquals("\"ENGINE_RESTART\"", MAPPER.writeValueAsString(StopReason.ENGINE_RESTART));
    assertEquals("\"COOPERATIVE\"", MAPPER.writeValueAsString(StopMode.COOPERATIVE));
    assertEquals("\"OWNER_DIED\"", MAPPER.writeValueAsString(ExitClass.OWNER_DIED));
    assertEquals("\"request_budget\"", MAPPER.writeValueAsString(StopDetail.REQUEST_BUDGET));
    for (StopDetail detail : StopDetail.values()) {
      String json = MAPPER.writeValueAsString(detail);
      assertEquals(detail, MAPPER.readValue(json, StopDetail.class));
      assertEquals(Optional.of(detail), StopDetail.fromToken(detail.token()));
    }
    assertEquals(Optional.empty(), StopDetail.fromToken("REQUEST_BUDGET"), "tokens are exact");
    assertEquals(Optional.empty(), StopDetail.fromToken("something_else"));
    assertEquals(Optional.empty(), StopDetail.fromToken(null));
  }

  @Test
  @DisplayName("exactly QUIT, UPGRADE, ENGINE_RESTART and DEVICE_LOST end the Engine")
  void endsEngine() {
    Set<StopReason> ending =
        Arrays.stream(StopReason.values()).filter(StopReason::endsEngine).collect(
            Collectors.toSet());
    assertEquals(
        Set.of(StopReason.QUIT, StopReason.UPGRADE, StopReason.ENGINE_RESTART,
            StopReason.DEVICE_LOST),
        ending);
  }

  @Test
  @DisplayName("exit class vs the supervision contract's restart class: only REQUESTED is shared")
  void exitClassAlignsWithSupervisionContract() throws Exception {
    JsonNode contract = MAPPER.readTree(
        Files.readString(repoRoot().resolve("governance/supervision-contract.v1.json")));
    JsonNode engine = null;
    for (JsonNode process : contract.get("processes")) {
      if ("engine".equals(process.get("id").asText())) engine = process;
    }
    Set<String> restartClasses = new HashSet<>();
    for (JsonNode code : engine.get("exitCodes")) restartClasses.add(code.get("class").asText());
    restartClasses.add(engine.get("unknownExitClass").asText());

    Set<String> shared = new HashSet<>(names(ExitClass.class));
    shared.retainAll(restartClasses);
    assertEquals(Set.of("REQUESTED"), shared,
        "an exit-class name may only reuse a restart-class name when it means the same thing");
    // The conformance cases' requested reasons are the Engine-ending stop reasons, in lower case.
    Set<String> requested = new HashSet<>();
    for (JsonNode c : engine.get("conformanceCases")) {
      JsonNode reason = c.path("observation").path("requestedReason");
      if (reason.isString()) requested.add(reason.asText());
    }
    for (String reason : requested) {
      boolean known = Arrays.stream(StopReason.values())
          .anyMatch(r -> r.endsEngine() && r.name().toLowerCase().endsWith(reason))
          || StopDetail.fromToken(reason).isPresent();
      assertTrue(known, "conformance requestedReason '" + reason + "' has no stop vocabulary");
    }
  }

  @Test
  @DisplayName("ChildSpec copies its collections and rejects a path label or an empty argv")
  void childSpecValidation() {
    var argv = new java.util.ArrayList<>(List.of("llama-server.exe", "--port", "0"));
    ChildSpec spec = spec(argv, "llama-main");
    argv.clear();
    assertEquals(3, spec.argv().size(), "argv is copied");

    assertThrows(IllegalArgumentException.class, () -> spec(List.of(), "x"));
    assertThrows(IllegalArgumentException.class, () -> spec(List.of("a"), "C:\\models\\x"));
    assertThrows(IllegalArgumentException.class, () -> spec(List.of("a"), "a/b"));
    assertThrows(IllegalArgumentException.class, () -> spec(List.of("a"), "x".repeat(65)));
    assertThrows(IllegalArgumentException.class, () -> spec(List.of("a"), " "));
  }

  @Test
  @DisplayName("CooperativeStop: NONE has no grace, and grace is never negative")
  void cooperativeStopValidation() {
    assertEquals(Duration.ZERO, CooperativeStop.NONE.grace());
    var close = new CooperativeStop(
        CooperativeStop.Action.CLOSE_STDIN, Duration.ofSeconds(2), Set.of(0));
    assertEquals(Set.of(0), close.expectedCodes());
    assertThrows(IllegalArgumentException.class,
        () -> new CooperativeStop(CooperativeStop.Action.NONE, Duration.ofSeconds(1), Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new CooperativeStop(
            CooperativeStop.Action.CLOSE_STDIN, Duration.ofSeconds(-1), Set.of()));
  }

  @Test
  @DisplayName("TerminateResult carries an error exactly when FAILED, with the ledger wire form")
  void terminateResult() {
    assertEquals("NOT_CALLED", TerminateResult.NOT_CALLED.wireValue());
    assertEquals("ALREADY_EXITED", TerminateResult.ALREADY_EXITED.wireValue());
    assertEquals("FAILED:5", TerminateResult.failed(5).wireValue());
    assertThrows(IllegalArgumentException.class,
        () -> new TerminateResult(TerminateResult.Status.FAILED, OptionalInt.empty()));
    assertThrows(IllegalArgumentException.class,
        () -> new TerminateResult(TerminateResult.Status.OK, OptionalInt.of(5)));
  }

  @Test
  @DisplayName("ExitOutcome: a REQUESTED exit names its reason; terminate time pairs with the call")
  void exitOutcomeValidation() {
    Instant t = Instant.parse("2026-10-05T10:00:00Z");
    ExitOutcome requested = outcome(ExitClass.REQUESTED, Optional.of(StopReason.RECYCLE),
        Optional.of(t), TerminateResult.OK);
    assertEquals(ExitClass.REQUESTED, requested.exitClass());
    ExitOutcome crash = outcome(ExitClass.UNEXPECTED, Optional.empty(), Optional.empty(),
        TerminateResult.NOT_CALLED);
    assertFalse(crash.forced());

    assertThrows(IllegalArgumentException.class, () -> outcome(ExitClass.REQUESTED,
        Optional.empty(), Optional.of(t), TerminateResult.OK));
    assertThrows(IllegalArgumentException.class, () -> outcome(ExitClass.UNEXPECTED,
        Optional.empty(), Optional.of(t), TerminateResult.NOT_CALLED));
    assertThrows(IllegalArgumentException.class, () -> outcome(ExitClass.REQUESTED,
        Optional.of(StopReason.QUIT), Optional.empty(), TerminateResult.OK));
  }

  @Test
  @DisplayName("ChildSurvivedException names the survivor")
  void survivedException() {
    var view = new ChildView("c-1", ChildKind.EXTRACTION, 42L, Instant.EPOCH,
        ChildHandle.State.SURVIVED, false, "extraction-1");
    var e = new ChildSurvivedException(view);
    assertEquals(view, e.child());
    assertTrue(e.getMessage().contains("pid 42"));
    assertThrows(IllegalArgumentException.class, () -> new ChildView("c", ChildKind.EXTRACTION,
        0L, Instant.EPOCH, ChildHandle.State.RUNNING, false, "x"));
  }

  private static ExitOutcome outcome(ExitClass exitClass, Optional<StopReason> reason,
      Optional<Instant> terminateCalledAt, TerminateResult result) {
    return new ExitOutcome("c-1", ChildKind.LLAMA_SERVER, exitClass, reason, Optional.empty(),
        reason.map(r -> StopMode.NOW), OptionalInt.of(0), Optional.empty(), Instant.EPOCH,
        reason.map(r -> Instant.EPOCH), terminateCalledAt, result, false, ExitOutcome.Phase.RUNNING,
        true);
  }

  private static ChildSpec spec(List<String> argv, String label) {
    return new ChildSpec(ChildKind.LLAMA_SERVER, argv, Path.of("."), Map.of(), new Stdio.Pipes(),
        true, OptionalLong.empty(), Optional.empty(), CooperativeStop.NONE, label);
  }

  private static List<String> names(Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
  }

  static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("governance"))) dir = dir.getParent();
    if (dir == null) throw new IllegalStateException("repository root not found");
    return dir;
  }
}
