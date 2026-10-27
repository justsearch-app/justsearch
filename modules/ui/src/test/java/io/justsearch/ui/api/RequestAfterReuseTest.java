/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.engine.EngineAdmissionController;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual exceptional async completion, keep-alive request reuse and a paused old after-handler. */
@Timeout(30)
final class RequestAfterReuseTest {
  @Test
  void oldAfterLookupCannotCloseNewerLiveFrontOnTheSameRecycledHttpRequest() throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var cancellationAttempted = new CountDownLatch(1);
    var terminalA = new CountDownLatch(1);
    var answerA = new CompletableFuture<Void>() {
      @Override public boolean cancel(boolean mayInterruptIfRunning) {
        cancellationAttempted.countDown();
        return false; // Keep the real continuation pending after the actual servlet timeout.
      }
    };
    var answerB = new CompletableFuture<Void>();
    var handlerA = new CountDownLatch(1);
    var handlerB = new CountDownLatch(1);
    var beforeOldLookup = new CountDownLatch(1);
    var allowOldLookup = new CountDownLatch(1);
    var afterOldLookup = new CountDownLatch(1);
    var allowOldTail = new CountDownLatch(1);
    var contextA = new AtomicReference<Context>();
    var requestA = new AtomicReference<jakarta.servlet.http.HttpServletRequest>();
    var requestB = new AtomicReference<jakarta.servlet.http.HttpServletRequest>();
    var physicalA = new AtomicReference<Request>();
    var frontB = new AtomicReference<EngineWorkHandle>();
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.http.asyncTimeout = 150; // Actual timeout ends A before its late continuation starts.
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try (var events = Executors.newSingleThreadExecutor(); var completer = Executors.newSingleThreadExecutor()) {
      app.after(ctx -> {
        if (ctx == contextA.get()) {
          beforeOldLookup.countDown();
          awaitGate(allowOldLookup);
        }
      });
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, null, admission).install(app);
      app.events(config -> config.serverStarting(() -> app.jettyServer().server().addBeanToAllConnectors(
          new HttpChannel.Listener() {
            @Override public void onComplete(Request request) {
              if (request == physicalA.get()) terminalA.countDown();
            }
          })));
      app.after(ctx -> {
        if (ctx == contextA.get()) {
          afterOldLookup.countDown();
          // Inspect the ownership operation before Javalin itself resumes old response writing.
          awaitGate(allowOldTail);
        }
      });
      app.get("/api/test/old", ctx -> {
        contextA.set(ctx);
        requestA.set(ctx.req());
        physicalA.set(Request.getBaseRequest(ctx.req()));
        ctx.result("");
        ctx.res().setContentLength(0);
        ctx.future(() -> answerA);
        handlerA.countDown();
      });
      app.get("/api/test/new", ctx -> {
        requestB.set(ctx.req());
        ctx.result("");
        ctx.res().setContentLength(0);
        var front = RequestEngineWork.get(ctx);
        frontB.set(front);
        var producer = front.retain();
        ctx.future(() -> {
          ctx.req().getAsyncContext().setTimeout(0); // B stays genuinely LIVE during the controlled old lookup.
          return answerB.whenComplete((ignored, failure) -> producer.close());
        });
        handlerB.countDown();
      });
      app.start("127.0.0.1", 0);
      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", app.port()), 2000);
        socket.setSoTimeout(5000);
        send(socket, app, "/api/test/old");
        assertTrue(handlerA.await(5, TimeUnit.SECONDS));
        assertEquals(500, readEmptyResponse(socket.getInputStream()), "actual async timeout terminates A before its continuation");
        assertTrue(cancellationAttempted.await(5, TimeUnit.SECONDS));
        assertFalse(answerA.isDone(), "timeout cancellation was genuinely refused");
        assertTrue(terminalA.await(5, TimeUnit.SECONDS), "actual terminal callback precedes B and late A");
        assertEquals(1, beforeOldLookup.getCount(), "old after has not run during timeout");
        send(socket, app, "/api/test/new");
        assertTrue(handlerB.await(5, TimeUnit.SECONDS));
        assertNotSame(requestA.get(), requestB.get(), "each Context has its own Javalin request wrapper");
        var baseA = Request.getBaseRequest(requestA.get());
        var baseB = Request.getBaseRequest(requestB.get());
        assertNotNull(baseA);
        assertNotNull(baseB);
        assertSame(physicalA.get(), baseA, "A's base was captured before recycling");
        assertSame(baseA, baseB, "require actual keep-alive recycling of the same Jetty request");
        var completingA = completer.submit(() -> answerA.complete(null));
        assertTrue(beforeOldLookup.await(5, TimeUnit.SECONDS));
        allowOldLookup.countDown();
        assertTrue(afterOldLookup.await(5, TimeUnit.SECONDS));
        try (var retained = assertDoesNotThrow(() -> frontB.get().retain(),
            "old after must leave the newer request's front reference usable")) {
          assertEquals(frontB.get().context().workId(), retained.context().workId(),
              "old Context cannot close B's own front reference even though the servlet request is reused");
        }
        assertTrue(frontB.get().cancellationReason().isEmpty());
        answerB.complete(null);
        assertEquals(200, readEmptyResponse(socket.getInputStream()));
        allowOldTail.countDown();
        completingA.get(5, TimeUnit.SECONDS);
        LocalApiServer.stopHttpTransport(app);
        assertEquals(0, admission.activeWorkCount());
      }
    } finally {
      allowOldLookup.countDown();
      allowOldTail.countDown();
      answerA.complete(null);
      answerB.complete(null);
      if (!app.jettyServer().server().isStopped()) app.stop();
    }
  }

  private static void send(Socket socket, Javalin app, String path) throws Exception {
    socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:"
        + app.port() + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  /** Only these two explicitly empty, fixed-length fixture responses are accepted. */
  private static int readEmptyResponse(InputStream input) throws Exception {
    var headers = new StringBuilder();
    while (!headers.toString().endsWith("\r\n\r\n")) {
      int value = input.read();
      assertTrue(value >= 0, "connection ended before the complete response");
      headers.append((char) value);
      assertTrue(headers.length() <= 16_384, "bounded fixture response headers");
    }
    var lines = headers.toString().split("\r\n");
    assertTrue(lines[0].startsWith("HTTP/1.1 "), lines[0]);
    int lengths = 0;
    for (String line : lines) {
      if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
        assertEquals("0", line.substring(15).trim(), "fixture response has no unread body");
        lengths++;
      }
      assertFalse(line.regionMatches(true, 0, "Transfer-Encoding:", 0, 18), "fixture requires fixed length");
      assertFalse(line.equalsIgnoreCase("Connection: close"), "same connection must survive A");
    }
    assertEquals(1, lengths, "require exactly one explicit empty-body length");
    return Integer.parseInt(lines[0].split(" ", 3)[1]);
  }

  private static void awaitGate(CountDownLatch gate) {
    try {
      if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("controlled after gate not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
