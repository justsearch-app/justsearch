/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * S2 with a real Windows sharing denial: another handle holds settings.json open without any
 * sharing, as a backup tool, virus scanner or second program can. While it stays held,
 * the load fails as settings unavailable and nothing is moved aside.
 */
// Known gap: ContendedFileReads does not retry a sharing violation, so the load fails cleanly at once.
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
final class UiSettingsStoreWindowsSharingTest {
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";

  @TempDir Path directory;

  private record Outcome(UiSettings settings, Throwable failure) {}

  @Test
  void aHolderBeyondTheBudgetFailsTheLoadAsUnavailableWithNothingMoved() throws Exception {
    Path file = directory.resolve("settings.json");
    byte[] bytes = envelope(4343, new SettingsWitness(3, KEY));
    Files.write(file, bytes);
    var store = new UiSettingsStore(READ_WRITE, file);

    Outcome outcome;
    try (FileChannel ignoredHeld = holdWithoutSharing(file)) {
      outcome = loadOnItsOwnThread(store).get(10, TimeUnit.SECONDS);
      assertEquals(List.of(), quarantine(file), "nothing may be moved aside while held");
    }

    assertInstanceOf(UncheckedIOException.class, outcome.failure(),
        "a held file is unavailable, not corrupt, and yields no settings: " + outcome);
    assertTrue(outcome.failure().getMessage().startsWith("Settings unavailable"), outcome.failure().getMessage());
    assertArrayEquals(bytes, Files.readAllBytes(file));
    assertEquals(List.of(), quarantine(file), "nothing may be moved aside");
    assertTrue(store.lastRecovery().isEmpty(), "no reset notice");
    assertEquals(4343, new UiSettingsStore(READ_WRITE, file).load().getMaxTokens(),
        "the same file loads normally once released");
  }

  private static FileChannel holdWithoutSharing(Path file) throws IOException {
    return FileChannel.open(file, StandardOpenOption.READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
  }

  private static CompletableFuture<Outcome> loadOnItsOwnThread(UiSettingsStore store) {
    var result = new CompletableFuture<Outcome>();
    Thread reader = new Thread(() -> {
      try {
        result.complete(new Outcome(store.load(), null));
      } catch (Throwable failure) {
        result.complete(new Outcome(null, failure));
      }
    }, "settings-sharing-reader");
    reader.setDaemon(true);
    reader.start();
    return result;
  }

  private static List<Path> quarantine(Path file) throws IOException {
    String prefix = file.getFileName() + ".corrupt-";
    try (Stream<Path> siblings = Files.list(file.getParent())) {
      return siblings.filter(path -> path.getFileName().toString().startsWith(prefix)).sorted().toList();
    }
  }

  private byte[] envelope(int maxTokens, SettingsWitness witness) throws IOException {
    Path scratch = Files.createTempDirectory(directory, "envelope");
    var writer = new UiSettingsStore(READ_WRITE, scratch.resolve("settings.json"));
    var settings = new UiSettings();
    settings.setMaxTokens(maxTokens);
    writer.replacePrepared(writer.prepareExact(settings, witness));
    return Files.readAllBytes(scratch.resolve("settings.json"));
  }
}
