/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.justsearch.app.inference.chat.GenerationTracker;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual HTTP/1 sockets keep their receiving side open after sending FIN. No native model. */
@Timeout(25)
class ProxyAbandonmentSocketTest {
  @Test void halfClosedQueuedClientReceivesNoStatusOrCompletionAndFreesLane() throws Exception {
    try (var f = new Fixture(false)) {
      var holder = f.post("holder");
      assertTrue(f.started.await(3, TimeUnit.SECONDS));
      try (var socket = f.socket()) {
        f.send(socket, "queued");
        assertTrue(f.entered.await(3, TimeUnit.SECONDS));
        socket.shutdownOutput();
        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(response.contains("HTTP/1.1 200"), "abandoned client received success: " + response);
        assertFalse(response.contains("completion"), response);
        assertEquals("", response, "abandonment must close without writing any status");
        assertTrue(f.departed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("holder"), f.order, "closed waiter must not dispatch");
        assertFalse(holder.isDone(), "holder must still own the lane");
        f.release.countDown();
        assertEquals(200, holder.get(3, TimeUnit.SECONDS).statusCode());
        f.assertFresh();
      }
    }
  }

  @Test void halfClosedStartedStreamHasNoNormalCompletionAndFreesLane() throws Exception {
    try (var f = new Fixture(true); var socket = f.socket()) {
      f.send(socket, "holder");
      var response = new StringBuilder();
      while (!response.toString().contains("data: started\n\n")) {
        int value = socket.getInputStream().read();
        assertNotEquals(-1, value, "stream must start before abandonment");
        response.append((char) value);
      }
      assertTrue(response.toString().contains("HTTP/1.1 200"), response.toString());
      socket.shutdownOutput();
      f.release.countDown();
      response.append(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      assertFalse(response.toString().contains("[DONE]"), "abandoned stream completed: " + response);
      assertFalse(response.toString().contains("completion"), response.toString());
      assertFalse(response.toString().endsWith("0\r\n\r\n"), "stream ended with normal chunk terminator: " + response);
      f.assertFresh();
    }
  }

  @Test void patientClientWaitsLongOpenThenReceivesCompletion() throws Exception {
    try (var f = new Fixture(false); var socket = f.socket()) {
      var holder = f.post("holder");
      assertTrue(f.started.await(3, TimeUnit.SECONDS));
      f.send(socket, "queued");
      assertTrue(f.entered.await(3, TimeUnit.SECONDS));
      socket.setSoTimeout(15000);
      assertThrows(java.net.SocketTimeoutException.class, () -> socket.getInputStream().read(),
          "patient client must remain queued without an early response");
      assertEquals(List.of("holder"), f.order);
      socket.setSoTimeout(3000);
      f.release.countDown();
      assertEquals(200, holder.get(3, TimeUnit.SECONDS).statusCode());
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(response.contains("HTTP/1.1 200"), response);
      assertTrue(response.contains("completion"), response);
      assertEquals(List.of("holder", "queued"), f.order);
      f.assertFresh();
    }
  }

  @Test void resetQueuedClientNeverDispatchesAndFreesLane() throws Exception {
    try (var f = new Fixture(false)) {
      var holder = f.post("holder");
      assertTrue(f.started.await(3, TimeUnit.SECONDS));
      try (var socket = f.socket()) {
        f.send(socket, "queued");
        assertTrue(f.entered.await(3, TimeUnit.SECONDS));
        socket.setSoLinger(true, 0);
      }
      assertTrue(f.departed.await(2, TimeUnit.SECONDS));
      assertEquals(List.of("holder"), f.order);
      f.release.countDown();
      assertEquals(200, holder.get(3, TimeUnit.SECONDS).statusCode());
      f.assertFresh();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    final CountDownLatch entered = new CountDownLatch(1), departed = new CountDownLatch(1);
    final List<String> order = new CopyOnWriteArrayList<>();
    final GenerationTracker tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final HttpClient http = HttpClient.newHttpClient();
    final HttpServer upstream;
    final Javalin app;
    final OpenAiCompatController controller;

    Fixture(boolean streaming) throws Exception {
      upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      upstream.setExecutor(workers);
      upstream.createContext("/v1/chat/completions", exchange -> {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = request.contains("holder") ? "holder" : request.contains("queued") ? "queued" : "fresh";
        order.add(name);
        try (exchange) {
          if (streaming && name.equals("holder")) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            // Exceed Jetty's output buffer so the status and first event reach the real client.
            exchange.getResponseBody().write(("data: started\n\n" + ": padding\n\n".repeat(8192))
                .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
          }
          if (name.equals("holder")) {
            started.countDown();
            try { release.await(20, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          }
          byte[] bytes = (streaming && name.equals("holder")
              ? "data: completion\n\ndata: [DONE]\n\n" : "{\"completion\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
          if (!streaming || !name.equals("holder")) exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
        }
      });
      upstream.start();
      controller = new OpenAiCompatController(http, () -> upstream.getAddress().getPort(), null, tracker::admit);
      controller.setGenerationLane(tracker.lane());
      app = Javalin.create(c -> c.showJavalinBanner = false);
      app.post("/v1/chat/completions", ctx -> {
        boolean queued = ctx.body().contains("queued");
        if (queued) entered.countDown();
        try { controller.handleChatCompletions(ctx); }
        finally { if (queued) departed.countDown(); }
      });
      app.start("127.0.0.1", 0);
    }

    CompletableFuture<HttpResponse<String>> post(String name) {
      return http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/v1/chat/completions"))
          .POST(HttpRequest.BodyPublishers.ofString(body(name))).build(), HttpResponse.BodyHandlers.ofString());
    }
    Socket socket() throws Exception {
      var socket = new Socket("127.0.0.1", app.port());
      socket.setSoTimeout(3000);
      return socket;
    }
    static String body(String name) { return "{\"messages\":[{\"content\":\"" + name + "\"}]}"; }
    void send(Socket socket, String name) throws Exception {
      String body = body(name);
      String wire = "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
          + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
      socket.getOutputStream().write(wire.getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
    }
    void assertFresh() throws Exception {
      assertEquals(200, post("fresh").get(3, TimeUnit.SECONDS).statusCode());
      assertEquals("fresh", order.getLast());
      assertEquals(0, tracker.inFlight());
    }
    @Override public void close() throws Exception {
      release.countDown(); app.stop(); controller.close(); upstream.stop(0); http.shutdownNow(); workers.close();
    }
  }
}
