/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ConfigStore;
import org.junit.jupiter.api.AfterEach;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelRegistry;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 840 Phase 4 — what the install status now says about each component, and what a user is
 * allowed to do about it.
 *
 * <p>Two properties are load-bearing here and neither is visible from a green endpoint alone:
 *
 * <ul>
 *   <li>the transfer-rate fields carry {@code AcquisitionRate}'s explicit UNKNOWN sentinel to the
 *       wire and are dropped back to it on every exit from {@code running} — a completed or stalled
 *       run must never keep publishing the speed it was moving at, and must never publish {@code 0};
 *   <li>declinability is read from {@code Necessity.userDeclinable()} at the moment of the request,
 *       so no id list maintained beside the registry can drift into turning a mandatory component
 *       off.
 * </ul>
 */
final class AiInstallServiceComponentIntentTest {

  @TempDir Path tmp;
  @TempDir Path settingsDir;
  private RuntimeIntentTestFixture fixture;
  private io.justsearch.app.api.operations.OperationAttemptRunner recordedRunner;

  @AfterEach
  void closeFixture() {
    if (fixture != null) fixture.close();
  }

  private AiInstallService recordedService(UiSettingsStore store) throws Exception {
    fixture = new RuntimeIntentTestFixture(settingsDir, store,
        new ConfigStore(ConfigStoreRebuilder.prepare(store.load())));
    recordedRunner = org.mockito.Mockito.spy(fixture.runner());
    return new AiInstallService(null, store, null, null, tmp, null,
        new SettingsServiceImpl(store, recordedRunner));
  }

  private static AiInstallStatus liveStatusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private UiSettingsStore writableStore() {
    return new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, settingsDir.resolve("settings.json"));
  }

  // ── the unknown sentinel ──────────────────────────────────────────────────

  @Test
  @DisplayName("a fresh status publishes -1 for rate and remaining time, and the copy keeps it")
  void unknownIsTheDefaultAndSurvivesSnapshot() {
    AiInstallStatus fresh = new AiInstallStatus();

    assertEquals(-1d, fresh.bytesPerSecond, "a run that has not measured anything knows no rate");
    assertEquals(-1L, fresh.remainingSeconds);

    fresh.bytesPerSecond = 1_234d;
    fresh.remainingSeconds = 56L;
    fresh.paused = true;
    AiInstallStatus copy = fresh.snapshot();

    assertEquals(1_234d, copy.bytesPerSecond, "snapshot() is what the HTTP handler serializes");
    assertEquals(56L, copy.remainingSeconds);
    assertTrue(copy.paused);
  }

  @Test
  @DisplayName("a stalled run reclaimed by the liveness backstop stops publishing its last rate")
  void terminalStateDropsTheRate() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus live = liveStatusOf(svc);
    live.state = "running";
    live.bytesPerSecond = 5_000_000d;
    live.remainingSeconds = 900L;
    // Older than the 5-minute liveness window, so the next read reaps it to `failed` — the real path
    // that ends a wedged run, not a hand-set terminal state.
    live.updatedAtEpochMs = System.currentTimeMillis() - (10 * 60_000L);

    AiInstallStatus after = svc.getStatus();

    assertEquals("failed", after.state, "precondition: the backstop fired");
    assertEquals(
        -1d,
        after.bytesPerSecond,
        "a reclaimed run must not keep claiming 5 MB/s; -1 is 'unknown', 0 would read as 'stopped'");
    assertEquals(-1L, after.remainingSeconds);
  }

  // ── per-component registry projection ─────────────────────────────────────

  @Test
  @DisplayName("package rows carry the registry's description, necessity and derived declinability")
  void packageRowsCarryNecessityAndDescription() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    ModelRegistry registry = svc.getManifest();
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0],
            List.of(),
            List.of(),
            0L,
            List.of("embedding", "reranker", "cuda-runtime"));

    assertTrue(svc.applyInstalledFromPlan(plan, registry), "precondition: the plan flips installed");

    AiInstallStatus.PackageStatus embedding = packageRow(liveStatusOf(svc), "embedding");
    assertEquals("required", embedding.necessity);
    assertFalse(embedding.declinable, "search does not work without embeddings");
    assertFalse(embedding.description.isBlank(), "the registry description finally reaches the wire");

    AiInstallStatus.PackageStatus reranker = packageRow(liveStatusOf(svc), "reranker");
    assertEquals("improves-results", reranker.necessity);
    assertTrue(reranker.declinable);

    AiInstallStatus.PackageStatus cuda = packageRow(liveStatusOf(svc), "cuda-runtime");
    assertEquals("infrastructure", cuda.necessity);
    assertFalse(
        cuda.declinable,
        "cuda-runtime also delivers the cuda12 llama-server; declining it would silently remove chat");
  }

  private static AiInstallStatus.PackageStatus packageRow(AiInstallStatus status, String id) {
    AiInstallStatus.PackageStatus found =
        status.packages.stream().filter(p -> id.equals(p.packageId)).findFirst().orElse(null);
    assertNotNull(found, "expected a status row for " + id);
    return found;
  }

  // ── decline / re-enable ───────────────────────────────────────────────────

  @Test
  @DisplayName("declining a non-declinable component throws PACKAGE_NOT_DECLINABLE, writing nothing")
  void decliningNonDeclinableIsRefused() {
    UiSettingsStore store = writableStore();
    AiInstallService svc = new AiInstallService(null, store, null, null, tmp);

    for (String mandatory : List.of("embedding", "cuda-runtime")) {
      AiInstallException e =
          assertThrows(
              AiInstallException.class, () -> svc.setPackageDeclined(mandatory, true), mandatory);
      assertEquals(ApiErrorCode.PACKAGE_NOT_DECLINABLE, e.errorCode(), mandatory);
      assertEquals(400, e.httpStatus(), mandatory);
    }
    assertTrue(store.load().getDeclinedAiPackages().isEmpty(), "a refusal must not half-write");
  }

  @Test
  @DisplayName("an unknown id is PACKAGE_NOT_FOUND — checked before declinability, so a typo is not"
      + " reported as 'this cannot be turned off'")
  void unknownPackageIsNotFound() {
    AiInstallService svc = new AiInstallService(null, writableStore(), null, null, tmp);

    AiInstallException e =
        assertThrows(AiInstallException.class, () -> svc.setPackageDeclined("embeddings", true));

    assertEquals(ApiErrorCode.PACKAGE_NOT_FOUND, e.errorCode());
    assertEquals(404, e.httpStatus());
  }

  @Test
  @DisplayName("a decline made between polls shows on the next status read")
  void declinedIsRefreshedOnRead() throws Exception {
    UiSettingsStore store = writableStore();
    AiInstallService svc = recordedService(store);
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0], List.of(), List.of(), 0L, List.of("embedding", "reranker"));
    assertTrue(svc.applyInstalledFromPlan(plan, svc.getManifest()));
    assertFalse(packageRow(svc.getStatus(), "reranker").declined, "precondition: nothing declined");

    var before = store.inspect().witness();
    svc.setPackageDeclined("reranker", true);
    var committed = store.inspect().witness();
    assertEquals(before.acceptedRevision() + 1, committed.acceptedRevision());
    assertNotNull(committed.lastCommittedOperationKey());
    svc.setPackageDeclined("reranker", true);
    assertEquals(committed, store.inspect().witness(), "unchanged choice cannot advance its witness");
    org.mockito.Mockito.verify(recordedRunner, org.mockito.Mockito.times(1))
        .accept(org.mockito.ArgumentMatchers.any());

    assertTrue(
        packageRow(svc.getStatus(), "reranker").declined,
        "the package list was built before the decline — the flag has to be re-read, not stamped");
    assertFalse(packageRow(svc.getStatus(), "embedding").declined, "only the named component");

    svc.setPackageDeclined("reranker", false);
    assertFalse(packageRow(svc.getStatus(), "reranker").declined, "withdrawal is visible too");
    assertTrue(store.load().getDeclinedAiPackages().isEmpty());
  }

  @Test
  @DisplayName("withdrawing a decline is never refused on necessity grounds")
  void reEnableIsAlwaysAllowed() {
    AiInstallService svc = new AiInstallService(null, writableStore(), null, null, tmp);

    svc.setPackageDeclined("embedding", false); // no throw: "install this after all" is always valid
  }


  @Test
  void staleChoiceCannotEraseAConcurrentIntent() throws Exception {
    UiSettingsStore store = writableStore();
    recordedService(store);
    var runner = org.mockito.Mockito.spy(fixture.runner());
    org.mockito.Mockito.doAnswer(call -> {
      fixture.spec().setChatEnabled(true);
      return call.callRealMethod();
    }).when(runner).accept(org.mockito.ArgumentMatchers.any());
    var svc = new AiInstallService(null, store, null, null, tmp, null,
        new SettingsServiceImpl(store, runner));
    AiInstallException failure = assertThrows(AiInstallException.class,
        () -> svc.setPackageDeclined("reranker", true));
    assertEquals(ApiErrorCode.AI_INSTALL_ERROR, failure.errorCode());
    assertTrue(fixture.spec().load().chatEnabled());
    assertTrue(store.load().getDeclinedAiPackages().isEmpty());
    assertEquals(1L, store.inspect().witness().acceptedRevision());
  }

  @Test
  void successfulButUnresolvedAttemptCannotReportChoiceSaved() {
    UiSettingsStore store = writableStore();
    var settings = org.mockito.Mockito.mock(io.justsearch.app.api.SettingsService.class);
    var row = org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationRecord.class);
    org.mockito.Mockito.when(row.state())
        .thenReturn(io.justsearch.app.api.operations.OperationState.ACCEPTED);
    org.mockito.Mockito.when(settings.applyInternal(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new io.justsearch.app.api.operations.OperationAttemptRunner.Result(row,
            io.justsearch.agent.api.registry.OperationResult.success("Accepted"),
            new java.util.concurrent.CompletableFuture<>()));
    var svc = new AiInstallService(null, store, null, null, tmp, null, settings);
    var before = store.inspect().witness();
    AiInstallException failure = assertThrows(AiInstallException.class,
        () -> svc.setPackageDeclined("reranker", true));
    assertEquals(ApiErrorCode.AI_INSTALL_ERROR, failure.errorCode());
    assertEquals(before, store.inspect().witness());
    assertTrue(store.load().getDeclinedAiPackages().isEmpty());
  }

  @Test
  void missingOwnerRefusesChangedChoiceWithoutWriting() {
    UiSettingsStore store = writableStore();
    var svc = new AiInstallService(null, store, null, null, tmp);
    var before = store.inspect().witness();
    assertThrows(AiInstallException.class, () -> svc.setPackageDeclined("reranker", true));
    assertEquals(before, store.inspect().witness());
    assertTrue(store.load().getDeclinedAiPackages().isEmpty());
  }

  // ── pause / resume ────────────────────────────────────────────────────────

  @Test
  @DisplayName("pause and resume refuse when no run is in flight, so the gate cannot be pre-armed")
  void pauseResumeRequireARunInFlight() {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);

    for (Runnable call : List.of((Runnable) svc::pauseInstall, svc::resumeInstall)) {
      AiInstallException e = assertThrows(AiInstallException.class, call::run);
      assertEquals(ApiErrorCode.INSTALL_NOT_RUNNING, e.errorCode());
      assertEquals(409, e.httpStatus());
    }
    assertFalse(
        svc.isInstallPaused(),
        "a refused pause must leave the gate untouched — an armed gate would halt the NEXT install");
  }
}
