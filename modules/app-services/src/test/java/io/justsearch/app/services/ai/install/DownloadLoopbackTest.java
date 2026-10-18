package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.configuration.model.InstallPlan;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production curl process, resume records, integrity verification and placement. */
@EnabledOnOs(OS.LINUX)
@Timeout(110)
class DownloadLoopbackTest {
  enum Mode { NORMAL, DROP, IGNORE_RANGE, REFUSE_RANGE, STALL_ONCE, SLOW, CORRUPT }
  @TempDir Path root;
  private final byte[] bytes = new byte[1024 * 1024];
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicInteger requests = new AtomicInteger();
  private final List<String> ranges = new CopyOnWriteArrayList<>();
  private volatile Mode mode = Mode.NORMAL;
  private HttpServer server;
  private java.util.concurrent.ExecutorService handlers;
  private String url;
  private String sha;
  private Path partial;

  @BeforeEach void startServer() throws Exception {
    new java.util.Random(1234).nextBytes(bytes);
    sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    handlers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(handlers);
    server.createContext("/model", this::serve);
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model";
    partial = root.resolve("model.onnx.partial");
  }

  @AfterEach void stopServer() throws Exception {
    if (server != null) server.stop(0);
    if (handlers != null) {
      handlers.shutdownNow();
      assertTrue(handlers.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  private void serve(HttpExchange exchange) throws IOException {
    int attempt = requests.incrementAndGet();
    String range = exchange.getRequestHeaders().getFirst("Range");
    ranges.add(range == null ? "none" : range);
    Mode current = mode;
    try (exchange) {
      if (range != null && current == Mode.REFUSE_RANGE) {
        exchange.getResponseHeaders().set("Content-Range", "bytes */" + bytes.length);
        exchange.sendResponseHeaders(416, -1);
        return;
      }
      int offset = range == null || current == Mode.IGNORE_RANGE ? 0
          : Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
      int status = offset == 0 ? 200 : 206;
      if (offset > 0) exchange.getResponseHeaders().set("Content-Range",
          "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
      exchange.sendResponseHeaders(status, bytes.length - offset);
      if (current == Mode.STALL_ONCE && attempt == 1) {
        try { Thread.sleep(90_000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return;
      }
      byte[] content = bytes.clone();
      if (current == Mode.CORRUPT) content[content.length - 1] ^= 1;
      int end = current == Mode.DROP ? Math.min(offset + 131072, bytes.length) : bytes.length;
      for (int cursor = offset; cursor < end; cursor += 8192) {
        exchange.getResponseBody().write(content, cursor, Math.min(8192, end - cursor));
        exchange.getResponseBody().flush();
        if (current == Mode.SLOW) {
          try { Thread.sleep(30); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
      }
    } catch (IOException peerClosed) {
      // Disconnects and curl cancellation deliberately close the response early.
    }
  }

  private ResumableFetch.Outcome fetch(DownloadExecutor download) {
    return ResumableFetch.fetch(new ResumableFetch.Request(partial, url, bytes.length, sha),
        download, null, cancelled::get, ResumableFetch.Hooks.none(),
        TransportRetryPolicy.defaultPolicy().withMaxAttempts(1));
  }

  private void seedResume() throws Exception {
    Files.write(partial, Arrays.copyOf(bytes, 131072));
    DownloadResume.write(partial, new DownloadResume.State(url, bytes.length, sha, null));
  }

  private void placeVerified() throws Exception {
    assertFalse(Files.exists(root.resolve("model.onnx")), "target is absent until verified placement");
    assertNull(new PlacementStage(root).place(new InstallPlan.PlannedDownload(
        "embedding", url, "model.onnx", sha, bytes.length, false, false)));
    assertArrayEquals(bytes, Files.readAllBytes(root.resolve("model.onnx")));
    assertFalse(Files.exists(partial));
    assertFalse(Files.exists(DownloadResume.sidecarFor(partial)));
  }

  @Test void interruptionThenResumeUsesRealRangeAndAtomicPlacement() throws Exception {
    mode = Mode.DROP;
    assertFalse(fetch(new DownloadExecutor(cancelled)).ok());
    assertTrue(Files.size(partial) > 0);
    assertFalse(Files.exists(root.resolve("model.onnx")));
    long kept = Files.size(partial);
    mode = Mode.NORMAL;
    assertTrue(fetch(new DownloadExecutor(cancelled)).ok());
    assertTrue(ranges.contains("bytes=" + kept + "-"), ranges.toString());
    placeVerified();
  }

  @Test void ignoredRangeRestartsFromZeroAndVerifies() throws Exception {
    seedResume();
    mode = Mode.IGNORE_RANGE;
    assertTrue(fetch(new DownloadExecutor(cancelled)).ok());
    assertTrue(ranges.contains("bytes=131072-"));
    assertTrue(ranges.contains("none"));
    placeVerified();
  }

  @Test void range416RestartsFromZeroAndVerifies() throws Exception {
    seedResume();
    mode = Mode.REFUSE_RANGE;
    assertTrue(fetch(new DownloadExecutor(cancelled)).ok());
    assertTrue(ranges.contains("bytes=131072-"));
    assertTrue(ranges.contains("none"));
    placeVerified();
  }

  @Test void stalledRealTransferRetriesAfterProductionSpeedDeadline() throws Exception {
    mode = Mode.STALL_ONCE;
    long start = System.nanoTime();
    assertTrue(fetch(new DownloadExecutor(cancelled)).ok());
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() >= 60);
    assertTrue(requests.get() >= 2);
    placeVerified();
  }

  @Test void cancellationKeepsOnlyUntrustedResumeBytesAndCanResume() throws Exception {
    mode = Mode.SLOW;
    DownloadExecutor download = new DownloadExecutor(cancelled);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var run = executor.submit(() -> fetch(download));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((!Files.exists(partial) || Files.size(partial) < 16384) && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(Files.size(partial) >= 16384);
      cancelled.set(true);
      download.cancel();
      var outcome = run.get(5, TimeUnit.SECONDS);
      assertTrue(outcome.cancelled());
      assertFalse(outcome.ok());
      assertFalse(Files.exists(root.resolve("model.onnx")));
      assertNotNull(DownloadResume.read(partial));
    }
    cancelled.set(false);
    mode = Mode.NORMAL;
    assertTrue(fetch(new DownloadExecutor(cancelled)).ok());
    placeVerified();
  }

  @Test void corruptHashNeverReachesTrustedPathOrRetainsPartial() throws Exception {
    mode = Mode.CORRUPT;
    var result = fetch(new DownloadExecutor(cancelled));
    assertFalse(result.ok());
    assertTrue(result.error().contains("SHA-256"), result.error());
    assertFalse(Files.exists(root.resolve("model.onnx")));
    assertFalse(Files.exists(partial));
    assertFalse(Files.exists(DownloadResume.sidecarFor(partial)));
  }
}
