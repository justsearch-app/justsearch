/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The local-only implicit-feedback preference.
 *
 * <p>A missing file defaults on. A present but unreadable or future-version file fails closed:
 * capture is disabled and writes are locked so the user's prior privacy choice cannot be silently
 * overwritten.
 */
public final class FeedbackCaptureSettings {
  private static final Logger log = LoggerFactory.getLogger(FeedbackCaptureSettings.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};
  private static final String FILE = "feedback-capture.json";
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public static final String PRIVACY_NOTE =
      "Feedback capture is local-only. Your clicks, opens, and dwell time on search results and chat"
          + " citations are recorded on this machine to improve ranking over time. Nothing is ever"
          + " uploaded or sent over the network. You can turn this off at any time.";

  private final Path file;
  private volatile Boolean cached;
  private volatile String persistenceError;
  private volatile boolean writeLocked;

  public FeedbackCaptureSettings(Path dataDir) {
    this.file = dataDir.resolve(FILE);
  }

  /** True unless the user turned capture off; corrupt persisted state returns false. */
  public boolean isEnabled() {
    Boolean value = cached;
    if (value != null) return value;
    value = readEnabled();
    cached = value;
    return value;
  }

  /** Persist atomically. Corrupt or future state must be repaired before it can be replaced. */
  public synchronized void setEnabled(boolean enabled) {
    isEnabled();
    if (writeLocked) {
      throw new CorruptDurableStoreException(
          "feedback-capture-preference",
          "writes are locked because the existing preference is unreadable: " + persistenceError);
    }
    try {
      AtomicFileWrites.replaceUtf8(
          file,
          MAPPER.writeValueAsString(
              Map.of("schemaVersion", CURRENT_SCHEMA_VERSION, "enabled", enabled)));
      cached = enabled;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to persist feedback-capture preference to " + file, e);
    }
  }

  public boolean isWriteLocked() {
    isEnabled();
    return writeLocked;
  }

  public Optional<String> persistenceError() {
    isEnabled();
    return Optional.ofNullable(persistenceError);
  }

  private boolean readEnabled() {
    if (!Files.exists(file)) return true;
    try {
      String raw = Files.readString(file);
      if (raw.isBlank()) {
        throw new CorruptDurableStoreException(
            "feedback-capture-preference", "preference file is blank");
      }
      Map<String, Object> state = MAPPER.readValue(raw, MAP_REF);
      Object versionValue = state.get("schemaVersion");
      Integer observedVersion =
          versionValue == null
              ? null
              : versionValue instanceof Number number
                  ? number.intValue()
                  : throwCorruptVersion(versionValue);
      StoreFormatVersions.requireReadable(
          "feedback-capture-preference",
          observedVersion,
          CURRENT_SCHEMA_VERSION,
          0,
          0);
      Object enabled = state.get("enabled");
      if (!(enabled instanceof Boolean value)) {
        throw new CorruptDurableStoreException(
            "feedback-capture-preference", "enabled must be a boolean");
      }
      return value;
    } catch (CorruptDurableStoreException | UnsupportedStoreVersionException e) {
      lockOnReadFailure(e);
      return false;
    } catch (Exception e) {
      CorruptDurableStoreException corrupt =
          new CorruptDurableStoreException(
              "feedback-capture-preference", "cannot parse " + file, e);
      lockOnReadFailure(corrupt);
      return false;
    }
  }

  private void lockOnReadFailure(RuntimeException error) {
    persistenceError = error.getMessage();
    writeLocked = true;
    log.warn("Feedback capture disabled and preference writes locked: {}", persistenceError);
  }

  private static int throwCorruptVersion(Object value) {
    throw new CorruptDurableStoreException(
        "feedback-capture-preference", "schemaVersion must be an integer, got " + value);
  }
}
