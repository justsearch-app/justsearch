/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsInternalProducerTest {
  @TempDir Path directory;

  @Test
  void internalCandidateCommitsThroughTheSameOwnerAndReturnsItsWitness() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var snapshot = fixture.settings().inspect();
      snapshot.settings().setTheme("dark");
      var result = new SettingsServiceImpl(fixture.settings(), fixture.runner())
          .applyInternal(snapshot.settings(), snapshot.witness(), TestEngineContexts.internal());
      assertEquals(OperationState.COMPLETE, result.record().state());
      assertTrue(result.response().success());
      assertEquals(1L, result.response().structuredData().get("acceptedRevision"));
      assertEquals(result.record().key(), fixture.settings().inspect().witness().lastCommittedOperationKey());
      assertEquals("dark", fixture.settings().inspect().settings().getTheme());
      assertFalse(result.record().descriptor().identityJson().contains("dark"), "row identity stores a digest");
    }
  }

  @Test
  void staleWholeDocumentCannotEraseAConcurrentMutation() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var snapshot = fixture.settings().inspect();
      snapshot.settings().setTheme("dark");
      fixture.spec().setChatEnabled(true);
      var current = fixture.settings().inspect().witness();
      var result = new SettingsServiceImpl(fixture.settings(), fixture.runner())
          .applyInternal(snapshot.settings(), snapshot.witness(), TestEngineContexts.internal());
      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("VERSION_CONFLICT", result.response().errorCode().orElseThrow());
      assertNull(result.record().expectedSettingsRevision(), "stale refusal cannot arm");
      assertEquals(current, fixture.settings().inspect().witness());
      assertTrue(fixture.spec().load().chatEnabled());
      assertEquals("system", fixture.settings().inspect().settings().getTheme());
    }
  }

  @Test
  void acceptanceFailureLeavesTheWholeDocumentUntouched() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory, false)) {
      var snapshot = fixture.settings().inspect();
      snapshot.settings().setTheme("dark");
      byte[] before = Files.readAllBytes(fixture.settings().settingsPath());
      var runner = spy(fixture.runner());
      doThrow(new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null))
          .when(runner).accept(any());
      var service = new SettingsServiceImpl(fixture.settings(), runner);
      assertThrows(OperationStoreException.class,
          () -> service.applyInternal(snapshot.settings(), snapshot.witness(), TestEngineContexts.internal()));
      assertArrayEquals(before, Files.readAllBytes(fixture.settings().settingsPath()));
    }
  }

  @Test
  void candidateIsFrozenBeforeAcceptanceAndMatchesTheRecordedDigest() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var snapshot = fixture.settings().inspect();
      snapshot.settings().setTheme("dark");
      var expectedDescriptor = io.justsearch.app.api.operations.OperationDescriptor.invocation(
          io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY, "settings.apply-internal",
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              java.util.Map.of("settings", snapshot.settings(), "expected", snapshot.witness())), false);
      var runner = spy(fixture.runner());
      doAnswer(call -> {
        snapshot.settings().setTheme("light");
        return call.callRealMethod();
      }).when(runner).accept(any());
      var result = new SettingsServiceImpl(fixture.settings(), runner)
          .applyInternal(snapshot.settings(), snapshot.witness(), TestEngineContexts.internal());
      assertEquals(OperationState.COMPLETE, result.record().state());
      assertTrue(expectedDescriptor.hasSameIdentity(result.record().descriptor()),
          "the store canonicalizes JSON member order; the complete identity must still match");
      assertEquals("dark", fixture.settings().inspect().settings().getTheme());
      assertEquals("light", snapshot.settings().getTheme());
    }
  }

  @Test
  void internalClientLabelCannotAuthorizeAgentOrWorkflowTransport() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var snapshot = fixture.settings().inspect();
      var runner = spy(fixture.runner());
      var service = new SettingsServiceImpl(fixture.settings(), runner);
      for (var context : java.util.List.of(TestEngineContexts.agent(), TestEngineContexts.workflow())) {
        assertThrows(IllegalArgumentException.class,
            () -> service.applyInternal(snapshot.settings(), snapshot.witness(), context));
      }
      verifyNoInteractions(runner);
    }
  }

  @Test
  void publicIngressCannotUseTheFreshInternalKeyPath() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var snapshot = fixture.settings().inspect();
      var runner = spy(fixture.runner());
      var service = new SettingsServiceImpl(fixture.settings(), runner);
      assertThrows(IllegalArgumentException.class,
          () -> service.applyInternal(snapshot.settings(), snapshot.witness(), TestEngineContexts.ui()));
      verifyNoInteractions(runner);
    }
  }
}
