/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in llama-server process whose chat replies stream until the test releases them. Run
 * through the launcher script {@link #install} writes, it appends {@code launch <model> <pid>} to
 * {@code events.txt}, answers {@code /health} and a llama-shaped {@code /props}, and answers
 * {@code /v1/chat/completions} with a first event at once and the rest only after the file
 * {@code release} appears, appending {@code done <pid>} once the reply is complete. It exits when the
 * process that launched it ends.
 */
public final class StreamingFakeLlamaServer {
  private static final String HOME_PROPERTY = "fake.llama.home";
  private static final String EVENTS = "events.txt";
  private static final String RELEASE = "release";

  /** The last event of every complete reply. */
  public static final String FINAL_EVENT = "data: {\"choices\":[{\"delta\":{\"content\":\"end\"}}]}";

  private StreamingFakeLlamaServer() {}

  /** Every event recorded under {@code dir}, in order ({@code launch <model> <pid>}, {@code done <pid>}). */
  public static List<String> events(Path dir) throws IOException {
    Path file = dir.resolve(EVENTS);
    if (!Files.exists(file)) return List.of();
    List<String> events = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
      if (!line.isBlank()) events.add(line.trim());
    }
    return events;
  }

  /** Lets every open reply finish. */
  public static void release(Path dir) throws IOException {
    Files.writeString(dir.resolve(RELEASE), "go", StandardCharsets.US_ASCII);
  }

  /** Ends every server launched from {@code dir} and waits for it (test cleanup). */
  public static void endAll(Path dir) throws IOException {
    for (String event : events(dir)) {
      String[] fields = event.split(" ");
      if (!fields[0].equals("launch")) continue;
      ProcessHandle.of(Long.parseLong(fields[2])).ifPresent(server -> {
        server.destroyForcibly();
        try {
          server.onExit().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
          // best-effort cleanup of a test helper
        }
      });
    }
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(value(args, "--port", "-1"));
    String model = value(args, "-m", "model.gguf").replace('\\', '/');
    String window = value(args, "-c", "4096");
    Path home = Path.of(System.getProperty(HOME_PROPERTY));
    long pid = ProcessHandle.current().pid();
    record(home, "launch " + model.substring(model.lastIndexOf('/') + 1) + " " + pid);
    ProcessHandle launcher = ProcessHandle.current().parent().orElse(null);
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.createContext("/health", exchange -> {
      byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/props", exchange -> {
      byte[] body = ("{\"model_path\":\"" + model + "\",\"n_ctx\":" + window + "}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        // Large enough to pass every buffer, so the client sees the reply start at once.
        output.write(("data: {\"pad\":\"" + "x".repeat(32768) + "\"}\n\n")
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(home.resolve(RELEASE)) && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        output.write((FINAL_EVENT + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
        record(home, "done " + pid);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    server.start();
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    while (launcher != null && launcher.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    server.stop(0);
    System.exit(0);
  }

  private static synchronized void record(Path home, String event) throws IOException {
    Files.writeString(home.resolve(EVENTS), event + "\n", StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private static String value(String[] args, String flag, String fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (flag.equals(args[i])) return args[i + 1];
    }
    return fallback;
  }

  /**
   * Writes {@code llama-server.cmd} (Windows) or {@code llama-server} into {@code dir}: a launcher
   * that runs this class on the test classpath with the arguments it receives. Returns its path, to
   * be configured as the executable.
   */
  public static Path install(Path dir) throws IOException {
    Files.createDirectories(dir);
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    Path argFile = dir.resolve("classpath.args");
    String classPath = System.getProperty("java.class.path").replace('\\', '/');
    String home = dir.toAbsolutePath().toString().replace('\\', '/');
    Files.writeString(argFile,
        "-D" + HOME_PROPERTY + "=\"" + home + "\"\n-cp \"" + classPath + "\"\n",
        StandardCharsets.UTF_8);
    Path script = dir.resolve(windows ? "llama-server.cmd" : "llama-server");
    String main = StreamingFakeLlamaServer.class.getName();
    String body = windows
        ? "@echo off\r\n\"" + java + "\" @\"" + argFile + "\" " + main + " %*\r\n"
        : "#!/bin/sh\nexec \"" + java + "\" @\"" + argFile + "\" " + main + " \"$@\"\n";
    Files.writeString(script, body, StandardCharsets.UTF_8);
    if (!script.toFile().setExecutable(true)) {
      throw new IOException("could not make " + script + " executable");
    }
    return script;
  }
}
