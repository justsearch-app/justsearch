package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Boot recovery CONVERGENCE against a real Head + a real Worker (tempdoc 825, top rung of the §D4
 * ladder below the live dev-stack leg).
 *
 * <p>The pure decision test pins the law and the component test pins the arc; neither can prove the
 * half that matters most to the acceptance criterion — that a boot which exhausts the #439 retries
 * <em>comes back</em>, in the same process, without a restart. That needs a worker which fails a
 * bounded number of times and then succeeds, which is exactly what the countdown fault injector
 * ({@code justsearch.worker.boot.faultInjectAttempts}) provides: the first N PID validations throw
 * the confirmed 821 §O.4 signature, then the injector stops. The pre-825 knob
 * ({@code pid_validation_timeout_ms}) fails EVERY attempt and so can only ever prove the pin.
 *
 * <p>N=3 consumes the ENTIRE boot-time retry budget
 * ({@code KnowledgeServerBootstrap.DEFAULT_START_ATTEMPTS}), so the process genuinely reaches the
 * 821 §O.4 state — capability pinned, no client bound, no monitor before this tempdoc — and only the
 * recovery arm can get it out.
 *
 * <p><b>Oracle.</b> Deliberately the health-event OCCURRENCE stream, not the log: occurrences are
 * the user-visible narration, so the same assertion proves convergence happened, that it happened
 * for the right REASON (a recovery attempt, not a lucky first boot), and that the arc did not FLAP —
 * exactly one {@code worker.restart-attempted} for a recovery of three failed boot attempts.
 */
@DisplayName("Worker boot recovery converges after an exhausted boot retry (tempdoc 825)")
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class WorkerBootRecoveryE2ETest {

  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** Consumes all three boot attempts, so recovery — not the boot retry — is what saves the run. */
  private static final String INJECTED_BOOT_FAULTS = "3";

  @AfterAll
  static void tearDown() {
    BACKEND.stop();
  }

  @Test
  @DisplayName("a boot that exhausts its retries recovers to a READY worker in the same process")
  void bootRecoveryConvergesWithoutAProcessRestart() throws Exception {
    // start() is itself the primary assertion: awaitWorkerReady blocks on components.worker.state =
    // READY inside the fixture's 90s worker gate, and (tempdoc 825) fails FAST if the Head narrates
    // worker.spawn_recovery_exhausted — the give-up this run must not reach.
    BACKEND.withSystemProperty("justsearch.worker.boot.faultInjectAttempts", INJECTED_BOOT_FAULTS);
    BACKEND.start();

    String health = get("/api/health");
    assertTrue(
        health.contains("\"worker\":{\"state\":\"LIFECYCLE_STATE_READY\"")
            || health.contains("\"worker\":{\"state\":\"READY\""),
        "the worker must be serving after recovery; body: " + health);
    assertFalse(
        health.contains("worker.spawn_recovery_exhausted"),
        "the recovery budget must not have been spent; body: " + health);

    String snapshot = healthEventSnapshot();
    assertTrue(
        snapshot.contains("worker.restart-attempted"),
        "READY must be the recovery arm's doing, not a boot that quietly succeeded anyway — the"
            + " injector fires on PID validation, so a missing occurrence means the injected"
            + " failures never happened and this run proves nothing. Snapshot: "
            + snapshot);
    assertTrue(
        snapshot.contains("\"id\":\"worker.recovered\""),
        "the positive milestone closes the arc (RECOVERING → READY). Snapshot: " + snapshot);
    assertTrue(
        snapshot.contains("\"faultKind\":\"boot\""),
        "the occurrence must carry the BOOT recovery context, not a supervised-restart one —"
            + " otherwise READY came from some other path. Snapshot: "
            + snapshot);
    assertEquals(
        1,
        // The id field, not a bare substring: every occurrence also names itself in its i18nKey
        // ("health-events.worker.restart-attempted.message"), which would double every count.
        countOccurrences(snapshot, "\"id\":\"worker.restart-attempted\""),
        "no flapping: three failed boot attempts inside ONE recovery arc narrate ONE"
            + " restart-attempted, not one per cycle. Snapshot: "
            + snapshot);

    // Review F3: the READY transition fires INSIDE the recovery attempt, before the handover has
    // populated HeadAssembly's reference, so a currentKnowledgeServer()-only supplier published
    // worker.state=ready with nothing identifying what it was serving — a manifest that says the
    // worker is ready and cannot say where. Read from the manifest the way every real consumer does.
    //
    // F3 asserted this through worker.grpcPort. Lane F item A11 left that field with no producer
    // (the index half is composed in this JVM: no process, no port, no channel), so the assertion
    // would now be pinning a fabricated number. The property is unchanged and still worth pinning
    // — a READY worker projection must identify what it is serving — so it is asserted through the
    // locator that survived the collapse: the index base path. The port was never the property.
    String manifest =
        Files.readString(
            BACKEND.dataDir().resolve("runtime").resolve("manifest.json"), StandardCharsets.UTF_8);
    java.util.regex.Matcher indexBasePath =
        java.util.regex.Pattern.compile("\"indexBasePath\"\\s*:\\s*\"([^\"]+)\"")
            .matcher(manifest);
    assertTrue(
        indexBasePath.find(),
        "the runtime manifest must identify the index the worker is serving after recovery."
            + " Manifest: "
            + manifest);
    assertFalse(
        indexBasePath.group(1).isBlank(),
        "…and it must be a real path, not a placeholder. Manifest: " + manifest);
    assertFalse(
        manifest.contains("\"grpcPort\""),
        "there is no worker port in the Engine; a manifest carrying one means something"
            + " fabricated it. Manifest: "
            + manifest);
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      count++;
    }
    return count;
  }

  private static String get(String path) throws Exception {
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + BACKEND.port() + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return resp.body();
  }

  /**
   * Reads the initial {@code snapshot} frame of {@code /api/health/events/stream} — {@code
   * {catalogVersion, conditions[], occurrences[]}} — and closes the stream. The endpoint is an
   * infinite SSE stream, so this consumes lines until the first data frame rather than the body.
   */
  private static String healthEventSnapshot() throws Exception {
    HttpResponse<Stream<String>> resp =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + BACKEND.port() + "/api/health/events/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofLines());
    List<String> collected = new ArrayList<>();
    try (Stream<String> lines = resp.body()) {
      for (String line : (Iterable<String>) lines::iterator) {
        collected.add(line);
        if (line.startsWith("data:") && line.contains("occurrences")) {
          break;
        }
        if (collected.size() > 200) {
          break;
        }
      }
    }
    return String.join("\n", collected);
  }
}
