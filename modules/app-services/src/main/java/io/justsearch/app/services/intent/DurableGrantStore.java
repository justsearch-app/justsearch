/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 550 thesis IV + 560 §28 (4d) — the durable "allow-always" grant: the second member of the
 * one Grant model (alongside the single-use consent capsule).
 *
 * <p>A durable grant says "auto-approve future invocations without re-prompting." Two scopes realize
 * the two positions of the {@link Grant} model's caveat:
 *
 * <ul>
 *   <li>an <b>operation grant</b> {@code (operationId, sourceTier)} — allow-always for exactly one
 *       operation (the per-op position). Args-independent <em>in this store</em> only: the executor
 *       additionally consults {@link DurableGrantScope}, so a grant does not cover an invocation
 *       reaching outside the containment it was granted against (tempdoc 875 C.3);
 *   <li>a <b>family grant</b> {@code (capabilityFamily, sourceTier)} — allow-always for every operation
 *       declaring that {@link io.justsearch.agent.api.registry.OperationPolicy#capabilityFamily() family}
 *       (the {@link Grant.CapabilityFamily} position, now realized — wider than one operation). The bare
 *       {@code CapabilityFamily} scope is fail-closed because op→family membership is not self-resolvable;
 *       this store IS the consumer that resolves it, via the family the executor reads off the policy.
 * </ul>
 *
 * <p>It is revocable (operation, family, or — for the Global Hard Stop — every non-user grant at once)
 * and audited via the one action-event log.
 *
 * <p><b>Risk ceiling (tempdoc 875 C.2).</b> Neither scope can cover a {@link RiskTier#HIGH} operation —
 * see {@link #isAllowed(String, Optional, RiskTier, EngineContext)}.
 *
 * <p><b>Persistence (560 §28).</b> Grants are persisted to {@code $JUSTSEARCH_HOME/ui/durable-grants.json}
 * so an allow-always survives a restart (previously process-lifetime only — a restart silently dropped
 * the grant, re-prompting the user). Persistence is mode-aware ({@link PersistenceMode}): READ_WRITE for
 * real use, IN_MEMORY for prod/CI isolation and tests (the no-arg constructors). Mirrors
 * {@code UiSettingsStore} / {@code FileConversationStore}; best-effort writes (a failure is logged,
 * never alters grant semantics).
 */
public final class DurableGrantStore {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  static final int CURRENT_SCHEMA_VERSION = 1;

  /**
   * An operation grant: allow-always for one operation from a source tier. The key is
   * args-independent because this store answers only "was a grant issued?"; whether the grant
   * reaches THESE arguments is {@link DurableGrantScope}'s question, asked by the executor
   * (tempdoc 875 C.3).
   */
  private record OperationKey(String operationId, SourceTier sourceTier) {}

  /** A family grant: allow-always for every operation in a capability family from a source tier. */
  private record FamilyKey(String family, SourceTier sourceTier) {}

  private final java.util.Set<OperationKey> grantedOps = ConcurrentHashMap.newKeySet();
  private final java.util.Set<FamilyKey> grantedFamilies = ConcurrentHashMap.newKeySet();
  private final Clock clock;
  /** Persistence target; {@code null} ⇒ in-memory only (tests / prod isolation). */
  private final Path persistenceFile;
  private volatile Consumer<ActionEvent> grantEventSink;

  public DurableGrantStore() {
    this(Clock.systemUTC());
  }

  public DurableGrantStore(Clock clock) {
    this(clock, null);
  }

  public DurableGrantStore(Clock clock, Path persistenceFile) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.persistenceFile = persistenceFile;
    load();
  }

  /**
   * 560 §28 — a persistence-backed store at the default location, mode-aware: READ_WRITE persists to
   * {@code $JUSTSEARCH_HOME/ui/durable-grants.json}; IN_MEMORY (prod/CI/test isolation) stays in-memory.
   */
  public static DurableGrantStore persistent() {
    return persistent(Clock.systemUTC());
  }

  public static DurableGrantStore persistent(Clock clock) {
    PersistenceMode mode = PersistenceMode.resolveMode();
    Path file = mode.isWritable() ? resolveDefaultGrantsFile() : null;
    return new DurableGrantStore(clock, file);
  }

  /** Wire the action-event log sink that records durable-grant lifecycle (tempdoc 550 thesis IV). */
  public void setGrantEventSink(Consumer<ActionEvent> sink) {
    this.grantEventSink = sink;
  }

  // ── Operation grants ────────────────────────────────────────────────────────────────────────────

  /** Record an allow-always grant for {@code operationId} at {@code sourceTier} (idempotent). */
  public void grantAllowAlways(String operationId, SourceTier sourceTier) {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(sourceTier, "sourceTier");
    if (grantedOps.add(new OperationKey(operationId, sourceTier))) {
      persist();
      emit("durable:" + sourceTier + ":" + operationId, operationId, "GRANTED_ALWAYS");
    }
  }

  /** Revoke a single operation allow-always grant. */
  public void revoke(String operationId, SourceTier sourceTier) {
    if (grantedOps.remove(new OperationKey(operationId, sourceTier))) {
      persist();
      emit("durable:" + sourceTier + ":" + operationId, operationId, "REVOKED");
    }
  }

  // ── Family grants (560 §28 / 4d) ──────────────────────────────────────────────────────────────────

  /** Record an allow-always grant for an entire capability {@code family} at {@code sourceTier}. */
  public void grantFamilyAllowAlways(String family, SourceTier sourceTier) {
    Objects.requireNonNull(family, "family");
    Objects.requireNonNull(sourceTier, "sourceTier");
    if (grantedFamilies.add(new FamilyKey(family, sourceTier))) {
      persist();
      emit("durable-family:" + sourceTier + ":" + family, family, "GRANTED_ALWAYS");
    }
  }

  /** Revoke a family allow-always grant. */
  public void revokeFamily(String family, SourceTier sourceTier) {
    if (grantedFamilies.remove(new FamilyKey(family, sourceTier))) {
      persist();
      emit("durable-family:" + sourceTier + ":" + family, family, "REVOKED");
    }
  }

  // ── Gate query ────────────────────────────────────────────────────────────────────────────────────

  /** True if an operation grant covers {@code operationId} at {@code risk} from {@code sourceTier}. */
  public boolean isAllowed(String operationId, RiskTier risk,
      io.justsearch.core.context.EngineContext engineContext) {
    return isAllowed(operationId, Optional.empty(), risk, engineContext);
  }

  /**
   * True if a durable grant covers {@code operationId} from {@code sourceTier} — either a per-operation
   * grant, or (when the operation declares one) a grant for its {@code capabilityFamily}. The executor
   * resolves the family off the operation's policy and passes it here; this is the consumer that makes
   * the {@link Grant.CapabilityFamily} scope real.
   *
   * <p><b>Risk ceiling (tempdoc 875 C.2).</b> A durable grant NEVER covers a {@link RiskTier#HIGH}
   * operation, whatever grant exists — destructive work always costs a fresh, args-bound gesture. This
   * restores agreement between issuance and enforcement: {@link IntentGateEvaluator#agentGate} already
   * carries the identical floor on the ISSUANCE side ("HIGH/destructive never auto-fires"), while
   * enforcement — this store, read by the executor's durable short-circuit — had none, so the two
   * disagreed. That is exactly the drift class tempdoc 550 thesis III exists to make impossible: ONE
   * verdict, no second computation that can diverge. The rule lives HERE rather than only at the
   * executor call-site so a future second consumer cannot re-open it, and so this store's own tests
   * pin it.
   *
   * <p><b>What is preserved.</b> 560 §28 (4d)'s {@code "file-operations"} family axis is untouched: a
   * family grant still auto-approves {@code core.ingest-files} (MEDIUM). Only the HIGH member
   * ({@code core.file-operations}) re-prompts — that member was added "so the axis is real", not
   * because the product wanted blanket destructive approval.
   */
  public boolean isAllowed(
      String operationId, Optional<String> capabilityFamily, RiskTier risk,
      io.justsearch.core.context.EngineContext engineContext) {
    SourceTier sourceTier = EngineProvenance.sourceTier(engineContext);
    Objects.requireNonNull(risk, "risk");
    if (risk == RiskTier.HIGH) {
      return false;
    }
    if (grantedOps.contains(new OperationKey(operationId, sourceTier))) {
      return true;
    }
    return capabilityFamily
        .map(family -> grantedFamilies.contains(new FamilyKey(family, sourceTier)))
        .orElse(false);
  }

  /**
   * Revoke every non-user ({@code UNTRUSTED}) durable grant — operation AND family — the consumer the
   * Global Hard Stop drives, matching the gate's hard-stop scope.
   */
  public void revokeNonUser() {
    boolean changed = false;
    for (OperationKey key : List.copyOf(grantedOps)) {
      if (key.sourceTier() == SourceTier.UNTRUSTED && grantedOps.remove(key)) {
        changed = true;
        emit(
            "durable:" + key.sourceTier() + ":" + key.operationId(),
            key.operationId(),
            "REVOKED");
      }
    }
    for (FamilyKey key : List.copyOf(grantedFamilies)) {
      if (key.sourceTier() == SourceTier.UNTRUSTED && grantedFamilies.remove(key)) {
        changed = true;
        emit(
            "durable-family:" + key.sourceTier() + ":" + key.family(),
            key.family(),
            "REVOKED");
      }
    }
    if (changed) {
      persist();
    }
  }

  // ── Read model ──────────────────────────────────────────────────────────────────────────────────

  /** The kind of durable grant — which caveat scope it realizes. */
  public enum GrantKind {
    OPERATION,
    FAMILY
  }

  /** A read-model row of one durable grant (operation or family) — for a management surface. */
  public record DurableGrant(GrantKind kind, String target, SourceTier sourceTier) {}

  /** A snapshot of all current durable grants (operation + family) — for a management view. */
  public List<DurableGrant> snapshot() {
    List<DurableGrant> out = new ArrayList<>(grantedOps.size() + grantedFamilies.size());
    for (OperationKey k : grantedOps) {
      out.add(new DurableGrant(GrantKind.OPERATION, k.operationId(), k.sourceTier()));
    }
    for (FamilyKey k : grantedFamilies) {
      out.add(new DurableGrant(GrantKind.FAMILY, k.family(), k.sourceTier()));
    }
    return List.copyOf(out);
  }

  // ── Persistence ─────────────────────────────────────────────────────────────────────────────────

  /** Serialized shape on disk: one flat list of grants (kind + target + tier). */
  private record PersistedState(int schemaVersion, List<DurableGrant> grants) {}

  private record LegacyPersistedState(List<DurableGrant> grants) {}

  private void load() {
    if (persistenceFile == null || !Files.exists(persistenceFile)) {
      return;
    }
    try {
      JsonNode root = MAPPER.readTree(persistenceFile.toFile());
      if (root == null || !root.isObject()) {
        throw new CorruptDurableStoreException("durable-grants", "JSON document is empty");
      }
      boolean versioned = root.has("schemaVersion");
      int observedVersion =
          versioned && root.get("schemaVersion").isIntegralNumber()
              ? root.get("schemaVersion").asInt()
              : versioned
                  ? -1
                  : 0;
      StoreFormatVersions.requireReadable(
          "durable-grants", observedVersion, CURRENT_SCHEMA_VERSION, 0, 0);
      List<DurableGrant> grants =
          versioned
              ? MAPPER.treeToValue(root, PersistedState.class).grants()
              : MAPPER.treeToValue(root, LegacyPersistedState.class).grants();
      if (grants == null) {
        throw new CorruptDurableStoreException("durable-grants", "grants payload is missing");
      }
      for (DurableGrant g : grants) {
        if (g.kind() == GrantKind.OPERATION) {
          grantedOps.add(new OperationKey(g.target(), g.sourceTier()));
        } else {
          grantedFamilies.add(new FamilyKey(g.target(), g.sourceTier()));
        }
      }
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "durable-grants", "cannot parse " + persistenceFile, e);
    }
  }

  private void persist() {
    if (persistenceFile == null) {
      return;
    }
    try {
      AtomicFileWrites.replace(
          persistenceFile,
          MAPPER.writeValueAsBytes(new PersistedState(CURRENT_SCHEMA_VERSION, snapshot())));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to persist durable grants to " + persistenceFile, e);
    }
  }

  /** Default grants file — a sibling of {@code settings.json}, resolved like {@code UiSettingsStore}. */
  private static Path resolveDefaultGrantsFile() {
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
    return base.resolve("ui").resolve("durable-grants.json");
  }

  private void emit(String idTail, String target, String action) {
    Consumer<ActionEvent> sink = this.grantEventSink;
    if (sink == null) {
      return;
    }
    try {
      sink.accept(
          new ActionEvent.Grant(
              "grant:" + action + ":" + idTail,
              clock.instant(),
              "user",
              "ALLOW_ALWAYS",
              idTail,
              action,
              target));
    } catch (RuntimeException ignored) {
      // Best-effort audit — never alter grant/revoke semantics (fail-closed discipline).
    }
  }
}
