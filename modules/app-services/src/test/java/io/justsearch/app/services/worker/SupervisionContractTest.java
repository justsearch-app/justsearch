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
   * the live record. Today that is the Brain, whose check is {@link #brainPolicyMatchesCode()};
   * this test fails if a third process is added without one, so the drift check cannot be skipped
   * by omission.
   */
  @Test
  @DisplayName("every live (non-retired) process has a policy-drift check in this class")
  void everyLiveProcessHasADriftCheck() throws IOException {
    Set<String> live = new HashSet<>();
    for (JsonNode proc : register().get("processes")) {
      if (!"retired".equals(proc.path("status").asText())) {
        live.add(proc.get("id").asText());
      }
    }
    assertEquals(
        Set.of("brain"),
        live,
        "a live process without a policy-drift assertion in this class would declare a contract"
            + " nothing enforces; add the assertion, then widen this set");
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

  // --- (4) tempdoc 630: the time-based zombie suicide-pact declares liveness-continuity ----------

  @Test
  @DisplayName("worker zombie fault-mode declares a livenessContinuity clause (tempdoc 630)")
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
        "the zombie suicide-pact is time-based (stale-heartbeat); it must declare a "
            + "livenessContinuity clause (tempdoc 630) describing how a benign OS-resume stale "
            + "beat is corroborated against Head liveness rather than misread as a Head death");
  }

  /** True if {@code fqcn} resolves to a *.java under any module's test/integrationTest/systemTest. */
  private static boolean testFileExists(Path repo, String fqcn) {
    String suffix = fqcn.replace('.', '/') + ".java";
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
