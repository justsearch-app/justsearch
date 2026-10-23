/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Temporary K8/K6 composition probe; cleanup always ends the birth-matched fake. */
final class IgnoredKeyWrapperProbeTest {
  @Test void rejectedUnprotectedWrapperMustLeaveNoServingDescendant() throws Exception {
    var check = new ManagedLlamaAuthenticationTest();
    check.tmp = Files.createTempDirectory(Path.of(System.getenv("TMPDIR")), "ignored-key-wrapper-");
    try {
      check.captureLaunches();
      var create = ManagedLlamaAuthenticationTest.class.getDeclaredMethod("create", String[].class);
      create.setAccessible(true);
      create.invoke(check, (Object) new String[]{"--wrapper", "--ignore-api-key", "--ignore-parent"});
      var field = ManagedLlamaAuthenticationTest.class.getDeclaredField("manager");
      field.setAccessible(true);
      var manager = (InferenceLifecycleManager) field.get(check);
      var rejected = assertThrows(io.justsearch.app.api.ModeTransitionException.class,
          manager::switchToOnlineMode);
      assertTrue(rejected.getMessage().contains("ignores its API key"));
      assertFalse(manager.isOnline());
      var argv = List.of(Files.readAllLines(check.tmp.resolve("fake/launches.txt")).getLast().split("\t"));
      int port = Integer.parseInt(argv.get(argv.indexOf("--port") + 1));
      long pid = Long.parseLong(Files.readAllLines(check.tmp.resolve("fake/pids.txt")).getLast().trim());
      int unauthenticated = -1;
      try (var http = HttpClient.newHttpClient()) {
        try {
          unauthenticated = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/props"))
              .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException gone) { /* Refusal must have cleaned up its server. */ }
      }
      System.out.println("IGNORED_KEY_WRAPPER rejected=true online=" + manager.isOnline()
          + " servingPid=" + pid + " unauthenticatedProps=" + unauthenticated);
      assertNotEquals(200, unauthenticated,
          "K8 rejection must not leave an unauthenticated serving descendant");
    } finally {
      try { HealthyFakeLlamaServer.endAll(check.tmp.resolve("fake")); }
      finally { check.cleanup(); }
    }
  }
}
