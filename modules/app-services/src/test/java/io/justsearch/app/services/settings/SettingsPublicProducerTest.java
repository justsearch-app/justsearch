/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Contract tests for the runner-backed public settings producer. */
class SettingsPublicProducerTest {
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  @TempDir Path directory;

  @Test
  void publicPatchRequiresWitnessAndCommitsThroughTheRealOwner() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var before = fixture.settings().inspect().witness();
      String key = key();
      var result = service(fixture).applyPublic(
          patch(key, before, "{\"ui\":{\"theme\":\"dark\"}}"), null,
          TestEngineContexts.ui());

      assertEquals(OperationState.COMPLETE, result.record().state());
      assertTrue(result.response().success());
      assertEquals("dark", fixture.settings().inspect().settings().getTheme());
      assertEquals(new SettingsWitness(1, key), fixture.settings().inspect().witness());
      assertEquals(key, result.response().structuredData().get("operationKey"));
      assertEquals(new SettingsWitness(1, key), result.response().structuredData().get("witness"));
    }
  }

  @Test
  void matchingRetryReturnsTheOriginalReceiptBeforeInspectingSettings() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var store = spy(fixture.settings());
      var service = new SettingsServiceImpl(store, fixture.runner());
      var witness = store.inspect().witness();
      String key = key();
      var input = patch(key, witness, "{\"ui\":{\"theme\":\"dark\"}}");
      var first = service.applyPublic(input, null, TestEngineContexts.ui());

      clearInvocations(store);
      var replay = service.applyPublic(input, null, TestEngineContexts.ui());

      assertEquals(first.record().key(), replay.record().key());
      assertEquals(key, replay.response().structuredData().get("operationKey"));
      assertEquals(new SettingsWitness(1, key), replay.response().structuredData().get("witness"));
      assertFalse(replay.response().structuredData().containsKey("ui"));
      verifyNoInteractions(store);
    }
  }

  @Test
  void changedInputUnderTheSameKeyIsRejectedBeforeInspectingSettings() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var store = spy(fixture.settings());
      var service = new SettingsServiceImpl(store, fixture.runner());
      var witness = store.inspect().witness();
      String key = key();
      service.applyPublic(patch(key, witness, "{\"ui\":{\"theme\":\"dark\"}}"), null,
          TestEngineContexts.ui());

      clearInvocations(store);
      var reused = assertThrows(OperationStoreException.class,
          () -> service.applyPublic(patch(key, witness, "{\"ui\":{\"theme\":\"light\"}}"), null,
              TestEngineContexts.ui()));

      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, reused.code());
      verifyNoInteractions(store);
    }
  }

  @Test
  void staleWitnessRecordsAFailedRowWithoutChangingSettings() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var service = service(fixture);
      var initial = fixture.settings().inspect().witness();
      service.applyPublic(patch(key(), initial, "{\"ui\":{\"theme\":\"dark\"}}"), null,
          TestEngineContexts.ui());
      var committedWitness = fixture.settings().inspect().witness();
      String staleKey = key();

      var result = service.applyPublic(patch(staleKey, initial, "{\"ui\":{\"theme\":\"light\"}}"), null,
          TestEngineContexts.ui());

      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("VERSION_CONFLICT", result.response().errorCode().orElseThrow());
      assertEquals("dark", fixture.settings().inspect().settings().getTheme());
      assertEquals(committedWitness, fixture.settings().inspect().witness());
    }
  }

  @Test
  void partialPatchPreservesFieldsOmittedByTheRequest() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var service = service(fixture);
      var firstWitness = fixture.settings().inspect().witness();
      var first = service.applyPublic(patch(key(), firstWitness,
          "{\"ui\":{\"theme\":\"dark\",\"density\":\"compact\"}}"), null,
          TestEngineContexts.ui());

      var second = service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"theme\":\"light\"}}"), null, TestEngineContexts.ui());

      assertTrue(first.response().success());
      assertTrue(second.response().success());
      assertEquals("light", fixture.settings().inspect().settings().getTheme());
      assertEquals("compact", fixture.settings().inspect().settings().getDensity());
    }
  }

  @Test
  void terminalRetryReturnsTheOriginalNestedWitnessWithoutCurrentSettings() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var store = spy(fixture.settings());
      var service = new SettingsServiceImpl(store, fixture.runner());
      var initial = store.inspect().witness();
      String keyA = key();
      var aToB = patch(keyA, initial, "{\"ui\":{\"theme\":\"dark\"}}");
      service.applyPublic(aToB, null, TestEngineContexts.ui());
      String keyB = key();
      service.applyPublic(patch(keyB, store.inspect().witness(), "{\"ui\":{\"theme\":\"light\"}}"), null,
          TestEngineContexts.ui());

      clearInvocations(store);
      var replay = service.applyPublic(aToB, null, TestEngineContexts.ui());

      assertEquals(OperationState.COMPLETE, replay.record().state());
      assertEquals(keyA, replay.response().structuredData().get("operationKey"));
      assertEquals(new SettingsWitness(1, keyA), replay.response().structuredData().get("witness"));
      assertFalse(replay.response().structuredData().containsKey("ui"));
      verifyNoInteractions(store);
      assertEquals("light", fixture.settings().inspect().settings().getTheme());
    }
  }

  @Test
  void staleModeSequenceSkipsOnlyModeAndAHigherSequenceAdvancesOrdering() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var service = service(fixture);
      var first = service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"mode\":\"advanced\"}}"), "client:2", TestEngineContexts.ui());
      assertTrue(first.response().success());

      var staleMode = service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"mode\":\"simple\",\"theme\":\"dark\"}}"), "client:1",
          TestEngineContexts.ui());
      assertTrue(staleMode.response().success());
      assertEquals("advanced", fixture.settings().inspect().settings().getMode());
      assertEquals("dark", fixture.settings().inspect().settings().getTheme());

      service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"mode\":\"advanced\"}}"), "client:3", TestEngineContexts.ui());
      service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"mode\":\"simple\"}}"), "client:2", TestEngineContexts.ui());
      assertEquals("advanced", fixture.settings().inspect().settings().getMode());
    }
  }

  @Test
  void modeIntentTrackingUsesA64EntryLru() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var service = service(fixture);
      for (int client = 0; client < 64; client++) {
        service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
            "{\"ui\":{\"mode\":\"advanced\"}}"), "client-" + client + ":1",
            TestEngineContexts.ui());
      }

      service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"mode\":\"simple\"}}"), "client-0:2", TestEngineContexts.ui());
      service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"mode\":\"advanced\"}}"), "client-64:1", TestEngineContexts.ui());
      service.applyPublic(patch(key(), fixture.settings().inspect().witness(),
          "{\"ui\":{\"mode\":\"simple\"}}"), "client-1:1", TestEngineContexts.ui());

      assertEquals("simple", fixture.settings().inspect().settings().getMode(),
          "the least recently used client must be evicted at the 64-entry bound");
    }
  }

  @Test
  void chatNudgeRunsOnceForAnActualCommitAndNeverForReplay() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      AtomicInteger nudges = new AtomicInteger();
      var service = new SettingsServiceImpl(fixture.settings(), fixture.runner(), nudges::incrementAndGet);
      var witness = fixture.settings().inspect().witness();
      String key = key();
      var input = patch(key, witness, "{\"ui\":{\"chatEnabled\":true}}");

      service.applyPublic(input, null, TestEngineContexts.ui());
      service.applyPublic(input, null, TestEngineContexts.ui());

      assertEquals(1, nudges.get());
      assertTrue(fixture.settings().inspect().settings().getChatEnabled());
    }
  }

  @Test
  void missingWitnessAndInvalidOperationKeyAreRejectedBeforeAcceptance() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var service = service(fixture);
      assertThrows(IllegalArgumentException.class,
          () -> service.applyPublic(JSON.readValue(
              "{\"ui\":{\"theme\":\"dark\"},\"operationKey\":\"" + key() + "\"}",
              SettingsV2.class), null, TestEngineContexts.ui()));
      assertThrows(OperationStoreException.class,
          () -> service.applyPublic(patch("not-a-uuid", fixture.settings().inspect().witness(),
              "{\"ui\":{\"theme\":\"dark\"}}"), null, TestEngineContexts.ui()));
    }
  }

  @Test
  void emptyAndExplicitNullSectionsHaveTheSamePreserveIdentity() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var service = service(fixture);
      var witness = fixture.settings().inspect().witness();
      String key = key();
      var first = service.applyPublic(patch(key, witness, "{}"), null, TestEngineContexts.ui());
      for (String body : java.util.List.of("{\"ui\":{}}", "{\"llm\":{}}",
          "{\"ui\":{\"theme\":null},\"llm\":{\"modelPath\":null}}")) {
        var replay = service.applyPublic(patch(key, witness, body), null, TestEngineContexts.ui());
        assertEquals(first.record().id(), replay.record().id());
        assertEquals(first.response().structuredData().get("witness"), replay.response().structuredData().get("witness"));
      }
      assertEquals(1, fixture.settings().inspect().witness().acceptedRevision());
    }
  }

  private static SettingsServiceImpl service(RuntimeIntentTestFixture fixture) {
    return new SettingsServiceImpl(fixture.settings(), fixture.runner());
  }

  private static String key() {
    return OperationKeys.generate(Clock.systemUTC());
  }

  private static SettingsV2 patch(String key, SettingsWitness witness, String ui) throws Exception {
    var tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(ui);
    tree.set("witness", JSON.valueToTree(witness));
    tree.put("operationKey", key);
    return JSON.treeToValue(tree, SettingsV2.class);
  }
}
