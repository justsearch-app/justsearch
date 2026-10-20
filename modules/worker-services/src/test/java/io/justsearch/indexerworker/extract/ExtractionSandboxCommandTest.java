package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The shipped child argv (tempdoc 885 item 14) — the piece tempdoc 410 left to the operator. */
@Tag("cross-platform")
final class ExtractionSandboxCommandTest {

  private static final String CHILD_MAIN =
      "io.justsearch.indexerworker.extract.ExtractionSandboxChild";

  /**
   * The inline form, asked for explicitly. The public entry point picks inline vs argfile by
   * length, and this JVM's own classpath decides which — under an isolated Gradle home it is long
   * enough to cross the threshold, so pinning the inline shape has to name the threshold rather
   * than inherit whatever the runner happened to hand us.
   */
  private static List<String> inlineCommand(String classpath) {
    return ExtractionSandboxCommand.defaultCommand(
        TikaExtractionPolicy.defaults(), "", Integer.MAX_VALUE, classpath);
  }

  @Test
  void defaultCommandLaunchesTheChildOnThisJvmAndClasspath() {
    String classpath = System.getProperty("java.class.path");
    List<String> argv = inlineCommand(classpath);

    assertTrue(
        argv.get(0).startsWith(System.getProperty("java.home")),
        "must reuse the running JVM, not a PATH lookup: " + argv.get(0));
    assertEquals(CHILD_MAIN, argv.get(argv.size() - 1));
    assertEquals("-cp", argv.get(argv.size() - 3));
    assertEquals(classpath, argv.get(argv.size() - 2));
    assertTrue(argv.contains("--enable-native-access=ALL-UNNAMED"), argv.toString());
    assertTrue(argv.contains("-XX:+UseSerialGC"), argv.toString());
  }

  @Test
  void heapFlagIsPresentAndSizedFromThePolicysLargestAcceptedInput() {
    List<String> argv = inlineCommand("ignored");

    // Default policy accepts 100 MB inputs; 4x that is 400 MB, below the 512m floor.
    assertTrue(argv.contains("-Xmx512m"), argv.toString());
    assertEquals("512m", ExtractionSandboxCommand.heapSpec(TikaExtractionPolicy.defaults(), null));

    TikaExtractionPolicy large =
        new TikaExtractionPolicy(
            "large-input",
            1024,
            400L * 1024 * 1024,
            1024,
            128,
            128,
            4096,
            0,
            0,
            100.0d,
            true,
            Set.of(),
            Set.of());
    assertEquals("1600m", ExtractionSandboxCommand.heapSpec(large, ""));
    assertEquals("768m", ExtractionSandboxCommand.heapSpec(large, "768m"));
  }

  @Test
  void aotCacheIsOnlyInheritedWhenTheWorkerHasOneAndTheFileExists() {
    String inherited = ExtractionSandboxCommand.inheritedAotCache();
    if (inherited == null) {
      // The Gradle test JVM is launched without -XX:AOTCache, which is the common case.
      assertFalse(
          inlineCommand("ignored").stream().anyMatch(a -> a.startsWith("-XX:AOTCache=")),
          "no AOT flag may be invented when the parent JVM has none");
    } else {
      assertTrue(Files.exists(Path.of(inherited)), "an inherited AOT cache path must exist");
    }
  }

  /**
   * Windows {@code CreateProcess} fails with {@code error=206} past 32,767 characters, and a
   * Gradle test JVM under an isolated Gradle home hands over an expanded classpath that clears it
   * on its own — which is how this surfaced, in three different lanes.
   */
  @Test
  void switchesToAnArgfileWhenTheCommandLineWouldExceedTheWindowsLimit() throws Exception {
    String longClasspath = ("C:\\some\\jar\\path with space\\lib.jar;").repeat(1200);
    assertTrue(longClasspath.length() > 32_767, "the fixture must actually be over the limit");

    List<String> argv =
        ExtractionSandboxCommand.defaultCommand(
            TikaExtractionPolicy.defaults(), "", 30_000, longClasspath);

    assertEquals(3, argv.size(), "argfile form is <java> @<file> <main>; got: " + argv);
    assertEquals(CHILD_MAIN, argv.get(2));
    assertTrue(argv.get(1).startsWith("@"), "second argument must be an argfile: " + argv.get(1));

    Path argFile = Path.of(argv.get(1).substring(1));
    assertTrue(Files.exists(argFile), "argfile must have been written: " + argFile);
    String content = Files.readString(argFile, StandardCharsets.UTF_8);
    assertTrue(content.contains(ExtractionSandboxCommand.argFileToken("-cp")), content);
    assertTrue(
        content.contains(ExtractionSandboxCommand.argFileToken(longClasspath)),
        "the classpath must round-trip through the argfile encoding");
    assertTrue(content.contains(ExtractionSandboxCommand.argFileToken("-Xmx512m")), content);

    assertTrue(
        ExtractionSandboxCommand.commandLineLength(argv) < 1000,
        "the point of the argfile is a short command line; got "
            + ExtractionSandboxCommand.commandLineLength(argv));
  }

  /**
   * The public entry point must pick the two forms consistently on whatever classpath this runner
   * happens to have — which is the variable that decides it, and the reason the same suite passed
   * under one Gradle home and died with {@code error=206} under an isolated one.
   */
  @Test
  void publicEntryPointPicksTheFormThatFitsThisRunnersClasspath() {
    List<String> actual =
        ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), "");
    List<String> inline = inlineCommand(System.getProperty("java.class.path"));
    boolean tooLong =
        ExtractionSandboxCommand.commandLineLength(inline)
            > ExtractionSandboxCommand.MAX_INLINE_COMMAND_CHARS;

    assertEquals(
        tooLong,
        actual.get(1).startsWith("@"),
        "inline length is "
            + ExtractionSandboxCommand.commandLineLength(inline)
            + " against a threshold of "
            + ExtractionSandboxCommand.MAX_INLINE_COMMAND_CHARS);
    assertTrue(
        ExtractionSandboxCommand.commandLineLength(actual)
            <= ExtractionSandboxCommand.MAX_INLINE_COMMAND_CHARS,
        "the chosen form must always fit the command-line limit");
  }

  @Test
  void inlineFormIsKeptBelowTheThreshold() {
    List<String> argv =
        ExtractionSandboxCommand.defaultCommand(
            TikaExtractionPolicy.defaults(), "", 30_000, "C:\\short\\lib.jar");

    assertEquals(CHILD_MAIN, argv.get(argv.size() - 1));
    assertEquals("C:\\short\\lib.jar", argv.get(argv.size() - 2));
    assertFalse(argv.stream().anyMatch(a -> a.startsWith("@")), argv.toString());
  }

  /**
   * The argfile grammar treats a backslash as an escape character inside a quoted token, so a
   * Windows path written verbatim loses its separators. Doubling is the encoding that survives.
   */
  @Test
  void argFileTokenQuotesSpacesAndEscapesBackslashes() {
    assertEquals(
        "\"C:\\\\Program Files\\\\jdk\\\\lib.jar\"",
        ExtractionSandboxCommand.argFileToken("C:\\Program Files\\jdk\\lib.jar"));
    assertEquals("\"-Xmx512m\"", ExtractionSandboxCommand.argFileToken("-Xmx512m"));
    assertEquals("\"say \\\"hi\\\"\"", ExtractionSandboxCommand.argFileToken("say \"hi\""));
  }

  // ---- the operator override's argv (tempdoc 885 §UD residue) ----------------------------------
  // JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND was `raw.trim().split("\\s+")`, so an operator whose JDK
  // lives under "C:\Program Files\..." could not name it at all: the path split into two arguments
  // and the spawn failed. Quoting fixes that WITHOUT changing the unquoted form.

  @Test
  void unquotedCommandsSplitOnWhitespaceExactlyAsBefore() {
    assertEquals(
        List.of("java", "-Xmx128m", "-cp", "C:\\jdk\\lib.jar", CHILD_MAIN),
        ExtractionSandboxCommand.tokenize(
            "  java   -Xmx128m\t-cp  C:\\jdk\\lib.jar\n" + CHILD_MAIN + " "));
    assertEquals(List.of(), ExtractionSandboxCommand.tokenize("   "));
    assertEquals(List.of(), ExtractionSandboxCommand.tokenize(null));
  }

  @Test
  void aQuotedPathWithSpacesIsOneArgument() {
    assertEquals(
        List.of("C:\\Program Files\\jdk\\bin\\java.exe", "-cp", "C:\\Program Files\\app\\lib.jar"),
        ExtractionSandboxCommand.tokenize(
            "\"C:\\Program Files\\jdk\\bin\\java.exe\" -cp 'C:\\Program Files\\app\\lib.jar'"),
        "a backslash inside quotes is a path separator, not an escape — the whole point is that a"
            + " Windows path can be pasted in verbatim");
  }

  @Test
  void escapesInsideDoubleQuotesAreQuoteAndBackslashOnly() {
    assertEquals(List.of("say \"hi\""), ExtractionSandboxCommand.tokenize("\"say \\\"hi\\\"\""));
    assertEquals(List.of("a\\b"), ExtractionSandboxCommand.tokenize("\"a\\\\b\""));
    // Single quotes have no escapes at all, so a backslash there is always literal.
    assertEquals(List.of("a\\\"b"), ExtractionSandboxCommand.tokenize("'a\\\"b'"));
  }

  @Test
  void quotesGroupWithinAnArgumentAndAnEmptyQuotedArgumentSurvives() {
    assertEquals(List.of("-Dopt=a b"), ExtractionSandboxCommand.tokenize("-Dopt=\"a b\""));
    assertEquals(List.of("ab", "c"), ExtractionSandboxCommand.tokenize("a\"b\" c"));
    assertEquals(List.of("java", ""), ExtractionSandboxCommand.tokenize("java \"\""));
  }

  @Test
  void anUnterminatedQuoteIsRefusedRatherThanMisSplit() {
    IllegalArgumentException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> ExtractionSandboxCommand.tokenize("java \"C:\\Program Files\\java.exe"));
    assertTrue(e.getMessage().contains("unterminated"), e.getMessage());
  }

  /**
   * The documented trailing-backslash caveat, pinned so the doc and the parser cannot drift: a
   * directory written with its separator before the closing quote escapes the quote.
   */
  @Test
  void aTrailingSeparatorBeforeTheClosingQuoteEscapesIt() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> ExtractionSandboxCommand.tokenize("java -cp \"C:\\dir\\\""));
    assertEquals(
        List.of("java", "-cp", "C:\\dir\\"),
        ExtractionSandboxCommand.tokenize("java -cp \"C:\\dir\\\\\""),
        "doubling the trailing separator is the documented way to express it");
  }
}
