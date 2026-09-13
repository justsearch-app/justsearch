/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationOutcomeView;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.api.registry.InvocationProvenance;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One connection and one lock for the shared operations.db, including its final close. */
public final class SqliteOperationStore implements OperationStore {
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private static final Logger LOG = LoggerFactory.getLogger(SqliteOperationStore.class);
  private static final long FUTURE_SKEW_MS = Duration.ofMinutes(5).toMillis();
  private static final long RETENTION_MS = Duration.ofDays(30).toMillis();
  private static final int ROW_CAP = 100000;
  private static final int PREPARATION_CAP = 512;
  private static final long PREPARATION_TTL_MS = Duration.ofMinutes(5).toMillis();
  private static final String TERMINAL = "state IN ('COMPLETE', 'FAILED', 'CANCELLED')";
  private static final String INSERT_OPERATION = """
      INSERT INTO operations(operation_key, kind, survival, urgency, state, operation_ref,
        identity_json, grant_ref, client_kind, client_id, session_id, source_tier, transport,
        executor, initiator, correlation_id, accepted_at, updated_at)
      """;
  private final ReentrantLock lock = new ReentrantLock();
  private final Path path;
  private final Clock clock;
  private final OpenStepHook hook;
  private Connection connection;
  private Recovery recovery;

  @Override
  public java.util.Optional<Recovery> recovery() { return java.util.Optional.ofNullable(recovery); }

  public SqliteOperationStore(Path path) throws IOException, SQLException {
    this(path, Clock.systemUTC(), step -> {});
  }

  @FunctionalInterface
  interface OpenStepHook {
    void afterStep(String step) throws IOException;
  }

  SqliteOperationStore(Path path, Clock clock, OpenStepHook hook) throws IOException, SQLException {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.hook = Objects.requireNonNull(hook, "hook");
    Files.createDirectories(this.path.getParent());
    try {
      resumePendingPreservation();
      if (Files.exists(this.path) && Files.size(this.path) > 0) {
        try {
          inspectExisting();
        } catch (SQLException failure) {
          if (!isCorruption(failure)) throw failure;
          preserveCorruptStore();
        }
      } else if (Files.exists(sidecar("-wal")) || Files.exists(sidecar("-shm"))) {
        // A kill may have moved the main file but left its WAL. It must never reach a new file.
        preserveCorruptStore();
      }
      Recovery latestRecovery = latestRecovery();
      long recoveryFloor = latestRecovery == null ? 0 : latestRecovery.historySinceMillis();
      connection = DriverManager.getConnection("jdbc:sqlite:" + this.path);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA busy_timeout = 5000");
        statement.execute("PRAGMA journal_mode = WAL");
        statement.execute("PRAGMA synchronous = NORMAL");
      }
      boolean recovered = initializeSchema(recoveryFloor);
      if (recovered) recovery = latestRecovery;
      pruneHistory();
    } catch (IOException | SQLException | RuntimeException | Error failure) {
      if (connection != null) {
        try { connection.close(); } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        connection = null;
      }
      throw failure;
    }
  }

  private void inspectExisting() throws SQLException, IOException {
    // Read-only SQLite sees user_version in WAL as well as the main header. Compatibility is
    // checked before journal-mode setup or writable DDL can modify a future database.
    try (var snapshot = io.justsearch.configuration.persistence.SqliteStoreSnapshot.open(path);
        Statement statement = snapshot.connection().createStatement()) {
      refuseFutureVersion(schemaVersion(snapshot.connection()));
      try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
        boolean sawResult = false;
        while (result.next()) {
          sawResult = true;
          if (!"ok".equalsIgnoreCase(result.getString(1))) {
            throw new SQLException("operations.db quick check failed", "SQLITE_CORRUPT", 11);
          }
        }
        if (!sawResult) throw new SQLException("operations.db quick check returned no result");
      }
    }
  }

  private static boolean isCorruption(SQLException failure) {
    // SQLite's primary CORRUPT and NOTADB codes; never quarantine access, locking or disk-full errors.
    int primaryCode = failure.getErrorCode() & 0xff;
    return primaryCode == 11 || primaryCode == 26;
  }

  private static int schemaVersion(Connection database) throws SQLException {
    try (Statement statement = database.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA user_version")) {
      if (!result.next()) throw new SQLException("operations.db has no schema version result");
      return result.getInt(1);
    }
  }

  private static void refuseFutureVersion(int version) {
    if (version > OperationSchema.VERSION) {
      throw new UnsupportedStoreVersionException("operations-db", version, OperationSchema.VERSION);
    }
  }

  private boolean initializeSchema(long recoveryFloor) throws SQLException, IOException {
    int version = schemaVersion(connection);
    refuseFutureVersion(version);
    long previousFloor = 0;
    if (version != 0) {
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("SELECT history_since_ms FROM operations_meta WHERE singleton = 1")) {
        if (!result.next()) throw new SQLException("operations.db metadata is missing");
        previousFloor = result.getLong(1);
      }
    }
    connection.setAutoCommit(false);
    boolean transactionEnded = false;
    try (Statement statement = connection.createStatement()) {
      if (version == 0) {
        OperationSchema.createTables(statement);
        try (var insert = connection.prepareStatement(
            "INSERT INTO operations_meta(singleton, history_since_ms, created_at_ms) VALUES (1, ?, ?)")) {
          insert.setLong(1, recoveryFloor);
          insert.setLong(2, clock.millis());
          insert.executeUpdate();
        }
        statement.execute("PRAGMA user_version = " + OperationSchema.VERSION);
      } else {
        if (version == 1) {
          OperationSchema.migrateV1(statement);
        }
        if (version <= 2) {
          OperationSchema.migrateV2(statement);
          statement.execute("PRAGMA user_version = " + OperationSchema.VERSION);
        }
        try (var update = connection.prepareStatement(
            "UPDATE operations_meta SET history_since_ms = MAX(history_since_ms, ?) WHERE singleton = 1")) {
          update.setLong(1, recoveryFloor);
          if (update.executeUpdate() != 1) throw new SQLException("operations.db metadata is missing");
        }
      }
      hook.afterStep("before-schema-commit");
      connection.commit();
      transactionEnded = true;
      return recoveryFloor > previousFloor;
    } catch (SQLException | IOException | RuntimeException | Error failure) {
      try { connection.rollback(); transactionEnded = true; } catch (SQLException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    } finally {
      if (transactionEnded) connection.setAutoCommit(true);
      else connection.close();
    }
  }

  private Path sidecar(String suffix) {
    return path.resolveSibling(path.getFileName() + suffix);
  }

  private void preserveCorruptStore() throws IOException {
    Path pending = path.resolveSibling(path.getFileName() + ".corrupt-"
        + clock.millis() + "-" + UUID.randomUUID() + ".pending");
    Files.createDirectory(pending);
    hook.afterStep("quarantine-directory");
    finishPreservation(pending);
  }

  private void resumePendingPreservation() throws IOException {
    Path pending = null;
    Pattern name = Pattern.compile(Pattern.quote(path.getFileName().toString())
        + "\\.corrupt-\\d+-[0-9a-f-]{36}\\.pending");
    try (var siblings = Files.newDirectoryStream(path.getParent(), path.getFileName() + ".corrupt-*.pending")) {
      for (Path candidate : siblings) {
        if (!Files.isDirectory(candidate) || !name.matcher(candidate.getFileName().toString()).matches()) {
          throw new IOException("Invalid pending operations preservation: " + candidate);
        }
        if (pending != null) throw new IOException("Multiple pending operations preservations");
        pending = candidate;
      }
    }
    if (pending != null) finishPreservation(pending);
  }

  private void finishPreservation(Path pending) throws IOException {
    for (Path artifact : List.of(path, sidecar("-wal"), sidecar("-shm"))) {
      if (Files.exists(artifact)) {
        Path destination = pending.resolve(artifact.getFileName());
        if (Files.exists(destination)) throw new IOException("Conflicting operations preservation: " + destination);
        movePreserved(artifact, destination);
      }
      hook.afterStep("quarantine-" + artifact.getFileName());
    }
    String name = pending.getFileName().toString();
    Path preserved = pending.resolveSibling(name.substring(0, name.length() - ".pending".length()));
    movePreserved(pending, preserved);
    hook.afterStep("quarantine-finalized");
    LOG.warn("Preserved corrupt operations database at {}; outcome history has a recovery fence", preserved);
  }

  private static void movePreserved(Path source, Path destination) throws IOException {
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, destination);
    }
  }

  private Recovery latestRecovery() throws IOException {
    Pattern pattern = Pattern.compile(Pattern.quote(path.getFileName().toString())
        + "\\.corrupt-(\\d+)-[0-9a-f-]{36}");
    Recovery latest = null;
    try (var siblings = Files.newDirectoryStream(path.getParent(), path.getFileName() + ".corrupt-*")) {
      for (Path sibling : siblings) {
        var match = pattern.matcher(sibling.getFileName().toString());
        if (Files.isDirectory(sibling) && match.matches()) {
          try {
            long floor = Math.addExact(Long.parseLong(match.group(1)), FUTURE_SKEW_MS + 1);
            if (latest == null || floor > latest.historySinceMillis()) latest = new Recovery(sibling, floor);
          } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IOException("Invalid operations recovery timestamp: " + sibling, invalid);
          }
        }
      }
    }
    return latest;
  }

  @Override
  public Acceptance accept(String key, OperationDescriptor descriptor, EngineContext context,
      InvocationProvenance provenance) {
    return acceptInternal(key, descriptor, context, provenance, null);
  }

  @Override
  public Acceptance acceptPrepared(String key, OperationDescriptor descriptor, EngineContext context,
      InvocationProvenance provenance, UUID nonce) {
    return acceptInternal(key, descriptor, context, provenance, Objects.requireNonNull(nonce, "nonce"));
  }

  private Acceptance acceptInternal(String key, OperationDescriptor descriptor, EngineContext context,
      InvocationProvenance provenance, UUID nonce) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(descriptor, "descriptor");
    long keyTime = validatedKeyTime(key);
    String identity = canonicalIdentity(descriptor.identityJson());
    return locked(() -> transaction(() -> {
      var existing = lookupRow(key, keyTime, descriptor, identity);
      if (existing.isPresent()) return new Acceptance(existing.get(), false);
      Preparation pending = pendingRow(key, descriptor, identity).orElse(null);
      if ((nonce == null && pending != null)
          || (nonce != null && (pending == null || !nonce.equals(pending.nonce())))) {
        throw new OperationStoreException(OperationStoreException.Code.OPERATION_PREPARATION_UNAVAILABLE, null);
      }
      long now = clock.millis();
      pruneToLimit(ROW_CAP - 1);
      if (rowCount() >= ROW_CAP) {
        throw new OperationStoreException(OperationStoreException.Code.OPERATIONS_CAPACITY, null);
      }
      if (keyTime < readHistorySince()) {
        throw new OperationStoreException(OperationStoreException.Code.OPERATION_EXPIRED, null,
            readHistorySince() - now);
      }
      String sql = INSERT_OPERATION + """
          VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(operation_key) DO NOTHING
          """;
      try (var insert = connection.prepareStatement(sql)) {
        Object[] values = {key, descriptor.kind().wireValue(),
            context.survival().name(), context.urgency().name(), descriptor.operationRef(), identity,
            context.grantReference().orElse(null), context.clientKind().name(), context.clientId(),
            context.sessionId().orElse(null), context.sourceTier(), context.transport(),
            provenance == null ? null : provenance.executor().name(),
            provenance == null ? null : provenance.initiator().orElse(null),
            provenance == null ? null : provenance.correlationId().orElse(null), now, now};
        for (int i = 0; i < values.length; i++) insert.setObject(i + 1, values[i]);
        if (insert.executeUpdate() != 1) throw new SQLException("Concurrent acceptance escaped queue ownership");
      }
      if (pending != null) {
        try (var update = connection.prepareStatement("UPDATE operations SET preparation_nonce = ?, "
            + "preparation_sealed = ?, preparation_payload = ? WHERE operation_key = ?")) {
          update.setString(1, pending.nonce().toString());
          update.setInt(2, pending.payload().sealed() ? 1 : 0);
          update.setString(3, pending.payload().value());
          update.setString(4, key);
          if (update.executeUpdate() != 1) throw new SQLException("Accepted preparation is missing");
        }
      }
      try (var delete = connection.prepareStatement("DELETE FROM operation_preparations WHERE operation_key = ?")) {
        delete.setString(1, key);
        delete.executeUpdate();
      }
      return new Acceptance(findRow(key).orElseThrow(() -> new SQLException("Accepted row is missing")), true);
    }));
  }

  @Override
  public java.util.Optional<Preparation> pendingPreparation(String key, OperationDescriptor descriptor) {
    long keyTime = validatedKeyTime(key);
    String identity = canonicalIdentity(descriptor.identityJson());
    return locked(() -> {
      if (lookupRow(key, keyTime, descriptor, identity).isPresent()) return java.util.Optional.empty();
      return pendingRow(key, descriptor, identity);
    });
  }

  @Override
  public java.util.Optional<Preparation> savePreparation(String key, OperationDescriptor descriptor,
      Preparation preparation) {
    Objects.requireNonNull(preparation, "preparation");
    long keyTime = validatedKeyTime(key);
    String identity = canonicalIdentity(descriptor.identityJson());
    return locked(() -> transaction(() -> {
      if (lookupRow(key, keyTime, descriptor, identity).isPresent()) return java.util.Optional.empty();
      var prior = pendingRow(key, descriptor, identity);
      if (prior.isPresent()) return prior;
      prunePreparations(PREPARATION_CAP - 1);
      try (var insert = connection.prepareStatement("""
          INSERT INTO operation_preparations(operation_key, kind, operation_ref, identity_json,
            nonce, sealed, payload, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """)) {
        long now = clock.millis();
        Object[] values = {key, descriptor.kind().wireValue(), descriptor.operationRef(), identity,
            preparation.nonce().toString(), preparation.payload().sealed() ? 1 : 0, preparation.payload().value(),
            now, now + PREPARATION_TTL_MS};
        for (int i = 0; i < values.length; i++) insert.setObject(i + 1, values[i]);
        if (insert.executeUpdate() != 1) throw new SQLException("Preparation was not stored");
      }
      return java.util.Optional.of(preparation);
    }));
  }

  private java.util.Optional<Preparation> pendingRow(String key, OperationDescriptor descriptor,
      String identity) throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM operation_preparations "
        + "WHERE operation_key = ? AND expires_at > ?")) {
      query.setString(1, key); query.setLong(2, clock.millis());
      try (var row = query.executeQuery()) {
        if (!row.next()) return java.util.Optional.empty();
        if (!descriptor.kind().wireValue().equals(row.getString("kind"))
            || !Objects.equals(descriptor.operationRef(), row.getString("operation_ref"))
            || !identity.equals(row.getString("identity_json"))) {
          throw new OperationStoreException(OperationStoreException.Code.OPERATION_KEY_REUSED, null);
        }
        return java.util.Optional.of(new Preparation(UUID.fromString(row.getString("nonce")),
            new OperationPreparedPayload(row.getInt("sealed") == 1, row.getString("payload"))));
      }
    }
  }

  @Override
  public java.util.Optional<Preparation> acceptedPreparation(long id) {
    return locked(() -> {
      try (var query = connection.prepareStatement("SELECT preparation_nonce, preparation_sealed, "
          + "preparation_payload FROM operations WHERE id = ?")) {
        query.setLong(1, id);
        try (var row = query.executeQuery()) {
          if (!row.next() || row.getString("preparation_nonce") == null) return java.util.Optional.empty();
          return java.util.Optional.of(new Preparation(UUID.fromString(row.getString("preparation_nonce")),
              new OperationPreparedPayload(row.getInt("preparation_sealed") == 1, row.getString("preparation_payload"))));
        }
      }
    });
  }

  private void prunePreparations(int limit) throws SQLException {
    try (var delete = connection.prepareStatement("DELETE FROM operation_preparations WHERE expires_at <= ?")) {
      delete.setLong(1, clock.millis()); delete.executeUpdate();
    }
    try (var delete = connection.prepareStatement("DELETE FROM operation_preparations WHERE operation_key IN "
        + "(SELECT operation_key FROM operation_preparations ORDER BY created_at, operation_key "
        + "LIMIT MAX(0, (SELECT count(*) FROM operation_preparations) - ?))")) {
      delete.setInt(1, limit); delete.executeUpdate();
    }
  }

  @Override
  public java.util.Optional<OperationRecord> lookup(String key, OperationDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    long keyTime = validatedKeyTime(key);
    String identity = canonicalIdentity(descriptor.identityJson());
    return locked(() -> lookupRow(key, keyTime, descriptor, identity));
  }

  private static long validatedKeyTime(String key) {
    try { return OperationKeys.timestampMillis(key); }
    catch (IllegalArgumentException invalid) {
      throw new OperationStoreException(OperationStoreException.Code.INVALID_OPERATION_KEY, invalid);
    }
  }

  private java.util.Optional<OperationRecord> lookupRow(String key, long keyTime,
      OperationDescriptor descriptor, String identity) throws SQLException {
    var existing = findRow(key);
    if (existing.isPresent()) {
      OperationDescriptor prior = existing.get().descriptor();
      if (prior.kind() != descriptor.kind()
          || !Objects.equals(prior.operationRef(), descriptor.operationRef())
          || !prior.identityJson().equals(identity)) {
        throw new OperationStoreException(OperationStoreException.Code.OPERATION_KEY_REUSED, null);
      }
      return existing;
    }
    long now = clock.millis();
    if (keyTime > now + FUTURE_SKEW_MS) {
      throw new OperationStoreException(OperationStoreException.Code.INVALID_OPERATION_KEY, null);
    }
    long historySince = readHistorySince();
    if (keyTime < historySince) {
      throw new OperationStoreException(OperationStoreException.Code.OPERATION_EXPIRED, null,
          historySince - now);
    }
    return java.util.Optional.empty();
  }

  private static String canonicalIdentity(String json) {
    var value = JSON.readTree(json);
    if (value == null || !value.isObject()) throw new IllegalArgumentException("Operation identity must be an object");
    return JSON.writeValueAsString(JSON.convertValue(value, java.util.Map.class));
  }

  @Override
  public OperationOutcomeView outcome(String key) {
    long keyTime = validatedKeyTime(key);
    return locked(() -> {
      // A single SQLite statement observes the selected row and its eviction fence together.
      // Select only the public projection columns: identity and prepared content stay private.
      try (var query = connection.prepareStatement("""
          SELECT m.history_since_ms, o.state, o.phase, o.accepted_at, o.completed_at,
            o.units_completed, o.units_failed, o.failure_reason, o.result_json, o.gaps_json
          FROM operations_meta m LEFT JOIN operations o ON o.operation_key = ?
          WHERE m.singleton = 1
          """)) {
        query.setString(1, key);
        try (var result = query.executeQuery()) {
          if (!result.next()) throw new SQLException("Operations metadata is missing");
          long fence = result.getLong("history_since_ms");
          String state = result.getString("state");
          if (state != null) return projectOutcome(result, OperationState.valueOf(state), fence);
          if (keyTime > clock.millis() + FUTURE_SKEW_MS) {
            throw new OperationStoreException(OperationStoreException.Code.INVALID_OPERATION_KEY, null);
          }
          return new OperationOutcomeView(keyTime < fence
              ? OperationOutcomeView.State.EXPIRED : OperationOutcomeView.State.UNKNOWN, null, fence,
              null, null, null, null, null, null);
        }
      }
    });
  }

  private static OperationOutcomeView projectOutcome(ResultSet row, OperationState state, long fence)
      throws SQLException {
    OperationOutcomeView.State wireState = switch (state) {
      case ACCEPTED -> OperationOutcomeView.State.ACCEPTED;
      case RUNNING, COMPLETE_WITH_GAPS -> OperationOutcomeView.State.RUNNING;
      case COMPLETE -> OperationOutcomeView.State.COMPLETE;
      case FAILED, CANCELLED -> OperationOutcomeView.State.FAILED;
    };
    OperationOutcomeView.Result result = null;
    if (state == OperationState.COMPLETE_WITH_GAPS) {
      String gaps = row.getString("gaps_json");
      if (gaps != null) result = new OperationOutcomeView.Result(null, null,
          List.of(JSON.readValue(gaps, OperationOutcomeView.Gap[].class)));
    } else {
      String receiptJson = row.getString("result_json");
      if (receiptJson != null) {
        OperationReceipt receipt = JSON.readValue(receiptJson, OperationReceipt.class);
        result = new OperationOutcomeView.Result(receipt.code(), receipt.executionId(), null);
      }
    }
    return new OperationOutcomeView(wireState,
        state == OperationState.COMPLETE_WITH_GAPS ? "awaiting_acceptance" : row.getString("phase"),
        fence, row.getLong("accepted_at"), nullableLong(row, "completed_at"),
        row.getLong("units_completed"), row.getLong("units_failed"),
        state == OperationState.CANCELLED ? "cancelled" : row.getString("failure_reason"), result);
  }

  @Override
  public java.util.Optional<OperationRecord> find(String key) {
    OperationKeys.timestampMillis(key);
    return locked(() -> findRow(key));
  }

  private java.util.Optional<OperationRecord> findRow(String key) throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM operations WHERE operation_key = ?")) {
      query.setString(1, key);
      try (ResultSet result = query.executeQuery()) {
        return result.next() ? java.util.Optional.of(readRecord(result)) : java.util.Optional.empty();
      }
    }
  }

  @Override
  public boolean start(long id) {
    return locked(() -> {
      try (var update = connection.prepareStatement("""
          UPDATE operations SET state = 'RUNNING', started_at = ?, updated_at = ?, attempts = attempts + 1
          WHERE id = ? AND state = 'ACCEPTED'
          """)) {
        long now = clock.millis();
        update.setLong(1, now); update.setLong(2, now); update.setLong(3, id);
        return update.executeUpdate() == 1;
      }
    });
  }

  @Override
  public boolean resume(long id) {
    return locked(() -> {
      try (var update = connection.prepareStatement("""
          UPDATE operations SET state = 'RUNNING', started_at = COALESCE(started_at, ?),
            updated_at = ?, attempts = attempts + 1
          WHERE id = ? AND state IN ('ACCEPTED', 'RUNNING')
          """)) {
        long now = clock.millis();
        update.setLong(1, now); update.setLong(2, now); update.setLong(3, id);
        return update.executeUpdate() == 1;
      }
    });
  }

  @Override
  public OperationRecord rejectBeforeStart(long id, OperationReceipt receipt) {
    return locked(() -> {
      try (var update = connection.prepareStatement("""
          UPDATE operations SET state = 'FAILED', completed_at = ?, updated_at = ?, result_json = ?, failure_reason = ?
          WHERE id = ? AND state = 'ACCEPTED'
          """)) {
        long now = clock.millis();
        update.setLong(1, now); update.setLong(2, now);
        update.setString(3, JSON.writeValueAsString(receipt)); update.setString(4, receipt.code());
        update.setLong(5, id);
        update.executeUpdate();
        return rowById(id);
      }
    });
  }

  @Override
  public boolean checkpoint(long id, String cursor, long unitsCompleted, long unitsFailed) {
    if (unitsCompleted < 0 || unitsFailed < 0) throw new IllegalArgumentException("Negative checkpoint count");
    return locked(() -> {
      try (var update = connection.prepareStatement("""
          UPDATE operations SET checkpoint_cursor = ?, units_completed = ?, units_failed = ?, updated_at = ?
          WHERE id = ? AND state IN ('RUNNING', 'COMPLETE_WITH_GAPS')
            AND units_completed <= ? AND units_failed <= ?
          """)) {
        update.setString(1, cursor); update.setLong(2, unitsCompleted); update.setLong(3, unitsFailed);
        update.setLong(4, clock.millis()); update.setLong(5, id);
        update.setLong(6, unitsCompleted); update.setLong(7, unitsFailed);
        return update.executeUpdate() == 1;
      }
    });
  }

  @Override
  public java.util.Optional<OperationRecord> finish(long id, OperationState terminalState, OperationReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    if (!terminalState.terminal()) throw new IllegalArgumentException("Expected a terminal operation state");
    String resultJson = JSON.writeValueAsString(receipt);
    return locked(() -> {
      try (var update = connection.prepareStatement("""
          UPDATE operations SET state = ?, completed_at = ?, updated_at = ?, result_json = ?, failure_reason = ?
          WHERE id = ? AND state IN ('ACCEPTED', 'RUNNING', 'COMPLETE_WITH_GAPS')
          """)) {
        long now = clock.millis();
        update.setString(1, terminalState.name()); update.setLong(2, now); update.setLong(3, now);
        update.setString(4, resultJson);
        update.setString(5, terminalState == OperationState.COMPLETE ? null : receipt.code());
        update.setLong(6, id);
        return update.executeUpdate() == 1 ? java.util.Optional.of(rowById(id)) : java.util.Optional.empty();
      }
    });
  }

  private OperationRecord rowById(long id) throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM operations WHERE id = ?")) {
      query.setLong(1, id);
      try (var row = query.executeQuery()) {
        if (!row.next()) throw new SQLException("Operation row disappeared during transition");
        return readRecord(row);
      }
    }
  }

  @Override
  public List<OperationRecord> openRecords() {
    return locked(() -> {
      List<OperationRecord> records = new java.util.ArrayList<>();
      try (Statement query = connection.createStatement(); ResultSet result = query.executeQuery(
          "SELECT * FROM operations WHERE state IN ('ACCEPTED','RUNNING','COMPLETE_WITH_GAPS') ORDER BY id")) {
        while (result.next()) records.add(readRecord(result));
      }
      return List.copyOf(records);
    });
  }

  @Override
  public long historySinceMillis() { return locked(this::readHistorySince); }

  @Override
  public void pruneHistory() {
    locked(() -> transaction(() -> { pruneToLimit(ROW_CAP); prunePreparations(PREPARATION_CAP); return null; }));
  }

  /** Called only inside the store lock and a transaction, including acceptance's reservation. */
  private void pruneToLimit(int limit) throws SQLException {
    long fence = readHistorySince();
    try (var delete = connection.prepareStatement("DELETE FROM operations WHERE " + TERMINAL
        + " AND completed_at < ? RETURNING operation_key")) {
      delete.setLong(1, clock.millis() - RETENTION_MS);
      try (var rows = delete.executeQuery()) { fence = evictedFence(rows, fence); }
    }
    long excess = rowCount() - limit;
    if (excess > 0) {
      try (var delete = connection.prepareStatement("DELETE FROM operations WHERE id IN "
          + "(SELECT id FROM operations WHERE " + TERMINAL
          + " ORDER BY completed_at, id LIMIT ?) RETURNING operation_key")) {
        delete.setLong(1, excess);
        try (var rows = delete.executeQuery()) { fence = evictedFence(rows, fence); }
      }
    }
    if (fence > readHistorySince()) {
      try (var update = connection.prepareStatement(
          "UPDATE operations_meta SET history_since_ms = MAX(history_since_ms, ?) WHERE singleton = 1")) {
        update.setLong(1, fence);
        if (update.executeUpdate() != 1) throw new SQLException("History fence did not advance");
      }
    }
  }

  private static long evictedFence(ResultSet rows, long fence) throws SQLException {
    while (rows.next()) fence = Math.max(fence, OperationKeys.timestampMillis(rows.getString(1)) + 1);
    return fence;
  }

  private long rowCount() throws SQLException {
    try (Statement query = connection.createStatement();
        ResultSet result = query.executeQuery("SELECT count(*) FROM operations")) {
      if (!result.next()) throw new SQLException("Operations count is missing");
      return result.getLong(1);
    }
  }

  private long readHistorySince() throws SQLException {
    try (Statement query = connection.createStatement(); ResultSet result = query.executeQuery(
        "SELECT history_since_ms FROM operations_meta WHERE singleton = 1")) {
      if (!result.next()) throw new SQLException("Operations metadata is missing");
      return result.getLong(1);
    }
  }

  private static OperationRecord readRecord(ResultSet result) throws SQLException {
    EngineContext context = new EngineContext(
        EngineContext.ClientKind.valueOf(result.getString("client_kind")), result.getString("client_id"),
        java.util.Optional.ofNullable(result.getString("session_id")),
        java.util.Optional.ofNullable(result.getString("grant_ref")), result.getString("source_tier"),
        result.getString("transport"), EngineContext.Survival.valueOf(result.getString("survival")),
        EngineContext.Urgency.valueOf(result.getString("urgency")));
    String receiptJson = result.getString("result_json");
    return new OperationRecord(result.getLong("id"), result.getString("operation_key"),
        new OperationDescriptor(OperationKind.fromWire(result.getString("kind")),
            result.getString("operation_ref"), result.getString("identity_json")),
        context, result.getString("executor"), result.getString("initiator"), result.getString("correlation_id"),
        OperationState.valueOf(result.getString("state")), result.getString("phase"),
        result.getString("checkpoint_cursor"), result.getLong("units_completed"), result.getLong("units_failed"),
        result.getInt("attempts"), result.getLong("accepted_at"), nullableLong(result, "started_at"),
        result.getLong("updated_at"), nullableLong(result, "completed_at"), result.getString("failure_reason"),
        receiptJson == null ? null : JSON.readValue(receiptJson, OperationReceipt.class));
  }

  private static Long nullableLong(ResultSet result, String column) throws SQLException {
    long value = result.getLong(column);
    return result.wasNull() ? null : value;
  }

  @FunctionalInterface
  private interface SqlWork<T> { T run() throws SQLException; }

  private <T> T locked(SqlWork<T> work) {
    lock.lock();
    try {
      if (connection == null || connection.isClosed()) throw new SQLException("Operations store is closed");
      return work.run();
    } catch (SQLException failure) {
      throw new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, failure);
    } finally {
      lock.unlock();
    }
  }

  private <T> T transaction(SqlWork<T> work) throws SQLException {
    connection.setAutoCommit(false);
    boolean ended = false;
    try {
      T result = work.run();
      connection.commit();
      ended = true;
      return result;
    } catch (SQLException | RuntimeException | Error failure) {
      try { connection.rollback(); ended = true; }
      catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
      throw failure;
    } finally {
      // Do not turn an uncertain rollback into an implicit commit by restoring auto-commit.
      if (ended) connection.setAutoCommit(true);
      else connection.close();
    }
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      if (connection == null) return;
      try {
        try (Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(FULL)")) {
          if (!result.next() || result.getInt(1) != 0) {
            LOG.warn("Operations WAL remains replayable after close; a reader held its checkpoint");
          }
        }
      } catch (SQLException checkpointFailure) {
        LOG.warn("Operations WAL checkpoint failed; retaining WAL for replay", checkpointFailure);
      }
      try {
        connection.close();
        connection = null;
      } catch (SQLException failure) {
        throw new IOException("Could not close operations database", failure);
      }
    } finally {
      lock.unlock();
    }
  }
}
