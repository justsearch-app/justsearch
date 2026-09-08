package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.inference.BrainSupervisionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces the declared supervision/crash-recovery contract (tempdoc 627): the single authority
 * {@code governance/supervision-contract.v1.json} must stay in sync with the live policy records and
 * with the tests that guard each fault-matrix row. This is what makes the register a real contract
 * rather than documentation — three checks fail the build:
 *
 * <ol>
 *   <li><b>Policy drift</b>: each process's declared {@code policy} block must equal the live policy
 *       record defaults ({@link BrainSupervisionPolicy} for the Brain; {@code SupervisionPolicy} for
 *       the Worker until lane F stage A item A11 deleted it). A constant changed in code without the
 *       register fails here, and vice versa. A <b>retired</b> entry has no live record left, so this
 *       check is replaced for it by {@link #workerEntryIsRetired()} — see that method.</li>
 *   <li><b>Matrix completeness</b>: every declared fault mode is in the known vocabulary, no process
 *       repeats a mode, and the union of both processes' modes covers the full vocabulary. Retired
 *       entries are included: the vocabulary is what stage B will be declared against.</li>
 *   <li><b>Guard resolution</b>: every fault mode names at least one guard, and each guard is either an
 *       allowed sentinel ({@code dev-stack-smoke} / {@code audit-verdict}) or an FQCN that resolves to a
 *       real test file — so no row is silently un-guarded. Retired entries are <b>skipped</b>: their
 *       guards were deleted with the code they pinned, so resolving them would fail the build for the
 *       retirement itself. The register's {@code guardsNote} records which of those FQCNs are gone.</li>
 * </ol>
 */
@DisplayName("supervision contract: register <-> live policies <-> guards")
class SupervisionContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve("governance/supervision-contract.v1.json"))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException(
        "repo root with governance/supervision-contract.v1.json not found from "
            + Paths.get("").toAbsolutePath());
  }

  private static JsonNode register() throws IOException {
    return MAPPER.readTree(
        repoRoot().resolve("governance/supervision-contract.v1.json").toFile());
  }

  private static JsonNode process(JsonNode reg, String id) {
    for (JsonNode p : reg.get("processes")) {
      if (id.equals(p.get("id").asText())) {
        return p;
      }
    }
    throw new IllegalStateException("process not in register: " + id);
  }

  // --- (1) policy drift -----------------------------------------------------------------------

  /**
   * Lane F stage A item A11 retired the Worker entry: {@code SupervisionPolicy} was deleted with
   * {@code WorkerSpawner}, so there is no live record to compare the declared block against, and
   * {@link #everyGuardResolves()} skips the entry for the same reason (its guards went too).
   *
   * <p>The assertion is not dropped, it is <b>inverted into a pin on the retirement</b>: a retired
   * entry must say so explicitly, and an entry that is NOT retired must still have its policy
   * checked against code (which is what {@link #brainPolicyMatchesCode()} does, unchanged). Without
   * this, deleting the equality check would leave the register free to drift back into claiming a
   * contract nothing enforces — the exact vacuous green the register exists to prevent.
   */
  @Test
  @DisplayName("the worker entry is retired, and a retired entry declares why")
  void workerEntryIsRetired() throws IOException {
    JsonNode worker = process(register(), "worker");
    assertEquals(
        "retired",
        worker.path("status").asText(),
        "the Worker is not a process any more (lane F stage A item A11); if this entry is live"
            + " again, its policy block needs a live record to be checked against");
    assertTrue(
        worker.path("retiredNote").asText().contains("stage B"),
        "a retired entry must name where the contract lands next, or the register loses the thread");
  }

  /**
   * The complement: every entry that is NOT retired has its declared policy block checked against
   * code somewhere. This test fails if a process is added without one, so the drift check cannot be
   * skipped by omission.
   *
   * <p><b>Two shapes are accepted, and the second is not a loosening.</b> The Brain's check is
   * {@link #brainPolicyMatchesCode()}, in this class. The Engine's cannot be: lane F stage B item
   * B7 puts its mirror in {@code app-engine}, and the module edge runs {@code app-engine ->
   * app-services}, so importing it here would invert the dependency for a test. A live row may
   * therefore instead <em>name</em> its drift check in a {@code driftCheck} field, and the name has
   * to resolve to a real test file — the same resolution the guards get. What is not accepted is a
   * live row with neither, which is the only case this test ever existed to catch.
   */
  @Test
  @DisplayName("every live (non-retired) process has a policy-drift check, here or named")
  void everyLiveProcessHasADriftCheck() throws IOException {
    Set<String> checkedInThisClass = Set.of("brain");
    Path repo = repoRoot();
    Set<String> live = new HashSet<>();
    List<String> failures = new ArrayList<>();
    for (JsonNode proc : register().get("processes")) {
      if ("retired".equals(proc.path("status").asText())) {
        continue;
      }
      String id = proc.get("id").asText();
      live.add(id);
      if (checkedInThisClass.contains(id)) {
        continue;
      }
      String named = proc.path("driftCheck").asText("");
      if (named.isBlank()) {
        failures.add(
            id
                + ": live process with no policy-drift check. Add one to this class and list the id"
                + " in checkedInThisClass, or name it in the row's `driftCheck` field.");
      } else if (!testFileExists(repo, named)) {
        failures.add(id + ": driftCheck does not resolve to a test file: " + named);
      }
    }
    assertEquals(
        Set.of("brain", "engine"),
        live,
        "the live process set changed. That is allowed, but not silently: every id here must have a"
            + " drift check, and the set is pinned so a row cannot appear (or vanish) unremarked.");
    if (!failures.isEmpty()) {
      fail("supervision-contract drift-check failures:\n  " + String.join("\n  ", failures));
    }
  }

  @Test
  @DisplayName("brain policy block equals BrainSupervisionPolicy declared defaults")
  void brainPolicyMatchesCode() throws IOException {
    JsonNode pol = process(register(), "brain").get("policy");
    // Assert against the DEFAULT_* constants — the shipped default contract the register declares —
    // NOT BrainSupervisionPolicy.defaults(), whose healthCheckTimeoutMs reads the
    // justsearch.inference.health_check_timeout_ms sysprop. Asserting against defaults() would make
    // this drift check fail for the wrong reason if any JVM-shared test (or eval mode) set that sysprop.
    assertEquals(BrainSupervisionPolicy.DEFAULT_MAX_CRASHES, pol.get("maxCrashes").asInt());
    assertEquals(
        BrainSupervisionPolicy.DEFAULT_CRASH_RECOVERY_DELAY_MS,
        pol.get("crashRecoveryDelayMs").longValue());
    assertEquals(
        BrainSupervisionPolicy.DEFAULT_CONSECUTIVE_FAILURES_BEFORE_RESTART,
        pol.get("consecutiveFailuresBeforeRestart").asInt());
    assertEquals(
        BrainSupervisionPolicy.DEFAULT_PERIODIC_HEALTH_INTERVAL_MS,
        pol.get("periodicHealthIntervalMs").longValue());
    assertEquals(
        BrainSupervisionPolicy.DEFAULT_HEALTH_CHECK_TIMEOUT_MS,
        pol.get("healthCheckTimeoutMs").longValue());
  }

  // --- (2) matrix completeness ----------------------------------------------------------------

  @Test
  @DisplayName("every declared mode is in vocabulary, no dups, union covers the full matrix")
  void matrixIsComplete() throws IOException {
    JsonNode reg = register();
    Set<String> vocab = new HashSet<>();
    reg.get("faultModes").forEach(n -> vocab.add(n.asText()));
    assertTrue(vocab.size() >= 5, "fault-mode vocabulary should be non-trivial");

    Set<String> union = new HashSet<>();
    for (JsonNode proc : reg.get("processes")) {
      // A retired process's fault modes are history: the guards that exercised them were deleted
      // with the process. They still have to be IN the vocabulary (checked below), because the
      // vocabulary is what stage B's supervisor will be declared against.
      Set<String> seen = new HashSet<>();
      for (JsonNode fm : proc.get("faultModes")) {
        String mode = fm.get("mode").asText();
        assertTrue(vocab.contains(mode),
            proc.get("id").asText() + " declares unknown mode: " + mode);
        assertTrue(seen.add(mode),
            proc.get("id").asText() + " repeats mode: " + mode);
        union.add(mode);
      }
    }
    assertEquals(vocab, union, "union of per-process modes must cover the full vocabulary");
  }

  // --- (3) guard resolution -------------------------------------------------------------------

  @Test
  @DisplayName("every fault mode has at least one guard, and every guard resolves")
  void everyGuardResolves() throws IOException {
    JsonNode reg = register();
    Path repo = repoRoot();
    Set<String> sentinels = new HashSet<>();
    reg.get("guardSentinels").forEach(n -> sentinels.add(n.asText()));

    List<String> failures = new ArrayList<>();
    for (JsonNode proc : reg.get("processes")) {
      String pid = proc.get("id").asText();
      if ("retired".equals(proc.path("status").asText())) {
        continue;
      }
      for (JsonNode fm : proc.get("faultModes")) {
        String mode = fm.get("mode").asText();
        JsonNode guards = fm.get("guards");
        if (guards == null || !guards.isArray() || guards.isEmpty()) {
          failures.add(pid + "/" + mode + ": no guards declared");
          continue;
        }
        for (JsonNode g : guards) {
          String guard = g.asText();
          if (!sentinels.contains(guard) && !testFileExists(repo, guard)) {
            failures.add(pid + "/" + mode + ": guard does not resolve: " + guard);
          }
        }
      }
    }
    if (!failures.isEmpty()) {
      fail("supervision-contract guard resolution failures:\n  " + String.join("\n  ", failures));
    }
  }

  // --- (4) tempdoc 630's liveness-continuity clause survives the A11 retirement ------------------

  /**
   * The Head/Worker suicide pact is GONE. Lane F stage A item A11 merged the Worker into the Head
   * JVM, so there is exactly one process and nothing left for it to die with: {@code
   * MmfWorkerSignalBus.shouldDie} (the pact's only production reader) and {@code
   * WorkerLivenessDecision} were deleted with it, and the register's {@code worker} entry is now
   * {@code status: "retired"} with every fault mode marked {@code (historical, A11)}. No running
   * code reads a heartbeat to decide whether to self-exit any more.
   *
   * <p>What this test asserts is therefore a record-keeping property of that RETIRED row, not a
   * live mechanism: the {@code zombie} fault mode must still carry a non-blank {@code
   * livenessContinuity} clause. The row was retired-in-place rather than deleted so the register
   * still records what the two-process split bought, and tempdoc 630's hardest-won detail is
   * exactly the part a stage-B Engine supervisor needs to inherit: a stale beat ALONE must never be
   * read as a dead peer, because an OS suspend/resume produces one benignly. Blanking the clause
   * while retiring the row would lose that silently; this fails the build instead.
   */
  @Test
  @DisplayName("retired worker/zombie row keeps its livenessContinuity clause (tempdoc 630)")
  void zombieDeclaresLivenessContinuity() throws IOException {
    JsonNode worker = process(register(), "worker");
    JsonNode zombie = null;
    for (JsonNode fm : worker.get("faultModes")) {
      if ("zombie".equals(fm.get("mode").asText())) {
        zombie = fm;
        break;
      }
    }
    assertTrue(zombie != null, "worker must declare a zombie fault mode");
    JsonNode lc = zombie.get("livenessContinuity");
    assertTrue(
        lc != null && !lc.asText().isBlank(),
        "the retired worker/zombie row must KEEP its livenessContinuity clause (tempdoc 630). "
            + "The heartbeat suicide-pact itself went with the Worker process at A11 — one JVM "
            + "cannot die with itself — but the record of how a benign OS-resume stale beat was "
            + "corroborated against peer liveness, rather than misread as a peer death, must "
            + "survive the retirement for stage B's Engine supervisor to inherit");
  }

  /**
   * True if {@code guard} names a real file: either a repo-relative path (it contains a {@code /})
   * or an FQCN resolving to a *.java under any module's test/integrationTest/systemTest.
   *
   * <p><b>Why paths are accepted at all</b> (lane F stage B item B7): the Engine supervisor has two
   * implementations and neither is a JVM, so the tests that exercise its fault-matrix rows are a
   * Node conformance harness and a Rust {@code #[cfg(test)]} table. An FQCN-only resolver would have
   * left every engine row on a sentinel — that is, unguarded with a note saying so — which is the
   * outcome the resolution check exists to prevent. A path still has to EXIST, so the register can
   * no more name a Node file that is not there than it can name a missing Java class.
   */
  private static boolean testFileExists(Path repo, String guard) {
    if (guard.contains("/")) {
      return Files.exists(repo.resolve(guard));
    }
    String suffix = guard.replace('.', '/') + ".java";
    Path modules = repo.resolve("modules");
    if (!Files.isDirectory(modules)) {
      return false;
    }
    try (Stream<Path> moduleDirs = Files.list(modules)) {
      return moduleDirs
          .filter(Files::isDirectory)
          .anyMatch(
              md -> {
                for (String ss : List.of("test", "integrationTest", "systemTest")) {
                  if (Files.exists(md.resolve("src/" + ss + "/java/" + suffix))) {
                    return true;
                  }
                }
                return false;
              });
    } catch (IOException e) {
      return false;
    }
  }
}
