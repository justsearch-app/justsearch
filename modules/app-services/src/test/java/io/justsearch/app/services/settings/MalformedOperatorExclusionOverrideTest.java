/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MalformedOperatorExclusionOverrideTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

  @Test
  void D1MalformedOperatorOverrideIsIgnoredAndWarnsOnce() {
    String key = "justsearch.ui.exclude_patterns";
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
        io.justsearch.configuration.excludes.ExclusionPatterns.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    var previousLevel = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.WARN);
    appender.start();
    logger.addAppender(appender);
    var installed = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.configuration.resolved.ResolvedConfig.builder()
            .putSettings(key, "[\"private\"]")
            .put(key, 400, "env var", "JUSTSEARCH_UI_EXCLUDE_PATTERNS", "[\"private\"").build());
    try {
      // A different live value first makes the warning assertion independent of test order.
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(
          new io.justsearch.configuration.resolved.ConfigStore(
              io.justsearch.configuration.resolved.ResolvedConfig.builder()
                  .putDefault(key, "[]").build()));
      io.justsearch.configuration.excludes.ExclusionPatterns.current();
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(installed);
      for (int i = 0; i < 3; i++) {
        var policy = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            io.justsearch.configuration.excludes.ExclusionPatterns::current,
            "D1: malformed operator JSON must never stop indexing");
        assertEquals(List.of(), policy.patterns(), "D1: ignore the override as on f9bd104d2");
        org.junit.jupiter.api.Assertions.assertFalse(
            policy.isExcludedPath(root, root.resolve("private/file.txt")),
            "D1: malformed override does not fall back to the GUI list");
      }
      assertEquals(1, appender.list.stream()
          .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN).count(),
          "D1: warn once, not for each indexing attempt");
      org.junit.jupiter.api.Assertions.assertTrue(appender.list.stream().anyMatch(event ->
          event.getFormattedMessage().contains(key)), "D1: warning identifies the setting");
    } finally {
      io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(installed, previous);
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }

}
