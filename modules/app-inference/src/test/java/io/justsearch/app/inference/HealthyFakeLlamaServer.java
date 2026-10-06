/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in llama-server for tests that need a managed launch to become healthy without a model.
 * Run through the launcher script {@link #install} writes, it binds the {@code --port} it is given
 * (the Engine's fresh loopback port), answers {@code /health} and a llama-shaped {@code /props}
 * naming the {@code -m} model and the {@code -c} window, and exits when the process that launched it
 * ends, so stopping the launched process stops the server.
 */
public final class HealthyFakeLlamaServer {
  private static final String HOME_PROPERTY = "fake.llama.home";
  private static final String PIDS = "pids.txt";

  private HealthyFakeLlamaServer() {}

  /**
   * Ends every server launched from {@code dir} and waits for it. On Windows the launched process
   * is the launcher script's shell; once that is stopped the server is no longer this JVM's
   * descendant, so it is found by the pid it recorded.
   */
  static void endAll(Path dir) throws IOException {
    Path pids = dir.resolve(PIDS);
    if (!Files.exists(pids)) return;
    for (String line : Files.readAllLines(pids, StandardCharsets.US_ASCII)) {
      if (line.isBlank()) continue;
      ProcessHandle.of(Long.parseLong(line.trim())).ifPresent(server -> {
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
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
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
    server.start();
    String home = System.getProperty(HOME_PROPERTY);
    if (home != null) {
      Files.writeString(Path.of(home, PIDS), ProcessHandle.current().pid() + "\n",
          StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }
    ProcessHandle launcher = ProcessHandle.current().parent().orElse(null);
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    while (launcher != null && launcher.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    server.stop(0);
    System.exit(0);
  }

  private static String value(String[] args, String flag, String fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (flag.equals(args[i])) return args[i + 1];
    }
    return fallback;
  }

  /**
   * Writes {@code llama-server.cmd} (Windows) or {@code llama-server} into {@code dir}: a launcher
   * that runs this class on the test classpath with the arguments it receives. Returns its path,
   * to be configured as the llama-server executable.
   */
  static Path install(Path dir) throws IOException {
    Files.createDirectories(dir);
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    Path argFile = dir.resolve("classpath.args");
    String classPath = System.getProperty("java.class.path").replace('\\', '/');
    String home = dir.toAbsolutePath().toString().replace('\\', '/');
    Files.writeString(
        argFile,
        "-D" + HOME_PROPERTY + "=\"" + home + "\"\n-cp \"" + classPath + "\"\n",
        StandardCharsets.UTF_8);
    Path script = dir.resolve(windows ? "llama-server.cmd" : "llama-server");
    String main = HealthyFakeLlamaServer.class.getName();
    String body =
        windows
            ? "@echo off\r\n\"" + java + "\" @\"" + argFile + "\" " + main + " %*\r\n"
            : "#!/bin/sh\nexec \"" + java + "\" @\"" + argFile + "\" " + main + " \"$@\"\n";
    Files.writeString(script, body, StandardCharsets.UTF_8);
    if (!script.toFile().setExecutable(true)) {
      throw new IOException("could not make " + script + " executable");
    }
    return script;
  }
}
