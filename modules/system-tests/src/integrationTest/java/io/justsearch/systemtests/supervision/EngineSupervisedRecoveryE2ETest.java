/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

/** Installed Engine proofs for durable recovery, migration and hostile filesystem survival. */
@Timeout(7 * 60)
final class EngineSupervisedRecoveryE2ETest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void deterministicLockHandleCanCloseAfterIntruderCleanup(@TempDir Path directory)
      throws Exception {
    Path lockPath = directory.resolve("index.index.lock");
    try (var intruder = new FileIntruder(directory)) {
      var hold = intruder.holdExclusive(lockPath);
      intruder.close();
      hold.close();
      hold.close();
      try (var channel = java.nio.channels.FileChannel.open(lockPath,
          java.nio.file.StandardOpenOption.WRITE);
          var reacquired = channel.tryLock()) {
        assertTrue(reacquired != null && reacquired.isValid(),
            "intruder cleanup must release the exact OS lock");
      }
    }
  }

  @Test
  @Tag("ai")
  void ordinaryQueryOnlyInstallerAcquisitionCommitsExactCitationAndSurvivesReboot()
      throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "query-only installer requires retained registry model bytes");
    // The installer's content-addressed candidate suffix plus ORT's optimized cache suffix must
    // stay below the Windows native path limit; this is still a private, unique test directory.
    Path work = repo.resolve("tmp/qi-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      String committed = runInstalledModelScenario(repo, work, "query-role-installer-commit",
          Map.of(), "QUERY_ROLE_INSTALLER_COMMIT_PASS");
      String committedSettings = Files.readString(work.resolve("data/ui/settings.json"));
      String rebooted = runInstalledModelScenario(repo, work, "query-role-installer-boot",
          Map.of(), "QUERY_ROLE_INSTALLER_BOOT_PASS");
      assertEquals(committedSettings, Files.readString(work.resolve("data/ui/settings.json")),
          "reboot changed the installer-committed query identity or witness");
      assertTrue(committed.contains("QUERY_ROLE_INSTALLER_COMMIT_PASS "), committed);
      assertTrue(rebooted.contains("QUERY_ROLE_INSTALLER_BOOT_PASS "), rebooted);
    });
  }

  @Test
  @Tag("ai")
  void queryOnlyCitationCommitSurvivesAnInstalledWorkerReboot() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "query-role installed scenario requires retained citation model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/query-role-commit-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      String committed = runInstalledModelScenario(repo, work, "query-role-commit", Map.of(),
          "QUERY_ROLE_COMMIT_PASS");
      String rebooted = runInstalledModelScenario(repo, work, "query-role-boot", Map.of(),
          "QUERY_ROLE_BOOT_PASS");
      Files.writeString(work.resolve("query-citation-b/tokenizer.json"), "tampered fixture bytes\n");
      String tampered = runInstalledModelScenario(repo, work, "query-role-tampered-boot", Map.of(),
          "QUERY_ROLE_TAMPERED_BOOT_PASS");
      String cleared = runInstalledModelScenario(repo, work, "query-role-clear", Map.of(),
          "QUERY_ROLE_CLEAR_PASS");
      String disabledBoot = runInstalledModelScenario(repo, work, "query-role-disabled-boot", Map.of(),
          "QUERY_ROLE_DISABLED_BOOT_PASS");
      Files.copy(repo.resolve("models/onnx/citation-scorer/tokenizer.json"),
          work.resolve("query-citation-b/tokenizer.json"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      String overrideBoot = runInstalledModelScenario(repo, work, "query-role-override-boot",
          Map.of("JUSTSEARCH_CITATION_SCORER_MODEL_PATH",
              work.resolve("query-citation-b").toString()),
          "QUERY_ROLE_OVERRIDE_BOOT_PASS");
      String invalidOverrideBoot = runInstalledModelScenario(repo, work,
          "query-role-invalid-override-boot",
          Map.of("JUSTSEARCH_CITATION_SCORER_MODEL_PATH",
              work.resolve("missing-query-model").toString()),
          "QUERY_ROLE_INVALID_OVERRIDE_BOOT_PASS");
      assertTrue(committed.contains("QUERY_ROLE_COMMIT_PASS "), committed);
      assertTrue(rebooted.contains("QUERY_ROLE_BOOT_PASS "), rebooted);
      assertTrue(tampered.contains("QUERY_ROLE_TAMPERED_BOOT_PASS "), tampered);
      assertTrue(cleared.contains("QUERY_ROLE_CLEAR_PASS "), cleared);
      assertTrue(disabledBoot.contains("QUERY_ROLE_DISABLED_BOOT_PASS "), disabledBoot);
      assertTrue(overrideBoot.contains("QUERY_ROLE_OVERRIDE_BOOT_PASS "), overrideBoot);
      assertTrue(invalidOverrideBoot.contains("QUERY_ROLE_INVALID_OVERRIDE_BOOT_PASS "),
          invalidOverrideBoot);
    });
  }

  @Test
  @Tag("ai")
  void queryOnlySettingsFileCommitBootsBAfterAPrepublicationEngineKill() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "query-role crash scenario requires retained citation model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/query-role-after-file-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      String output = runInstalledModelScenario(repo, work, "query-role-after-file-crash",
          Map.of(), "QUERY_ROLE_AFTER_FILE_CRASH_PASS");
      assertTrue(output.contains("QUERY_ROLE_AFTER_FILE_CRASH_PASS "), output);
    });
  }

  @Test
  @Tag("ai")
  void issuedACitationFinishesAfterQueryOnlyBPublishesAndThenRetires() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "query-role issued scenario requires retained citation model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/query-role-issued-a-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      String output = runInstalledModelScenario(repo, work, "query-role-issued-a",
          Map.of(), "QUERY_ROLE_ISSUED_A_PASS");
      assertTrue(output.contains("QUERY_ROLE_ISSUED_A_PASS "), output);
      Path runs = work.resolve("state/runs");
      Path run;
      try (var entries = Files.list(runs)) {
        run = entries.filter(Files::isDirectory).findFirst().orElseThrow();
      }
      String engineLog = Files.readString(run.resolve("logs/engine.log"));
      assertTrue(engineLog.contains(
          "Query settings old query set retired after serving leases drained"),
          "A's query owner did not retire after its issued lease completed");
    });
  }

  @Test
  @Tag("ai")
  void queryViewAndRegistryCaptureWaitForTheSamePublication() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "query publication scenario requires retained citation model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/query-role-publication-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      String output = runInstalledModelScenario(repo, work, "query-role-publication-hold",
          Map.of(), "QUERY_ROLE_PUBLICATION_HOLD_PASS");
      assertTrue(output.contains("QUERY_ROLE_PUBLICATION_HOLD_PASS "), output);
    });
  }

  @Test
  @Tag("ai")
  void contractBWithSettingsARebootsTheExactCommittedQueryA() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "query-role contract crash scenario requires retained citation model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/query-role-contract-crash-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "query-role-commit", Map.of(),
          "QUERY_ROLE_COMMIT_PASS");
      String output = runInstalledModelScenario(repo, work, "query-role-contract-b-settings-a",
          Map.of(), "QUERY_ROLE_CONTRACT_B_SETTINGS_A_PASS");
      String line = output.lines()
          .filter(value -> value.startsWith("QUERY_ROLE_CONTRACT_B_SETTINGS_A_PASS "))
          .findFirst().orElseThrow();
      JsonNode proof = MAPPER.readTree(line.substring(
          "QUERY_ROLE_CONTRACT_B_SETTINGS_A_PASS ".length()));
      Instant publishedAt = Instant.ofEpochMilli(proof.path("contractPublishedAtMs").asLong());
      Path run = work.resolve("state/runs").resolve(proof.path("runId").asText());
      String selectedA = work.resolve("query-citation-b/model.onnx").toString();
      String contractB = work.resolve("query-citation-c/model.onnx").toString();
      boolean selectedAfterCrash = false;
      for (String logLine : Files.readAllLines(run.resolve("logs/engine.log"))) {
        JsonNode row = MAPPER.readTree(logLine);
        if (!row.path("message").asText().startsWith("Citation scorer settings selected:")) {
          continue;
        }
        if (OffsetDateTime.parse(row.path("@timestamp").asText()).toInstant()
            .isBefore(publishedAt)) continue;
        String message = row.path("message").asText();
        assertFalse(message.contains(contractB), "successor selected contract B over settings A");
        if (message.contains(selectedA)) selectedAfterCrash = true;
      }
      assertTrue(selectedAfterCrash,
          "successor did not log exact settings A selection after contract B publication");
    });
  }

  static void runSeededBesideSemanticTransition() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-18 BESIDE scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-beside-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b",
          besideModelEnvironment(), "MODEL_LIVE_AB_PASS");
      String line = output.lines().filter(value -> value.startsWith("MODEL_LIVE_AB_PASS "))
          .findFirst().orElseThrow();
      var result = MAPPER.readTree(line.substring("MODEL_LIVE_AB_PASS ".length()));
      var semantic = result.path("semantic");
      assertTrue(output.contains("\"mode\":\"BESIDE\""), output);
      assertTrue(semantic.path("sampledRequests").asInt() > 0, line);
      assertEquals(semantic.path("sampledRequests").asInt(), semantic.path("available").asInt(), line);
      assertEquals(semantic.path("hybridSampled").asInt(), semantic.path("hybridAvailable").asInt(), line);
      assertEquals(0, semantic.path("reloadingRefusals").asInt(), line);
      assertTrue(semantic.has("indexStarting"), line);
      assertEquals(0, semantic.path("indexStarting").asInt(), line);
      assertEquals(0, semantic.path("transport").asInt(), line);
      assertEquals(0, semantic.path("apiOutageWindowMs").asInt(), line);
      assertEquals(0, result.path("restartCount").asInt(), line);
      assertFalse(result.path("instanceId").asText().isBlank(), line);
      System.out.println("LIFECYCLE_BESIDE_AVAILABILITY_PASS §16 " + line);
    });
  }

  static void runSeededInPlaceSemanticTransition() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-16 AI scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-semantic-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b", inPlaceModelEnvironment(),
          "MODEL_LIVE_AB_PASS");
      String line = output.lines().filter(value -> value.startsWith("MODEL_LIVE_AB_PASS "))
          .findFirst().orElseThrow();
      var semantic = MAPPER.readTree(line.substring("MODEL_LIVE_AB_PASS ".length()))
          .path("semantic");
      assertTrue(semantic.path("transitionMs").asLong() > 0, line);
      assertTrue(semantic.path("refusalWindowMs").asLong() > 0, line);
      assertTrue(semantic.path("refusedFraction").asDouble() > 0, line);
      assertTrue(semantic.path("recoveredAfterRefusal").asBoolean(), line);
      assertTrue(output.contains("\"mode\":\"IN_PLACE\""), output);
      System.out.println("LIFECYCLE_SEMANTIC_AVAILABILITY_PASS §16 " + line);
    });
  }

  static void runSeededBesideIssuedSearch() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-12 issued-search scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-issued-search-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b-issued-search",
          besideModelEnvironment(), "MODEL_LIVE_AB_ISSUED_SEARCH_PASS");
      var issued = markerPayload(output, "MODEL_LIVE_AB_ISSUED_SEARCH_PASS");
      var cut = markerPayload(output, "MODEL_LIVE_AB_CUT");
      var terminal = markerPayload(output, "MODEL_LIVE_AB_PASS");
      assertEquals(issued.path("sourceGeneration").asText(),
          issued.path("capturedGeneration").asText(), output);
      assertEquals(issued.path("promotedGeneration").asText(),
          terminal.path("activeGeneration").asText(), output);
      assertFalse(issued.path("sourceGeneration").asText().equals(
          issued.path("promotedGeneration").asText()), output);
      assertTrue(issued.path("aVectorHits").asInt() > 0, output);
      assertTrue(issued.path("bVectorHitsWhileAHeld").asInt() > 0, output);
      assertBooleanTrue(issued.path("aSearchCompletedAfterB"), output);
      assertEquals("BESIDE", cut.path("mode").asText(), output);
      assertFalse(cut.path("aModel").path("sha256").asText().equals(
          cut.path("bModel").path("sha256").asText()), output);
      assertEquals(0, terminal.path("restartCount").asInt(), output);
      System.out.println("LIFECYCLE_ISSUED_A_SEARCH_PASS §12 " + issued);
    });
  }

  static void runSeededInPlaceAcceptedWriteDuringBuild() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-18 accepted-write scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-accepted-write-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      var environment = new java.util.HashMap<>(inPlaceModelEnvironment());
      environment.put("JUSTSEARCH_WRITER_RECOVERY_ACCEPTED_WRITE", "1");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b", environment,
          "MODEL_LIVE_AB_PASS");
      String line = output.lines().filter(value -> value.startsWith("MODEL_LIVE_AB_PASS "))
          .findFirst().orElseThrow();
      var result = MAPPER.readTree(line.substring("MODEL_LIVE_AB_PASS ".length()));
      assertTrue(output.contains("MODEL_LIVE_AB_ACCEPTED_WRITE "), output);
      assertTrue(output.contains("\"mode\":\"IN_PLACE\""), output);
      assertEquals(0, result.path("restartCount").asInt(), line);
      System.out.println("LIFECYCLE_ACCEPTED_WRITE_PASS §18 " + line);
    });
  }

  static void runSeededInPlaceGapRestoration() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-16 AI scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-gap-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      var environment = new java.util.HashMap<>(inPlaceModelEnvironment());
      environment.put("JUSTSEARCH_RESTORED_A_NATIVE_LEASE_PROBE", "1");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b-gap",
          environment, "MODEL_LIVE_AB_GAP_PASS");
      String line = output.lines().filter(value -> value.startsWith("MODEL_LIVE_AB_GAP_PASS "))
          .findFirst().orElseThrow();
      var result = MAPPER.readTree(line.substring("MODEL_LIVE_AB_GAP_PASS ".length()));
      var citationIdentity = markerPayload(output, "MODEL_LIVE_AB_CITATION_IDENTITY");
      var cudaA = markerPayload(output, "MODEL_LIVE_AB_CUDA_A");
      var nativeLease = markerPayload(output, "MODEL_LIVE_AB_RESTORED_NATIVE_LEASE");
      assertEquals("floor simulated by device-memory cap", result.path("floor").asText(), line);
      assertEquals("PROMOTED_WITH_GAPS", result.path("terminalReason").asText(), line);
      assertTrue(result.path("aVectorHits").asInt() > 0, line);
      assertTrue(result.path("bVectorHits").asInt() > 0, line);
      assertBooleanTrue(result.path("semantic").path("recoveredAfterRefusal"), line);
      assertCitationIdentity(citationIdentity, output);
      assertEquals("cuda", cudaA.path("before").asText(), output);
      assertEquals("cuda", cudaA.path("restored").asText(), output);
      assertBooleanTrue(cudaA.path("availableBefore"), output);
      assertBooleanTrue(cudaA.path("availableRestored"), output);
      assertBooleanTrue(nativeLease.path("ok"), output);
      assertEquals("RETIRING", nativeLease.path("retirementStatus").asText(), output);
      assertTrue(nativeLease.path("inputCount").asInt() > 0, output);
      System.out.println("LIFECYCLE_IN_PLACE_GAP_RESTORATION_PASS §16 " + line);
    });
  }

  static void runSeededInPlaceRecomposeFailureCancellation() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-13/D1-14 recompose failure requires retained CPU A and CUDA B model bytes");
    Path work = repo.resolve(
        "tmp/lane-f-takeover/lifecycle-recompose-failure-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b-recompose-failure",
          inPlaceModelEnvironment(), "MODEL_LIVE_AB_CANCEL_PASS");
      var result = markerPayload(output, "MODEL_LIVE_AB_CANCEL_PASS");
      var cudaA = markerPayload(output, "MODEL_LIVE_AB_CUDA_A");
      var failedRecompose = result.path("failedRecompose");
      var floor = result.path("floorEvidence");
      assertEquals("CANCELLED", result.path("terminalState").asText(), output);
      assertFalse(result.path("sourceGeneration").asText().isBlank(), output);
      assertEquals(result.path("sourceGeneration").asText(),
          result.path("activeGeneration").asText(), output);
      assertEquals("floor simulated by device-memory cap", result.path("floor").asText(), output);
      assertEquals("IN_PLACE", floor.path("mode").asText(), output);
      assertEquals("cuda", cudaA.path("before").asText(), output);
      assertEquals("cuda", cudaA.path("restored").asText(), output);
      assertBooleanTrue(cudaA.path("availableBefore"), output);
      assertBooleanTrue(cudaA.path("availableRestored"), output);
      assertTrue(floor.path("freeBytes").asLong() <= 1024L * 1024L, output);
      assertTrue(floor.path("footprintBytes").asLong() > floor.path("freeBytes").asLong(), output);
      assertEquals("UNAVAILABLE", failedRecompose.path("state").asText(), output);
      assertTrue(failedRecompose.path("recoveryAttempts").asInt() > 0, output);
      assertTrue(failedRecompose.path("evidence").isTextual(), output);
      String evidence = failedRecompose.path("evidence").asText();
      assertTrue(evidence.contains("B refused: Candidate awaits gap acceptance"), output);
      assertTrue(evidence.contains("A recompose refused:"), output);
      var manualRecovery = result.path("manualEncoderRecovery");
      assertEquals("encoders", manualRecovery.path("receipt").path("component").asText(), output);
      assertEquals("ACCEPTED", manualRecovery.path("receipt").path("recovery").asText(), output);
      assertEquals("UNAVAILABLE", manualRecovery.path("before").path("state").asText(), output);
      assertEquals("READY", manualRecovery.path("ready").path("state").asText(), output);
      assertTrue(manualRecovery.path("ready").path("reasonCode").isMissingNode(), output);
      // EngineComponentView omits null fields; READY must carry no stale failure evidence.
      assertTrue(manualRecovery.path("ready").path("evidence").isMissingNode(), output);
      assertEquals(failedRecompose.path("recoveryAttempts").asInt() + 1,
          manualRecovery.path("ready").path("recoveryAttempts").asInt(), output);
      assertTrue(result.path("aVectorHitsDuringWait").asInt() > 0, output);
      assertTrue(result.path("aVectorHitsAfterCancel").asInt() > 0, output);
      System.out.println("LIFECYCLE_RECOMPOSE_FAILURE_CANCEL_PASS §16 " + result);
    });
  }

  static void runSeededInPlaceCombinedMaintenance(String scenario, String expectedBoundary)
      throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-16 combined maintenance requires retained CPU A and CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-low-memory-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      var environment = new java.util.HashMap<>(inPlaceModelEnvironment());
      environment.put("JUSTSEARCH_WRITER_RECOVERY_ACCEPTED_WRITE", "1");
      String output = runInstalledModelScenario(repo, work, scenario, environment,
          "MODEL_LIVE_AB_LOW_MEMORY_CRASH_PASS");
      var result = markerPayload(output, "MODEL_LIVE_AB_LOW_MEMORY_CRASH_PASS");
      assertEquals(expectedBoundary, result.path("boundary").asText(), output);
      var floor = result.path("floorEvidence");
      assertEquals("IN_PLACE", floor.path("mode").asText(), output);
      assertEquals("candidate_fits_after_source_release", floor.path("reason").asText(), output);
      assertTrue(floor.path("freeBytes").asLong() <= 1024 * 1024, output);
      assertTrue(floor.path("footprintBytes").asLong() > floor.path("freeBytes").asLong(), output);
      assertEquals(1, result.path("restartCount").asInt(), output);
      assertEquals("COMPLETE", result.path("terminalState").asText(), output);
      assertEquals(result.path("settingsRevision").path("before").asLong() + 1,
          result.path("settingsRevision").path("after").asLong(), output);
      assertBooleanTrue(result.path("latestText"), output);
      assertBooleanTrue(result.path("latestVector"), output);
      assertBooleanTrue(result.path("staleAbsent"), output);
      assertBooleanTrue(result.path("acceptedWrite"), output);
      for (String role : List.of("embedding", "ner", "splade", "citation-scorer")) {
        var modelIdentity = result.path("modelIdentities").path(role);
        assertFalse(modelIdentity.path("modelPath").asText().isBlank(), output);
        assertFalse(modelIdentity.path("settingsPath").asText().isBlank(), output);
        assertTrue(modelIdentity.path("sha256").asText().matches("[0-9a-f]{64}"), output);
      }
      var embeddingRuntime = result.path("runtimeIdentities").path("embedding");
      assertEquals(result.path("modelIdentities").path("embedding").path("sha256").asText(),
          embeddingRuntime.path("fingerprint").asText(), output);
      assertEquals(result.path("activeGeneration").asText(),
          embeddingRuntime.path("activeGeneration").asText(), output);
      assertEquals("READY", embeddingRuntime.path("state").asText(), output);
      var capturedReplay = result.path("capturedReplay");
      assertTrue(capturedReplay.path("planned").asText().matches("[0-9a-f]{64}"), output);
      assertTrue(capturedReplay.path("committed").asText().matches("[0-9a-f]{64}"), output);
      assertNotEquals(capturedReplay.path("planned").asText(),
          capturedReplay.path("committed").asText(), output);
      assertFalse(capturedReplay.path("unitRevision").asText().isBlank(), output);
      assertEquals(1, capturedReplay.path("supersededEvents").asInt(), output);
      System.out.println("LIFECYCLE_LOW_MEMORY_COMBINED_PASS §16 " + result);
    });
  }

  @Test
  @Tag("ai")
  @Timeout(15 * 60)
  void realGenerativeChildRecoversOnTheSecondSameConfigurationAttempt() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedGenerativeRuntime(repo, "compact"),
        "generative recovery requires retained compact GGUF and cuda12 runtime bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/generative-recovery-success-" + UUID.randomUUID());
    String output = runInstalledModelScenario(repo, work, "generative-recovery-success",
        Map.of("JUSTSEARCH_CHAT_PROFILE", "compact"),
        "GENERATIVE_RECOVERY_SUCCESS_PASS");
    var result = markerPayload(output, "GENERATIVE_RECOVERY_SUCCESS_PASS");
    assertRecordedOnlineIntent(result.path("activation"), output);
    assertInitialGenerativeReady(result, output);
    assertEquals("FAILED", result.path("held").path("state").asText(), output);
    assertEquals(0, result.path("held").path("recoveryAttempts").asInt(), output);
    assertEquals(429, result.path("busy").path("status").asInt(), output);
    assertEquals("ADMISSION_ENGINE_LIMIT",
        result.path("busy").path("body").path("errorCode").asText(), output);
    assertEquals(1, result.path("failedOne").path("recoveryAttempts").asInt(), output);
    assertEquals(202, result.path("accepted").path("status").asInt(), output);
    assertEquals("READY", result.path("terminal").path("row").path("state").asText(), output);
    assertEquals(2,
        result.path("terminal").path("row").path("recoveryAttempts").asInt(), output);
    assertFalse(result.path("terminal").path("replacement").path("id").asText().isBlank(), output);
    assertTrue(result.path("terminal").path("replacement").path("pid").asLong() > 0, output);
    assertFalse(result.path("child").path("id").asText().equals(
        result.path("terminal").path("replacement").path("id").asText()), output);
    assertFalse(result.path("child").path("pid").asLong()
        == result.path("terminal").path("replacement").path("pid").asLong(), output);
    assertEquals(result.path("child").path("executable").asText(),
        result.path("terminal").path("replacement").path("executable").asText(), output);
    assertEquals(result.path("child").path("modelPath").asText(),
        result.path("terminal").path("replacement").path("modelPath").asText(), output);
    assertEquals("match",
        result.path("terminal").path("replacementVerified").path("verdict").asText(), output);
    assertEquals(200, result.path("terminal").path("chat").path("status").asInt(), output);
    assertFalse(result.path("terminal").path("chat").path("terminal")
        .path("finalResponse").asText().isBlank(), output);
    assertEquals(0, result.path("finalSupervisor").path("restartCount").asInt(), output);
  }

  @Test
  @Tag("ai")
  @Timeout(15 * 60)
  void realGenerativeChildSpendsTwoAttemptsWithoutEscalatingOptionalFailure() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedGenerativeRuntime(repo, "compact"),
        "generative recovery requires retained compact GGUF and cuda12 runtime bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/generative-recovery-exhaustion-"
        + UUID.randomUUID());
    String output = runInstalledModelScenario(repo, work, "generative-recovery-exhaustion",
        Map.of("JUSTSEARCH_CHAT_PROFILE", "compact"),
        "GENERATIVE_RECOVERY_EXHAUSTION_PASS");
    var result = markerPayload(output, "GENERATIVE_RECOVERY_EXHAUSTION_PASS");
    assertRecordedOnlineIntent(result.path("activation"), output);
    assertInitialGenerativeReady(result, output);
    assertEquals(429, result.path("busy").path("status").asInt(), output);
    assertEquals(1, result.path("failedOne").path("recoveryAttempts").asInt(), output);
    assertEquals(202, result.path("accepted").path("status").asInt(), output);
    assertEquals("FAILED", result.path("terminal").path("state").asText(), output);
    assertEquals(2, result.path("terminal").path("recoveryAttempts").asInt(), output);
    assertEquals(503, result.path("terminal").path("exhausted").path("status").asInt(), output);
    assertEquals("WORKER_RECOVERY_EXHAUSTED",
        result.path("terminal").path("exhausted").path("body").path("errorCode").asText(), output);
    assertEquals(0, result.path("finalSupervisor").path("restartCount").asInt(), output);
    assertEquals(result.path("first").path("instanceId").asText(),
        result.path("finalSupervisor").path("instanceId").asText(), output);
  }

  @Test
  @Tag("ai")
  @Timeout(15 * 60)
  void standardProfileGenerativeRecoveryAnswersARealChat() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedGenerativeRuntime(repo, "standard"),
        "standard generative recovery requires retained 9B GGUF, projector and cuda12 runtime");
    Path work = repo.resolve("tmp/lane-f-takeover/generative-recovery-standard-"
        + UUID.randomUUID());
    String output = runInstalledModelScenario(repo, work, "generative-recovery-success",
        Map.of("JUSTSEARCH_CHAT_PROFILE", "standard"),
        "GENERATIVE_RECOVERY_SUCCESS_PASS");
    var result = markerPayload(output, "GENERATIVE_RECOVERY_SUCCESS_PASS");
    assertRecordedOnlineIntent(result.path("activation"), output);
    assertInitialGenerativeReady(result, output);
    assertEquals("standard", result.path("profile").path("profileId").asText(), output);
    assertEquals("Qwen_Qwen3.5-9B-Q4_K_M.gguf",
        result.path("profile").path("modelFile").asText(), output);
    assertBooleanTrue(result.path("profile").path("mmprojActive"), output);
    assertEquals("READY", result.path("terminal").path("row").path("state").asText(), output);
    assertEquals(2,
        result.path("terminal").path("row").path("recoveryAttempts").asInt(), output);
    assertEquals(200, result.path("terminal").path("chat").path("status").asInt(), output);
    var chatTerminal = result.path("terminal").path("chat").path("terminal");
    assertEquals("COMPLETED", chatTerminal.path("disposition").asText(), output);
    assertEquals("quokka",
        chatTerminal.path("finalResponse").asText().strip().toLowerCase(Locale.ROOT), output);
    assertTrue(chatTerminal.path("totalTokensUsed").asLong() > 0, output);
    assertEquals(0, result.path("finalSupervisor").path("restartCount").asInt(), output);
  }

  private static void assertRecordedOnlineIntent(JsonNode activation, String output) {
    assertEquals(200, activation.path("status").asInt(), output);
    assertBooleanTrue(activation.path("body").path("success"), output);
    assertEquals("online", activation.path("body").path("requested").asText(), output);
    assertTrue(java.util.Set.of("recorded", "converged")
        .contains(activation.path("body").path("state").asText()), output);
    assertFalse(activation.path("body").path("operationKey").asText().isBlank(), output);
    assertTrue(activation.path("body").path("acceptedRevision").asLong() > 0, output);
  }

  private static void assertInitialGenerativeReady(JsonNode result, String output) {
    assertEquals("READY", result.path("initialGenerative").path("state").asText(), output);
    assertEquals(0, result.path("initialGenerative").path("recoveryAttempts").asInt(), output);
  }

  private static JsonNode markerPayload(String output, String marker) throws Exception {
    String prefix = marker + " ";
    String line = output.lines().filter(value -> value.startsWith(prefix))
        .findFirst().orElseThrow(() -> new AssertionError("Missing " + marker + ": " + output));
    return MAPPER.readTree(line.substring(prefix.length()));
  }

  private static void assertCitationIdentity(JsonNode identity, String output) {
    assertBooleanTrue(identity.path("verified"), output);
    String aPath = identity.path("aPath").asText();
    String bPath = identity.path("bPath").asText();
    String restoredAPath = identity.path("restoredAPath").asText();
    String aSha256 = identity.path("aSha256").asText();
    String bSha256 = identity.path("bSha256").asText();
    String restoredASha256 = identity.path("restoredASha256").asText();
    assertFalse(aPath.isBlank(), output);
    assertFalse(bPath.isBlank(), output);
    assertEquals(aPath, restoredAPath, output);
    assertFalse(aPath.equals(bPath), output);
    assertTrue(aSha256.matches("[0-9a-f]{64}"), output);
    assertEquals(aSha256, bSha256, output);
    assertEquals(aSha256, restoredASha256, output);
  }

  private static void assertBooleanTrue(JsonNode value, String output) {
    assertTrue(value.isBoolean(), output);
    assertTrue(value.asBoolean(), output);
  }

  private static Map<String, String> inPlaceModelEnvironment() {
    var environment = new java.util.HashMap<>(besideModelEnvironment());
    environment.put("JUSTSEARCH_WRITER_RECOVERY_FORCE_IN_PLACE", "1");
    return Map.copyOf(environment);
  }

  private static Map<String, String> besideModelEnvironment() {
    return Map.of(
        "JUSTSEARCH_WRITER_RECOVERY_DISTINCT_B", "1",
        "JUSTSEARCH_EMBED_GPU_MEM_MB", "2048",
        "JUSTSEARCH_SPLADE_GPU_MEM_MB", "2048",
        "JUSTSEARCH_NER_GPU_MEM_MB", "1024",
        "JUSTSEARCH_RERANK_GPU_MEM_MB", "1024");
  }

  @FunctionalInterface
  private interface FixtureWork {
    void run() throws Exception;
  }

  private static void withModelCacheCleanup(Path repo, Path work, FixtureWork fixture) throws Exception {
    Throwable primary = null;
    try {
      fixture.run();
    } catch (Exception | AssertionError failure) {
      primary = failure;
      throw failure;
    } finally {
      try {
        pruneStoppedModelCaches(repo, work);
      } catch (Exception cleanupFailure) {
        if (primary == null) throw cleanupFailure;
        primary.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void pruneStoppedModelCaches(Path repo, Path work) throws Exception {
    Path runs = work.resolve("state/runs");
    if (Files.isDirectory(runs)) {
      try (var entries = Files.list(runs)) {
        for (Path run : entries.filter(Files::isDirectory).toList()) {
          Path report = run.resolve("stop-report.json");
          if (!Files.isRegularFile(report)
              || !MAPPER.readTree(Files.readString(report)).path("portsClosed").asBoolean(false)) {
            throw new IllegalStateException("Cannot prune model caches before owned run has stopped: " + run);
          }
        }
      }
    }
    if ("1".equals(System.getenv("JUSTSEARCH_FIXTURE_KEEP_MODEL_CACHES"))) return;
    Path outputFile = work.resolve("model-cache-prune-output.txt");
    ProcessBuilder prune = new ProcessBuilder("node",
        repo.resolve("scripts/supervisor-conformance/prune-model-caches.mjs").toString(),
        work.toString()).directory(repo.toFile()).redirectErrorStream(true)
        .redirectOutput(outputFile.toFile());
    Process process = prune.start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      throw new IllegalStateException("Model cache prune exceeded 30 seconds");
    }
    String output = Files.readString(outputFile, StandardCharsets.UTF_8);
    if (process.exitValue() != 0 || !output.contains("MODEL_CACHE_PRUNED ")) {
      throw new IllegalStateException("Model cache prune failed: " + output);
    }
    System.out.print(output);
  }

  private static String runInstalledModelScenario(Path repo, Path work, String scenario,
      Map<String, String> extraEnvironment, String marker) throws Exception {
    Files.createDirectories(work);
    Path outputFile = work.resolve(scenario + "-fixture-output.txt");
    ProcessBuilder builder = new ProcessBuilder("node",
        repo.resolve("scripts/supervisor-conformance/real-writer-recovery.mjs").toString())
        .directory(repo.toFile()).redirectErrorStream(true)
        .redirectOutput(outputFile.toFile());
    builder.environment().put("JUSTSEARCH_WRITER_RECOVERY_WORK", work.toString());
    builder.environment().put("JUSTSEARCH_REAL_RECOVERY_SCENARIO", scenario);
    builder.environment().putAll(extraEnvironment);
    Process process = builder.start();
    Throwable primary = null;
    try {
      long timeoutSeconds = 330L;
      if (scenario.startsWith("generative-recovery-") || scenario.contains("low-memory")) {
        timeoutSeconds = 720L;
      }
      assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
          "D1-16 installed " + scenario + " exceeded " + timeoutSeconds
              + " seconds: " + outputFile);
      String output = Files.readString(outputFile, StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      assertTrue(output.contains(marker), output);
      assertTrue(output.contains("STOP 0") && output.contains("\"portsClosed\":true"), output);
      return output;
    } catch (Exception | AssertionError failure) {
      primary = failure;
      throw failure;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      Exception cleanupFailure = null;
      boolean stopped = false;
      try {
        if (Files.isRegularFile(outputFile)) {
          for (String line : Files.readAllLines(outputFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("{\"ok\":true,\"runId\":")) {
              stopOwnedRun(repo, work, MAPPER.readTree(line).path("runId").asText());
              break;
            }
          }
        }
        stopped = true;
      } catch (Exception failure) {
        cleanupFailure = failure;
      }
      if (stopped && "model-live-a-b-recompose-failure".equals(scenario)) {
        try {
          restorePrivateAAfterForcedExit(work);
        } catch (Exception failure) {
          if (cleanupFailure == null) cleanupFailure = failure;
          else cleanupFailure.addSuppressed(failure);
        }
      }
      if (cleanupFailure != null) {
        if (primary == null) throw cleanupFailure;
        primary.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void restorePrivateAAfterForcedExit(Path work) throws Exception {
    Path privateRoot = work.resolve("installer-models").toAbsolutePath().normalize();
    if (!Files.isDirectory(privateRoot)) return;
    try (var files = Files.walk(privateRoot)) {
      for (Path hidden : files.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".recompose-held")).toList()) {
        String name = hidden.getFileName().toString();
        Path original = hidden.resolveSibling(
            name.substring(0, name.length() - ".recompose-held".length())).normalize();
        if (!original.startsWith(privateRoot) || Files.exists(original)) {
          throw new IllegalStateException("Private A model cannot be restored: " + hidden);
        }
        Files.move(hidden, original);
      }
    }
  }

  private static boolean hasRetainedAlternateEmbedding(Path repo) {
    for (Path ancestor = repo; ancestor != null; ancestor = ancestor.getParent()) {
      if (Files.isRegularFile(ancestor.resolve(
          "models/onnx/gte-multilingual-base/model_fp16.onnx"))) return true;
    }
    return false;
  }

  private static boolean hasRetainedStandardEmbedding(Path repo) {
    for (Path ancestor = repo; ancestor != null; ancestor = ancestor.getParent()) {
      Path model = ancestor.resolve("models/onnx/gte-multilingual-base");
      if (Files.isRegularFile(model.resolve("model.onnx"))
          && Files.isRegularFile(model.resolve("model_fp16.onnx"))
          && Files.isRegularFile(model.resolve("tokenizer.json"))) return true;
    }
    return false;
  }

  @ParameterizedTest
  @ValueSource(strings = {"writer", "migration", "lock-ingest", "processing"})
  void supervisedRecoveryUsesTheCorrectExitAndReopensDurableState(String scenario) throws Exception {
    runScenario(scenario);
  }

  @ParameterizedTest
  @Tag("ai")
  @ValueSource(strings = {"lock-index-release", "lock-index-exhaustion"})
  void supervisedIndexRecoveryUsesTheRetainedStandardEmbedding(String scenario) throws Exception {
    runScenario(scenario);
  }

  @Test
  void initialBootstrapSurvivesHostileLocks() throws Exception {
    runScenario("lock-boot");
  }

  static void runScenario(String scenario) throws Exception {
    boolean processingFamily = "processing".equals(scenario) || "operation".equals(scenario);
    boolean operationFault = scenario.startsWith("ingest-") || scenario.startsWith("settings-")
        || scenario.startsWith("bulk-");
    boolean semanticIndexLockScenario = "lock-index-release".equals(scenario)
        || "lock-index-exhaustion".equals(scenario);
    boolean indexLockExhaustionScenario = "lock-index-exhaustion".equals(scenario)
        || "lock-index-exhaustion-no-ai".equals(scenario);
    boolean indexLockScenario = semanticIndexLockScenario || indexLockExhaustionScenario;
    if ("lock-boot".equals(scenario) || indexLockScenario) {
      assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
          "mandatory file-locking contention at boot is a Windows property");
    }
    if (processingFamily || operationFault) {
      assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
          "the repository's process identity collector currently supports Windows only");
    }
    Path repo = repositoryRoot();
    if (semanticIndexLockScenario) {
      assumeTrue(hasRetainedStandardEmbedding(repo),
          "installed index recovery requires retained standard embedding model bytes");
    }
    if (scenario.startsWith("installer-")) {
      assumeTrue(hasRetainedInstallerModels(repo),
          "installed standard-model activation requires retained model bytes; run on a model-equipped host");
    }
    Path work =
        repo.resolve("tmp/lane-f-takeover/writer-junit-" + UUID.randomUUID()).normalize();
    Path outputFile = work.resolve("fixture-output.txt");
    Files.createDirectories(work);
    ProcessBuilder builder =
        new ProcessBuilder(
                "node",
                repo.resolve("scripts/supervisor-conformance/real-writer-recovery.mjs")
                    .toString())
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile());
    builder.environment().put("JUSTSEARCH_WRITER_RECOVERY_WORK", work.toString());
    builder.environment().put("JUSTSEARCH_REAL_RECOVERY_SCENARIO", scenario);
    if (processingFamily) {
      Path childArgs = work.resolve("processing-child-args.txt");
      Files.writeString(childArgs, "-Xmx128m\n-Dfile.encoding=UTF-8\n-cp\n\""
          + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n"
          + io.justsearch.indexerworker.fixtures.ChaosExtractionSandboxChild.class.getName() + "\n");
      String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
      builder.environment().put("JUSTSEARCH_EXTRACTION_SANDBOX_MODE", "process");
      builder.environment().put("JUSTSEARCH_EXTRACTION_SANDBOX_POOL", "1");
      builder.environment().put("JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND",
          "\"" + javaExe + "\" \"@" + childArgs + "\"");
      builder.environment().put("JUSTSEARCH_PROCESSING_TEST_ARGFILE", childArgs.toString());
      builder.environment().put("JUSTSEARCH_PROCESSING_TEST_ENTERED", work.resolve("processing-entered").toString());
    }
    FileIntruder intruder = scenario.startsWith("lock-") ? new FileIntruder(work.resolve("data")) : null;
    FileIntruder.ExclusiveHold indexLock = null;
    boolean initialBarrierReleased = false;
    boolean recoveryBarrierReleased = false;
    boolean indexLockReleased = false;
    long initialBarrierSeenAt = 0L;
    String firstRunId = null;
    int firstIncarnation = -1;
    int firstPid = -1;
    boolean intruderStarted = false;
    if ("lock-boot".equals(scenario)) {
      Files.createDirectories(work.resolve("data"));
      intruder.start(5, 10);
      intruderStarted = true;
    }
    Process process = null;
    int exit;
    boolean interrupted = false;
    Throwable primaryFailure = null;
    try {
      process = builder.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(330);
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (indexLockScenario) {
          Path runtime = work.resolve("data/runtime");
          Path initialReached = runtime.resolve("index-start-initial-reached.json");
          Path initialRelease = runtime.resolve("index-start-initial-release");
          Path recoveryReached = runtime.resolve("index-start-recovery-1-reached.json");
          Path recoveryRelease = runtime.resolve("index-start-recovery-1-release");
          Path recoveryProof = work.resolve("index-start-recovery-proofed");
          if (!initialBarrierReleased && indexLock == null
              && Files.isRegularFile(initialReached)) {
            Path firstSupervisorPath = runtime.resolve("supervisor.v1.json");
            Path firstManifestPath = runtime.resolve("manifest.json");
            // The worker hook can publish the admission marker before the supervisor
            // projection. Keep polling this loop until both identity witnesses exist;
            // reading either path before its atomic publication makes the installed
            // recovery proof fail with a transient NoSuchFileException.
            if (!Files.isRegularFile(firstSupervisorPath)
                || !Files.isRegularFile(firstManifestPath)) {
              continue;
            }
            JsonNode marker = MAPPER.readTree(Files.readString(initialReached));
            assertEquals("index-start", marker.path("point").asText(), marker.toString());
            assertEquals("initial", marker.path("attempt").asText(), marker.toString());
            assertEquals(0, marker.path("recoveryAttempts").asInt(), marker.toString());
            assertEquals("STARTING", marker.path("indexState").asText(), marker.toString());
            assertTrue(marker.hasNonNull("stateSince"), marker.toString());
            JsonNode firstSupervisor = MAPPER.readTree(Files.readString(firstSupervisorPath));
            JsonNode firstManifest = MAPPER.readTree(Files.readString(firstManifestPath));
            firstRunId = firstSupervisor.path("runId").asText();
            firstIncarnation = firstSupervisor.path("incarnation").asInt(-1);
            assertFalse(firstRunId.isBlank(), firstSupervisor.toString());
            assertTrue(firstIncarnation >= 1, firstSupervisor.toString());
            firstPid = firstSupervisor.path("pid").asInt(-1);
            assertTrue(firstPid > 0, firstSupervisor.toString());
            assertEquals(firstPid, firstManifest.path("pid").asInt(-1), firstManifest.toString());
            assertEquals(firstPid, marker.path("pid").asInt(-1), marker.toString());
            Path indexBase = Path.of(marker.path("indexBase").asText());
            Path lockFile = indexBase.resolveSibling(indexBase.getFileName() + ".index.lock");
            indexLock = intruder.holdExclusive(lockFile);
            initialBarrierSeenAt = System.nanoTime();
          }
          if (indexLock != null && !initialBarrierReleased) {
            Path deadlineProof = work.resolve("index-start-deadline-proved");
            boolean proofPublished = Files.isRegularFile(deadlineProof);
            boolean safetyRelease = initialBarrierSeenAt != 0L
                && System.nanoTime() - initialBarrierSeenAt > TimeUnit.SECONDS.toNanos(150);
            if (proofPublished || safetyRelease) {
              Files.writeString(initialRelease, proofPublished ? "deadline-proved" : "safety-release");
              initialBarrierReleased = true;
            }
          }
          if (initialBarrierReleased && !recoveryBarrierReleased
              && Files.isRegularFile(recoveryReached)
              && Files.isRegularFile(recoveryProof)) {
            JsonNode marker = MAPPER.readTree(Files.readString(recoveryReached));
            assertEquals("index-start", marker.path("point").asText(), marker.toString());
            assertEquals("recovery-1", marker.path("attempt").asText(), marker.toString());
            assertEquals(1, marker.path("recoveryAttempts").asInt(), marker.toString());
            assertEquals("STARTING", marker.path("indexState").asText(), marker.toString());
            assertEquals(firstPid, marker.path("pid").asInt(-1), marker.toString());
            if ("lock-index-release".equals(scenario)) {
              indexLock.close();
              indexLockReleased = true;
            }
            Files.writeString(recoveryRelease,
                indexLockReleased ? "released-before-retry" : "held-for-exhaustion");
            recoveryBarrierReleased = true;
          }
          if (indexLockExhaustionScenario && recoveryBarrierReleased
              && !indexLockReleased && indexLock != null) {
            Path supervisor = runtime.resolve("supervisor.v1.json");
            JsonNode state = Files.isRegularFile(supervisor)
                ? MAPPER.readTree(Files.readString(supervisor)) : null;
            JsonNode lastExit = state == null ? null : state.path("lastExit");
            if (lastExit != null
                && state.path("runId").asText().equals(firstRunId)
                && lastExit.path("incarnation").asInt(-1) == firstIncarnation
                && lastExit.path("code").asInt(-1) == 5
                && lastExit.path("reason").asText().equals("escalated_restart")
                && lastExit.path("class").asText().equals("TRANSIENT")
                && lastExit.path("counted").asBoolean(false)) {
              indexLock.close();
              indexLockReleased = true;
              Files.writeString(work.resolve("index-lock-exit-observed"), lastExit.toString());
            }
          }
        }
        if (intruder != null) {
          if (!intruderStarted && Files.exists(work.resolve("intruder-start"))) {
            intruder.start(3, 50);
            intruderStarted = true;
            Files.writeString(work.resolve("intruder-started"), "ready");
          }
          if (Files.exists(work.resolve("intruder-stop"))) {
            intruder.close();
            Files.writeString(work.resolve("intruder-stopped"), "closed");
          }
        }
        if (System.nanoTime() >= deadline) {
          throw new AssertionError("Engine recovery fixture exceeded 330 seconds");
        }
      }
      exit = process.exitValue();
      assertEquals(0, exit, Files.readString(outputFile, StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      interrupted = true;
      primaryFailure = e;
      throw e;
    } catch (Exception | AssertionError failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      if (indexLock != null) indexLock.close();
      if (intruder != null) intruder.close();
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        try {
          process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      try {
        stopOwnedRun(repo, work);
        if ("writer".equals(scenario) || scenario.startsWith("installer-")) {
          pruneStoppedModelCaches(repo, work);
        }
      } catch (Exception cleanupFailure) {
        if (primaryFailure == null) {
          primaryFailure = cleanupFailure;
          throw cleanupFailure;
        }
        primaryFailure.addSuppressed(cleanupFailure);
      } finally {
        try {
          if (intruder != null) intruder.writeEvidence(work.resolve("intruder-acquisitions.json"));
        } catch (Exception evidenceFailure) {
          if (primaryFailure == null) throw evidenceFailure;
          primaryFailure.addSuppressed(evidenceFailure);
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
    String output = Files.readString(outputFile, StandardCharsets.UTF_8);

    if ("writer".equals(scenario)) {
      assertTrue(output.contains("QUEUE_BEFORE_DEATH"), output);
      assertTrue(output.contains("fatal_or_uncaught"), output);
    } else if ("migration".equals(scenario)) {
      assertTrue(output.contains("MIGRATION_PASS"), output);
    } else if (scenario.startsWith("bulk-")) {
      assertTrue(output.contains("\"scenario\":\"" + scenario + "\""), output);
      assertTrue(output.contains("BULK_FAULT_PASS"), output);
      assertTrue(Files.isRegularFile(work.resolve("bulk-cut.json")), "Missing crash-cut evidence");
      assertTrue(Files.isRegularFile(work.resolve("bulk-final.json")), "Missing successor evidence");
      assertTrue(Files.isRegularFile(work.resolve("bulk-after-retry.json")), "Missing retry evidence");
      if (scenario.startsWith("bulk-live-")) {
        var cut = MAPPER.readTree(Files.readString(work.resolve("bulk-cut.json")));
        String expected = "Resuming durable BUILDING generation g-"
            + cut.path("operationKey").asText() + " from active "
            + cut.path("cut").path("state").path("active_generation").asText();
        Path log = work.resolve("state/runs")
            .resolve(cut.path("cooldown").path("runId").asText())
            .resolve("logs/engine.log");
        assertTrue(Files.readString(log).contains(expected),
            "Successor did not enter the exact durable BUILDING boot branch: " + expected);
      }
    } else if (operationFault) {
      assertTrue(output.contains("\"scenario\":\"" + scenario + "\""), output);
      if ("ingest-client-disconnect".equals(scenario)) {
        assertTrue(output.contains("OPERATION_FAULT_DISCONNECT_PASS"), output);
      } else {
        assertTrue(output.contains("OPERATION_FAULT_COOLDOWN_SNAPSHOT"), output);
        String marker = "settings-after-accept-before-effect".equals(scenario)
            ? "OPERATION_FAULT_SETTINGS_PRE_EFFECT_PASS"
            : scenario.startsWith("settings-") ? "OPERATION_FAULT_SETTINGS_PASS" : "OPERATION_FAULT_INGEST_PASS";
        assertTrue(output.contains(marker), output);
      }
    } else if (processingFamily) {
      assertTrue(output.contains("PROCESSING_AFTER_DEATH"), output);
      assertTrue(output.contains("PROCESSING_REPLAY_PASS"), output);
      if ("operation".equals(scenario)) {
        assertTrue(output.contains("OPERATION_RETRY_NO_DUPLICATES_PASS"), output);
        assertTrue(output.contains("OPERATION_ROW_AFTER_DEATH"), output);
        assertTrue(output.contains("OPERATION_ROW_AFTER_RESTART"), output);
      }
    } else if ("lock-index-release".equals(scenario)) {
      assertTrue(output.contains("INDEX_LOCK_RELEASE_PASS"), output);
      assertTrue(indexLockReleased, "the exact index lock was not released before retry");
      String engineLog = Files.readString(work.resolve("data/logs/engine.log"));
      assertFalse(engineLog.contains("Recovery/API cleanup failed"), engineLog);
      assertFalse(engineLog.contains("Head cleanup incomplete"), engineLog);
    } else if (indexLockExhaustionScenario) {
      String marker = "lock-index-exhaustion-no-ai".equals(scenario)
          ? "INDEX_LOCK_EXHAUSTION_NO_AI_PASS" : "INDEX_LOCK_EXHAUSTION_PASS";
      assertTrue(output.contains(marker), output);
      assertTrue(indexLockReleased, "the exact index lock was not released after escalation");
    } else {
      assertTrue(output.contains("LOCK_SURVIVAL_PASS"), output);
      assertTrue(intruder.acquiredLockCount() > 0, "the attack must acquire real filesystem locks");
    }
    if (indexLockScenario) {
      String marker = "lock-index-release".equals(scenario)
          ? "INDEX_LOCK_RELEASE_PASS"
          : "lock-index-exhaustion-no-ai".equals(scenario)
              ? "INDEX_LOCK_EXHAUSTION_NO_AI_PASS" : "INDEX_LOCK_EXHAUSTION_PASS";
      var result = markerPayload(output, marker);
      assertEquals(429, result.path("busyRecovery").path("status").asInt(), output);
      assertEquals("ADMISSION_ENGINE_LIMIT",
          result.path("busyRecovery").path("body").path("errorCode").asText(), output);
    }
    assertTrue(output.contains("PASS"), output);
    assertTrue(output.contains("\"portsClosed\":true"), output);
  }

  private static boolean hasRetainedInstallerModels(Path repo) {
    for (Path ancestor = repo; ancestor != null; ancestor = ancestor.getParent()) {
      Path models = ancestor.resolve("models");
      if (Files.isRegularFile(models.resolve("onnx/gte-multilingual-base/model.onnx"))
          && Files.isRegularFile(models.resolve("onnx/gte-multilingual-base/tokenizer.json"))
          && Files.isRegularFile(models.resolve("onnx/ner/model.onnx"))
          && Files.isRegularFile(models.resolve("onnx/ner/model_fp16.onnx"))
          && Files.isRegularFile(models.resolve("onnx/ner/tokenizer.json"))
          && Files.isRegularFile(models.resolve("splade/naver-splade-v3/model.onnx"))
          && Files.isRegularFile(models.resolve("splade/naver-splade-v3/model_fp16.onnx"))
          && Files.isRegularFile(models.resolve("splade/naver-splade-v3/tokenizer.json"))
          && Files.isRegularFile(models.resolve("onnx/citation-scorer/model.onnx"))
          && Files.isRegularFile(models.resolve("onnx/citation-scorer/tokenizer.json"))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRetainedGenerativeRuntime(Path repo, String profile) {
    String model = "standard".equals(profile) ? "Qwen_Qwen3.5-9B-Q4_K_M.gguf"
        : "compact/Qwen3.5-4B-Q4_K_M.gguf";
    String mmproj = "standard".equals(profile) ? "mmproj-F16.gguf"
        : "compact/mmproj-F16.gguf";
    for (Path ancestor = repo; ancestor != null; ancestor = ancestor.getParent()) {
      if (Files.isRegularFile(ancestor.resolve("models").resolve(model))
          && Files.isRegularFile(ancestor.resolve("models").resolve(mmproj))
          && Files.isRegularFile(ancestor.resolve(
              "modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe"))) {
        return true;
      }
    }
    return false;
  }

  private static void stopOwnedRun(Path repo, Path work) throws Exception {
    Path runs = work.resolve("state/runs");
    if (!Files.isDirectory(runs)) {
      return;
    }
    List<String> runIds;
    try (var entries = Files.list(runs)) {
      runIds =
          entries
              .filter(Files::isDirectory)
              .filter(candidate -> Files.isRegularFile(candidate.resolve("run.json")))
              .map(Path::getFileName)
              .map(Path::toString)
              .toList();
    }
    if (runIds.isEmpty()) {
      return;
    }
    if (runIds.size() != 1) {
      throw new IllegalStateException("ambiguous owned run identities under " + runs + ": " + runIds);
    }
    stopOwnedRun(repo, work, runIds.getFirst());
  }

  private static void stopOwnedRun(Path repo, Path work, String runId) throws Exception {
    Path runs = work.resolve("state/runs");
    Path stopReport = runs.resolve(runId).resolve("stop-report.json");
    if (Files.isRegularFile(stopReport)) {
      try {
        var report = MAPPER.readTree(Files.readString(stopReport));
        if (report != null && report.path("portsClosed").asBoolean(false)) {
          return;
        }
      } catch (java.io.IOException | tools.jackson.core.JacksonException incompleteReport) {
        // A timed-out fixture can die while its stop child is publishing this report.
        // An unreadable report is not proof of cleanup; run the identity-checked stop below.
      }
    }
    ProcessBuilder cleanup =
        new ProcessBuilder(
                "node",
                repo.resolve("scripts/dev/dev-runner.cjs").toString(),
                "stop",
                "--json",
                "--session-id",
                "writer-recovery-live",
                "--run",
                runId)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .redirectOutput(work.resolve("cleanup-output.txt").toFile());
    cleanup
        .environment()
        .put("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", work.resolve("state").toString());
    Process stop = cleanup.start();
    if (!stop.waitFor(30, TimeUnit.SECONDS) || stop.exitValue() != 0) {
      throw new IllegalStateException("identity-checked cleanup failed for run " + runId);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from " + Path.of(""));
  }
}
