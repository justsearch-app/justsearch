package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OperationPreparationTest {

  @Test
  void passthroughRetainsExactRawArgumentsAndNoReplayPayload() {
    String argumentsJson = "{\"path\":\"/tmp/input\"}";

    OperationPreparation preparation = OperationPreparation.passthrough(argumentsJson);

    assertSame(argumentsJson, preparation.argumentsJson());
    assertNull(preparation.replaySchema());
    assertNull(preparation.replayPayloadJson());
  }

  @Test
  void constructorRequiresBoundedPairedNonBlankReplayValues() {
    assertThrows(
        NullPointerException.class, () -> new OperationPreparation(null, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new OperationPreparation("{}", "v1", null));
    assertThrows(
        IllegalArgumentException.class, () -> new OperationPreparation("{}", null, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new OperationPreparation("{}", " ", "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new OperationPreparation("{}", "v1", " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationPreparation("{}", "x".repeat(129), "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationPreparation("{}", "v1", "x".repeat(200_001)));
  }

  @Test
  void preparationRefusalPreservesTypedFailureExactly() {
    OperationResult refusal =
        OperationResult.failure(
            "generation unavailable", "GENERATION_UNAVAILABLE", Map.of("target", "g1"), true);

    OperationPreparationRefused exception = new OperationPreparationRefused(refusal);

    assertSame(refusal, exception.refusal());
    assertEquals(refusal.message(), exception.getMessage());
    assertSame(refusal.errorDetails(), exception.refusal().errorDetails());
    assertEquals(refusal.errorCode(), exception.refusal().errorCode());
    assertEquals(refusal.retryable(), exception.refusal().retryable());
    OperationResult longest = OperationResult.failure("bounded code", "X".repeat(96), Map.of(), true);
    assertSame(longest, new OperationPreparationRefused(longest).refusal());
  }

  @Test
  void preparationRefusalRejectsSuccessAndMalformedFailureResults() {
    assertThrows(
        NullPointerException.class, () -> new OperationPreparationRefused(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationPreparationRefused(OperationResult.success("unexpected success")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationPreparationRefused(OperationResult.failure("missing code")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationPreparationRefused(
                OperationResult.failure("blank code", " ", Map.of(), false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationPreparationRefused(
                OperationResult.failure("non-token code", "generation-failed", Map.of(), false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationPreparationRefused(
                OperationResult.failure("digit code", "1_GENERATION", Map.of(), false)));
    assertThrows(
        IllegalArgumentException.class,
        ()
            -> new OperationPreparationRefused(
                OperationResult.failure("long code", "X".repeat(97), Map.of(), false)));
  }

  @Test
  void defaultPreparedExecutionPreservesArgumentsContextProvenanceAndRecord() {
    InvocationProvenance provenance =
        InvocationProvenance.uiButton(Instant.parse("2026-09-12T00:00:00Z"));
    EngineContext context = TestEngineContexts.agentLoop("prep-session");
    OperationResult result = OperationResult.success("executed");
    OperationRecordHandle record = recordHandle();
    AtomicReference<String> seenArguments = new AtomicReference<>();
    AtomicReference<InvocationProvenance> seenProvenance = new AtomicReference<>();
    AtomicReference<EngineContext> seenContext = new AtomicReference<>();
    AtomicReference<OperationRecordHandle> seenRecord = new AtomicReference<>();

    OperationHandler handler =
        new OperationHandler() {
          @Override
          public OperationResult execute(String argumentsJson, EngineContext engineContext) {
            throw new AssertionError("recorded path should be used");
          }

          @Override
          public OperationExecution executeRecorded(
              String argumentsJson,
              InvocationProvenance actualProvenance,
              EngineContext actualContext,
              OperationRecordHandle actualRecord) {
            seenArguments.set(argumentsJson);
            seenProvenance.set(actualProvenance);
            seenContext.set(actualContext);
            seenRecord.set(actualRecord);
            return OperationExecution.finished(result);
          }
        };

    String argumentsJson = "{\"exact\":true}";
    OperationExecution execution =
        handler.executePrepared(
            OperationPreparation.passthrough(argumentsJson), provenance, context, record);

    assertSame(result, execution.response());
    assertSame(argumentsJson, seenArguments.get());
    assertSame(provenance, seenProvenance.get());
    assertSame(context, seenContext.get());
    assertSame(record, seenRecord.get());
  }

  @Test
  void defaultPreparedExecutionUsesOrdinaryExecutionWithoutRecord() {
    InvocationProvenance provenance = InvocationProvenance.systemInternal(Instant.EPOCH);
    EngineContext context = TestEngineContexts.agentLoop();
    OperationResult result = OperationResult.success("executed");
    AtomicReference<String> seenArguments = new AtomicReference<>();
    AtomicReference<InvocationProvenance> seenProvenance = new AtomicReference<>();
    AtomicReference<EngineContext> seenContext = new AtomicReference<>();

    OperationHandler handler =
        new OperationHandler() {
          @Override
          public OperationResult execute(String argumentsJson, EngineContext actualContext) {
            throw new AssertionError("context-aware overload should be used");
          }

          @Override
          public OperationResult execute(
              String argumentsJson,
              InvocationProvenance actualProvenance,
              EngineContext actualContext) {
            seenArguments.set(argumentsJson);
            seenProvenance.set(actualProvenance);
            seenContext.set(actualContext);
            return result;
          }
        };

    String argumentsJson = "{\"exact\":true}";
    OperationExecution execution =
        handler.executePrepared(
            OperationPreparation.passthrough(argumentsJson), provenance, context, null);

    assertSame(result, execution.response());
    assertSame(argumentsJson, seenArguments.get());
    assertSame(provenance, seenProvenance.get());
    assertSame(context, seenContext.get());
  }

  @Test
  void defaultPreparedExecutionRefusesReplayPayloadBeforeRunningHandler() {
    OperationHandler handler =
        new OperationHandler() {
          @Override
          public OperationResult execute(String argumentsJson, EngineContext engineContext) {
            throw new AssertionError("replay payload must not reach legacy execution");
          }
        };

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            handler.executePrepared(
                new OperationPreparation("{}", "roots-v1", "{\"roots\":[]}"),
                InvocationProvenance.systemInternal(Instant.EPOCH),
                TestEngineContexts.agentLoop(),
                null));
  }

  private static OperationRecordHandle recordHandle() {
    return new OperationRecordHandle() {
      @Override
      public long id() {
        return 17L;
      }

      @Override
      public String key() {
        return "operation-key";
      }

      @Override
      public void checkpoint(String cursor, long completed, long failed) {}
    };
  }
}
