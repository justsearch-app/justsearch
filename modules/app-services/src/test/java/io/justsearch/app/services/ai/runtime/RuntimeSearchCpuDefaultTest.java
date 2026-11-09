/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.observability.EncoderRuntimeExplainer;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.worker.OnnxModelStatus;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ort.EncoderRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Model-free observed-session fixtures exercise the existing status and installer interfaces. */
final class RuntimeSearchCpuDefaultTest {
  @TempDir Path tmp;
  private final ConfigStore previous = ConfigStore.globalOrNull();

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  private void publish(String choice, boolean gpu, int pathOrdinal, boolean path) {
    var builder = new ResolvedConfigBuilder()
        .putDefault("justsearch.home", tmp.toString())
        .putDefault("justsearch.models.dir", tmp.resolve("models").toString())
        .putDefault("justsearch.rerank.gpu.enabled", Boolean.toString(gpu));
    if (path) builder.put("justsearch.rerank.model_path", pathOrdinal,
        "fixture", "path", tmp.resolve("reranker").toString());
    if (choice != null) builder.putSettings("justsearch.rerank.enabled", choice);
    ConfigStore.setGlobal(new ConfigStore(builder.build()));
  }

  @Test
  void cpuNullRetainedSelectionShowsSearchOffWithPhysicalSessionAndNoSettingsWrite() throws Exception {
    publish(null, false, 300, true);
    var settings = new UiSettings();
    settings.setRerankerModelPath(tmp.resolve("reranker").toString());
    settings.setRerankerPreferenceRevision(7);
    var selected = QueryRoleSelection.Role.selected("fixture-cpu",
        new ModelFile(tmp.resolve("reranker/model.onnx"), "a".repeat(64), 10),
        new ModelFile(tmp.resolve("reranker/tokenizer.json"), "b".repeat(64), 11),
        ModelPrecision.FP32, ExecutionProvider.CPU);
    var roles = new QueryRoleSelection(selected, QueryRoleSelection.Role.disabled());
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        tmp.resolve("settings.json"));
    store.replacePrepared(store.prepareExact(settings,
        new SettingsWitness(7, OperationKeys.generate(Clock.systemUTC())), roles));
    byte[] before = Files.readAllBytes(store.settingsPath());
    try (var executors = new TestEngineExecutors()) {
      var runtime = service(executors, store, true, true);
      runtime.setEncoderRuntimeCache(() -> Map.of(EncoderRole.RERANKER,
          EncoderRuntimeExplainer.explain(EncoderRole.RERANKER,
              io.justsearch.app.api.status.OrtCudaView.notConfigured(),
              Map.of("variant", Map.of("executionProvider", "CPU")))));
      var row = runtime.getStatus().onnxFeatures().getFirst();
      assertEquals(tmp.resolve("reranker").toString(), row.modelPath());
      assertTrue(row.modelActive(), "physical retained session stays loaded");
      assertEquals("cpu", row.executionProvider());
      assertFalse(row.gpuFallback());
      assertEquals("active", runtime.functionalStatusByPackage().get("reranker"));
      assertArrayEquals(before, Files.readAllBytes(store.settingsPath()));
      assertEquals(roles, store.inspect().queryRoles());
      assertEquals(7, store.inspect().settings().getRerankerPreferenceRevision());
      assertNull(store.inspect().settings().getRerankerEnabled());
      assertEquals("inactive", row.status(), "CPU/null search intent must be Off");
      assertEquals("cpu_default_off", row.reason());
    }
  }

  @Test
  void preferenceAheadOfItsEnvelopeRemainsUnavailableInsteadOfShowingAutomaticOff() throws Exception {
    publish(null, false, 300, true);
    var settings = new UiSettings();
    settings.setRerankerPreferenceRevision(7);
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        tmp.resolve("ahead-of-witness.json"));
    store.replacePrepared(store.prepareExact(settings, new SettingsWitness(0, null)));
    assertThrows(io.justsearch.configuration.persistence.CorruptDurableStoreException.class,
        store::inspect);
    try (var executors = new TestEngineExecutors()) {
      var runtime = service(executors, store, true, true);
      var row = runtime.getStatus().onnxFeatures().getFirst();
      assertEquals("unknown", row.status());
      assertEquals("settings_unavailable", row.reason());
      assertFalse(row.modelActive());
      assertEquals("unknown", runtime.functionalStatusByPackage().get("reranker"));
    }
  }

  @Test
  void completeAndFailedRequiredFilePackagesKeepTheirPhysicalVerdict() throws Exception {
    publish(null, false, 300, true);
    try (var executors = new TestEngineExecutors()) {
      var runtime = service(executors, emptySettings(), true, true);
      for (String state : new String[] {"installed", "failed"}) {
        var installer = new AiInstallService(null, null, null, null, tmp.resolve(state));
        var field = AiInstallService.class.getDeclaredField("status");
        field.setAccessible(true);
        var status = (AiInstallStatus) field.get(installer);
        var pkg = new AiInstallStatus.PackageStatus();
        pkg.packageId = "reranker";
        pkg.state = state;
        pkg.targetPath = tmp.resolve(state).resolve("model.onnx").toString();
        pkg.error = "failed".equals(state) ? "Required model file is missing" : "";
        if ("installed".equals(state)) {
          Files.createDirectories(Path.of(pkg.targetPath).getParent());
          Files.writeString(Path.of(pkg.targetPath), "metadata-only fixture");
        }
        status.repairNeeded = "failed".equals(state);
        // A failed required file may coexist with a retained session; physical status is not
        // permission to claim every required file is present or to change the failed state.
        status.packages.add(pkg);
        installer.setFunctionalStatusSource(runtime::functionalStatusByPackage);
        var result = installer.getStatus().packages.getFirst();
        assertEquals(state, result.state);
        assertEquals("active", result.functionalStatus);
        assertEquals(pkg.error, result.error);
        assertEquals("installed".equals(state), Files.exists(Path.of(result.targetPath)));
      }
    }
  }

  @Test
  void discoveredCpuNullSessionProjectsOnlyItsSearchIntent() {
    publish(null, false, 300, false);
    try (var executors = new TestEngineExecutors()) {
      var runtime = service(executors, emptySettings(), true, true);
      var row = runtime.getStatus().onnxFeatures().getFirst();
      assertEquals("inactive", row.status());
      assertEquals("cpu_default_off", row.reason());
      assertEquals("discovered-reranker", row.modelPath());
      assertTrue(row.modelActive());
    }
  }

  @Test
  void physicalVerdictDoesNotPromoteUnknownWrongRoleWrongReasonOrMissingSession() throws Exception {
    var method = java.util.Arrays.stream(RuntimeActivationService.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals("functionalVerdict")).findFirst().orElseThrow();
    method.setAccessible(true);
    for (EncoderRole role : new EncoderRole[] {EncoderRole.RERANKER, EncoderRole.CITATION}) {
      for (String status : new String[] {"unknown", "inactive"}) {
        for (String reason : new String[] {"cpu_default_off", "disabled", "not_found"}) {
          for (boolean active : new boolean[] {false, true}) {
            if (role == EncoderRole.RERANKER && status.equals("inactive")
                && reason.equals("cpu_default_off") && active) continue;
            var row = new AiRuntimeStatusResponse.OnnxFeatureStatus(
                role == EncoderRole.RERANKER ? "reranker" : "citation_scorer", "fixture",
                status, reason, "fixture", active, "unknown", false, null);
            // Preserve the original seam too: the baseline takes only the row.
            Object verdict = method.getParameterCount() == 1
                ? method.invoke(null, row) : method.invoke(null, role, row);
            assertEquals(status.equals("unknown") ? "unknown" : "inactive", verdict);
          }
        }
      }
    }
  }

  @Test
  void authorityAvailabilityColdAndOtherRolesKeepTheirExistingDiagnostics() throws Exception {
    for (String choice : new String[] {null, "true", "false"}) {
      for (boolean gpu : new boolean[] {false, true}) {
        for (int ordinal : new int[] {300, 400, 500}) {
          for (boolean active : new boolean[] {false, true}) {
            if (choice == null && !gpu && ordinal < 400 && active) continue;
            publish(choice, gpu, ordinal, true);
            try (var executors = new TestEngineExecutors()) {
              var runtime = service(executors, emptySettings(), true, active);
              var row = runtime.getStatus().onnxFeatures().getFirst();
              boolean overriddenOff = "false".equals(choice) && ordinal >= 400 && active;
              boolean disabled = "false".equals(choice) && !overriddenOff;
              boolean policy = choice == null && !gpu && ordinal < 400 && active;
              assertEquals(disabled || policy ? "inactive" : "active", row.status());
              assertEquals(disabled ? "disabled" : overriddenOff ? "operator_override"
                  : policy ? "cpu_default_off" : "explicit_path", row.reason());
              assertEquals(active, row.modelActive());
              assertEquals(!disabled && active ? "active" : "inactive",
                  runtime.functionalStatusByPackage().get("reranker"));
              var citation = runtime.getStatus().onnxFeatures().get(1);
              assertEquals("active", citation.status());
              assertEquals("auto_discovered", citation.reason());
              assertEquals(active, citation.modelActive());
            }
          }
        }
      }
    }
    publish(null, false, 300, false);
    try (var executors = new TestEngineExecutors()) {
      var missing = service(executors, emptySettings(), false, true);
      assertEquals("not_found", missing.getStatus().onnxFeatures().getFirst().reason());
      assertEquals("inactive", missing.functionalStatusByPackage().get("reranker"));
      var unavailable = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          tmp.resolve("broken.json"));
      Files.writeString(unavailable.settingsPath(), "{broken");
      var unknown = service(executors, unavailable, true, true);
      assertEquals("settings_unavailable", unknown.getStatus().onnxFeatures().getFirst().reason());
      assertEquals("unknown", unknown.functionalStatusByPackage().get("reranker"));
    }
  }

  private UiSettingsStore emptySettings() {
    return new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        tmp.resolve("empty-settings.json"));
  }

  private RuntimeActivationService service(TestEngineExecutors executors, UiSettingsStore store,
      boolean found, boolean active) {
    return new RuntimeActivationService(executors, OnlineAiService.unavailable(), store,
        null, null, () -> List.of(
            new OnnxModelStatus("reranker", found, "discovered-reranker", true, active),
            new OnnxModelStatus("citation-scorer", true, "citations", true, active)));
  }
}
