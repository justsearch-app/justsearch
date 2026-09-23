/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.adapters.lucene.runtime.SafeIndexPathOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages generation-scoped Lucene index directories under an index base path.
 *
 * <p>Layout (per collection):
 * <pre>
 *   &lt;indexBasePath&gt;/
 *     state.json
 *     indices/
 *       &lt;genId&gt;/                (Lucene index directory)
 *         .justsearch-generation.sentinel
 *         .justsearch-index-generation.json
 * </pre>
 *
 * <p>This is a Worker-owned component. Main/UI must never touch Lucene directories.
 */
// PERMANENT COMPAT - DO NOT REMOVE (generation layout is an on-disk contract)
public final class IndexGenerationManager {
  private static final Logger log = LoggerFactory.getLogger(IndexGenerationManager.class);
  // Several manager instances share one Engine's generation pointers. Serialize control and
  // fallback repair without a persistent lock or an alias-sensitive/unbounded path registry.
  private static final java.util.concurrent.locks.ReentrantLock STATE_CONTROL =
      new java.util.concurrent.locks.ReentrantLock();

  private static StateControlGuard stateControl() {
    STATE_CONTROL.lock();
    return new StateControlGuard();
  }

  private static final class StateControlGuard implements AutoCloseable {
    private boolean released;

    @Override public void close() {
      if (released) return;
      STATE_CONTROL.unlock();
      released = true;
    }
  }
  private static final ObjectMapper RECORDED_JSON = JsonMapper.builder()
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .changeDefaultPropertyInclusion(
              v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
          .build();
  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private static final int STATE_FORMAT_VERSION = 2;
  private static final int MANIFEST_FORMAT_VERSION = 1;
  private static final int RECORDED_MANIFEST_FORMAT_VERSION = 2;

  private static final String STATE_FILE = "state.json";
  private static final String STATE_TMP_FILE = "state.json.tmp";
  private static final String STATE_PREV_FILE = "state.json.prev";

  private static final String INDICES_DIR = "indices";

  private static final String GENERATION_SENTINEL = ".justsearch-generation.sentinel";
  private static final String GENERATION_MANIFEST = ".justsearch-index-generation.json";
  private static final String DELETE_MARKER = "DELETEME";

  public record IndexLayout(
      Path basePath,
      Path indicesDir,
      Path statePath,
      State state,
      String activeGenerationId,
      Path activeGenerationPath) {}

  /**
   * On-disk pointer for the active generation.
   *
   * <p>{@code auto_rebuild_key} / {@code auto_rebuild_count} / {@code auto_rebuild_first_ms} are
   * the repeat-rebuild brake (tempdoc 915 §C). An index that fails to build a valid Green will
   * present the same mismatch on the next boot, so without a bound the Worker would rebuild
   * forever, burning the machine and never converging. The key is the {@code index_fingerprint}
   * the attempt targeted, so a genuinely new upgrade resets the count instead of inheriting the
   * previous failure's budget.
   */
  public record State(
      int format_version,
      String active_generation,
      String building_generation,
      String previous_generation,
      String migration_state,
      Boolean migration_paused,
      String pause_reason,
      Long paused_at_ms,
      long updated_at_ms,
      String auto_rebuild_key,
      Integer auto_rebuild_count,
      Long auto_rebuild_first_ms) {}

  /** Migration lifecycle states (stored in state.json as strings). */
  public enum MigrationState {
    IDLE,
    MIGRATING,
    SWITCHING,
    FAILED
  }

  /** Per-generation manifest (debugging + GC safety). */
  public record GenerationManifest(
      int format_version,
      String generation_id,
      String source,
      long created_at_ms,
      String target_index_fingerprint) {
    public GenerationManifest(int formatVersion, String generationId, String source, long createdAtMs) {
      this(formatVersion, generationId, source, createdAtMs, null);
    }
  }

  private final Path basePath;
  private final Path indicesDir;
  private final Path statePath;
  private final Path stateTmpPath;
  private final Path statePrevPath;
  private final StateMoveProbe stateMoveProbe;

  @FunctionalInterface
  interface StateMoveProbe {
    void afterMove(State state) throws IOException;
  }

  // Read-cache for readStateBestEffort(): avoids re-parsing state.json on every RPC when nothing has
  // changed. The cached State is published atomically through ONE volatile reference, so a reader on
  // a gRPC handler thread can never observe a torn version/state pair while a migration thread writes;
  // writeState() invalidates by nulling it. (tempdoc 589 — replaces a non-volatile lastReadVersion +
  // a non-atomic stateVersion++ counter, which together formed a data race.)
  //
  // Lane F stage A item A12: the cache also carries a stamp identifying the state.json revision it
  // was parsed from, and a hit is only a hit while that stamp still matches. Invalidating on THIS
  // instance's own writes is not enough, because one state.json has several managers over it in one
  // JVM — KnowledgeServer builds one (KnowledgeServer.java:611) for the migration enumerator and the
  // cutover monitor, WorkerIngestService builds its own from the same indexBasePath
  // (WorkerIngestService.java:177) for the migration control calls, and the switch-buffer replay
  // builds two more temporaries (KnowledgeServerMigrationOps.java:542, :718). A write through one
  // left every other instance serving its own stale parse forever. The concrete defect that found
  // this: `resumeMigration` wrote through the service's manager, the enumerator kept reading
  // `migration_paused=true` out of the server's manager, and the migration never resumed.
  //
  // The stamp is a CONTENT HASH, not (mtime, size). The review pass caught that pair colliding on
  // precisely the writes this cache has to notice: state.json's fields are mostly fixed-width, so
  // an active/previous generation-id swap, a migration_state transition between two equal-length
  // names, or a `migration_paused` true->... flip through a rewrite lands at the SAME byte length,
  // and two writes inside one filesystem timestamp tick then produce the same (mtime, size) for
  // different content. Windows makes that likely rather than theoretical — NTFS updates the
  // last-write time lazily, so back-to-back writes routinely share a stamp. A collision here is not
  // a missed refresh; it is the stale read the stamp exists to prevent, restored silently. Hashing
  // the bytes has no such window. It costs a read of a file measured in hundreds of bytes plus one
  // SHA-256 where the miss costs a full Jackson parse, so the cache still does the job tempdoc 589
  // gave it.
  private volatile CachedState cache = null;

  /**
   * Atomically-published read-cache entry; only PRESENT states are cached (null == "re-read").
   *
   * @param value the parsed, normalized state
   * @param contentHash hex SHA-256 of the state.json bytes it was parsed from, or {@code null} if
   *     the file was unreadable at that moment
   */
  private record CachedState(State value, String contentHash) {}

  public IndexGenerationManager(Path indexBasePath) {
    this(indexBasePath, ignored -> {});
  }

  IndexGenerationManager(Path indexBasePath, StateMoveProbe stateMoveProbe) {
    this.basePath = normalize(Objects.requireNonNull(indexBasePath, "indexBasePath"));
    this.stateMoveProbe = Objects.requireNonNull(stateMoveProbe, "stateMoveProbe");
    if (this.basePath.getParent() == null) {
      throw new IllegalArgumentException(
          "indexBasePath must not be a filesystem root: " + this.basePath);
    }
    this.indicesDir = this.basePath.resolve(INDICES_DIR);
    this.statePath = this.basePath.resolve(STATE_FILE);
    this.stateTmpPath = this.basePath.resolve(STATE_TMP_FILE);
    this.statePrevPath = this.basePath.resolve(STATE_PREV_FILE);
  }

  /**
   * Ensures the index layout exists and returns the active generation directory.
   *
   * <p>If {@code state.json} exists, it is authoritative.
   * If not, this method will:
   * <ul>
   *   <li>import a legacy index found directly under {@code basePath} into {@code indices/v0_imported}, or</li>
   *   <li>adopt an existing single generation under {@code indices/}, or</li>
   *   <li>create a new generation under {@code indices/}.</li>
   * </ul>
   */
  public IndexLayout initializeOrLoad() throws IOException {
    try (var ignored = stateControl()) {
      Files.createDirectories(basePath);

      State state = loadStateBestEffort();
      if (state != null) {
        return resolveFromState(state);
      }

      // No state.json: decide between legacy import, adoption, or new generation.
      if (looksLikeLegacyIndexInBasePath(basePath)) {
        return importLegacyIndex();
      }

      IndexLayout adopted = tryAdoptSingleExistingGeneration();
      if (adopted != null) {
        return adopted;
      }

      return createFreshGeneration("new");
    }
  }

  /** One application-owned boot observation, projected before any generation repair or allocation. */
  public sealed interface BootOwnership {
    record Native() implements BootOwnership {}
    record Fenced() implements BootOwnership {}
    record Recorded(String operationKey, String sourceGeneration, String source,
        String targetFingerprint, boolean captureComplete, boolean continuationAuthorized)
        implements BootOwnership {
      public Recorded(String operationKey, String sourceGeneration, String source,
          String targetFingerprint, boolean captureComplete) {
        this(operationKey, sourceGeneration, source, targetFingerprint, captureComplete, true);
      }
      public Recorded {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(sourceGeneration, "sourceGeneration");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetFingerprint, "targetFingerprint");
      }
    }
  }

  public enum BootDisposition { NATIVE, CAPTURING, BUILDING, PROMOTED, FENCED }

  /** Runtime observation only; no second persisted generation state. */
  public record BootLayout(IndexLayout layout, BootDisposition disposition) {}

  /**
   * Resolve boot ownership before native fallback, import, adoption or normalization can write.
   * Recorded/fenced layouts are read-only observations; callers must honor their disposition.
   */
  public BootLayout initializeForBoot(BootOwnership ownership, String effectiveFingerprint) throws IOException {
    Objects.requireNonNull(ownership, "ownership");
    try (var ignored = stateControl()) {
      if (ownership instanceof BootOwnership.Native) {
        State observed = null;
        try { observed = readRecordedState(); }
        catch (IOException unreadable) {
          if (hasRecordedFallbackEvidence()) throw unreadable;
        }
        if (observed != null && isRecordedIdentity(observed.active_generation())) {
          boolean noBuilding = observed.building_generation() == null || observed.building_generation().isBlank();
          boolean nativeMigration = !noBuilding && !isRecordedIdentity(observed.building_generation())
              && observed.migration_state() != null && Set.of("MIGRATING", "SWITCHING", "FAILED").contains(observed.migration_state());
          boolean completed = noBuilding && MigrationState.IDLE.name().equals(observed.migration_state());
          if (observed.format_version() != STATE_FORMAT_VERSION || !completed && !nativeMigration) {
            return new BootLayout(strictBootLayout(observed), BootDisposition.FENCED);
          }
        }
        if (observed == null || !isRecordedIdentity(observed.building_generation())
            && !hasPristineRecordedOrphan(observed)) {
          return new BootLayout(initializeOrLoad(), BootDisposition.NATIVE);
        }
        return new BootLayout(strictBootLayout(observed), BootDisposition.FENCED);
      }
      IndexLayout layout = strictBootLayout(readRecordedState());
      if (ownership instanceof BootOwnership.Fenced) {
        return new BootLayout(layout, BootDisposition.FENCED);
      }
      var recorded = (BootOwnership.Recorded) ownership;
      String target = recordedGenerationId(recorded.operationKey());
      State state = layout.state();
      String building = state.building_generation();
      boolean idle = MigrationState.IDLE.name().equals(state.migration_state())
          && (building == null || building.isBlank());
      if (!recorded.targetFingerprint().matches("[0-9a-f]{64}")
          || !recorded.targetFingerprint().equals(effectiveFingerprint)) {
        return new BootLayout(layout, BootDisposition.FENCED);
      }
      if (idle && target.equals(layout.activeGenerationId())) {
        requireRecordedGeneration(layout.activeGenerationPath(), target, recorded.source(),
            recorded.targetFingerprint(), false);
        return new BootLayout(layout, recorded.captureComplete()
            && recorded.sourceGeneration().equals(state.previous_generation())
            ? BootDisposition.PROMOTED : BootDisposition.FENCED);
      }
      if (!recorded.continuationAuthorized()) return new BootLayout(layout, BootDisposition.FENCED);
      if (!recorded.sourceGeneration().equals(layout.activeGenerationId())) {
        return new BootLayout(layout, BootDisposition.FENCED);
      }
      if (idle) return new BootLayout(layout, BootDisposition.CAPTURING);
      if (target.equals(building) && recorded.captureComplete()
          && (MigrationState.MIGRATING.name().equals(state.migration_state())
              || MigrationState.SWITCHING.name().equals(state.migration_state()))) {
        requireRecordedGeneration(resolveGenerationPathReadOnly(target), target, recorded.source(),
            recorded.targetFingerprint(), false);
        return new BootLayout(layout, BootDisposition.BUILDING);
      }
      return new BootLayout(layout, BootDisposition.FENCED);
    }
  }

  private State readRecordedState() throws IOException {
    try {
      State state = RECORDED_JSON.readValue(
          io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(statePath), State.class);
      if (state == null) throw new IOException("Authoritative generation state is empty");
      return state;
    } catch (tools.jackson.core.JacksonException malformed) {
      throw new IOException("Authoritative generation state is invalid", malformed);
    }
  }

  private IndexLayout strictBootLayout(State state) throws IOException {
    if (state.format_version() != STATE_FORMAT_VERSION) {
      throw new IOException("Recorded boot requires current generation state format");
    }
    String active = requireSafeGenerationId(state.active_generation(), "state.json active_generation");
    try { MigrationState.valueOf(state.migration_state()); }
    catch (IllegalArgumentException | NullPointerException invalid) {
      throw new IOException("Authoritative generation phase is invalid", invalid);
    }
    if (active.equals(state.building_generation())) {
      throw new IOException("Serving and building generation identities must be distinct");
    }
    Path activePath = resolveGenerationPathReadOnly(active);
    if (!Files.isDirectory(indicesDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || !Files.isDirectory(activePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Authoritative active generation directory is unavailable");
    }
    return new IndexLayout(basePath, indicesDir, statePath, state, active, activePath);
  }

  /**
   * Inspects the current generation pointer for boot recovery without repairing it.
   *
   * <p>An absent {@code state.json} is represented by {@link java.util.Optional#empty()} so a
   * caller can distinguish a store that has never published state from a damaged store. A present
   * but malformed or structurally invalid state is reported as {@link IOException}; {@code
   * state.json.prev} is never consulted and no directory or state file is created or rewritten.
   * When present and valid, the returned layout has passed the same current-format, pointer and
   * active-directory checks used by strict recorded boot recovery.
   */
  public java.util.Optional<IndexLayout> inspectCurrentLayoutForBoot() throws IOException {
    try (var ignored = stateControl()) {
      if (Files.notExists(statePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        return java.util.Optional.empty();
      }
      if (!Files.isRegularFile(statePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Authoritative generation state is not a regular file: " + statePath);
      }
      return java.util.Optional.of(strictBootLayout(readRecordedState()));
    }
  }

  private static boolean isRecordedIdentity(String generation) {
    if (generation == null || !generation.startsWith("g-")) return false;
    try { return generation.equals(recordedGenerationId(generation.substring(2))); }
    catch (IOException invalid) { return false; }
  }

  private boolean hasPristineRecordedOrphan(State state) throws IOException {
    if (!Files.isDirectory(indicesDir)) return false;
    try (var entries = Files.list(indicesDir)) {
      for (Path directory : entries.toList()) {
        String identity = directory.getFileName().toString();
        if (!isRecordedIdentity(identity) || identity.equals(state.active_generation())
            || identity.equals(state.previous_generation())
            || !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
        try (var contents = Files.list(directory)) {
          // A crash-created target can have either metadata file missing. Completed empty
          // generations have Lucene commit files; GC-retained archives must not fence native boot.
          if (contents.allMatch(path -> Set.of(GENERATION_SENTINEL, GENERATION_MANIFEST)
              .contains(path.getFileName().toString()))) return true;
        }
      }
    }
    return false;
  }

  /** Evidence may fence fallback; it never supplies a serving or writable identity. */
  private boolean hasRecordedFallbackEvidence() throws IOException {
    if (Files.isDirectory(indicesDir)) {
      try (var entries = Files.list(indicesDir)) {
        if (entries.anyMatch(path -> isRecordedIdentity(path.getFileName().toString()))) return true;
      }
    }
    if (Files.isRegularFile(statePrevPath)) {
      try {
        State previous = RECORDED_JSON.readValue(
            io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(statePrevPath), State.class);
        return previous != null && (isRecordedIdentity(previous.active_generation())
            || isRecordedIdentity(previous.building_generation()));
      } catch (tools.jackson.core.JacksonException malformed) {
        // An undecodable backup cannot establish either kind of generation authority.
        return false;
      }
    }
    return false;
  }

  /**
   * Resolves a generation directory path by generation id (validated).
   *
   * <p>This does not create the directory.
   */
  public Path resolveGenerationPathStrict(String generationId) throws IOException {
    String genId = requireSafeGenerationId(generationId, "generationId");
    return resolveGenerationPath(genId);
  }

  /**
   * Builds a generation id guaranteed unique on disk (tempdoc 628 G4 / obs #484).
   *
   * <p>The base is second-precision for human readability ({@code g-yyyyMMdd-HHmmss}). Because the
   * timestamp is only second-precision, a second/programmatic migration started within the SAME
   * wall-clock second as an existing generation would collide on the directory name. Instead of
   * throwing ("generation already exists"), a numeric suffix is appended until the path is free, so
   * rapid/back-to-back migrations get distinct ids deterministically. {@code requireSafeGenerationId}
   * only forbids path-separator characters, so the suffixed form ({@code g-...-1}) stays valid and any
   * previously-persisted second-precision ids remain parseable — no state migration needed.
   */
  private String newUniqueGenerationId() throws IOException {
    String base = "g-" + TS.format(Instant.now());
    String candidate = base;
    int suffix = 1;
    while (Files.exists(resolveGenerationPath(candidate))) {
      candidate = base + "-" + suffix;
      suffix++;
    }
    return candidate;
  }

  /**
   * Starts a migration (Blue/Green) by creating a new building generation and updating {@code state.json}.
   *
   * <p>If a migration is already in progress, this is a no-op and returns the current normalized state.
   *
   * @param source short label for the generation manifest (e.g., "schema_mismatch")
   * @return the updated normalized state (format_version=2)
   */
  public State startMigration(String source) throws IOException {
    try (var ignored = stateControl()) {
      IndexLayout layout = initializeOrLoad();
      State current = layout.state();
      State normalized = normalizeAndUpgradeStateIfNeeded(current);
      MigrationState ms = parseMigrationStateOrDefault(normalized.migration_state(), MigrationState.IDLE);
      if (ms == MigrationState.MIGRATING || ms == MigrationState.SWITCHING) {
        return normalized;
      }
      // If a prior migration failed but a building generation exists, don't create a new one implicitly.
      // This avoids generation churn; operators can decide whether to retry or discard the failed build.
      if (ms == MigrationState.FAILED
          && normalized.building_generation() != null
          && !normalized.building_generation().isBlank()) {
        return normalized;
      }

      String active = requireSafeGenerationId(normalized.active_generation(), "state.json active_generation");
      String genId = newUniqueGenerationId();
      Path genPath = resolveGenerationPath(genId);
      Files.createDirectories(genPath);
      writeGenerationFiles(genPath, genId, source == null || source.isBlank() ? "migration" : source.trim());

      State next =
          new State(
              STATE_FORMAT_VERSION,
              active,
              genId,
              active,
              MigrationState.MIGRATING.name(),
              false,
              null,
              null,
              System.currentTimeMillis(),
              normalized.auto_rebuild_key(),
              normalized.auto_rebuild_count(),
              normalized.auto_rebuild_first_ms());
      writeState(next);
      return next;
    }
  }

  /**
   * Starts the one generation derived from an already accepted operation. The current pointer
   * and recorded metadata must be authoritative; this path never allocates a fallback identity.
   * The caller persists its response witness before requesting the first Engine restart.
   */
  public State startRecordedMigration(String operationKey, String source, String targetIndexFingerprint)
      throws IOException {
    return startRecordedMigration(operationKey, source, targetIndexFingerprint, null);
  }

  /** Bind source comparison and generation creation under the same control lock. */
  public State startRecordedMigration(String operationKey, String source, String targetIndexFingerprint,
      String expectedSourceGeneration) throws IOException {
    try (var ignored = stateControl()) {
      String target = recordedGenerationId(operationKey);
      if (source == null || source.isBlank() || source.length() > 256
          || source.chars().anyMatch(Character::isISOControl)
          || targetIndexFingerprint == null || !targetIndexFingerprint.matches("[0-9a-f]{64}")) {
        throw new IOException("Recorded generation source or target fingerprint is invalid");
      }
      final State raw;
      try {
        raw = RECORDED_JSON.readValue(
            io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(statePath), State.class);
      } catch (tools.jackson.core.JacksonException malformed) {
        throw new IOException("Authoritative generation state is invalid", malformed);
      }
      if (raw == null || raw.migration_state() == null || raw.migration_state().isBlank()) {
        throw new IOException("Authoritative generation state is unavailable");
      }
      State current = expectedSourceGeneration == null ? normalizeAndUpgradeStateIfNeeded(raw)
          : strictBootLayout(raw).state();
      String active = requireSafeGenerationId(current.active_generation(), "state.json active_generation");
      if (expectedSourceGeneration != null) {
        String expected = requireSafeGenerationId(expectedSourceGeneration, "accepted source generation");
        String observedSource = target.equals(active) ? current.previous_generation() : active;
        if (!expected.equals(expectedSourceGeneration) || !expected.equals(observedSource)) {
          throw new IOException("Recorded generation source differs from the accepted preparation");
        }
      }
      if (!Files.isDirectory(resolveGenerationPathReadOnly(active), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Active generation directory is unavailable");
      }
      MigrationState phase = parseMigrationStateOrDefault(current.migration_state(), MigrationState.FAILED);
      String building = current.building_generation();
      if (active.equals(building)) {
        throw new IOException("Serving and building generation identities must be distinct");
      }
      Path targetPath = resolveGenerationPathReadOnly(target);
      if (phase == MigrationState.IDLE && target.equals(active) && (building == null || building.isBlank())) {
        requireRecordedGeneration(targetPath, target, source, targetIndexFingerprint, false);
        return current;
      }
      if ((phase == MigrationState.MIGRATING || phase == MigrationState.SWITCHING) && target.equals(building)) {
        requireRecordedGeneration(targetPath, target, source, targetIndexFingerprint, false);
        return current;
      }
      if (phase != MigrationState.IDLE || building != null && !building.isBlank()) {
        throw new IOException("Recorded generation conflicts with the current migration state");
      }
      requireRecordedBuildCapacity(current, target);
      if (Files.exists(targetPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        requireRecordedGeneration(targetPath, target, source, targetIndexFingerprint, true);
      } else {
        Files.createDirectories(indicesDir);
        Files.createDirectory(targetPath);
        long created = System.currentTimeMillis();
        var manifest = new GenerationManifest(RECORDED_MANIFEST_FORMAT_VERSION, target, source, created, targetIndexFingerprint);
        io.justsearch.configuration.persistence.AtomicFileWrites.replaceStrict(
            targetPath.resolve(GENERATION_SENTINEL), recordedSentinel(target, created).getBytes(StandardCharsets.UTF_8));
        io.justsearch.configuration.persistence.AtomicFileWrites.replaceStrict(
            targetPath.resolve(GENERATION_MANIFEST), RECORDED_JSON.writeValueAsBytes(manifest));
      }
      State next = new State(STATE_FORMAT_VERSION, active, target, active, MigrationState.MIGRATING.name(),
          false, null, null, System.currentTimeMillis(), current.auto_rebuild_key(),
          current.auto_rebuild_count(), current.auto_rebuild_first_ms());
      writeState(next);
      return next;
    }
  }

  /** Stable target identity; only canonical accepted UUIDv7 keys belong to this namespace. */
  public static String recordedGenerationId(String operationKey) throws IOException {
    try {
      var key = java.util.UUID.fromString(operationKey);
      if (key.version() != 7 || key.variant() != 2 || !key.toString().equals(operationKey)) {
        throw new IllegalArgumentException("Noncanonical operation key");
      }
      return "g-" + operationKey;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new IOException("Recorded generation requires a canonical UUIDv7 operation key", invalid);
    }
  }

  private static String recordedSentinel(String target, long created) {
    return "justsearch_generation_sentinel_v1\ngeneration_id=" + target + "\ncreated_at_ms=" + created + "\n";
  }

  private static void requireRecordedGeneration(Path directory, String target, String source,
      String targetFingerprint, boolean pristine) throws IOException {
    if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Recorded generation directory is not an owned regular directory");
    }
    Path manifestPath = directory.resolve(GENERATION_MANIFEST);
    Path sentinelPath = directory.resolve(GENERATION_SENTINEL);
    if (!Files.isRegularFile(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || !Files.isRegularFile(sentinelPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || Files.size(manifestPath) > 16_384 || Files.size(sentinelPath) > 1_024) {
      throw new IOException("Recorded generation ownership metadata is unavailable");
    }
    final GenerationManifest manifest;
    try {
      manifest = RECORDED_JSON.readValue(
          io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(manifestPath), GenerationManifest.class);
    } catch (tools.jackson.core.JacksonException malformed) {
      throw new IOException("Recorded generation manifest is invalid", malformed);
    }
    if (manifest == null || manifest.format_version() != RECORDED_MANIFEST_FORMAT_VERSION || !target.equals(manifest.generation_id())
        || !source.equals(manifest.source()) || !targetFingerprint.equals(manifest.target_index_fingerprint())
        || manifest.created_at_ms() <= 0) {
      throw new IOException("Recorded generation metadata does not match its accepted target");
    }
    String sentinel = new String(
        io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(sentinelPath), StandardCharsets.UTF_8);
    if (!recordedSentinel(target, manifest.created_at_ms()).equals(sentinel)) {
      throw new IOException("Recorded generation sentinel does not match its manifest");
    }
    if (pristine) {
      try (var entries = Files.list(directory)) {
        if (entries.anyMatch(path -> !Set.of(GENERATION_SENTINEL, GENERATION_MANIFEST)
            .contains(path.getFileName().toString()))) {
          throw new IOException("Unbound recorded generation is not pristine");
        }
      }
    }
  }

  /**
   * Discards the current building generation: clears {@code building_generation}, returns {@code
   * migration_state} to {@code IDLE} and marks the abandoned directory for deletion, so a following
   * {@link #startMigration(String)} allocates a FRESH Green instead of returning the old one.
   *
   * <p>Exists because {@code startMigration} deliberately no-ops while a migration is in flight
   * (see its contract above), which is right for concurrent callers and wrong for the one case that
   * needs the opposite: a resumed migration whose Green is itself unusable. Retrying the same
   * generation there re-raises the same failure on every boot — the Worker died three boots running
   * before the brake could even fire (tempdoc 915 B5).
   *
   * <p>The auto-rebuild budget is carried over unchanged. Abandoning a Green is not evidence the
   * rebuild converged, so it must not refresh the brake; the caller spends its attempt.
   *
   * @param reason short label for the log line
   * @return the updated normalized state, or the unchanged state when there is no building
   *     generation to abandon
   */
  public State abandonBuildingGeneration(String reason) throws IOException {
    try (var ignored = stateControl()) {
      State current = readStateBestEffort();
      if (current == null) {
        return null;
      }
      State normalized = normalizeAndUpgradeStateIfNeeded(current);
      String building = normalized.building_generation();
      if (building == null || building.isBlank()) {
        return normalized;
      }
      String safeBuilding = requireSafeGenerationId(building, "state.json building_generation");
      State next =
          new State(
              STATE_FORMAT_VERSION,
              normalized.active_generation(),
              null,
              normalized.previous_generation(),
              MigrationState.IDLE.name(),
              false,
              null,
              null,
              System.currentTimeMillis(),
              normalized.auto_rebuild_key(),
              normalized.auto_rebuild_count(),
              normalized.auto_rebuild_first_ms());
      writeState(next);
      log.warn(
          "Abandoned building generation {} (reason={}); migration_state reset to IDLE",
          safeBuilding,
          reason == null || reason.isBlank() ? "unspecified" : reason.trim());
      // Marked AFTER the pointer is gone, and best-effort: a crash between the two leaves an orphan
      // directory the GC reaps, whereas marking first would leave state.json pointing at a directory
      // already being deleted.
      try {
        SafeIndexPathOps.markForDeletion(resolveGenerationPath(safeBuilding), indicesDir);
      } catch (Exception e) {
        log.debug("Failed to mark abandoned generation {} for deletion: {}", safeBuilding, e.getMessage());
      }
      return next;
    }
  }

  /** Sets operator pause intent for migration orchestration (best-effort). */
  public State setMigrationPaused(boolean paused, String reason) throws IOException {
    try (var ignored = stateControl()) {
      State current = readStateBestEffort();
      if (current == null) {
        // Ensure layout exists
        initializeOrLoad();
        current = readStateBestEffort();
        if (current == null) {
          return null;
        }
      }
      State normalized = normalizeAndUpgradeStateIfNeeded(current);
      long now = System.currentTimeMillis();
      Boolean nextPaused = paused;
      String nextReason = paused ? (reason == null || reason.isBlank() ? "operator" : reason.trim()) : null;
      Long nextPausedAt = paused ? now : null;
      State next =
          new State(
              STATE_FORMAT_VERSION,
              normalized.active_generation(),
              normalized.building_generation(),
              normalized.previous_generation(),
              normalized.migration_state(),
              nextPaused,
              nextReason,
              nextPausedAt,
              now,
              normalized.auto_rebuild_key(),
              normalized.auto_rebuild_count(),
              normalized.auto_rebuild_first_ms());
      writeState(next);
      return next;
    }
  }

  /**
   * Updates migration state best-effort. Intended for future Phase F transitions (SWITCHING, FAILED, etc.).
   *
   * <p>Tempdoc 542 Phase 4 (Amendment-B trigger): when Worker autonomously advances into
   * SWITCHING or FAILED, this is a Worker-originated long-op transition that today is NOT
   * covered by an op-lease (the op-lease was registered by Head when the migration was
   * initiated, but it doesn't follow Worker-side state-machine advancement). The audit log
   * below makes the absence visible so operators can confirm the V1 design's "Head-originated
   * lease covers the whole op" assumption is holding — when it isn't, this log fires and
   * Amendment B (Worker-side registration via Head SPI callback) should be implemented.
   */
  public void updateMigrationState(MigrationState state) throws IOException {
    try (var ignored = stateControl()) {
      Objects.requireNonNull(state, "state");
      State current = readStateBestEffort();
      if (current == null) {
        // Nothing to update; ensure layout exists.
        initializeOrLoad();
        current = readStateBestEffort();
        if (current == null) return;
      }
      State normalized = normalizeAndUpgradeStateIfNeeded(current);
      // Tempdoc 542 Phase 4: audit Worker-autonomous state transitions. SWITCHING + FAILED are
      // the long-op-relevant transitions; the log carries a stable marker string the future
      // Amendment-B work can grep for.
      if (state == MigrationState.SWITCHING || state == MigrationState.FAILED) {
        log.info(
            "tempdoc-542 phase-4 audit: Worker-autonomous migration state transition: "
                + "{} -> {} (active_generation={}, building_generation={}). "
                + "If this fires in production, consider implementing Amendment B "
                + "(Worker-side op-lease registration via Head SPI callback).",
            normalized.migration_state(),
            state.name(),
            normalized.active_generation(),
            normalized.building_generation());
      }
      State next =
          new State(
              STATE_FORMAT_VERSION,
              normalized.active_generation(),
              normalized.building_generation(),
              normalized.previous_generation(),
              state.name(),
              normalized.migration_paused(),
              normalized.pause_reason(),
              normalized.paused_at_ms(),
              System.currentTimeMillis(),
              normalized.auto_rebuild_key(),
              normalized.auto_rebuild_count(),
              normalized.auto_rebuild_first_ms());
      writeState(next);
    }
  }

  /**
   * How many times the Worker will auto-start a rebuild for the same target fingerprint before it
   * stops and leaves the decision to an operator. Three is enough to absorb a transient failure
   * (a crash mid-build, a full disk that clears) and few enough that a genuinely unbuildable index
   * stops costing a full rebuild on every boot.
   */
  public static final int MAX_AUTO_REBUILD_ATTEMPTS = 3;

  /**
   * Records an attempt to auto-start a rebuild targeting {@code targetKey} and returns the attempt
   * number, 1-based. The count resets whenever {@code targetKey} differs from the last recorded
   * one, so a new upgrade is never refused because an older one exhausted the budget.
   *
   * <p>Persisted before the rebuild starts, not after it succeeds: a rebuild that crashes the
   * process must still consume its attempt, or a crash loop is invisible to the brake.
   *
   * @param targetKey the {@code index_fingerprint} the rebuild is meant to produce
   * @return the 1-based attempt number for this key
   */
  public int recordAutoRebuildAttempt(String targetKey) throws IOException {
    try (var ignored = stateControl()) {
      State current = readStateBestEffort();
      if (current == null) {
        initializeOrLoad();
        current = readStateBestEffort();
        if (current == null) {
          return 1;
        }
      }
      State normalized = normalizeAndUpgradeStateIfNeeded(current);
      String key = targetKey == null || targetKey.isBlank() ? "<unknown>" : targetKey;
      boolean sameTarget = key.equals(normalized.auto_rebuild_key());
      int nextCount =
          sameTarget && normalized.auto_rebuild_count() != null
              ? normalized.auto_rebuild_count() + 1
              : 1;
      long now = System.currentTimeMillis();
      Long firstMs =
          sameTarget && normalized.auto_rebuild_first_ms() != null
              ? normalized.auto_rebuild_first_ms()
              : now;
      writeState(
          new State(
              STATE_FORMAT_VERSION,
              normalized.active_generation(),
              normalized.building_generation(),
              normalized.previous_generation(),
              normalized.migration_state(),
              normalized.migration_paused(),
              normalized.pause_reason(),
              normalized.paused_at_ms(),
              now,
              key,
              nextCount,
              firstMs));
      return nextCount;
    }
  }

  /**
   * The attempt count already recorded for {@code targetKey}, or 0 if the last recorded attempt
   * targeted something else. Read-only — use it to decide before spending
   * {@link #recordAutoRebuildAttempt}.
   */
  public int autoRebuildAttemptsFor(String targetKey) {
    State current = readStateBestEffort();
    if (current == null || current.auto_rebuild_count() == null) {
      return 0;
    }
    String key = targetKey == null || targetKey.isBlank() ? "<unknown>" : targetKey;
    return key.equals(current.auto_rebuild_key()) ? current.auto_rebuild_count() : 0;
  }

  /**
   * Holds the durable generation state owner while a recorded activation acquires the process
   * publication lock. The owner must acquire this after its runtime lock and release it after
   * installing the prepared serving view. No unbound promotion is available through this lease.
   */
  public RecordedPromotion beginRecordedPromotion(String operationKey, String source,
      String targetIndexFingerprint, String expectedSourceGeneration) {
    Objects.requireNonNull(operationKey, "operationKey");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(targetIndexFingerprint, "targetIndexFingerprint");
    Objects.requireNonNull(expectedSourceGeneration, "expectedSourceGeneration");
    return new RecordedPromotion(operationKey, source, targetIndexFingerprint,
        expectedSourceGeneration, stateControl());
  }

  public final class RecordedPromotion implements AutoCloseable {
    /** Exact pointer witness while this lease still excludes competing generation writers. */
    public enum CommitWitness { UNCHANGED, COMMITTED, UNRESOLVED }

    private final String operationKey;
    private final String source;
    private final String targetIndexFingerprint;
    private final String expectedSourceGeneration;
    private final StateControlGuard guard;
    private final Thread owner = Thread.currentThread();
    private boolean attempted;
    private boolean closed;

    private RecordedPromotion(String operationKey, String source, String targetIndexFingerprint,
        String expectedSourceGeneration, StateControlGuard guard) {
      this.operationKey = operationKey;
      this.source = source;
      this.targetIndexFingerprint = targetIndexFingerprint;
      this.expectedSourceGeneration = expectedSourceGeneration;
      this.guard = guard;
    }

    /** Revalidates the exact accepted binding and replaces the durable pointer at most once. */
    public State promote() throws IOException {
      requireOwner();
      if (attempted) throw new IllegalStateException("Recorded promotion already attempted");
      attempted = true;
      try {
        return promoteRecordedGenerationToActive(operationKey, source,
            targetIndexFingerprint, expectedSourceGeneration);
      } catch (IOException ambiguous) {
        // A failed state.json move can be reported after the exact pointer is durable. Only the
        // strict recorded boot witness may turn that ambiguous outcome into committed promotion.
        try {
          var observed = initializeForBoot(new BootOwnership.Recorded(operationKey,
              expectedSourceGeneration, source, targetIndexFingerprint, true),
              targetIndexFingerprint);
          if (observed.disposition() == BootDisposition.PROMOTED) return observed.layout().state();
        } catch (IOException unreadable) {
          ambiguous.addSuppressed(unreadable);
        }
        throw ambiguous;
      }
    }

    /** Resolve a failed promotion before the caller changes admission or serving references. */
    public CommitWitness inspectCommitWitness() {
      requireOwner();
      try {
        // A failed target/manifest validation can leave the exact source pointer untouched,
        // even though initializeForBoot quite correctly calls the *candidate* fenced. Read the
        // pointer first so that refusal does not unnecessarily close the healthy source view.
        IndexLayout layout = strictBootLayout(readRecordedState());
        if (expectedSourceGeneration.equals(layout.activeGenerationId())) {
          return CommitWitness.UNCHANGED;
        }
        if (!recordedGenerationId(operationKey).equals(layout.activeGenerationId())) {
          return CommitWitness.UNRESOLVED;
        }
        var observed = initializeForBoot(new BootOwnership.Recorded(operationKey,
            expectedSourceGeneration, source, targetIndexFingerprint, true),
            targetIndexFingerprint);
        return switch (observed.disposition()) {
          case BUILDING -> CommitWitness.UNCHANGED;
          case PROMOTED -> CommitWitness.COMMITTED;
          default -> CommitWitness.UNRESOLVED;
        };
      } catch (IOException | RuntimeException unreadable) {
        return CommitWitness.UNRESOLVED;
      }
    }

    private void requireOwner() {
      if (closed || owner != Thread.currentThread()) {
        throw new IllegalStateException("Recorded promotion requires its owning live thread");
      }
    }

    @Override public void close() {
      if (closed) return;
      requireOwner();
      guard.close();
      closed = true;
    }
  }

  /** Promote only the strict target/source binding validated by the recorded owner. */
  State promoteRecordedGenerationToActive(String operationKey, String source,
      String targetIndexFingerprint, String expectedSourceGeneration) throws IOException {
    try (var ignored = stateControl()) {
      var observed = initializeForBoot(new BootOwnership.Recorded(operationKey,
          expectedSourceGeneration, source, targetIndexFingerprint, true), targetIndexFingerprint);
      if (observed.disposition() == BootDisposition.PROMOTED) return observed.layout().state();
      if (observed.disposition() != BootDisposition.BUILDING) {
        throw new IOException("Recorded generation binding changed before promotion");
      }
      return promoteBuildingGenerationToActive();
    }
  }

  /**
   * Promotes {@code building_generation} to {@code active_generation} and clears {@code building_generation}.
   *
   * <p>This is the Phase F cutover step (pointer swap). It does not delete any generations; it
   * simply updates {@code state.json}.
   *
   * @return the updated normalized state, or null if no state exists
   */
  public State promoteBuildingGenerationToActive() throws IOException {
    try (var ignored = stateControl()) {
      State current = readStateBestEffort();
      if (current == null) {
        return null;
      }
      State normalized = normalizeAndUpgradeStateIfNeeded(current);
      String building = normalized.building_generation();
      if (building == null || building.isBlank()) {
        return normalized;
      }
      String nextActive = requireSafeGenerationId(building, "state.json building_generation");
      String prevActive = requireSafeGenerationId(normalized.active_generation(), "state.json active_generation");
      State next =
          new State(
              STATE_FORMAT_VERSION,
              nextActive,
              null,
              prevActive,
              MigrationState.IDLE.name(),
              false,
              null,
              null,
              System.currentTimeMillis(),
              // A completed cutover is the proof the rebuild converged: release the brake so a
              // future, unrelated upgrade starts with a full budget.
              null,
              null,
              null);
      writeState(next);
      return next;
    }
  }

  /**
   * Retires one exact predecessor after its process resources have been closed by the caller.
   *
   * <p>The predecessor remains capacity-owning until its original directory and any exact
   * {@code .del-*} representation are both absent. A repeated call completes either crash cut:
   * deletion with the pointer still present, or pointer removal with deletion still incomplete.
   */
  public State retirePreviousGeneration(String expectedActive, String expectedPrevious)
      throws IOException {
    try (var ignored = stateControl()) {
      String active = requireSafeGenerationId(expectedActive, "expected active generation");
      String previous =
          requireSafeGenerationId(expectedPrevious, "expected previous generation");
      if (active.equals(previous)) {
        throw new IOException("Active and previous generation identities must be distinct");
      }

      State current = requireExactIdleRetirementState(active);
      String observedPrevious = current.previous_generation();
      if (observedPrevious != null
          && !observedPrevious.isBlank()
          && !previous.equals(
              requireSafeGenerationId(
                  observedPrevious, "state.json previous_generation"))) {
        throw new IOException("Previous generation differs from the expected retirement target");
      }

      boolean pointerBindsRetirement =
          observedPrevious != null && !observedPrevious.isBlank();
      deleteExactRetiredRepresentation(previous, pointerBindsRetirement);
      if (observedPrevious == null || observedPrevious.isBlank()) {
        return current;
      }

      State next =
          new State(
              STATE_FORMAT_VERSION,
              active,
              null,
              null,
              MigrationState.IDLE.name(),
              current.migration_paused(),
              current.pause_reason(),
              current.paused_at_ms(),
              System.currentTimeMillis(),
              current.auto_rebuild_key(),
              current.auto_rebuild_count(),
              current.auto_rebuild_first_ms());
      try {
        writeState(next);
        return next;
      } catch (IOException ambiguous) {
        try {
          State observed = requireExactIdleRetirementState(active);
          if (observed.previous_generation() == null
              || observed.previous_generation().isBlank()) {
            return observed;
          }
        } catch (IOException unresolved) {
          ambiguous.addSuppressed(unresolved);
        }
        throw ambiguous;
      }
    }
  }

  private State requireExactIdleRetirementState(String expectedActive) throws IOException {
    State state = strictBootLayout(readRecordedState()).state();
    if (!expectedActive.equals(state.active_generation())
        || !MigrationState.IDLE.name().equals(state.migration_state())
        || state.building_generation() != null && !state.building_generation().isBlank()) {
      throw new IOException("Generation state changed before predecessor retirement");
    }
    return state;
  }

  private void deleteExactRetiredRepresentation(
      String generationId, boolean pointerBindsRetirement) throws IOException {
    Path original = resolveGenerationPathReadOnly(generationId);
    if (Files.exists(original, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      requireOwnedRetirementDirectory(original, generationId, false, false);
      SafeIndexPathOps.MarkResult marked = SafeIndexPathOps.markForDeletion(original, indicesDir);
      Path effective = normalize(marked.effectivePath());
      requireOwnedRetirementDirectory(
          effective,
          generationId,
          !effective.getFileName().toString().equals(generationId),
          false);
    }

    for (Path retired :
        exactRetirementRepresentations(generationId, pointerBindsRetirement)) {
      deleteRetirementPayloadBeforeOwnership(retired);
      io.justsearch.configuration.FileOps.deleteRecursivelyBestEffort(retired, log);
    }
    if (!exactRetirementRepresentations(generationId, pointerBindsRetirement).isEmpty()) {
      throw new IOException(
          "Exact predecessor directory remains after deletion: " + generationId);
    }
  }

  private List<Path> exactRetirementRepresentations(
      String generationId, boolean pointerBindsRetirement) throws IOException {
    if (!Files.isDirectory(indicesDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<Path> exact = new ArrayList<>();
    try (var entries = Files.list(indicesDir)) {
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        if (!name.equals(generationId) && !isMarkedRetirementName(name, generationId)) {
          continue;
        }
        boolean markedName = !name.equals(generationId);
        requireOwnedRetirementDirectory(
            entry,
            generationId,
            markedName,
            markedName && pointerBindsRetirement);
        exact.add(entry);
      }
    }
    exact.sort(Comparator.comparing(Path::toString));
    return exact;
  }

  private void requireOwnedRetirementDirectory(
      Path directory,
      String generationId,
      boolean markedName,
      boolean allowPointerBoundPartialMetadata)
      throws IOException {
    Path exact = normalize(directory);
    Path root = normalize(indicesDir);
    String name = exact.getFileName().toString();
    boolean exactName =
        markedName ? isMarkedRetirementName(name, generationId) : name.equals(generationId);
    if (!exact.startsWith(root)
        || exact.equals(root)
        || !exactName
        || !Files.isDirectory(exact, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Refusing unsafe predecessor retirement path: " + exact);
    }
    Path sentinel = exact.resolve(GENERATION_SENTINEL);
    Path manifestPath = exact.resolve(GENERATION_MANIFEST);
    boolean sentinelExists = Files.exists(sentinel, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    boolean manifestExists = Files.exists(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    // FileOps historically could delete both metadata files before a locked payload. In that
    // recovery shape the strict previous_generation pointer plus SafeIndexPathOps' exact encoded
    // rename is the durable ownership proof. Any surviving metadata must still agree.
    if ((!sentinelExists || !manifestExists) && !allowPointerBoundPartialMetadata) {
      throw new IOException("Predecessor ownership metadata is unavailable: " + exact);
    }
    if (sentinelExists) {
      requireRetirementSentinel(sentinel, generationId, exact);
    }
    if (manifestExists) {
      requireRetirementManifest(manifestPath, generationId, exact);
    }
  }

  private static boolean isMarkedRetirementName(String name, String generationId) {
    String prefix = generationId + ".del-";
    if (!name.startsWith(prefix)) {
      return false;
    }
    String suffix = name.substring(prefix.length());
    return suffix.matches("[0-9]{8}-[0-9]{6}");
  }

  private static void requireRetirementSentinel(
      Path sentinel, String generationId, Path directory) throws IOException {
    if (!Files.isRegularFile(sentinel, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || Files.size(sentinel) > 1_024) {
      throw new IOException("Predecessor sentinel is invalid: " + directory);
    }
    String body =
        new String(
            io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(sentinel),
            StandardCharsets.UTF_8);
    if (body.lines().noneMatch(line -> line.equals("generation_id=" + generationId))) {
      throw new IOException("Predecessor sentinel identifies another generation: " + directory);
    }
  }

  private static void requireRetirementManifest(
      Path manifestPath, String generationId, Path directory) throws IOException {
    if (!Files.isRegularFile(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || Files.size(manifestPath) > 16_384) {
      throw new IOException("Predecessor manifest is invalid: " + directory);
    }
    final GenerationManifest manifest;
    try {
      manifest =
          JSON.readValue(
              io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(
                  manifestPath),
              GenerationManifest.class);
    } catch (tools.jackson.core.JacksonException malformed) {
      throw new IOException("Predecessor manifest is invalid: " + directory, malformed);
    }
    if (manifest == null || !generationId.equals(manifest.generation_id())) {
      throw new IOException("Predecessor manifest identifies another generation: " + directory);
    }
  }

  private static void deleteRetirementPayloadBeforeOwnership(Path directory)
      throws IOException {
    List<Path> payload;
    try (var entries = Files.list(directory)) {
      payload =
          entries
              .filter(
                  path ->
                      !Set.of(GENERATION_SENTINEL, GENERATION_MANIFEST)
                          .contains(path.getFileName().toString()))
              .sorted(Comparator.comparing(Path::toString))
              .toList();
    }
    for (Path entry : payload) {
      io.justsearch.configuration.FileOps.deleteRecursivelyBestEffort(entry, log);
    }
    for (Path entry : payload) {
      if (Files.exists(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException(
            "Predecessor payload remains locked after deletion: " + directory);
      }
    }
  }

  private void requireRecordedBuildCapacity(State state, String acceptedTarget)
      throws IOException {
    String active =
        requireSafeGenerationId(state.active_generation(), "state.json active_generation");
    if (state.previous_generation() != null && !state.previous_generation().isBlank()) {
      throw new IOException("Previous generation still occupies recorded build capacity");
    }
    if (!Files.isDirectory(indicesDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var entries = Files.list(indicesDir)) {
      for (Path entry : entries.toList()) {
        if (!Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        String name = entry.getFileName().toString();
        if (!name.equals(active) && !name.equals(acceptedTarget)) {
          throw new IOException(
              "A retained generation representation still occupies recorded build capacity");
        }
      }
    }
  }

  private IndexLayout resolveFromState(State state) throws IOException {
    State normalized = normalizeAndUpgradeStateIfNeeded(state);
    String genId = requireSafeGenerationId(normalized.active_generation(), "state.json active_generation");
    Path genPath = resolveGenerationPath(genId);
    if (!Files.isDirectory(genPath)) {
      throw new IOException(
          "Index state points to missing generation directory: active_generation="
              + genId
              + " path="
              + genPath);
    }
    return new IndexLayout(basePath, indicesDir, statePath, normalized, genId, genPath);
  }

  private IndexLayout importLegacyIndex() throws IOException {
    // Move basePath out of the way, then recreate basePath with the generation layout.
    String ts = TS.format(Instant.now());
    Path legacyMoved = basePath.resolveSibling(basePath.getFileName() + ".legacy-" + ts);

    if (!Files.isDirectory(basePath)) {
      throw new IOException("Index base path is not a directory: " + basePath);
    }
    if (Files.exists(legacyMoved)) {
      throw new IOException("Legacy import destination already exists: " + legacyMoved);
    }

    log.info("Legacy index detected at {}; importing into generation layout", basePath);
    Files.move(basePath, legacyMoved);
    Files.createDirectories(basePath);
    Files.createDirectories(indicesDir);

    String genId = "v0_imported";
    Path genPath = resolveGenerationPath(genId);
    if (Files.exists(genPath)) {
      throw new IOException("Refusing legacy import: generation already exists: " + genPath);
    }

    Files.move(legacyMoved, genPath);
    writeGenerationFiles(genPath, genId, "legacy_import");

    State state = newIdleState(genId, null, null);
    writeState(state);
    log.info("Legacy import complete. Active generation: {} ({})", genId, genPath);

    return new IndexLayout(basePath, indicesDir, statePath, state, genId, genPath);
  }

  private IndexLayout tryAdoptSingleExistingGeneration() throws IOException {
    if (!Files.isDirectory(indicesDir)) {
      return null;
    }

    List<Path> candidates = new ArrayList<>();
    try (var stream = Files.list(indicesDir)) {
      stream
          .filter(Files::isDirectory)
          .filter(p -> !p.getFileName().toString().contains(".del-"))
          .forEach(candidates::add);
    }

    if (candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() > 1) {
      log.warn(
          "Index has {} generations under {} but no state.json; refusing to guess active generation",
          candidates.size(),
          indicesDir);
      return null;
    }

    Path genPath = candidates.get(0).toAbsolutePath().normalize();
    String genId = genPath.getFileName().toString();
    requireSafeGenerationId(genId, "adopted generation id");

    log.warn(
        "Adopting existing generation {} under {} because state.json is missing",
        genId,
        indicesDir);
    writeGenerationFiles(genPath, genId, "adopt_existing");
    State state = newIdleState(genId, null, null);
    writeState(state);
    return new IndexLayout(basePath, indicesDir, statePath, state, genId, genPath);
  }

  private IndexLayout createFreshGeneration(String source) throws IOException {
    Files.createDirectories(indicesDir);
    String genId = newUniqueGenerationId();
    Path genPath = resolveGenerationPath(genId);
    Files.createDirectories(genPath);
    writeGenerationFiles(genPath, genId, source);

    State state = newIdleState(genId, null, null);
    writeState(state);
    return new IndexLayout(basePath, indicesDir, statePath, state, genId, genPath);
  }

  /**
   * Fresh authoritative state check for a mutation targeting the captured serving generation.
   * No cached state, backup recovery, normalization write or missing-state fallback is allowed.
   * This is an observation, not a lease across a concurrent generation transition.
   */
  public boolean isIdleActiveGeneration(Path capturedTarget) throws IOException {
    return idleActiveGeneration(capturedTarget).isPresent();
  }

  /**
   * Returns the active identity from a strict authoritative-state read when it still names the
   * captured runtime path. Unlike {@link #idleActiveGeneration}, an in-progress build does not make
   * the serving Blue generation unobservable.
   */
  public java.util.Optional<String> activeGeneration(Path capturedTarget) throws IOException {
    StrictActiveGeneration active = strictActiveGeneration(capturedTarget);
    return active.matchesCapturedTarget() ? java.util.Optional.of(active.generationId())
        : java.util.Optional.empty();
  }

  /** Return the identity from the same strict observation that validates the captured target. */
  public java.util.Optional<String> idleActiveGeneration(Path capturedTarget) throws IOException {
    StrictActiveGeneration active = strictActiveGeneration(capturedTarget);
    State current = active.state();
    boolean eligible = active.matchesCapturedTarget()
        && MigrationState.IDLE.name().equals(current.migration_state())
        && (current.building_generation() == null || current.building_generation().isBlank());
    return eligible ? java.util.Optional.of(active.generationId()) : java.util.Optional.empty();
  }

  private StrictActiveGeneration strictActiveGeneration(Path capturedTarget) throws IOException {
    Objects.requireNonNull(capturedTarget, "capturedTarget");
    State current = JSON.readValue(
        io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(statePath), State.class);
    if (current == null || (current.format_version() != 1
        && current.format_version() != STATE_FORMAT_VERSION)) {
      throw new IOException("Unsupported or empty authoritative index state");
    }
    String active = requireSafeGenerationId(current.active_generation(), "state.json active_generation");
    Path activePath = resolveGenerationPathReadOnly(active);
    boolean matches = activePath.equals(capturedTarget.toAbsolutePath().normalize())
        && Files.isDirectory(activePath);
    return new StrictActiveGeneration(current, active, matches);
  }

  private record StrictActiveGeneration(
      State state, String generationId, boolean matchesCapturedTarget) {}

  /**
   * Reads the current state pointer best-effort, without performing legacy imports or creating new
   * generations.
   *
   * <p>Uses a read-cache: a populated {@link CachedState} (published atomically through one volatile
   * reference) is returned directly without touching disk. {@link #writeState} invalidates it by
   * nulling the reference, so the cache is always re-loaded after a write. Concurrency-safe: a reader
   * never observes a torn version/state pair (tempdoc 589).
   */
  public State readStateBestEffort() {
    try (var ignored = stateControl()) {
      String stamp = contentStampBestEffort();
      CachedState cached = cache; // single volatile read — the whole entry is atomic
      // A hit needs the stamp to be UNCHANGED, or unreadable. Unreadable is a hit on purpose:
      // writeState replaces state.json by renaming (state.json -> state.json.prev, then tmp ->
      // state.json), and on Windows a file being renamed over is briefly unopenable. Treating that
      // window as "re-read" rather than "unchanged" would trade the stale read this stamp exists to
      // fix for a transient NULL — which is worse, because every caller projects null as an empty
      // migration state. A permanently missing state.json serves the last parse, which is exactly
      // what the pre-stamp cache did.
      if (cached != null && (stamp == null || stamp.equals(cached.contentHash()))) {
        return cached.value();
      }
      try {
        State s = loadStateBestEffort();
        if (s == null) {
          // Absent state.json is NOT cached (matches prior behavior: re-read on the next call).
          return null;
        }
        State normalized = normalizeAndUpgradeStateIfNeeded(s);
        // Stamp from BEFORE the read — the one taken at the top of this method — and not a fresh one
        // taken after the parse. The two orders fail in opposite directions and only this one fails
        // safe:
        //
        //   after-parse:  read stamp S1, parse V from revision R1, ANOTHER MANAGER WRITES (file
        //                 becomes R2), stamp S2 = R2, cache (V-from-R1, stamp-of-R2). The next
        //                 caller's stamp is R2, which MATCHES, so it is served R1's value — and goes
        //                 on being served it until some later write moves the stamp again. That is
        //                 the exact stale-read this stamp was added to prevent, reintroduced in a
        //                 narrower window.
        //   before-read:  cache (V, S1). If the file changed at any point during the read, the next
        //                 caller's stamp is S2 != S1, so it misses and re-parses. The cost is one
        //                 extra parse; there is no order in which a stale value can be served.
        //
        // The window is small either way. It is also exactly the window this whole change exists for
        // — concurrent writes through a DIFFERENT manager instance over the same file — so sizing the
        // fix to the common case rather than the racing one would have missed the point.
        cache = new CachedState(normalized, stamp);
        return normalized;
      } catch (Exception e) {
        return null;
      }
    }
  }

  /**
   * Hex SHA-256 of state.json's bytes; {@code null} when the file is absent or unreadable.
   *
   * <p>Deliberately reads the whole file rather than sampling its attributes — see the cache comment
   * on {@link #cache} for why (mtime, size) cannot identify a state.json revision. The file is a
   * handful of fixed-width fields; SHA-256 over it is not the expensive part of anything.
   *
   * <p>{@code null} is a distinct answer from "hash of nothing", and callers treat it as "cannot
   * tell, keep serving the last parse" rather than "changed": {@code writeState} replaces state.json
   * by rename, and the brief window where the old name is gone must not be reported as a new
   * revision.
   */
  private String contentStampBestEffort() {
    try {
      byte[] bytes = Files.readAllBytes(statePath);
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception absentOrUnreadable) {
      return null;
    }
  }

  private static State newIdleState(String activeGenId, String previousGenId, String buildingGenId) {
    return new State(
        STATE_FORMAT_VERSION,
        activeGenId,
        buildingGenId,
        previousGenId,
        MigrationState.IDLE.name(),
        false,
        null,
        null,
        System.currentTimeMillis(),
        null,
        null,
        null);
  }

  private State normalizeAndUpgradeStateIfNeeded(State state) throws IOException {
    if (state == null) {
      return null;
    }
    int v = state.format_version();
    if (v != 1 && v != STATE_FORMAT_VERSION) {
      throw new IOException(
          "Unsupported index state format_version=" + v + " at " + statePath);
    }

    String active = requireSafeGenerationId(state.active_generation(), "state.json active_generation");
    String building =
        state.building_generation() == null || state.building_generation().isBlank()
            ? null
            : requireSafeGenerationId(state.building_generation(), "state.json building_generation");
    String previous =
        state.previous_generation() == null || state.previous_generation().isBlank()
            ? null
            : requireSafeGenerationId(state.previous_generation(), "state.json previous_generation");

    String migRaw = state.migration_state();
    String mig =
        migRaw == null || migRaw.isBlank() ? MigrationState.IDLE.name() : migRaw.trim().toUpperCase(Locale.ROOT);
    // Normalize unknown values to FAILED (safer than pretending everything is OK).
    try {
      MigrationState.valueOf(mig);
    } catch (Exception ignored) {
      mig = MigrationState.FAILED.name();
    }

    long updated = state.updated_at_ms() > 0 ? state.updated_at_ms() : System.currentTimeMillis();
    boolean paused = Boolean.TRUE.equals(state.migration_paused());
    String pauseReason = state.pause_reason();
    Long pausedAtMs = state.paused_at_ms();
    State normalized =
        new State(
            STATE_FORMAT_VERSION,
            active,
            building,
            previous,
            mig,
            paused,
            pauseReason,
            pausedAtMs,
            updated,
            state.auto_rebuild_key(),
            state.auto_rebuild_count(),
            state.auto_rebuild_first_ms());

    if (v != STATE_FORMAT_VERSION) {
      // Upgrade v1 -> v2 in-place (best-effort).
      try {
        writeState(normalized);
      } catch (Exception e) {
        log.debug("Failed to upgrade state file from v{} to v{}: {}", v, STATE_FORMAT_VERSION, e.getMessage());
      }
    }

    return normalized;
  }

  private static MigrationState parseMigrationStateOrDefault(String raw, MigrationState def) {
    if (raw == null || raw.isBlank()) {
      return def;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return MigrationState.valueOf(normalized);
    } catch (Exception ignored) {
      return MigrationState.FAILED;
    }
  }

  private void writeGenerationFiles(Path genDir, String genId, String source) {
    try {
      // Sentinel authorizes future deletion/GC (defense-in-depth against deleting arbitrary dirs).
      Path sentinel = genDir.resolve(GENERATION_SENTINEL);
      if (!Files.exists(sentinel)) {
        String body =
            "justsearch_generation_sentinel_v1\n"
                + "generation_id="
                + genId
                + "\ncreated_at_ms="
                + System.currentTimeMillis()
                + "\n";
        Files.writeString(sentinel, body, StandardCharsets.UTF_8);
      }

      Path manifest = genDir.resolve(GENERATION_MANIFEST);
      if (!Files.exists(manifest)) {
        GenerationManifest m =
            new GenerationManifest(MANIFEST_FORMAT_VERSION, genId, source, System.currentTimeMillis());
        JSON.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), m);
      }
    } catch (Exception e) {
      // Best-effort. These files are guardrails/diagnostics, not required to open Lucene.
      log.warn("Failed to write generation metadata files for {}", genDir, e);
    }
  }

  /**
   * Best-effort read of a generation's manifest {@code source} label (e.g. {@code
   * "corrupt_index_rebuild"}, {@code "schema_mismatch"}) — used by the status projection to tell the
   * user WHY a rebuild is running (tempdoc 628 Stage C). Returns {@code null} if unreadable.
   */
  public String readGenerationSourceBestEffort(String genId) {
    if (genId == null || genId.isBlank()) {
      return null;
    }
    try {
      Path genDir = resolveGenerationPath(requireSafeGenerationId(genId, "generationId"));
      Path manifest = genDir.resolve(GENERATION_MANIFEST);
      if (!Files.exists(manifest)) {
        return null;
      }
      GenerationManifest m = JSON.readValue(manifest.toFile(), GenerationManifest.class);
      return m == null ? null : m.source();
    } catch (Exception e) {
      log.debug("Failed to read generation manifest source for {}: {}", genId, e.getMessage());
      return null;
    }
  }

  private State loadStateBestEffort() throws IOException {
    // state.json is authoritative; state.json.prev is a fallback if state.json is corrupted/partial.
    State s = tryReadState(statePath);
    if (s != null) {
      return s;
    }
    State prev = tryReadState(statePrevPath);
    if (prev != null) {
      log.warn("Recovered index state from {} (state.json was missing/invalid)", statePrevPath);
      try {
        writeState(prev);
      } catch (Exception e) {
        log.debug("Failed to restore state.json from backup: {}", e.getMessage());
      }
      return prev;
    }
    return null;
  }

  private State tryReadState(Path p) throws IOException {
    if (!Files.exists(p)) return null;
    // Unavailable bytes are not an absent/corrupt pointer. In particular, never adopt a new
    // IDLE state or restore .prev merely because an external process holds the current file.
    byte[] bytes = io.justsearch.configuration.persistence.ContendedFileReads.readAllBytes(p);
    try {
      return JSON.readValue(bytes, State.class);
    } catch (Exception e) {
      log.warn("Failed to read index state from {}", p, e);
      return null;
    }
  }

  private void writeState(State state) throws IOException {
    Objects.requireNonNull(state, "state");
    Files.createDirectories(basePath);

    // 1) Write tmp
    JSON.writerWithDefaultPrettyPrinter().writeValue(stateTmpPath.toFile(), state);

    // 2) Rotate current -> prev (best-effort)
    if (Files.exists(statePath)) {
      try {
        Files.move(statePath, statePrevPath, StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception e) {
        // best-effort; proceed with writing the new state
        log.debug("Failed to rotate state.json -> state.json.prev (continuing): {}", e.getMessage());
      }
    }

    // 3) Move tmp -> state.json (prefer atomic)
    try {
      Files.move(
          stateTmpPath,
          statePath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(stateTmpPath, statePath, StandardCopyOption.REPLACE_EXISTING);
    }

    // 4) Invalidate the read cache so the next readStateBestEffort() re-reads from disk.
    cache = null;
    stateMoveProbe.afterMove(state);
  }

  private Path resolveGenerationPath(String genId) throws IOException {
    Files.createDirectories(indicesDir);
    return resolveGenerationPathReadOnly(genId);
  }

  private Path resolveGenerationPathReadOnly(String genId) throws IOException {
    Path p = indicesDir.resolve(genId).toAbsolutePath().normalize();
    if (!p.startsWith(indicesDir.toAbsolutePath().normalize())) {
      throw new IOException("Refusing generation path outside indicesDir. genId=" + genId + " path=" + p);
    }
    return p;
  }

  private static boolean looksLikeLegacyIndexInBasePath(Path basePath) {
    // Detect legacy Lucene index files directly under basePath (not under indices/<gen>/).
    if (basePath == null || !Files.isDirectory(basePath)) {
      return false;
    }
    try (var stream = Files.list(basePath)) {
      return stream.anyMatch(
          p -> {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            // Only look for the canonical marker at the base path level.
            return n.startsWith("segments");
          });
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String requireSafeGenerationId(String id, String ctx) throws IOException {
    if (id == null || id.isBlank()) {
      throw new IOException(ctx + " is missing/blank");
    }
    String trimmed = id.trim();
    // Defensive: no traversal or path separators.
    if (trimmed.contains("..")
        || trimmed.contains("/")
        || trimmed.contains("\\")
        || trimmed.contains(":")) {
      throw new IOException(ctx + " contains illegal characters: " + trimmed);
    }
    return trimmed;
  }

  private static Path normalize(Path p) {
    return p.toAbsolutePath().normalize();
  }

  /**
   * Best-effort deletion helper for already-marked generations (directories named "*.del-*" or with
   * a DELETEME marker). Not currently invoked by default; provided for future GC policies.
   */
  public void pruneMarkedForDeletionBestEffort() {
    try (var ignored = stateControl()) {
      if (!Files.isDirectory(indicesDir)) {
        return;
      }
      State state = readStateBestEffort();
      if (state == null) {
        // Safer to skip deletion when we don't know what's active/previous/building.
        return;
      }
      Set<String> protectedIds = protectedGenerationIds(state);
      try (var stream = Files.list(indicesDir)) {
        stream
            .filter(Files::isDirectory)
            .filter(
                p -> {
                  String name = p.getFileName().toString();
                  if (protectedIds.contains(name)) {
                    return false;
                  }
                  boolean marked = name.contains(".del-") || Files.exists(p.resolve(DELETE_MARKER));
                  if (!marked) return false;
                  // Defense-in-depth: only delete dirs that look like JustSearch generations.
                  return Files.exists(p.resolve(GENERATION_SENTINEL));
                })
            .sorted(Comparator.comparing(Path::toString))
            .forEach(
                p -> {
                  try {
                    io.justsearch.configuration.FileOps.deleteRecursivelyBestEffort(p, log);
                  } catch (Exception e) {
                    log.debug("Failed to delete marked directory {}: {}", p, e.getMessage());
                  }
                });
      } catch (Exception e) {
        log.debug("Error while pruning marked-for-deletion directories: {}", e.getMessage());
      }
    }
  }

  public record GcResult(int markedCount, int prunedCount) {}

  /**
   * Best-effort GC for old, unreferenced generations/backups.
   *
   * <p>This method is intended to be called via an explicit operator control, not automatically.
   */
  public GcResult gcBestEffort(int keepLatest, boolean pruneMarkedOnly) {
    try (var ignored = stateControl()) {
      try {
        if (!Files.isDirectory(indicesDir)) {
          return new GcResult(0, 0);
        }
        State state = readStateBestEffort();
        if (state == null) {
          return new GcResult(0, 0);
        }
        Set<String> protectedIds = protectedGenerationIds(state);

        int marked = 0;
        if (!pruneMarkedOnly) {
          List<Path> candidates = new ArrayList<>();
          try (var stream = Files.list(indicesDir)) {
            stream
                .filter(Files::isDirectory)
                .filter(p -> Files.exists(p.resolve(GENERATION_SENTINEL)))
                .filter(
                    p -> {
                      String name = p.getFileName().toString();
                      if (protectedIds.contains(name)) return false;
                      // Never "mark" already-marked dirs; prune step handles them.
                      if (name.contains(".del-") || Files.exists(p.resolve(DELETE_MARKER))) {
                        return false;
                      }
                      return true;
                    })
                .forEach(candidates::add);
          }
          candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));
          int keep = Math.max(0, keepLatest);
          int cutoff = Math.max(0, candidates.size() - keep);
          for (int i = 0; i < cutoff; i++) {
            Path p = candidates.get(i);
            try {
              SafeIndexPathOps.markForDeletion(p, indicesDir);
              marked++;
            } catch (Exception e) {
              log.debug("Failed to mark {} for deletion: {}", p, e.getMessage());
            }
          }
        }

        // Always try to prune already-marked directories (best-effort).
        int before = countMarkedDeletableDirsBestEffort(state);
        pruneMarkedForDeletionBestEffort();
        int after = countMarkedDeletableDirsBestEffort(state);
        int pruned = Math.max(0, before - after);
        return new GcResult(marked, pruned);
      } catch (Exception e) {
        return new GcResult(0, 0);
      }
    }
  }

  private int countMarkedDeletableDirsBestEffort(State state) {
    if (state == null || !Files.isDirectory(indicesDir)) {
      return 0;
    }
    Set<String> protectedIds = protectedGenerationIds(state);
    try (var stream = Files.list(indicesDir)) {
      return (int)
          stream
              .filter(Files::isDirectory)
              .filter(
                  p -> {
                    String name = p.getFileName().toString();
                    if (protectedIds.contains(name)) return false;
                    boolean marked = name.contains(".del-") || Files.exists(p.resolve(DELETE_MARKER));
                    return marked && Files.exists(p.resolve(GENERATION_SENTINEL));
                  })
              .count();
    } catch (Exception e) {
      return 0;
    }
  }

  private static Set<String> protectedGenerationIds(State state) {
    if (state == null) return Set.of();
    return java.util.stream.Stream.of(state.active_generation(), state.previous_generation(), state.building_generation())
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }

}
