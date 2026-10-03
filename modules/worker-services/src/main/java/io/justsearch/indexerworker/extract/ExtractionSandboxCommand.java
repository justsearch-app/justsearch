/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the extraction child's argv in-process (tempdoc 885 item 14, design decision 1).
 *
 * <p>Tempdoc 410 shipped the {@code process} sandbox mode but required the operator to author the
 * command, which is why it was unreachable as shipped. The recipe is the one the retired
 * {@code ProcessExtractionSandboxTest} already proved: the running JVM's launcher plus its own
 * classpath. The JVM this code runs in starts from a plain {@code -cp lib\*} classpath, not a jlink
 * image, so the same pair works in production. That was {@code WorkerSpawner.buildCommand} until
 * lane F stage A item A11 deleted it; since item A6 this code runs in the Head JVM, whose
 * {@code installDist} start script sets the same {@code lib\*} wildcard.
 *
 * <p>Both are read as <b>JVM self-introspection</b>, not as configuration: the launcher from
 * {@link ProcessHandle} and the classpath from {@link ManagementFactory}, so no configuration key
 * is invented for something the process already knows about itself. Only the operator-facing knobs
 * (heap, pool size, request budget, full argv override) go through {@code EnvRegistry}.
 *
 * <p><b>Long classpaths go through a JDK {@code @argfile}.</b> Windows caps a command line at
 * 32,767 characters and {@code CreateProcess} fails with {@code error=206} past it. Production is
 * comfortably under, because the production launcher passes a {@code -cp lib\*} wildcard the JVM
 * expands itself ({@code WorkerSpawner} until item A11, the Head's {@code installDist} start script
 * since) — but this builder must not depend on that. A Gradle test
 * JVM (and any embedder) hands over a fully expanded classpath that clears 32k on its own, which
 * is exactly how this surfaced. Above {@link #MAX_INLINE_COMMAND_CHARS} the JVM options move into
 * an argfile and the command becomes {@code java @<file> <main>}.
 */
public final class ExtractionSandboxCommand {

  private static final String CHILD_MAIN =
      "io.justsearch.indexerworker.extract.ExtractionSandboxChild";

  /** Floor for the child heap (design decision 1). */
  static final long MIN_HEAP_BYTES = 512L * 1024 * 1024;

  /** Multiple of the largest accepted input the child heap must cover. */
  static final int HEAP_INPUT_MULTIPLE = 4;

  private static final String AOT_CACHE_FLAG = "-XX:AOTCache=";

  /**
   * Command-line length above which the JVM options move into an argfile. Windows'
   * {@code CreateProcess} limit is 32,767 characters; the margin covers the launcher path, the
   * main class, the {@code --parent-pid} argument the pool appends, and per-argument quoting.
   */
  static final int MAX_INLINE_COMMAND_CHARS = 30_000;

  private ExtractionSandboxCommand() {}

  /**
   * The default child command.
   *
   * @param policy the extraction policy; its {@code maxInputBytes} sizes the child heap
   * @param heapOverride an explicit heap string (e.g. {@code 768m}), or blank for the default
   */
  public static List<String> defaultCommand(TikaExtractionPolicy policy, String heapOverride) {
    return defaultCommand(
        policy,
        heapOverride,
        MAX_INLINE_COMMAND_CHARS,
        ManagementFactory.getRuntimeMXBean().getClassPath());
  }

  /**
   * Splits an operator-supplied {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND} into an argv.
   *
   * <p>Whitespace separates arguments, as it always did. What is new (tempdoc 885 §UD residue) is
   * that an argument may be QUOTED, so a path containing a space can be used inline instead of
   * being smuggled through a JVM {@code @argfile}:
   *
   * <ul>
   *   <li>{@code "…"} and {@code '…'} both group; the quotes themselves are removed.
   *   <li>Inside double quotes, {@code \"} and {@code \\} are escapes. <b>Every other backslash is
   *       literal</b> — {@code "C:\Program Files\jdk\bin\java.exe"} must survive verbatim, and a
   *       shell-strict reading would eat {@code \P}, {@code \j} and {@code \b}.
   *   <li>Inside single quotes nothing is an escape, exactly as in POSIX shells.
   *   <li>Outside quotes a backslash is literal too, so an unquoted Windows path is unchanged. The
   *       way to include a space is therefore to quote the argument, not to escape the space.
   *   <li>{@code ""} produces an empty argument rather than disappearing.
   * </ul>
   *
   * <p><b>Trailing-backslash caveat.</b> Because {@code \\} is an escape inside double quotes, a
   * directory path written with a trailing separator before the closing quote — {@code "C:\dir\"} —
   * escapes the quote instead of ending the argument, and the value is reported as unterminated.
   * Write {@code "C:\dir"} or {@code "C:\dir\\"}.
   *
   * @throws IllegalArgumentException on an unterminated quote. This is deliberately FAIL-CLOSED:
   *     {@code DefaultWorkerAppServices.buildContentExtractor} calls this before
   *     {@code probeChildCommand}, so a malformed value aborts Worker startup rather than degrading
   *     to in-process extraction the way a command that merely fails its probe does. A mis-split
   *     argv would otherwise spawn a child with silently wrong arguments, surfacing as "every file
   *     fails to extract" instead of as the configuration error it is.
   */
  public static List<String> tokenize(String raw) {
    List<String> argv = new ArrayList<>();
    if (raw == null) {
      return argv;
    }
    StringBuilder current = new StringBuilder();
    boolean inArgument = false;
    char quote = 0;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (quote == '\'') {
        if (c == '\'') {
          quote = 0;
        } else {
          current.append(c);
        }
      } else if (quote == '"') {
        if (c == '\\' && i + 1 < raw.length()) {
          char next = raw.charAt(i + 1);
          if (next == '"' || next == '\\') {
            current.append(next);
            i++;
            continue;
          }
          current.append(c);
        } else if (c == '"') {
          quote = 0;
        } else {
          current.append(c);
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
        inArgument = true;
      } else if (Character.isWhitespace(c)) {
        if (inArgument) {
          argv.add(current.toString());
          current.setLength(0);
          inArgument = false;
        }
      } else {
        current.append(c);
        inArgument = true;
      }
    }
    if (quote != 0) {
      throw new IllegalArgumentException(
          "JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND has an unterminated "
              + (quote == '"' ? "double" : "single")
              + " quote: "
              + raw);
    }
    if (inArgument) {
      argv.add(current.toString());
    }
    return argv;
  }

  /** As above, with the threshold and classpath supplied — both are inputs, not policy. */
  static List<String> defaultCommand(
      TikaExtractionPolicy policy, String heapOverride, int maxInlineChars, String classpath) {
    String launcher = javaBinary();
    List<String> options = jvmOptions(policy, heapOverride, classpath);

    List<String> direct = new ArrayList<>();
    direct.add(launcher);
    direct.addAll(options);
    direct.add(CHILD_MAIN);
    if (commandLineLength(direct) <= maxInlineChars) {
      return List.copyOf(direct);
    }
    // One argfile per built command, reused by every child the pool spawns from it: the file has
    // to outlive any single child, so it cannot be deleted on child exit. Removed at Worker exit.
    return List.of(launcher, "@" + writeArgFile(options), CHILD_MAIN);
  }

  private static List<String> jvmOptions(
      TikaExtractionPolicy policy, String heapOverride, String classpath) {
    List<String> options = new ArrayList<>();
    // Serial GC: the child is a single-request-at-a-time parser with a small heap, so parallel GC
    // threads are pure overhead against the Worker's own CPU budget.
    options.add("-XX:+UseSerialGC");
    options.add("-Xmx" + heapSpec(policy, heapOverride));
    options.add("-Dfile.encoding=UTF-8");
    // Tika's PDFBox/POI paths make FFM downcalls; JDK 25 warns without this and a later JDK
    // refuses outright (the same reason WorkerSpawner passed it to the Worker, until item A11).
    options.add("--enable-native-access=ALL-UNNAMED");
    String aot = inheritedAotCache();
    if (aot != null) {
      options.add(AOT_CACHE_FLAG + aot);
    }
    options.add("-cp");
    options.add(classpath == null ? "" : classpath);
    return options;
  }

  /** Conservative command-line size: every argument may be quoted, and all are space-separated. */
  static int commandLineLength(List<String> argv) {
    int total = 0;
    for (String arg : argv) {
      total += arg.length() + 3;
    }
    return total;
  }

  /**
   * Encodes one token for a JDK argfile: quote it, and double every backslash.
   *
   * <p>The grammar splits on whitespace, so quoting is what carries a path with a space. Inside a
   * quoted token the backslash is an escape character — but an <em>unrecognised</em> escape is
   * passed through unchanged, which is what makes this latent rather than obvious: a typical
   * Windows classpath survives unescaped, so the bug only appears once a path happens to contain
   * a recognised sequence. Measured on JDK 25 by feeding an unescaped token through the launcher
   * and reading it back out of a child (see {@code argFileEncodingRoundTripsThroughTheJdkParser}):
   * {@code C:\tab\back\form\already\\doubled\a"quoted".jar} came back as
   * {@code C:<TAB>ab<BS>ack<FF>orm...} with the quotes eaten. Doubling is therefore not defensive
   * — it is the only encoding that survives an arbitrary path.
   */
  static String argFileToken(String token) {
    return '"' + token.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  static Path writeArgFile(List<String> options) {
    try {
      Path file = Files.createTempFile("justsearch-extraction-sandbox-", ".args");
      file.toFile().deleteOnExit();
      StringBuilder body = new StringBuilder();
      for (String option : options) {
        body.append(argFileToken(option)).append('\n');
      }
      Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
      return file.toAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the extraction sandbox argfile", e);
    }
  }

  /**
   * Child heap. {@code TikaExtractionPolicy.maxInputBytes} (default 100 MB, matching
   * {@code ContentExtractor.MAX_FILE_SIZE}) is the largest file the Worker will hand to a parser,
   * and POI needs 10-20x a document's size in heap — so the child gets at least 4x the largest
   * accepted input, with a 512m floor for the small-policy case.
   */
  static String heapSpec(TikaExtractionPolicy policy, String heapOverride) {
    if (heapOverride != null && !heapOverride.isBlank()) {
      return heapOverride.trim();
    }
    long effectivePolicyBytes =
        policy == null ? TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES : policy.maxInputBytes();
    long bytes = Math.max(MIN_HEAP_BYTES, effectivePolicyBytes * HEAP_INPUT_MULTIPLE);
    return (bytes / (1024 * 1024)) + "m";
  }

  /**
   * The running JVM's own launcher. Asked of the OS rather than reconstructed from
   * {@code os.name} + {@code java.home}: the child must be the same JVM the Worker runs, and a
   * bare {@code java} PATH lookup can resolve to an incompatible JDK (the trap tempdoc 696 fixed
   * for {@code WorkerSpawner}). The {@code java.home} branch is a fallback for a platform where
   * {@link ProcessHandle.Info#command()} is unavailable, and picks the launcher by file existence
   * rather than by parsing a platform string.
   */
  static String javaBinary() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent() && !command.get().isBlank()) {
      return command.get();
    }
    Path bin = Path.of(System.getProperty("java.home"), "bin");
    Path windows = bin.resolve("java.exe");
    return Files.exists(windows) ? windows.toString() : bin.resolve("java").toString();
  }

  /**
   * The Worker's own AOT cache path, if it was launched with one and the file still exists.
   * Reusing it is free (identical classpath, so the cache is valid) and removes most of the
   * child's class-loading cost; a stale or absent path is skipped rather than passed on, because
   * an unusable cache is a warning on every spawn for no benefit.
   */
  static String inheritedAotCache() {
    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (arg != null && arg.startsWith(AOT_CACHE_FLAG)) {
        String path = arg.substring(AOT_CACHE_FLAG.length());
        if (!path.isBlank() && Files.exists(Path.of(path))) {
          return path;
        }
        return null;
      }
    }
    return null;
  }
}
