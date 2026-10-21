/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
 * A stand-in llama-server whose start takes a while, for tests that need a launch to be in progress
 * when something else happens. Run through the launcher script {@link #install} writes, it records
 * each launch ({@code -m} model file name and pid) in {@code launches.txt}, waits the configured start delay,
 * then binds the {@code --port} it is given and answers {@code /health} and a llama-shaped
 * {@code /props}. It exits when the process that launched it ends, during the delay too, so
 * stopping the launched process stops the server. Tests can separately hold port binding and
 * a health response with explicit file signals.
 */
public final class SlowStartFakeLlamaServer {
  private static final String HOME_PROPERTY = "fake.llama.home";
  private static final String DELAY_PROPERTY = "fake.llama.startDelayMs";
  private static final String LAUNCHES = "launches.txt";

  private SlowStartFakeLlamaServer() {}

  /** One recorded launch: the file name of the {@code -m} model it was started with. */
  public record Launch(String model, long pid, java.time.Instant birth) {}

  /** Every launch made from {@code dir}, in order. */
  public static List<Launch> launches(Path dir) throws IOException {
    Path file = dir.resolve(LAUNCHES);
    List<Launch> launches = new ArrayList<>();
    if (!Files.exists(file)) return launches;
    for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
      if (line.isBlank()) continue;
      String[] fields = line.trim().split(" ");
      launches.add(new Launch(fields[0], Long.parseLong(fields[1]), java.time.Instant.parse(fields[2])));
    }
    return launches;
  }

  /** True when no server launched from {@code dir} is still alive. */
  public static boolean noneAlive(Path dir) throws IOException {
    for (Launch launch : launches(dir)) {
      if (ProcessHandle.of(launch.pid())
          .filter(server -> server.info().startInstant().filter(launch.birth()::equals).isPresent())
          .map(ProcessHandle::isAlive).orElse(false)) return false;
    }
    return true;
  }

  /** Ends every server launched from {@code dir} and waits for it (test cleanup). */
  public static void endAll(Path dir) throws IOException {
    for (Launch launch : launches(dir)) {
      ProcessHandle.of(launch.pid())
          .filter(server -> server.info().startInstant().filter(launch.birth()::equals).isPresent())
          .ifPresent(server -> {
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
    String home = System.getProperty(HOME_PROPERTY);
    if (home != null) {
      String modelName = model.substring(model.lastIndexOf('/') + 1);
      Files.writeString(Path.of(home, LAUNCHES),
          modelName + " " + ProcessHandle.current().pid() + " "
              + ProcessHandle.current().info().startInstant().orElseThrow() + "\n",
          StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    ProcessHandle launcher = ProcessHandle.current().parent().orElse(null);
    java.time.Instant launcherBirth = launcher == null ? null
        : launcher.info().startInstant().orElseThrow();
    java.util.function.BooleanSupplier launcherAlive = () -> launcher != null && launcher.isAlive()
        && launcher.info().startInstant().filter(launcherBirth::equals).isPresent();
    if (home != null && "1".equals(value(args, "-np", "2"))
        && Files.exists(Path.of(home, "refuse-vision"))) {
      throw new IOException("The fake process refuses its vision configuration");
    }
    if (home != null && !"1".equals(value(args, "-np", "2"))
        && Files.exists(Path.of(home, "refuse-normal"))) {
      throw new IOException("The fake process refuses its normal configuration");
    }
    if (home != null) Files.writeString(Path.of(home, "last-args.txt"),
        String.join("\n", args), StandardCharsets.UTF_8);
    long ready = System.nanoTime()
        + TimeUnit.MILLISECONDS.toNanos(Long.getLong(DELAY_PROPERTY, 0L));
    while (System.nanoTime() < ready) {
      if (launcher != null && !launcherAlive.getAsBoolean()) System.exit(0);
      Thread.sleep(50);
    }
    if (home != null && Files.exists(Path.of(home, "hold-port"))) {
      Files.writeString(Path.of(home, "port-entered-" + ProcessHandle.current().pid()), "entered");
      long portDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (Files.exists(Path.of(home, "hold-port"))) {
        if (System.nanoTime() >= portDeadline) throw new IOException("port release timed out");
        if (launcher != null && !launcherAlive.getAsBoolean()) System.exit(0);
        Thread.sleep(5);
      }
    }
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext("/health", exchange -> {
      if (home != null) {
        Path entered = Path.of(home, "health-entered-" + ProcessHandle.current().pid());
        Files.writeString(entered, "entered");
        long healthDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (Files.exists(Path.of(home, "hold-health"))) {
          if (System.nanoTime() >= healthDeadline) {
            throw new IOException("health release timed out");
          }
          if (launcher != null && !launcherAlive.getAsBoolean()) System.exit(0);
          try {
            Thread.sleep(5);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("health wait interrupted", interrupted);
          }
        }
      }
      byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    var props = server.createContext("/props", exchange -> {
      byte[] body = ("{\"model_path\":\"" + model + "\",\"n_ctx\":" + window + "}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    var chat = server.createContext("/v1/chat/completions", exchange -> {
      if (home != null) {
        Files.writeString(Path.of(home, "requests.txt"), "completion\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (Files.exists(Path.of(home, "pause-completion")) && System.nanoTime() < end) {
          try { Thread.sleep(10); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
      }
      byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"A clear document with visible words.\"},"
          + "\"finish_reason\":\"stop\",\"logprobs\":{\"content\":[{\"token\":\"document\","
          + "\"logprob\":-0.01}]}}],\"usage\":{\"completion_tokens\":8,\"prompt_tokens\":8}}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    String key = System.getenv("LLAMA_API_KEY");
    if (!(home != null && Files.exists(Path.of(home, "ignore-api-key")))
        && (key != null || (home != null && Files.exists(Path.of(home, "require-api-key"))))) {
      String expected = key == null ? "missing-engine-key" : key;
      for (var context : List.of(props, chat)) {
        context.getFilters().add(new com.sun.net.httpserver.Filter() {
          @Override public String description() { return "llama API authentication"; }
          @Override public void doFilter(com.sun.net.httpserver.HttpExchange exchange,
              Chain chain) throws IOException {
            if (("Bearer " + expected).equals(
                exchange.getRequestHeaders().getFirst("Authorization"))) {
              chain.doFilter(exchange);
            } else {
              byte[] body = "Invalid API Key".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(401, body.length);
              exchange.getResponseBody().write(body);
              exchange.close();
            }
          }
        });
      }
    }
    server.start();
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    while (launcherAlive.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    // Model the actual managed process stop. HttpServer.stop can wait for a deliberately paused
    // handler; the fake must instead end with its launcher, without delaying terminal cleanup.
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
   * that runs this class on the test classpath with the arguments it receives, binding its port
   * {@code startDelayMs} after it starts. Returns its path, to be configured as the executable.
   */
  public static Path install(Path dir, long startDelayMs) throws IOException {
    Files.createDirectories(dir);
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    Path argFile = dir.resolve("classpath.args");
    String classPath = System.getProperty("java.class.path").replace('\\', '/');
    String home = dir.toAbsolutePath().toString().replace('\\', '/');
    Files.writeString(
        argFile,
        "-D" + HOME_PROPERTY + "=\"" + home + "\"\n-D" + DELAY_PROPERTY + "=" + startDelayMs
            + "\n-cp \"" + classPath + "\"\n",
        StandardCharsets.UTF_8);
    Path script = dir.resolve(windows ? "llama-server.cmd" : "llama-server");
    String main = SlowStartFakeLlamaServer.class.getName();
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
