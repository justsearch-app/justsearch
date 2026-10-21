/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.EncryptionKeystore;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("cross-platform")
class KeystoreStartupAvailabilityHttpTest {
  @TempDir Path dir;
  private Path primary() { return dir.resolve("encryption/keystore.json"); }
  private Path replica() { return dir.resolve("encryption/keystore.replica.json"); }
  private DataKeyManager manager() { return new DataKeyManager(new EncryptionKeystore(dir)); }

  @ParameterizedTest(name = "validPrimaryLoadsAndWarnsThenRetries({0}, {1})")
  @CsvSource({"read-only, restart", "blocked, restart", "read-error, restart",
      "read-only, save", "blocked, save", "read-error, save",
      "read-only-filesystem, restart", "read-only-filesystem, save"})
  void validPrimaryLoadsAndWarnsThenRetries(String problem, String retry) throws Exception {
    if (problem.equals("read-only")) {
      org.junit.jupiter.api.Assumptions.assumeTrue(
          Files.getFileStore(dir).supportsFileAttributeView("posix"), "real read-only folder needs POSIX permissions");
    }
    var initial = manager();
    initial.setup("old-password".toCharArray());
    byte[] dek = initial.dek();
    byte[] original = Files.readAllBytes(primary());
    byte[] originalReplica = Files.readAllBytes(replica());
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EncryptionKeystore.class);
    var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    messages.start();
    logger.addAppender(messages);
    Set<PosixFilePermission> permissions = null;
    DataKeyManager loaded;
    try {
      if (!problem.equals("read-error")) Files.delete(replica());
      if (problem.equals("blocked")) {
        Files.createDirectory(replica());
        Files.writeString(replica().resolve("sentinel"), "keep me");
      }
      if (problem.equals("read-only")) {
        permissions = Files.getPosixFilePermissions(primary().getParent());
        Files.setPosixFilePermissions(primary().getParent(), Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        assertFalse(Files.isWritable(primary().getParent()), "the folder must really be read-only");
      }
      var deniedModeRepair = mock(PosixFileAttributeView.class);
      doThrow(new AccessDeniedException(primary().getParent().toString()))
          .when(deniedModeRepair).setPermissions(anySet());
      try (var writes = mockStatic(AtomicFileWrites.class, invocation -> {
            if (problem.equals("read-only-filesystem") && invocation.getMethod().getName().equals("replaceStrict")
                && replica().equals(invocation.getArgument(0))) throw new java.nio.file.ReadOnlyFileSystemException();
            return invocation.callRealMethod();
          });
          var _ = mockStatic(Files.class, invocation -> {
            // Preserve the real read-only write failure even when startup attempts privacy repair.
            if (problem.equals("read-only") && invocation.getMethod().getName().equals("getFileAttributeView")
                && primary().getParent().equals(invocation.getArgument(0))
                && PosixFileAttributeView.class.equals(invocation.getArgument(1))) return deniedModeRepair;
            if (problem.equals("read-error") && invocation.getMethod().getName().equals("readAllBytes")
                && replica().equals(invocation.getArgument(0))) throw new IOException("injected replica read error");
            return invocation.callRealMethod();
          })) {
        // This is the exact key-manager constructor called by HeadAssembly before its infra phase.
        loaded = assertDoesNotThrow(this::manager,
            "Engine assembly's key-manager construction must accept a valid primary despite replica failure");
        assertEquals(DataKeyManager.State.LOCKED, loaded.state());
        assertNotNull(loaded.warning(), "degraded startup must expose its warning to Security & Privacy");
        assertTrue(loaded.warning().contains("replica"));
        assertDoesNotThrow(() -> loaded.unlock("old-password".toCharArray()));
        assertArrayEquals(dek, loaded.dek());
        assertArrayEquals(original, Files.readAllBytes(primary()));
        if (problem.equals("read-error")) writes.verifyNoInteractions();
      }
      assertTrue(messages.list.stream().anyMatch(event -> event.getFormattedMessage()
          .contains("Could not read or repair encryption keystore recovery replica")), "startup must log the failure");
      if (problem.equals("read-error")) assertArrayEquals(originalReplica, Files.readAllBytes(replica()));
      else if (problem.equals("blocked")) assertEquals("keep me", Files.readString(replica().resolve("sentinel")));
      else assertFalse(Files.exists(replica()));
      loaded.lock();
      var controller = new ConversationEncryptionController(loaded);
      var app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      app.get("/status", controller::handleStatus);
      app.post("/unlock", controller::handleUnlock);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        URI base = URI.create("http://127.0.0.1:" + app.port());
        var status = client.send(HttpRequest.newBuilder(base.resolve("/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode(), status.body());
        var json = JsonMapper.builder().build().readTree(status.body());
        assertEquals("locked", json.path("state").asString());
        assertEquals(loaded.warning(), json.path("warning").asString());
        var unlocked = client.send(HttpRequest.newBuilder(base.resolve("/unlock"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"passphrase\":\"old-password\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, unlocked.statusCode(), unlocked.body());
        assertArrayEquals(dek, loaded.dek());
      } finally {
        app.stop();
      }
    } finally {
      if (permissions != null) Files.setPosixFilePermissions(primary().getParent(), permissions);
      logger.detachAppender(messages);
      messages.stop();
    }
    if (problem.equals("blocked")) {
      Files.delete(replica().resolve("sentinel"));
      Files.delete(replica());
    }
    var retried = retry.equals("restart") ? assertDoesNotThrow(this::manager) : loaded;
    if (retry.equals("restart")) {
      assertNull(retried.warning(), "a repaired replica clears the startup warning");
      retried.unlock("old-password".toCharArray());
    } else {
      assertDoesNotThrow(loaded::regenerateRecovery);
      assertNull(loaded.warning(), "a successful save retries the replica and clears the warning");
    }
    assertArrayEquals(dek, retried.dek());
    assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()));
  }
}
