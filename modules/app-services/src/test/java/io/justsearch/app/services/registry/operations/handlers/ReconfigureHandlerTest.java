/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.settings.UiSettingsV2;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.configuration.model.ChatModelProfile;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Focused prepared and durable-identity tests for {@link ReconfigureHandler}. */
final class ReconfigureHandlerTest {
  private static final EngineContext CONTEXT = TestEngineContexts.internal();
  private static final InvocationProvenance PROVENANCE =
      InvocationProvenance.systemInternal(java.time.Instant.EPOCH);

  @Test
  void preparationIsTypedAndDoesNotAskTheSettingsOwnerToAcceptOrStart() {
    String key = key();
    String arguments = arguments(settings(key), "client:7");
    ReconfigureHandler handler = new ReconfigureHandler(() -> {
      throw new AssertionError("preparation must not resolve or invoke the settings owner");
    });

    OperationPreparation prepared = handler.prepare(arguments, PROVENANCE, CONTEXT);
    handler.validatePreparation(prepared);

    assertEquals(ReconfigureHandler.SCHEMA, prepared.replaySchema());
    assertEquals(OperationPreparation.Content.METADATA, prepared.content());
    assertEquals(arguments, prepared.argumentsJson());
    assertTrue(prepared.replayPayloadJson().contains("lastCommittedOperationKey"));
    assertTrue(prepared.replayPayloadJson().contains(key));
  }

  @Test
  void malformedOrIncompleteEnvelopeIsATypedBadRequestRefusal() {
    ReconfigureHandler handler = new ReconfigureHandler(() -> null);
    for (String malformed : List.of("{}", "{\"settings\":{},\"modeIntent\":null}",
        "{\"settings\":{},\"modeIntent\":3}", "not-json")) {
      OperationPreparationRefused refusal = assertThrows(OperationPreparationRefused.class,
          () -> handler.prepare(malformed, PROVENANCE, CONTEXT));
      assertFalse(refusal.refusal().success());
      assertEquals("BAD_REQUEST", refusal.refusal().errorCode().orElseThrow());
    }
  }

  @Test
  void acceptedExecutionPassesTheFrozenSettingsAndModeIntentToTheOwner() {
    String key = key();
    String arguments = arguments(settings(key), "client:9");
    CapturingSettings owner = new CapturingSettings();
    ReconfigureHandler handler = new ReconfigureHandler(() -> owner);
    OperationPreparation prepared = handler.prepare(arguments, PROVENANCE, CONTEXT);
    OperationRecordHandle record = record(key);

    OperationExecution execution = handler.executePrepared(prepared, PROVENANCE, CONTEXT, record);

    assertSame(owner.result, execution.response());
    assertEquals(settings(key), owner.settings.get());
    assertEquals("client:9", owner.modeIntent.get());
    assertSame(CONTEXT, owner.context.get());
    assertSame(record, owner.record.get());
    assertFalse(owner.refreshInference);
  }

  @Test
  void acceptedRefreshIntentReachesTheSettingsOwnerAndCannotBeChangedInReplay() {
    String key = key();
    CapturingSettings owner = new CapturingSettings();
    ReconfigureHandler handler = new ReconfigureHandler(() -> owner);
    OperationPreparation prepared = handler.prepare(arguments(settings(key), null, true),
        PROVENANCE, CONTEXT);
    handler.executePrepared(prepared, PROVENANCE, CONTEXT, record(key));

    assertTrue(owner.refreshInference);
    assertEquals(settings(key), owner.settings.get());
    OperationPreparation tampered = new OperationPreparation(prepared.argumentsJson(),
        prepared.replaySchema(), prepared.replayPayloadJson().replace(
            "\"refreshInference\":true", "\"refreshInference\":false"), prepared.content());
    assertThrows(IllegalArgumentException.class, () -> handler.validatePreparation(tampered));
  }

  @Test
  void refreshFreezesTheServingProfileBeforeAcceptance() {
    String key = key();
    CapturingSettings owner = new CapturingSettings();
    owner.servingProfileId = "standard";
    ReconfigureHandler handler = new ReconfigureHandler(() -> owner);
    OperationPreparation prepared = handler.prepare(arguments(settings(key), null, true),
        PROVENANCE, CONTEXT);
    owner.servingProfileId = "compact";

    handler.executePrepared(prepared, PROVENANCE, CONTEXT, record(key));

    assertEquals(new SettingsCandidateContext(ChatModelProfile.STANDARD, true),
        owner.candidateContext);
    assertTrue(prepared.replayPayloadJson().contains("standard"));
  }

  @Test
  void legacyAcceptedRefreshRetainsItsOriginalNoProfileMeaning() {
    String key = key();
    String arguments = arguments(settings(key), null, true);
    CapturingSettings owner = new CapturingSettings();
    ReconfigureHandler handler = new ReconfigureHandler(() -> owner);
    OperationPreparation legacy = new OperationPreparation(arguments, "settings-reconfigure-v1",
        arguments, OperationPreparation.Content.METADATA);

    handler.executePrepared(legacy, PROVENANCE, CONTEXT, record(key));

    assertEquals(new SettingsCandidateContext(null, true), owner.candidateContext);
  }

  @Test
  void replayAndAcceptedRecordMustRetainTheOperationKey() {
    String key = key();
    ReconfigureHandler handler = new ReconfigureHandler(() -> new CapturingSettings());
    OperationPreparation prepared = handler.prepare(arguments(settings(key), null), PROVENANCE, CONTEXT);
    OperationPreparation tampered = new OperationPreparation(
        prepared.argumentsJson(), prepared.replaySchema(),
        prepared.replayPayloadJson().replace(key, key()), prepared.content());
    assertThrows(IllegalArgumentException.class, () -> handler.validatePreparation(tampered));
    assertThrows(IllegalArgumentException.class,
        () -> handler.executePrepared(prepared, PROVENANCE, CONTEXT, record(key())));
  }

  @Test
  void directExecutionCannotBypassTheAcceptedRunnerRecord() {
    ReconfigureHandler handler = new ReconfigureHandler(() -> new CapturingSettings());
    assertThrows(IllegalStateException.class, () -> handler.execute("{}", CONTEXT));
    OperationPreparation prepared = handler.prepare(arguments(settings(key()), null), PROVENANCE, CONTEXT);
    assertThrows(NullPointerException.class,
        () -> handler.executePrepared(prepared, PROVENANCE, CONTEXT, null));
  }

  private static String arguments(SettingsV2 settings, String modeIntent) {
    return arguments(settings, modeIntent, false);
  }

  private static String arguments(SettingsV2 settings, String modeIntent, boolean refreshInference) {
    return HandlerJson.MAPPER.writeValueAsString(
        new ReconfigureHandler.Envelope(settings, modeIntent, refreshInference));
  }

  private static SettingsV2 settings(String operationKey) {
    return new SettingsV2(
        new UiSettingsV2("system", false, "comfort", false, "open", null, false, "simple", false,
            List.of(), false),
        null, null, null, new SettingsWitness(0, null), operationKey, null, null);
  }

  private static String key() {
    return io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
  }

  private static OperationRecordHandle record(String key) {
    return new OperationRecordHandle() {
      @Override public long id() { return 41L; }
      @Override public String key() { return key; }
      @Override public void checkpoint(String cursor, long completed, long failed) {}
    };
  }

  private static final class CapturingSettings implements SettingsService {
    private final OperationResult result = OperationResult.success("committed");
    private final AtomicReference<SettingsV2> settings = new AtomicReference<>();
    private final AtomicReference<String> modeIntent = new AtomicReference<>();
    private final AtomicReference<EngineContext> context = new AtomicReference<>();
    private final AtomicReference<OperationRecordHandle> record = new AtomicReference<>();
    private boolean refreshInference;
    private String servingProfileId;
    private SettingsCandidateContext candidateContext;

    @Override public String servingRefreshProfileId() { return servingProfileId; }

    @Override public OperationResult applyAccepted(SettingsV2 input, String modeIntentHeader,
        EngineContext context, OperationRecordHandle record) {
      this.settings.set(input);
      this.modeIntent.set(modeIntentHeader);
      this.context.set(context);
      this.record.set(record);
      return result;
    }

    @Override public OperationResult applyAccepted(SettingsV2 input, String modeIntentHeader,
        EngineContext context, OperationRecordHandle record, boolean refreshInference) {
      this.refreshInference = refreshInference;
      return applyAccepted(input, modeIntentHeader, context, record);
    }

    @Override public OperationResult applyAccepted(SettingsV2 input, String modeIntentHeader,
        EngineContext context, OperationRecordHandle record,
        SettingsCandidateContext candidateContext) {
      this.candidateContext = candidateContext;
      this.refreshInference = candidateContext.forceGenerativeRefresh();
      return applyAccepted(input, modeIntentHeader, context, record);
    }

    @Override public OperationAttemptRunner.Result applyInternal(io.justsearch.app.api.UiSettings candidate,
        SettingsWitness expected, EngineContext context) { throw new UnsupportedOperationException(); }

    @Override public OperationAttemptRunner.Result applyPublic(SettingsV2 input, String modeIntentHeader,
        EngineContext context) { throw new UnsupportedOperationException(); }

    @Override public OperationPreparation prepareReset(String argumentsJson) {
      throw new UnsupportedOperationException();
    }

    @Override public OperationResult resetToDefaults(OperationRecordHandle record) {
      throw new UnsupportedOperationException();
    }
  }
}
