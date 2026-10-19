/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import io.justsearch.app.api.runtime.child.OwnedHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Newline-delimited JSON-RPC 2.0 over a child process's stdio — the standard MCP local transport.
 *
 * <p>The process is long-lived (one {@code initialize} handshake, then many {@code tools/call}s),
 * so this is a persistent session, not the one-shot request/response of {@code
 * PersistentExtractionSandbox}. A daemon reader thread parses one JSON message per line and completes
 * the pending {@link CompletableFuture} matched by JSON-RPC {@code id}; server→client requests and
 * notifications (no/echoed {@code id} we did not issue) are ignored — sufficient for the EXECUTABLE
 * first consumer (tempdoc 560 §4.4). The subprocess + lifecycle pattern mirrors {@code
 * PersistentExtractionSandbox} / {@code LlamaServerOps}; isolation is the process boundary (P2).
 */
public final class StdioMcpTransport implements McpTransport {
  private static final Logger log = LoggerFactory.getLogger(StdioMcpTransport.class);

  private final List<String> command;
  private final Map<String, String> extraEnv;
  private final ObjectMapper mapper;
  private final AtomicLong nextId = new AtomicLong(1);
  private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final Object writeLock = new Object();

  private volatile Process process;
  private volatile BufferedWriter writer;
  private volatile boolean closed;
  // Server-initiated messages (notifications + server→client requests). When set, the handler
  // receives the raw JSON-RPC message and may reply to requests via respond()/respondError().
  private volatile java.util.function.Consumer<JsonNode> incomingHandler;

  public StdioMcpTransport(List<String> command) {
    this(command, Map.of(), JsonMapper.builder().build());
  }

  public StdioMcpTransport(List<String> command, Map<String, String> extraEnv, ObjectMapper mapper) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("MCP server command must not be empty");
    }
    this.command = List.copyOf(command);
    this.extraEnv = Map.copyOf(extraEnv);
    this.mapper = mapper;
  }

  private synchronized void ensureStarted() {
    if (closed) {
      throw new McpException("MCP transport is closed");
    }
    if (process != null) {
      return;
    }
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().putAll(extraEnv);
    Process p;
    try {
      p = OwnedHelper.start(pb, "MCP stdio transport");
    } catch (IOException e) {
      throw new McpException("Failed to start MCP server: " + command, e);
    }
    this.process = p;
    this.writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
    Thread reader = new Thread(() -> readLoop(p), "mcp-stdio-reader");
    reader.setDaemon(true);
    reader.start();
    Thread stderr = new Thread(() -> drainStderr(p), "mcp-stdio-stderr");
    stderr.setDaemon(true);
    stderr.start();
  }

  @Override
  public JsonNode request(String method, JsonNode params, Duration timeout) {
    ensureStarted();
    long id = nextId.getAndIncrement();
    ObjectNode req = mapper.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", id);
    req.put("method", method);
    req.set("params", params == null ? mapper.createObjectNode() : params);

    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);
    writeLine(req);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      pending.remove(id);
      throw new McpException("MCP request timed out after " + timeout + ": " + method, e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw (cause instanceof McpException me) ? me : new McpException("MCP request failed: " + method, cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pending.remove(id);
      throw new McpException("MCP request interrupted: " + method, e);
    }
  }

  @Override
  public void notify(String method, JsonNode params) {
    ensureStarted();
    ObjectNode note = mapper.createObjectNode();
    note.put("jsonrpc", "2.0");
    note.put("method", method);
    note.set("params", params == null ? mapper.createObjectNode() : params);
    writeLine(note);
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    Process p = process;
    OwnedHelper.expectInputClose(p, "transport close");
    try {
      if (writer != null) {
        writer.close();
      }
    } catch (IOException ignored) {
      // best-effort; the destroy below is the real shutdown
    }
    if (p != null) {
      p.destroy();
      try {
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
          p.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        p.destroyForcibly();
      }
    }
    failAllPending("MCP transport closed");
  }

  private void writeLine(JsonNode node) {
    String line = mapper.writeValueAsString(node);
    synchronized (writeLock) {
      try {
        writer.write(line);
        writer.write("\n");
        writer.flush();
      } catch (IOException e) {
        throw new McpException("Failed to write MCP request", e);
      }
    }
  }

  private void readLoop(Process p) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        dispatchMessage(trimmed);
      }
    } catch (IOException e) {
      if (!closed) {
        log.debug("MCP reader stream error: {}", e.toString());
      }
    } finally {
      failAllPending(closed ? "MCP transport closed" : "MCP server stream ended");
    }
  }

  private void dispatchMessage(String line) {
    JsonNode msg;
    try {
      msg = mapper.readTree(line);
    } catch (RuntimeException e) {
      log.debug("Dropped non-JSON MCP line: {}", summarize(line));
      return;
    }
    JsonNode idNode = msg.get("id");
    JsonNode methodNode = msg.get("method");
    boolean hasMethod = methodNode != null && !methodNode.isNull();

    // Server-INITIATED message (has a method): a notification (no id) or a request (with id) — e.g.
    // notifications/tools/list_changed, notifications/progress, sampling/createMessage, roots/list.
    // Route to the handler; if none and it's a request, reply method-not-found so the server does
    // NOT hang waiting for a response (the de-risk found sampling would hang the call).
    if (hasMethod) {
      java.util.function.Consumer<JsonNode> handler = incomingHandler;
      if (handler != null) {
        try {
          handler.accept(msg);
        } catch (RuntimeException e) {
          log.debug("MCP incoming handler error for {}: {}", methodNode.asString(""), e.toString());
        }
      } else if (idNode != null && !idNode.isNull()) {
        respondError(idNode, -32601, "method not handled by host: " + methodNode.asString(""));
      } else {
        log.debug("MCP notification dropped (no handler): {}", methodNode.asString(""));
      }
      return;
    }

    // Otherwise it's a RESPONSE to one of our requests, correlated by id.
    if (idNode == null || idNode.isNull()) {
      return;
    }
    CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
    if (future == null) {
      return; // response to an id we already abandoned (timeout) or never sent
    }
    JsonNode error = msg.get("error");
    if (error != null && !error.isNull()) {
      future.completeExceptionally(
          new McpException(
              "MCP error " + error.path("code").asInt(0) + ": " + error.path("message").asString("")));
    } else {
      future.complete(msg.path("result"));
    }
  }

  /** Register a handler for server-initiated messages (notifications + server→client requests). */
  @Override
  public void setIncomingHandler(java.util.function.Consumer<JsonNode> handler) {
    this.incomingHandler = handler;
  }

  /** Reply to a server→client request with a result. */
  @Override
  public void respond(JsonNode id, JsonNode result) {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result == null ? mapper.createObjectNode() : result);
    writeLine(response);
  }

  /** Reply to a server→client request with a JSON-RPC error. */
  @Override
  public void respondError(JsonNode id, int code, String message) {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    ObjectNode error = mapper.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    response.set("error", error);
    writeLine(response);
  }

  private void drainStderr(Process p) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (log.isDebugEnabled() && !line.isBlank()) {
          log.debug("MCP server stderr: {}", summarize(line));
        }
      }
    } catch (IOException e) {
      if (!closed) {
        log.debug("MCP stderr stream error: {}", e.toString());
      }
    }
  }

  private void failAllPending(String reason) {
    pending.forEach(
        (id, future) -> {
          pending.remove(id);
          future.completeExceptionally(new McpException(reason));
        });
  }

  private static String summarize(String value) {
    String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
    return oneLine.length() <= 256 ? oneLine : oneLine.substring(0, 256);
  }
}
