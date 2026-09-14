/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves UI settings to {@code $JUSTSEARCH_HOME/ui/settings.json} (or
 * {@code ~/.config/justsearch/ui/settings.json} on Linux).
 *
 * <p>Corruption policy (ADR-0008, restored by tempdoc 882 item 24): an UNREADABLE settings file is
 * preserved, not destroyed and not fatal. The bytes are moved to a timestamped
 * {@code settings.json.corrupt-<UTC>} sibling, defaults are loaded, and the reset is published as a
 * lifecycle condition ({@code settings.reset_from_corrupt}) so the user learns their preferences
 * were reset instead of the Head refusing to start over a preferences file. A FUTURE
 * {@code schemaVersion} is a different question and stays fail-loud: {@link
 * StoreFormatVersions#requireReadable} still throws, because refusing to touch state a newer build
 * wrote is the safe answer, while defaulting over it would silently downgrade it on the next save.
 *
 * <p>A PAST {@code schemaVersion} is neither: it is listed in {@link #READABLE_LEGACY_VERSIONS} and
 * forward-migrated by {@link #migrate} on load (tempdoc 883). Migration runs on every load until the
 * next save rewrites the file at the current version, so each step must be idempotent.
 */
public final class UiSettingsStore {

  private static final Logger log = LoggerFactory.getLogger(UiSettingsStore.class);

  static final int CURRENT_SCHEMA_VERSION = 4;

  /**
   * Versions this build can still read and migrate forward. {@code 0} is the unversioned legacy
   * file (no envelope); {@code 1} is the pre-tempdoc-883 envelope. A version NOT listed here is
   * fatal by {@link StoreFormatVersions#requireReadable}, so every schema bump must extend this
   * list or every existing install fails to start.
   */
  private static final int[] READABLE_LEGACY_VERSIONS = {0, 1, 2, 3};

  /**
   * The pre-883 shipped default for {@code contextLength}. Tempdoc 883 made the context window a
   * derived resource with {@code 0} = auto, so a stored 4096 — which every install has on disk,
   * because settings are serialized whole on every save — has to become auto or the derived ladder
   * is unreachable forever. A deliberate user 4096 is indistinguishable from the default (there was
   * never a UI control for it) and is discarded; see tempdoc 883 §B.c.
   */
  private static final int LEGACY_DEFAULT_CONTEXT_LENGTH = 4096;

  private static final DateTimeFormatter BACKUP_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private final Path settingsFile;
  private final PersistenceMode mode;
  private volatile RecoveredFromCorrupt lastRecovery;
  private volatile Runnable onRecoveryCleared;

  /**
   * What {@link #load()} did when it found an unreadable settings file: where the original bytes
   * were preserved, and the parse failure that sent them there.
   */
  public record RecoveredFromCorrupt(Path backupPath, String detail) {}

  public UiSettingsStore(PersistenceMode mode) {
    this(mode, resolveSettingsFile());
  }

  public UiSettingsStore(PersistenceMode mode, Path settingsFile) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile");
  }

  public UiSettings load() {
    if (mode == PersistenceMode.IN_MEMORY) {
      return new UiSettings();
    }
    if (!Files.exists(settingsFile)) {
      return new UiSettings();
    }
    try {
      return parseOrThrow().settings();
    } catch (CorruptDurableStoreException e) {
      lastRecovery = quarantineCorruptFile(e);
      return new UiSettings();
    }
  }

  /** The last unreadable-file recovery this store performed, until the next successful save. */
  public Optional<RecoveredFromCorrupt> lastRecovery() {
    return Optional.ofNullable(lastRecovery);
  }

  /**
   * Called once the first time {@link #save(UiSettings)} succeeds after a quarantine, whoever
   * performs that save - the user re-authoring settings via the Settings UI, or a runtime
   * component such as the AI autostart seed writing its own defaults - because a rewritten file is
   * no longer the quarantined one, so the reset condition no longer describes anything true.
   */
  public void setOnRecoveryCleared(Runnable r) {
    this.onRecoveryCleared = r;
  }

  public record Snapshot(UiSettings settings, SettingsWitness witness) {}

  /** Reads without quarantine/default recovery, for commitment classification by the apply owner. */
  public Snapshot inspect() {
    if (mode == PersistenceMode.IN_MEMORY) {
      return new Snapshot(new UiSettings(), new SettingsWitness(0, null));
    }
    if (!Files.notExists(settingsFile)) {
      // Unknown accessibility is not absence. Parsing fails closed if the file cannot be read.
      return parseOrThrow();
    }
    if (lastRecovery != null) {
      throw new CorruptDurableStoreException("ui-settings", "settings were quarantined; witness unavailable");
    }
    // The preserved sibling outlives this process. Absence after quarantine is not proof of
    // an untouched zero-revision store. Only proven-absent parents may skip inspection.
    Path parent = settingsFile.toAbsolutePath().getParent();
    if (!Files.notExists(parent)) {
      try (var siblings = Files.list(parent)) {
        String prefix = settingsFile.getFileName() + ".corrupt-";
        if (siblings.anyMatch(path -> path.getFileName().toString().startsWith(prefix))) {
          throw new CorruptDurableStoreException("ui-settings", "preserved quarantine; witness unavailable");
        }
      } catch (IOException failure) {
        throw new UncheckedIOException("Cannot inspect settings quarantine evidence", failure);
      }
    }
    return new Snapshot(new UiSettings(), new SettingsWitness(0, null));
  }

  /**
   * Frozen identity of all preserved corruption evidence, only while the live file is proven absent.
   * This does not authorize a reset: the fixed owner must bind it to accepted server preparation
   * and revalidate it before arming. Unknown, empty or non-regular evidence cannot prove precommit.
   */
  public String recoveryFingerprint() throws IOException {
    if (!mode.isWritable() || !Files.notExists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Recovery requires writable storage and a proven absent settings file");
    }
    List<Path> siblings = quarantineFiles();
    if (siblings.isEmpty()) throw new IOException("Recovery requires preserved quarantine evidence");
    MessageDigest evidence = sha256();
    evidence.update("justsearch-settings-quarantine-v1".getBytes(StandardCharsets.UTF_8));
    evidence.update(ByteBuffer.allocate(Integer.BYTES).putInt(siblings.size()).array());
    for (Path sibling : siblings) {
      BasicFileAttributes before = Files.readAttributes(sibling, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()) throw new IOException("Quarantine evidence must be a regular file");
      MessageDigest content = sha256();
      try (var input = new DigestInputStream(Files.newInputStream(sibling, LinkOption.NOFOLLOW_LINKS), content)) {
        input.transferTo(OutputStream.nullOutputStream());
      }
      BasicFileAttributes after = Files.readAttributes(sibling, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile() || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(before.fileKey(), after.fileKey())) {
        throw new IOException("Quarantine evidence changed while reading");
      }
      byte[] name = sibling.getFileName().toString().getBytes(StandardCharsets.UTF_8);
      evidence.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
      evidence.update(name);
      evidence.update(content.digest());
    }
    requireSameQuarantineNames(siblings, quarantineFiles());
    if (!Files.notExists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Settings recovery evidence changed while reading");
    }
    return HexFormat.of().formatHex(evidence.digest());
  }

  // Compare exact names: Windows Path equality would hide a case-only rename after hashing.
  static void requireSameQuarantineNames(List<Path> before, List<Path> after) throws IOException {
    if (!before.stream().map(path -> path.getFileName().toString()).toList()
        .equals(after.stream().map(path -> path.getFileName().toString()).toList())) {
      throw new IOException("Quarantine names changed while reading");
    }
  }

  private List<Path> quarantineFiles() throws IOException {
    Path parent = settingsFile.toAbsolutePath().getParent();
    String prefix = settingsFile.getFileName() + ".corrupt-";
    try (var siblings = Files.list(parent)) {
      return siblings.filter(path -> path.getFileName().toString().startsWith(prefix))
          .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString())).toList();
    }
  }

  private static MessageDigest sha256() {
    try { return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException failure) { throw new AssertionError("SHA-256 is required by Java", failure); }
  }

  private Snapshot parseOrThrow() {
    try {
      JsonNode root = MAPPER.readTree(settingsFile.toFile());
      if (root == null || !root.isObject()) {
        throw new CorruptDurableStoreException("ui-settings", "expected a JSON object");
      }
      JsonNode declaredVersion = root.get("schemaVersion");
      // Legacy raw UiSettings itself had a schemaVersion field (0/1). Preserve those
      // payloads, but never deserialize a damaged modern envelope as empty raw settings.
      boolean rawLegacyVersion = declaredVersion == null
          || (declaredVersion.isIntegralNumber() && declaredVersion.canConvertToInt()
              && (declaredVersion.intValue() == 0 || declaredVersion.intValue() == 1));
      boolean envelope = root.has("settings") || root.has("acceptedRevision")
          || root.has("lastCommittedOperationKey") || !rawLegacyVersion;
      JsonNode versionNode = envelope ? declaredVersion : null;
      if (envelope && (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt())) {
        throw new CorruptDurableStoreException(
            "ui-settings", "versioned envelope requires an integer schemaVersion");
      }
      Integer observedVersion =
          versionNode == null || versionNode.isNull() ? null : versionNode.asInt();
      int resolvedVersion =
          StoreFormatVersions.requireReadable(
              "ui-settings",
              observedVersion,
              CURRENT_SCHEMA_VERSION,
              0,
              READABLE_LEGACY_VERSIONS);
      UiSettings settings =
          !envelope
              ? MAPPER.treeToValue(root, UiSettings.class)
              : MAPPER.treeToValue(root.get("settings"), UiSettings.class);
      if (settings == null) {
        throw new CorruptDurableStoreException("ui-settings", "settings payload is missing");
      }
      SettingsWitness witness = new SettingsWitness(0, null);
      if (resolvedVersion < 3 && (root.has("acceptedRevision") || root.has("lastCommittedOperationKey"))) {
        throw new CorruptDurableStoreException("ui-settings", "legacy schema cannot carry a revision witness");
      }
      if (resolvedVersion >= 3) {
        JsonNode revision = root.get("acceptedRevision");
        JsonNode key = root.get("lastCommittedOperationKey");
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()
            || key == null || !(key.isNull() || key.isTextual())) {
          throw new CorruptDurableStoreException("ui-settings", "invalid revision witness fields");
        }
        witness = new SettingsWitness(revision.longValue(), key.isNull() ? null : key.asText());
      }
      return new Snapshot(migrate(settings, resolvedVersion), witness);
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "ui-settings", "cannot parse " + settingsFile, e);
    }
  }

  /**
   * Forward-migrates a settings payload read at {@code storedVersion} to
   * {@link #CURRENT_SCHEMA_VERSION}. The next successful {@link #save} rewrites the file at the
   * current version; until then the migration is applied on every load, so it must be idempotent.
   *
   * <p>1 → 2 (tempdoc 883): {@code contextLength} 4096 means "the pre-883 default", which is now
   * spelled 0 = auto. Any other positive value is a deliberate operator override and is preserved.
   */
  private static UiSettings migrate(UiSettings settings, int storedVersion) {
    // Through schema3 zero was omitted from ConfigStore and meant automatic, including
    // whole-document defaults. Preserve that behavior; schema4 can persist an explicit CPU zero.
    if (storedVersion < 4 && settings.getGpuLayers() == 0) settings.setGpuLayers(null);
    if (storedVersion < 2 && settings.getContextLength() == LEGACY_DEFAULT_CONTEXT_LENGTH) {
      log.info(
          "ui-settings schema {} → {}: contextLength {} (the pre-883 shipped default) migrated to 0"
              + " = auto; the context window is now derived at activation.",
          storedVersion,
          CURRENT_SCHEMA_VERSION,
          LEGACY_DEFAULT_CONTEXT_LENGTH);
      settings.setContextLength(0);
    }
    return settings;
  }

  /**
   * Move the unreadable file aside so its bytes survive, then let the caller load defaults.
   *
   * <p>If the move itself fails the original exception is rethrown with the IO failure attached:
   * a file we could not preserve must not be reported as preserved.
   */
  private RecoveredFromCorrupt quarantineCorruptFile(CorruptDurableStoreException e) {
    Path backup = nextBackupPath();
    try {
      try {
        Files.move(settingsFile, backup, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(settingsFile, backup);
      }
    } catch (IOException io) {
      e.addSuppressed(io);
      throw e;
    }
    String detail = e.getMessage();
    log.warn(
        "ui-settings file was unreadable ({}); moved to {} and defaults loaded", detail, backup);
    return new RecoveredFromCorrupt(backup, detail);
  }

  private Path nextBackupPath() {
    String base =
        settingsFile.getFileName() + ".corrupt-" + BACKUP_STAMP.format(Clock.systemUTC().instant());
    Path candidate = settingsFile.resolveSibling(base);
    for (int suffix = 2; Files.exists(candidate); suffix++) {
      candidate = settingsFile.resolveSibling(base + "-" + suffix);
    }
    return candidate;
  }

  /** Prepared bytes and snapshot are private copies; callers cannot alter the file candidate. */
  public static final class PreparedSettings {
    private final UiSettingsStore owner;
    private final UiSettings settings;
    private final SettingsWitness witness;
    private final byte[] bytes;

    private PreparedSettings(UiSettingsStore owner, UiSettings settings, SettingsWitness witness, byte[] bytes) {
      this.owner = owner;
      this.settings = settings;
      this.witness = witness;
      this.bytes = bytes;
    }

    public UiSettings settings() { return copy(settings); }
    public SettingsWitness witness() { return witness; }
  }

  public PreparedSettings prepare(UiSettings settings, SettingsWitness witness) {
    if (!mode.isWritable()) throw new IllegalStateException("Settings store is read-only");
    UiSettings candidate = copy(Objects.requireNonNull(settings, "settings"));
    Objects.requireNonNull(witness, "witness");
    candidate.getWindow().stampLastShown();
    byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(
        new PersistedSettings(CURRENT_SCHEMA_VERSION, candidate,
            witness.acceptedRevision(), witness.lastCommittedOperationKey()));
    return new PreparedSettings(this, candidate, witness, bytes);
  }

  /** Performs only strict replacement; the owner resolves ambiguous errors using inspect(). */
  public void replacePrepared(PreparedSettings prepared) throws IOException {
    Objects.requireNonNull(prepared, "prepared");
    if (prepared.owner != this) throw new IllegalArgumentException("Foreign settings preparation");
    if (!mode.isWritable()) throw new IllegalStateException("Settings store is read-only");
    AtomicFileWrites.replaceStrict(settingsFile, prepared.bytes);
  }

  /** Notification is deliberately separate from file replacement and may invoke arbitrary code. */
  public void notifyRecoveryCleared() {
    if (lastRecovery != null) {
      lastRecovery = null;
      Runnable cleared = onRecoveryCleared;
      if (cleared != null) cleared.run();
    }
  }

  private static UiSettings copy(UiSettings settings) {
    return MAPPER.convertValue(settings, UiSettings.class);
  }

  /** Transitional unrecorded writer, removed by C2-6's all-producer migration. */
  public void save(UiSettings settings) {
    if (settings == null || mode == PersistenceMode.IN_MEMORY) return;
    // Once recorded settings exist, this legacy path must never erase their witness.
    SettingsWitness witness = inspect().witness();
    if (witness.acceptedRevision() != 0) {
      throw new IllegalStateException("Recorded settings require the accepted-revision owner");
    }
    PreparedSettings prepared = prepare(settings, witness);
    try {
      replacePrepared(prepared);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to persist UI settings to " + settingsFile, e);
    }
    notifyRecoveryCleared();
  }

  private record PersistedSettings(
      int schemaVersion, UiSettings settings, long acceptedRevision, String lastCommittedOperationKey) {}

  private static Path resolveSettingsFile() {
    // Tempdoc 519 §9 Block B3.0.d: moved from io.justsearch.ui.settings to app-services.
    // The app-services AppServicesWorkerGuardrailsTest bars ad-hoc System.getProperty /
    // System.getenv access; platform detection routes through PlatformPaths instead.
    String homeOverride = EnvRegistry.HOME.get().orElse(null);
    Path base;
    if (homeOverride != null && !homeOverride.isBlank()) {
      base = Path.of(homeOverride);
    } else {
      Path userHome = PlatformPaths.resolveUserHome();
      if (PlatformPaths.isWindows()) {
        base = userHome.resolve("AppData").resolve("Roaming").resolve("justsearch");
      } else if (PlatformPaths.isMac()) {
        base = userHome.resolve("Library").resolve("Application Support").resolve("justsearch");
      } else {
        base = userHome.resolve(".config").resolve("justsearch");
      }
    }
    return base.resolve("ui").resolve("settings.json");
  }

  public Path settingsPath() {
    return settingsFile;
  }

  public PersistenceMode mode() {
    return mode;
  }

  public enum PersistenceMode {
    READ_WRITE,
    IN_MEMORY;

    public boolean isWritable() {
      return this == READ_WRITE;
    }

    /**
     * Resolve persistence mode from its own explicit configuration.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit override via {@code justsearch.ui.settings.mode} sysprop/env
     *   <li>Read-only flags ({@code justsearch.ui.settings.readOnly})
     *   <li>Default: READ_WRITE
     * </ol>
     *
     * <p>Persistence is its own axis: {@code justsearch.prod} governs the loopback trust
     * boundary only and implies nothing here. Verification harnesses that want isolation pass
     * the explicit override themselves (tempdoc 804 §B1).
     */
    public static PersistenceMode resolveMode() {
      String override = EnvRegistry.UI_SETTINGS_MODE.get().orElse(null);
      PersistenceMode parsed = parseMode(override);
      if (parsed != null) {
        return parsed;
      }
      // Tempdoc 519 §9 Block B3.0.d: routed through EnvRegistry instead of direct sysprop/env access.
      boolean readOnly =
          EnvRegistry.UI_SETTINGS_READONLY.get().map(s -> Boolean.parseBoolean(s.trim())).orElse(false);
      if (readOnly) {
        return IN_MEMORY;
      }
      return READ_WRITE;
    }

    private static PersistenceMode parseMode(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      String normalized = raw.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "rw", "read_write", "file", "persist" -> READ_WRITE;
        case "memory", "in_memory", "mem", "readonly", "read_only" -> IN_MEMORY;
        default -> null;
      };
    }
  }

}
