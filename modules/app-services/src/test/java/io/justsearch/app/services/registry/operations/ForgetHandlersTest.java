/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Actual handlers over a fixed owner port; reflection lets f8 fail a behavior assertion. */
final class ForgetHandlersTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String KEY = "01993ba0-0000-7000-8000-000000000001";
  @TempDir Path directory;
  private final AtomicInteger prepares = new AtomicInteger();
  private final AtomicInteger effects = new AtomicInteger();
  private String savedPath;
  private String decidedKey;

  private EngineContext context(EngineContext.ClientKind kind) {
    return EngineProvenance.context(kind, "forget-handler-test", Optional.empty(), Optional.empty(),
        kind == EngineContext.ClientKind.MCP_CLIENT ? TransportTag.MCP : TransportTag.BUTTON,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
  }
  private OperationHandler handler(String decision) {
    Class<?> port = assertDoesNotThrow(() -> Class.forName("io.justsearch.app.api.operations.ForgetService"));
    Object owner = Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
      return switch (method.getName()) {
        case "prepare" -> {
          prepares.incrementAndGet();
          String path = (String) args[0]; String scope = args[1].toString();
          var plan = new LinkedHashMap<String, Object>();
          plan.put("version", 1); plan.put("path", path); plan.put("scope", scope);
          plan.put("keepOut", scope.equals("FOLDER") ? Map.of("literal", Map.of("version", 1,
              "id", "00000000-0000-4000-8000-000000000001", "path", path, "scope", scope)) : null);
          plan.put("watchedRoots", List.of()); plan.put("source", "FILE_SYSTEM");
          yield JSON.readValue(JSON.writeValueAsString(plan), Class.forName("io.justsearch.app.api.operations.ForgetPlan"));
        }
        case "observe" -> Optional.of(JSON.readValue(JSON.writeValueAsString(Map.of("operationKey", KEY,
            "path", savedPath, "scope", "FILE", "status", "INCOMPLETE", "removal", "UNKNOWN",
            "keepOut", "INSTALLED", "reason", "OUTCOME_UNCONFIRMED", "explanation", "Not confirmed")),
            Class.forName("io.justsearch.app.api.operations.ForgetOutcome")));
        case "retry", "withdraw" -> {
          effects.incrementAndGet(); decidedKey = (String) args[0]; yield OperationResult.success("decision received");
        }
        case "execute" -> { effects.incrementAndGet(); yield OperationExecution.finished(OperationResult.success("executed")); }
        default -> throw new AssertionError("Unexpected owner call " + method.getName());
      };
    });
    return assertDoesNotThrow(() -> {
      if (decision == null) return (OperationHandler) Class.forName(
          "io.justsearch.app.services.registry.operations.handlers.ForgetIndexedPathHandler")
          .getConstructor(Supplier.class).newInstance((Supplier<Object>) () -> owner);
      Class<?> type = Class.forName("io.justsearch.app.services.registry.operations.handlers.ForgetDecisionHandler");
      Class<?> choice = Class.forName(type.getName() + "$Decision");
      Object selected = java.util.Arrays.stream(choice.getEnumConstants()).filter(value -> value.toString().equals(decision)).findFirst().orElseThrow();
      return (OperationHandler) type.getConstructor(Supplier.class, choice).newInstance((Supplier<Object>) () -> owner, selected);
    });
  }
  private OperationPreparation prepare(OperationHandler handler, String arguments, EngineContext context) {
    return handler.prepare(arguments, EngineProvenance.invocation(context, ExecutorTag.UI,
        Clock.systemUTC().instant(), Optional.empty()), context);
  }
  private OperationRecordHandle record() {
    return new OperationRecordHandle() {
      @Override public long id() { return 1; }
      @Override public String key() { return KEY; }
      @Override public void checkpoint(String cursor, long completed, long failed) { fail("preparation tests perform no checkpoints"); }
    };
  }

  @Test void S56ExactConsentFreezesEveryByteAndStatesOutsideFileException() {
    var handler = handler(null); String exact = directory.resolve("file\\[1].txt ").toString();
    var prepared = prepare(handler, JSON.writeValueAsString(Map.of("path", exact, "scope", "FILE")), context(EngineContext.ClientKind.WEBVIEW));
    String preview = handler.approvalPreview(prepared).summary();
    assertTrue(preview.contains(JSON.writeValueAsString(exact)));
    assertTrue(preview.contains("trailing whitespace"));
    assertTrue(preview.contains("no permanent keep-out"));
    assertTrue(preview.contains("new explicit add or import"));
    assertTrue(preview.contains("Original files and retained answers, logs and backups are not erased"));
    assertEquals(exact, JSON.readTree(prepared.replayPayloadJson()).path("path").asString());
    assertEquals(1, prepares.get()); assertEquals(0, effects.get());
  }

  @Test void RF55FolderConsentDistinguishesUnixWhitespaceControlsAndMetacharacters() {
    var handler = handler(null); var previews = new java.util.HashSet<String>();
    for (String suffix : List.of("name", "name ", "name\t", "back\\name", "back/name", "name\u00a0", "name\u200b")) {
      String path = directory.resolve(suffix).toString();
      var prepared = prepare(handler, JSON.writeValueAsString(Map.of("path", path, "scope", "FOLDER")), context(EngineContext.ClientKind.WEBVIEW));
      String preview = handler.approvalPreview(prepared).summary(); previews.add(preview);
      String expected = JSON.writeValueAsString(path).replace("\u00a0", "\\u00a0").replace("\u200b", "\\u200b");
      assertTrue(preview.contains(expected)); assertTrue(preview.contains("includes descendants"));
      assertFalse(preview.contains("00000000-0000-4000-8000-000000000001"), "private token is not product consent wording");
    }
    assertEquals(7, previews.size()); assertEquals(0, effects.get());
  }

  @Test void N55UnknownDuplicateAndCoercedPublicFieldsAndOversizedApprovalRefuseWithoutEffects() {
    var handler = handler(null); String path = JSON.writeValueAsString(directory.resolve("safe").toString());
    for (String arguments : List.of("{\"path\":" + path + ",\"scope\":\"FILE\",\"keepOut\":null}",
        "{\"path\":" + path + ",\"path\":" + path + ",\"scope\":\"FILE\"}",
        "{\"path\":" + path + ",\"scope\":0}", "{\"path\":7,\"scope\":\"FILE\"}")) {
      assertThrows(OperationPreparationRefused.class, () -> prepare(handler, arguments, context(EngineContext.ClientKind.WEBVIEW)));
    }
    assertEquals(0, prepares.get());
    assertThrows(OperationPreparationRefused.class, () -> prepare(handler,
        JSON.writeValueAsString(Map.of("path", directory.resolve("x".repeat(9_000)).toString(), "scope", "FILE")), context(EngineContext.ClientKind.WEBVIEW)));
    assertEquals(0, effects.get());
  }

  @Test void N55OnlyWebviewCanPrepareOrExecuteEvenWithAValidFrozenPreparation() {
    var handler = handler(null);
    var arguments = JSON.writeValueAsString(Map.of("path", directory.resolve("safe").toString(), "scope", "FILE"));
    var prepared = prepare(handler, arguments, context(EngineContext.ClientKind.WEBVIEW));
    for (var kind : List.of(EngineContext.ClientKind.MCP_CLIENT, EngineContext.ClientKind.INTERNAL,
        EngineContext.ClientKind.CLI, EngineContext.ClientKind.PAIRED_DEVICE)) {
      var caller = context(kind);
      assertThrows(OperationPreparationRefused.class, () -> prepare(handler, arguments, caller));
      assertThrows(OperationPreparationRefused.class, () -> handler.executePrepared(prepared,
          EngineProvenance.invocation(caller, ExecutorTag.UI, Clock.systemUTC().instant(), Optional.empty()), caller, record()));
    }
    assertEquals(1, prepares.get()); assertEquals(0, effects.get());
    assertThrows(IllegalStateException.class, () -> handler.execute(arguments, context(EngineContext.ClientKind.WEBVIEW)));
  }

  @Test void WD55RetryAndWithdrawUseExactSavedConsentAndCannotRetargetTheOriginalKey() {
    savedPath = directory.resolve("saved\\file ").toString();
    for (String decision : List.of("RETRY", "WITHDRAW")) {
      var handler = handler(decision); var caller = context(EngineContext.ClientKind.WEBVIEW);
      var prepared = prepare(handler, JSON.writeValueAsString(Map.of("forgetKey", KEY)), caller);
      String frozen = handler.approvalPreview(prepared).summary();
      assertTrue(frozen.contains(JSON.writeValueAsString(savedPath))); assertTrue(frozen.contains("trailing whitespace"));
      if (decision.equals("WITHDRAW")) assertTrue(frozen.contains("Prior index removal may already have happened"));
      String original = savedPath; savedPath = directory.resolve("different").toString();
      assertEquals(frozen, handler.approvalPreview(prepared).summary());
      handler.executePrepared(prepared, EngineProvenance.invocation(caller, ExecutorTag.UI,
          Clock.systemUTC().instant(), Optional.empty()), caller, record());
      assertEquals(KEY, decidedKey);
      var changed = new OperationPreparation("{\"forgetKey\":\"01993ba0-0000-7000-8000-000000000002\"}",
          prepared.replaySchema(), prepared.replayPayloadJson());
      assertThrows(IllegalArgumentException.class, () -> handler.validatePreparation(changed));
      savedPath = original;
    }
    assertEquals(2, effects.get());
  }
}
