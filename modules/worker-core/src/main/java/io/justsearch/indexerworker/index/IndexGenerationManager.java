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
      long created_at_ms) {}

  private final Path basePath;
  private final Path indicesDir;
  private final Path statePath;
  private final Path stateTmpPath;
  private final Path statePrevPath;

  // Read-cache for readStateBestEffort(): avoids re-parsing state.json on every RPC when nothing has
  // changed. The cached State is published atomically through ONE volatile reference, so a reader on
  // a gRPC handler thread can never observe a torn version/state pair while a migration thread writes;
  // writeState() invalidates by nulling it. (tempdoc 589 — replaces a non-volatile lastReadVersion +
  // a non-atomic stateVersion++ counter, which together formed a data race.)
  //
  // Lane F stage A item A12: the cache also carries the (mtime, size) of the state.json it was
  // parsed from, and a hit is only a hit while that stamp still matches. Invalidating on THIS
  // instance's own writes is not enough, because one state.json has several managers over it in one
  // JVM — KnowledgeServer builds one (KnowledgeServer.java:611) for the migration enumerator and the
  // cutover monitor, WorkerIngestService builds its own from the same indexBasePath
  // (WorkerIngestService.java:177) for the migration control calls, and the switch-buffer replay
  // builds two more temporaries (KnowledgeServerMigrationOps.java:542, :718). A write through one
  // left every other instance serving its own stale parse forever. The concrete defect that found
  // this: `resumeMigration` wrote through the service's manager, the enumerator kept reading
  // `migration_paused=true` out of the server's manager, and the migration never resumed. The stamp
  // costs one file-attribute read where the miss cost a full JSON parse, so the cache still does the
  // job tempdoc 589 gave it.
  private volatile CachedState cache = null;

  /**
   * Atomically-published read-cache entry; only PRESENT states are cached (null == "re-read").
   *
   * @param value the parsed, normalized state
   * @param mtimeMillis the state.json modification time it was parsed from, or -1 if unreadable
   * @param size the state.json size it was parsed from, or -1 if unreadable
   */
  private record CachedState(State value, long mtimeMillis, long size) {}

  /** The (mtime, size) pair identifying a state.json revision; {@code null} when unreadable. */
  private record FileStamp(long mtimeMillis, long size) {}

  public IndexGenerationManager(Path indexBasePath) {
    this.basePath = normalize(Objects.requireNonNull(indexBasePath, "indexBasePath"));
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

  /** Sets operator pause intent for migration orchestration (best-effort). */
  public State setMigrationPaused(boolean paused, String reason) throws IOException {
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
   * Promotes {@code building_generation} to {@code active_generation} and clears {@code building_generation}.
   *
   * <p>This is the Phase F cutover step (pointer swap). It does not delete any generations; it
   * simply updates {@code state.json}.
   *
   * @return the updated normalized state, or null if no state exists
   */
  public State promoteBuildingGenerationToActive() throws IOException {
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

  /**
   * Rolls back {@code active_generation} to {@code previous_generation} (if present) and swaps
   * {@code previous_generation} to the current active generation.
   *
   * <p>This is an operator control used after a cutover when Green is unhealthy and we want to
   * revert to the last known-good generation.
   *
   * <p>This method does not delete any generations; it only updates {@code state.json}.
   *
   * @return the updated normalized state, or null if no state exists
   */
  public State rollbackToPreviousGeneration() throws IOException {
    State current = readStateBestEffort();
    if (current == null) {
      return null;
    }
    State normalized = normalizeAndUpgradeStateIfNeeded(current);
    String prev = normalized.previous_generation();
    if (prev == null || prev.isBlank()) {
      return normalized;
    }
    String nextActive = requireSafeGenerationId(prev, "state.json previous_generation");
    String curActive = requireSafeGenerationId(normalized.active_generation(), "state.json active_generation");
    State next =
        new State(
            STATE_FORMAT_VERSION,
            nextActive,
            null, // clear building on rollback (operators can start a new migration explicitly)
            curActive,
            MigrationState.IDLE.name(),
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
   * Reads the current state pointer best-effort, without performing legacy imports or creating new
   * generations.
   *
   * <p>Uses a read-cache: a populated {@link CachedState} (published atomically through one volatile
   * reference) is returned directly without touching disk. {@link #writeState} invalidates it by
   * nulling the reference, so the cache is always re-loaded after a write. Concurrency-safe: a reader
   * never observes a torn version/state pair (tempdoc 589).
   */
  public State readStateBestEffort() {
    FileStamp stamp = stampBestEffort();
    CachedState cached = cache; // single volatile read — the whole entry is atomic
    // A hit needs the stamp to be UNCHANGED, or unreadable. Unreadable is a hit on purpose:
    // writeState replaces state.json by renaming (state.json -> state.json.prev, then tmp ->
    // state.json), and on Windows a file being renamed over is briefly unopenable. Treating that
    // window as "re-read" rather than "unchanged" would trade the stale read this stamp exists to
    // fix for a transient NULL — which is worse, because every caller projects null as an empty
    // migration state. A permanently missing state.json serves the last parse, which is exactly
    // what the pre-stamp cache did.
    if (cached != null
        && (stamp == null
            || (cached.mtimeMillis() == stamp.mtimeMillis() && cached.size() == stamp.size()))) {
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
      cache =
          new CachedState(
              normalized,
              stamp == null ? -1L : stamp.mtimeMillis(),
              stamp == null ? -1L : stamp.size());
      return normalized;
    } catch (Exception e) {
      return null;
    }
  }

  /** Reads state.json's (mtime, size); {@code null} when the file is absent or unreadable. */
  private FileStamp stampBestEffort() {
    try {
      java.nio.file.attribute.BasicFileAttributes attrs =
          Files.readAttributes(statePath, java.nio.file.attribute.BasicFileAttributes.class);
      return new FileStamp(attrs.lastModifiedTime().toMillis(), attrs.size());
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

  private State loadStateBestEffort() {
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

  private State tryReadState(Path p) {
    try {
      if (!Files.exists(p)) {
        return null;
      }
      return JSON.readValue(p.toFile(), State.class);
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
  }

  private Path resolveGenerationPath(String genId) throws IOException {
    Files.createDirectories(indicesDir);
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

  public record GcResult(int markedCount, int prunedCount) {}

  /**
   * Best-effort GC for old, unreferenced generations/backups.
   *
   * <p>This method is intended to be called via an explicit operator control, not automatically.
   */
  public GcResult gcBestEffort(int keepLatest, boolean pruneMarkedOnly) {
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
