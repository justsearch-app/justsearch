/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class RuntimeManifestPredecessorRecoveryTest {
  @Test
  void malformedManifestIsQuarantinedAndStartupContinues(@TempDir Path data) throws Exception {
    recover(data, "MALFORMED", List.of("{\"sessionToken\":\"quarantine-secret\",", "null",
        "{\"schemaVersion\":\"2\"}", "{\"schemaVersion\":-1}"));
  }

  @Test
  void invalidChildrenAreQuarantinedAndStartupContinues(@TempDir Path data) throws Exception {
    recover(data, "INVALID_CHILDREN", List.of(
        "{\"schemaVersion\":2,\"children\":[{}],\"sessionToken\":\"quarantine-secret\"}",
        "{\"schemaVersion\":2,\"children\":[null]}",
        "{\"schemaVersion\":2,\"children\":{}}"));
  }

  @Test
  void futureSchemaIsQuarantinedAndStartupContinues(@TempDir Path data) throws Exception {
    recover(data, "FUTURE_SCHEMA", List.of(
        "{\"schemaVersion\":3,\"pid\":424242,\"sessionToken\":\"quarantine-secret\"}"));
  }

  private static void recover(Path data, String reason, List<String> fixtures) throws Exception {
    var logger = (Logger) LoggerFactory.getLogger(RuntimeManifestPublisher.class);
    var messages = new ListAppender<ILoggingEvent>();
    messages.start();
    logger.addAppender(messages);
    try {
      Path runtime = Files.createDirectories(data.resolve("runtime"));
      Path manifest = runtime.resolve("manifest.json");
      Path quarantine = runtime.resolve("manifest.quarantine.json");
      for (String fixture : fixtures) {
        for (int boot = 0; boot < 2; boot++) {
          String original = fixture + "\n".repeat(boot + 1);
          Files.writeString(manifest, original);
          var registry = new MutableManagedChildRegistry();
          try (var publisher = assertDoesNotThrow(() -> new RuntimeManifestPublisher(data, registry),
              "bad predecessor evidence must not prevent startup: " + reason)) {
            assertTrue(publisher.detectedUncleanPreviousShutdown(), "quarantine is crash evidence");
            assertTrue(registry.snapshot().isEmpty(), "invalid evidence grants no child ownership");
            assertFalse(Files.exists(manifest), "quarantine moves the predecessor aside");
            assertEquals(original, Files.readString(quarantine), "preserve original bytes");
            assertPrivate(quarantine);
            try (var files = Files.list(runtime)) {
              assertEquals(1, files.filter(path -> path.getFileName().toString()
                  .startsWith("manifest.quarantine")).count(), "replace the older quarantine");
            }
            assertTrue(messages.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("PREDECESSOR_MANIFEST_" + reason)),
                "recovery logs the named reason");
            assertTrue(messages.list.stream().noneMatch(event ->
                event.getFormattedMessage().contains("quarantine-secret")), "never log the token");
            publisher.publishHead(54321, "new-session-token");
            try (var history = Files.walk(publisher.instancesRoot())) {
              for (Path file : history.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains("quarantine-secret"), file.toString());
              }
            }
            assertEquals(original, Files.readString(quarantine), "publishing keeps quarantine");
          }
        }
      }
    } finally {
      logger.detachAppender(messages);
      messages.stop();
    }
  }

  private static void assertPrivate(Path file) throws Exception {
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    } else {
      var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
      assertTrue(acl != null, "quarantined tokens need enforceable permissions");
      var owner = Files.getOwner(file);
      var system = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("SYSTEM");
      assertTrue(acl.getAcl().stream().filter(entry -> entry.type() == AclEntryType.ALLOW)
          .allMatch(entry -> entry.principal().equals(owner) || entry.principal().equals(system)));
    }
  }

  @Test
  void unreadableLedgerWarnsAndKeepsManifestChildren(@TempDir Path data) throws Exception {
    Path runtime = Files.createDirectories(data.resolve("runtime"));
    var child = new ManagedChild("recorded", ManagedChild.Kind.EXTRACTION, 424242,
        "2026-09-08T00:00:00Z", Path.of("java").toAbsolutePath().toString(),
        null, null, null, "argv");
    Files.writeString(runtime.resolve("manifest.json"),
        "{\"schemaVersion\":2,\"pid\":424241,\"children\":["
            + new ObjectMapper().writeValueAsString(child) + "]}");
    Path blocked = LedgerFamily.CHILDREN.current(LedgerFamily.directory(data));
    Files.createDirectories(blocked);
    var logger = (Logger) LoggerFactory.getLogger(RuntimeManifestPublisher.class);
    var messages = new ListAppender<ILoggingEvent>();
    messages.start();
    logger.addAppender(messages);
    try {
      var registry = new MutableManagedChildRegistry();
      try (var publisher = assertDoesNotThrow(() -> new RuntimeManifestPublisher(data, registry),
          "unreadable ledger must not prevent startup")) {
        assertTrue(publisher.detectedUncleanPreviousShutdown(), "valid predecessor stays crash evidence");
        assertEquals(List.of(child), registry.snapshot(), "manifest ownership remains available");
        assertTrue(messages.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().contains("PREDECESSOR_LEDGER_UNREADABLE")));
      }
      assertTrue(Files.isDirectory(blocked), "keep unreadable evidence");
    } finally {
      logger.detachAppender(messages);
      messages.stop();
    }
  }
}
