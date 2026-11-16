/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.excludes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.registry.operations.handlers.ApplyExcludesHandler;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Slice 6b-2 R6: Library's "Remove from search" is consent for one saved list. core.apply-excludes
 * carries that list and removes nothing when the rules in effect differ from it at apply time.
 */
final class ExcludesConsentTest {
  @TempDir Path root;
  private ConfigStore previous;
  private final IndexingService indexing = mock(IndexingService.class);
  private final io.justsearch.core.context.EngineContext context = TestEngineContexts.durableInternal();
  private final ApplyExcludesHandler handler = new ApplyExcludesHandler(() -> new ExcludesServiceImpl(() -> indexing));
  private static final String LITERAL = "{\"literal\":{\"version\":1,\"id\":\"aa10bcec-4102-47f8-a26d-d02346c8a210\","
      + "\"path\":\"/docs/kept-out.txt\",\"scope\":\"FILE\"}}";

  @BeforeEach void setup() throws Exception {
    previous = ConfigStore.globalOrNull();
    effective("[\"*.log\"]");
    when(indexing.getWatchedRoots(context)).thenReturn(List.of(new IndexingService.WatchedRoot("docs", root)));
    when(indexing.deleteDocByIdWithOutcome(anyString(), eq(context))).thenReturn(new IndexingService.DeletionOutcome(true, ""));
    when(indexing.deleteDocsByPathPrefixWithOutcome(any(), eq(context))).thenReturn(new IndexingService.PrefixDeletionOutcome(true, 1, ""));
    Files.writeString(root.resolve("a.log"), "a");
    Files.writeString(root.resolve("b.txt"), "b");
    Files.createDirectories(root.resolve("sub"));
    Files.writeString(root.resolve("sub").resolve("c.txt"), "c");
  }
  @AfterEach void restore() { TestResolvedConfigHelper.restoreGlobal(previous); }

  /** The rules in effect, as a settings save (this window or another) publishes them. */
  private static void effective(String rawRules) {
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", rawRules).build()));
  }

  private void nothingRemoved() {
    verify(indexing, never()).deleteDocByIdWithOutcome(anyString(), any());
    verify(indexing, never()).deleteDocsByPathPrefixWithOutcome(any(), any());
    verify(indexing, never()).deleteExcludedImportMember(anyString(), anyString(), any());
  }

  @Test void removalUnderTheListAskedAboutAppliesExactlyThatList() {
    OperationResult result = handler.execute("{\"rules\":[\"*.log\"]}", context);
    assertTrue(result.success(), () -> result.message());
    verify(indexing).deleteDocByIdWithOutcome(root.resolve("a.log").toString(), context);
    verify(indexing, never()).deleteDocByIdWithOutcome(eq(root.resolve("b.txt").toString()), any());
  }

  @Test void aListSavedElsewhereAfterTheAskRemovesNothing() {
    // The ask was answered for ["*.log"]; another window saved "*" before the removal ran.
    effective("[\"*.log\",\"*\"]");
    OperationResult result = handler.execute("{\"rules\":[\"*.log\"]}", context);
    assertFalse(result.success(), "consent for one list never applies another");
    assertEquals("EXCLUDES_CHANGED", result.errorCode().orElseThrow());
    nothingRemoved();
  }

  @Test void anOverrideInEffectRemovesNothingForTheSavedList() {
    // The saved list does not take effect while an override is set: consent for it applies nothing.
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .put("justsearch.ui.exclude_patterns", 300, "settings.json", null, "[\"*.log\"]")
        .put("justsearch.ui.exclude_patterns", 400, "env_var", "JUSTSEARCH_UI_EXCLUDE_PATTERNS", "[\"*.txt\"]").build()));
    OperationResult result = handler.execute("{\"rules\":[\"*.log\"]}", context);
    assertFalse(result.success());
    assertEquals("EXCLUDES_CHANGED", result.errorCode().orElseThrow());
    nothingRemoved();
  }

  @Test void exactPathEntriesArePartOfTheConsentedList() {
    effective("[\"*.log\"," + LITERAL + "]");
    OperationResult changed = handler.execute("{\"rules\":[\"*.log\"," + LITERAL.replace("a210", "a211") + "]}", context);
    assertFalse(changed.success());
    assertEquals("EXCLUDES_CHANGED", changed.errorCode().orElseThrow());
    OperationResult missing = handler.execute("{\"rules\":[\"*.log\"]}", context);
    assertFalse(missing.success());
    assertEquals("EXCLUDES_CHANGED", missing.errorCode().orElseThrow());
    nothingRemoved();
    OperationResult same = handler.execute("{\"rules\":[\"*.log\"," + LITERAL + "]}", context);
    assertTrue(same.success(), () -> same.message());
  }

  @Test void malformedConsentIsRefusedBeforeAnythingIsRemoved() {
    for (String invalid : List.of("{\"rules\":\"*.log\"}", "{\"rules\":[1]}", "{\"rules\":[\"*.log\"],\"more\":1}",
        "{\"other\":[]}", "{\"rules\":[{\"literal\":{}}]}", "[]")) {
      OperationResult refused = handler.execute(invalid, context);
      assertFalse(refused.success(), invalid);
      assertEquals("BAD_REQUEST", refused.errorCode().orElseThrow(), invalid);
    }
    nothingRemoved();
  }

  @Test void aCallerWithoutAListKeepsApplyingTheRulesInEffect() {
    // Kept for callers that carry only the operation's own HIGH-risk consent capsule.
    assertTrue(handler.execute("{}", context).success());
    verify(indexing).deleteDocByIdWithOutcome(root.resolve("a.log").toString(), context);
  }
}
