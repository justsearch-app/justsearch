/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A checkpoint beside jobs.db, written BEFORE v13 is changed; no queue schema version is used. */
public final class Release030RecoveryState {
  private static final Logger log = LoggerFactory.getLogger(Release030RecoveryState.class);
  private static final String ROOT = "root|";
  private static final String QUARANTINED = "quarantined-root|";
  public static final String RETIREMENT_ROOT_KEY_PREFIX = "release030-root:";
  private static final String RETIRE = "retire|";
  private static final String RETIRE_PATH = "retire-path|";
  private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
  private static final String RECEIPT = RETIRE + "root-receipt|";
  private static final String HEAD = RETIRE + "root-head|";
  private static final String NEXT = RETIRE + "root-next|";
  private static final String REPLACING = RETIRE + "root-replacing|";
  private static final String REMOVAL = RETIRE + "removal|";
  private static final String EXPECTATION = RETIRE + "legacy-file|";
  private static final String CONVERSION = RETIRE + "prefix-conversion|";
  private String openedSession;
  private String admissionSet;
  private final Path file;
  private final Properties state;

  private Release030RecoveryState(Path file, Properties state) {
    this.file = file;
    this.state = state;
    String proof = state.getProperty(RETIRE + "queue-session");
    if (proof != null && proof.contains(":")) {
      int split = proof.lastIndexOf(':'); openedSession = proof.substring(0,split); admissionSet = proof.substring(split+1);
    }
  }

  static void capture(Path database) throws IOException {
    var existing = load(database);
    if (existing != null) {
      if (existing.prepared()) {
        existing.state.remove("prepared");
        existing.state.remove("database");
        existing.save(); // A restored actual v13 snapshot must prepare again.
      }
      return;
    }
    Properties state = new Properties();
    state.setProperty("format", "1");
    new Release030RecoveryState(path(database), state).save();
  }

  public static Release030RecoveryState load(Path database) throws IOException {
    Properties state = new Properties();
    Path file = path(database);
    try (var input = Files.newInputStream(file)) {
      state.load(input);
    } catch (NoSuchFileException absent) {
      return null;
    } catch (IllegalArgumentException invalid) {
      state.clear(); // An invalid escape makes even the database binding uncertain.
    }
    if (!"1".equals(state.getProperty("format"))) {
      log.warn("Invalid 0.3.0 recovery checkpoint; retaining debt and repeating preparation");
      state.clear();
      state.setProperty("format", "1");
      state.setProperty("uncertain", "true");
    }
    var recovery = new Release030RecoveryState(file, state);
    int invalidKeys = 0;
    for (String key : List.copyOf(state.stringPropertyNames())) {
      if (!key.startsWith(ROOT)) continue;
      try {
        if (Path.of(key.substring(ROOT.length())).isAbsolute()) continue;
      } catch (java.nio.file.InvalidPathException invalid) {
        // A valid properties escape can still decode to an invalid filesystem path.
      }
      var encoded = new StringBuilder();
      var hex = java.util.HexFormat.of();
      for (int i = 0; i < key.length(); i++) encoded.append(hex.toHexDigits(key.charAt(i)));
      state.setProperty(QUARANTINED + encoded, state.getProperty(key));
      state.remove(key);
      invalidKeys++;
    }
    if (invalidKeys > 0) {
      recovery.save(); // Move only invalid keys aside, once; retain their uninterpretable debt.
      log.warn("Quarantined {} invalid 0.3.0 recovery root keys; valid roots will continue", invalidKeys);
    }
    return recovery;
  }

  /** One shared instance belongs to the opened queue; reopening never inherits CURRENT Q proof. */
  public synchronized void openedSession(String session) { openedSession = session; admissionSet = null; }
  /** In-memory freshness is derived only by the queue owner under its mutation lock. */
  public synchronized void admissionsChanged() { admissionSet = null; }

  public synchronized void bindAdmissions(List<SwitchBufferCapableQueue.SwitchBufferOp> receipts) {
    try {
      byte[] bytes = JSON.writeValueAsString(receipts).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      admissionSet = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  public synchronized String admissionBinding() { return currentProof(); }

  private String currentProof() {
    return openedSession == null || admissionSet == null ? null : openedSession + ":" + admissionSet;
  }

  public synchronized List<SwitchBufferCapableQueue.SwitchBufferOp> removalPackets() throws IOException {
    var result = new java.util.ArrayList<SwitchBufferCapableQueue.SwitchBufferOp>();
    for (String key : state.stringPropertyNames()) if (key.startsWith(REMOVAL)) {
      var op = decodeReceipt(state.getProperty(key));
      if (!key.equals(REMOVAL + op.retirementUuid())) throw new IOException("Invalid removal packet identity");
      result.add(op);
    }
    return List.copyOf(result);
  }

  public synchronized SwitchBufferUpsert legacyExpectation(SwitchBufferCapableQueue.SwitchBufferOp op) throws IOException {
    String value = state.getProperty(EXPECTATION + op.retirementUuid());
    return value == null ? null : SwitchBufferUpsert.decode(value);
  }

  /** One atomic sidecar replacement precedes SQL removal; a packet is never an ACK-won bit. */
  public synchronized void retainRemovalPackets(List<SwitchBufferCapableQueue.SwitchBufferOp> verified,
      java.util.Map<String,SwitchBufferUpsert> legacyFiles) throws IOException {
    // Validate the union before replacing any previously retained representation.
    var combined = new java.util.ArrayList<>(removalPackets()); combined.addAll(verified);
    retirementReceipts(combined);
    var previousState = new Properties(); previousState.putAll(state);
    try {
    for (var op : verified) {
      state.setProperty(REMOVAL + op.retirementUuid(), JSON.writeValueAsString(op));
      var expectation = legacyFiles.get(op.retirementUuid());
      if (expectation != null) {
        String previous = state.getProperty(EXPECTATION + op.retirementUuid());
        if (previous != null && !SwitchBufferUpsert.decode(previous).equals(expectation))
          throw new IOException("Legacy UPSERT expectation changed");
        state.setProperty(EXPECTATION + op.retirementUuid(), expectation.encode());
      }
    }
    save();
    } catch (IOException | RuntimeException failed) { state.clear(); state.putAll(previousState); throw failed; }
  }

  public record PrefixConversion(SwitchBufferCapableQueue.SwitchBufferOp predecessor,
      SwitchBufferCapableQueue.SwitchBufferOp successor) {}

  public synchronized List<PrefixConversion> prefixConversions() throws IOException {
    var result = new java.util.ArrayList<PrefixConversion>();
    for (String key : state.stringPropertyNames()) if (key.startsWith(CONVERSION)) {
      var pair = JSON.readValue(state.getProperty(key), PrefixConversion.class);
      validateConversion(pair);
      if (!key.equals(CONVERSION + pair.predecessor().retirementUuid())) throw new IOException("Invalid prefix conversion key");
      result.add(pair);
    }
    return List.copyOf(result);
  }

  private static void validateConversion(PrefixConversion pair) throws IOException {
    var before = pair.predecessor(); var after = pair.successor();
    before.retirementUuid(); after.retirementUuid();
    if (!"DELETE_PREFIX".equals(before.op()) || !before.op().equals(after.op())
        || !before.generation().equals(after.generation()) || !before.key().equals(after.key())
        || before.lastUpdatedMs()!=after.lastUpdatedMs() || before.acceptedOrder()!=after.acceptedOrder()
        || before.revision().equals(after.revision())
        || !io.justsearch.adapters.lucene.runtime.PathPrefixScope.isLegacy(before.payload())
        || io.justsearch.adapters.lucene.runtime.PathPrefixScope.isLegacy(after.payload())
        || !PathNormalizer.normalizePathPrefix(before.payload()).equals(
            io.justsearch.adapters.lucene.runtime.PathPrefixScope.decode(after.payload()).prefix()))
      throw new IOException("Unbound legacy prefix conversion");
  }

  public synchronized SwitchBufferCapableQueue.SwitchBufferOp conversionSuccessor(
      SwitchBufferCapableQueue.SwitchBufferOp predecessor) throws IOException {
    for (var pair : prefixConversions()) if (pair.predecessor().retirementUuid().equals(predecessor.retirementUuid())) {
      if (!pair.predecessor().equals(predecessor)) throw new IOException("Legacy prefix predecessor changed");
      return pair.successor();
    }
    return null;
  }

  public synchronized void retainPrefixConversion(SwitchBufferCapableQueue.SwitchBufferOp predecessor,
      SwitchBufferCapableQueue.SwitchBufferOp successor) throws IOException {
    var pair = new PrefixConversion(predecessor,successor); validateConversion(pair);
    var prior = conversionSuccessor(predecessor);
    if (prior != null && !prior.equals(successor)) throw new IOException("Prefix conversion changed");
    state.setProperty(CONVERSION + predecessor.retirementUuid(), JSON.writeValueAsString(pair));
    save();
  }

  /** Ordered UUID union; only an explicitly bound legacy conversion may share a position. */
  public synchronized List<SwitchBufferCapableQueue.SwitchBufferOp> retirementReceipts(
      List<SwitchBufferCapableQueue.SwitchBufferOp> actual) throws IOException {
    var conversions = prefixConversions();
    var all = new java.util.ArrayList<>(rootReceipts());
    all.addAll(removalPackets()); all.addAll(committedDeletes()); all.addAll(actual);
    for (var pair : conversions) { all.add(pair.predecessor()); all.add(pair.successor()); }
    var byUuid = new java.util.HashMap<String,SwitchBufferCapableQueue.SwitchBufferOp>();
    for (var op : all) {
      op.retirementUuid();
      var previous = byUuid.putIfAbsent(op.retirementUuid(), op);
      if (previous != null && !previous.equals(op)) throw new IOException("Conflicting retirement receipt UUID");
    }
    for (var pair : conversions) byUuid.remove(pair.predecessor().retirementUuid());
    var result = new java.util.ArrayList<>(byUuid.values());
    result.sort(java.util.Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder));
    long previous = 0;
    for (var op : result) {
      if (op.acceptedOrder()<=previous) throw new IOException("Conflicting retirement admission order");
      previous = op.acceptedOrder();
    }
    return List.copyOf(result);
  }

  public synchronized long retainedOrderFloor() throws IOException {
    var receipts = retirementReceipts(List.of());
    return receipts.isEmpty() ? 0 : receipts.getLast().acceptedOrder();
  }

  public synchronized SwitchBufferCapableQueue.SwitchBufferOp requiredRootHead(String generation,String key) throws IOException {
    String uuid = state.getProperty(HEAD + headKey(generation,key));
    return uuid == null ? null : decodeReceipt(state.getProperty(RECEIPT + uuid));
  }

  public synchronized String session() { return openedSession; }
  public synchronized boolean currentSession() {
    return currentProof() != null && currentProof().equals(state.getProperty(RETIRE + "queue-session"));
  }
  public synchronized void queuePrepared() throws IOException {
    state.setProperty(RETIRE + "queue-session", java.util.Objects.requireNonNull(currentProof(), "Unbound current queue proof"));
    save();
  }
  public synchronized boolean rootsInventoried() { return "true".equals(state.getProperty(RETIRE + "root-inventory")); }
  public synchronized java.util.List<SwitchBufferCapableQueue.SwitchBufferOp> rootReceipts() throws IOException {
    var receipts = new java.util.ArrayList<SwitchBufferCapableQueue.SwitchBufferOp>();
    for (String key : state.stringPropertyNames()) if (key.startsWith(RECEIPT)) {
      var receipt = decodeReceipt(state.getProperty(key));
      if (!"SYNC_ROOT".equals(receipt.op()) || !key.equals(RECEIPT + receipt.retirementUuid()))
        throw new IOException("Invalid retained ROOT receipt");
      receipts.add(receipt);
    }
    receipts.sort(java.util.Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder));
    return java.util.List.copyOf(receipts);
  }
  public synchronized java.util.List<SwitchBufferCapableQueue.SwitchBufferOp> committedDeletes() throws IOException {
    var result = new java.util.ArrayList<SwitchBufferCapableQueue.SwitchBufferOp>();
    for (String key : state.stringPropertyNames()) if (key.startsWith(RETIRE + "delete|") && !key.endsWith("|queue"))
      result.add(decodeReceipt(state.getProperty(key)));
    return java.util.List.copyOf(result);
  }
  private static SwitchBufferCapableQueue.SwitchBufferOp decodeReceipt(String value) throws IOException {
    try {
      var receipt = JSON.readValue(value, SwitchBufferCapableQueue.SwitchBufferOp.class);
      receipt.retirementUuid();
      return receipt;
    }
    catch (RuntimeException invalid) { throw new IOException("Unbound or invalid retirement receipt", invalid); }
  }
  private static String headKey(String generation, String key) {
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        (generation + "\0" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  /** Save the predecessor BEFORE the queue transaction can replace its coalesced SQL head. */
  public synchronized void replacingRoot(String generation, String key,
      SwitchBufferCapableQueue.SwitchBufferOp predecessor) throws IOException {
    if (predecessor != null) retainRoot(predecessor);
    state.setProperty(REPLACING + headKey(generation, key), predecessor == null ? "new" : predecessor.retirementUuid());
    save();
  }
  public synchronized void retainRootCollection(SwitchBufferCapableQueue.SwitchBufferOp root,String collection) throws IOException {
    if (!rootReceipts().contains(root)) throw new IOException("ROOT collection lacks retained intent");
    if (collection != null && !collection.isBlank()) {
      state.setProperty(RETIRE + "root-collection|" + root.retirementUuid(),collection);save();
    }
  }
  public synchronized String rootCollection(SwitchBufferCapableQueue.SwitchBufferOp root) {
    return state.getProperty(RETIRE + "root-collection|" + root.retirementUuid());
  }
  private void retainRoot(SwitchBufferCapableQueue.SwitchBufferOp receipt) {
    state.setProperty(RECEIPT + receipt.retirementUuid(), JSON.writeValueAsString(receipt));
  }
  public synchronized void retainRootHead(SwitchBufferCapableQueue.SwitchBufferOp receipt) throws IOException {
    String key = headKey(receipt.generation(), receipt.key());
    String previous = state.getProperty(HEAD + key);
    retainRoot(receipt);
    if (previous != null && !previous.equals(receipt.retirementUuid())) state.setProperty(NEXT + previous, receipt.retirementUuid());
    state.setProperty(HEAD + key, receipt.retirementUuid());
    state.remove(REPLACING + key);
    save();
  }
  public synchronized void inventoryRoots(java.util.List<SwitchBufferCapableQueue.SwitchBufferOp> roots,
      java.util.Map<String,SwitchBufferUpsert> legacyFiles) throws IOException {
    if (rootsInventoried()) throw new IOException("ROOT inventory already exists");
    for (var root : roots) {
      retainRoot(root);
      state.setProperty(HEAD + headKey(root.generation(), root.key()), root.retirementUuid());
    }
    for (var entry : legacyFiles.entrySet()) state.setProperty(EXPECTATION + entry.getKey(),entry.getValue().encode());
    state.setProperty(RETIRE + "root-inventory", "true");
    save();
  }
  /** A crash may leave exactly the committed replacement after a saved predecessor intent. */
  public synchronized void validateRootHeads(java.util.List<SwitchBufferCapableQueue.SwitchBufferOp> actual) throws IOException {
    if (!rootsInventoried()) throw new IOException("Missing retirement ROOT inventory");
    var heads = new java.util.HashMap<String,SwitchBufferCapableQueue.SwitchBufferOp>();
    for (var root : actual) heads.put(headKey(root.generation(),root.key()),root);
    for (String key : java.util.List.copyOf(state.stringPropertyNames())) if (key.startsWith(REPLACING)) {
      String head = key.substring(REPLACING.length());var surviving = heads.get(head);
      if (surviving == null) throw new IOException("Missing interrupted ROOT replacement");
      String predecessor = state.getProperty(key);
      if (!predecessor.equals("new")) {
        var prior = decodeReceipt(state.getProperty(RECEIPT + predecessor));
        if (predecessor.equals(surviving.retirementUuid())) {
          if (!prior.equals(surviving)) throw new IOException("Interrupted ROOT predecessor changed");
        } else if (prior.acceptedOrder() >= surviving.acceptedOrder()) {
          throw new IOException("Stale ROOT replacement");
        }
      }
      retainRootHead(surviving);
    }
    for (String key : state.stringPropertyNames()) if (key.startsWith(HEAD)) {
      String head = key.substring(HEAD.length());String required = state.getProperty(key);
      var current = heads.get(head);
      var expected = decodeReceipt(state.getProperty(RECEIPT + required));
      if (current == null) {
        if (!removalPackets().contains(expected)) throw new IOException("Required latest ROOT receipt missing or changed");
      } else if (!current.equals(expected)) throw new IOException("Required latest ROOT receipt missing or changed");
    }
    for (var root : actual) if (!state.containsKey(HEAD + headKey(root.generation(),root.key())))
      throw new IOException("ROOT head has no retained inventory or replacement intent");
    var byUuid = new java.util.HashMap<String,SwitchBufferCapableQueue.SwitchBufferOp>();
    for (var root : rootReceipts()) byUuid.put(root.retirementUuid(),root);
    for (var root : byUuid.values()) {
      String required = state.getProperty(HEAD + headKey(root.generation(),root.key()));
      var seen = new java.util.HashSet<String>();var cursor = root;
      while (!cursor.retirementUuid().equals(required)) {
        if (!seen.add(cursor.retirementUuid())) throw new IOException("Cyclic ROOT receipt history");
        var next = byUuid.get(state.getProperty(NEXT + cursor.retirementUuid()));
        if (next == null || next.acceptedOrder() <= cursor.acceptedOrder()
            || !next.key().equals(root.key())
            || !next.generation().equals(root.generation()))
          throw new IOException("Incomplete ROOT successor chain");
        cursor = next;
      }
    }
  }

  public boolean prepared() {
    return "true".equals(state.getProperty("prepared"));
  }

  /** A restored/replaced database cannot borrow another database's preparation receipt. */
  public boolean preparedFor(String databaseIdentity) {
    return prepared() && databaseIdentity.equals(state.getProperty("database"));
  }

  /** Called after the atomic boundary backfill/retry repair and before the first job claim. */
  public synchronized void prepared(Collection<Path> roots, String databaseIdentity) throws IOException {
    boolean uncertain = "true".equals(state.getProperty("uncertain"));
    var quarantine = new Properties();
    for (String key : state.stringPropertyNames()) {
      if (key.startsWith(QUARANTINED) || key.startsWith(RETIRE) || key.startsWith(RETIRE_PATH))
        quarantine.setProperty(key, state.getProperty(key));
    }
    state.clear();
    state.putAll(quarantine);
    state.setProperty("format", "1");
    state.setProperty("database", databaseIdentity);
    for (Path root : roots) {
      String key = PathNormalizer.normalizeKey(root);
      state.setProperty(ROOT + key, "pending");
      if (uncertain) state.setProperty("retry|" + key, Long.toString(System.currentTimeMillis() + 60_000L));
    }
    state.setProperty("prepared", "true");
    save();
  }

  public List<Path> pendingRoots() {
    return state.stringPropertyNames().stream()
        .filter(key -> key.startsWith(ROOT) && !"done".equals(state.getProperty(key)))
        .sorted().map(key -> Path.of(key.substring(ROOT.length()))).toList();
  }

  /** The directory walk has durably admitted its work and committed confirmed deletions. */
  public synchronized void completed(Path root) throws IOException {
    state.setProperty(ROOT + PathNormalizer.normalizeKey(root), "done");
    save();
  }

  /** Failed evidence is durable debt, with a cooldown across ticks and process restarts. */
  public synchronized void deferred(Path root, long now) throws IOException {
    state.setProperty("retry|" + PathNormalizer.normalizeKey(root), Long.toString(now + 60_000L));
    save();
  }

  public synchronized boolean ready(Path root, long now) throws IOException {
    String key = PathNormalizer.normalizeKey(root);
    if (!"pending".equals(state.getProperty(ROOT + key))) {
      state.setProperty(ROOT + key, "pending");
      deferred(root, now);
      return false;
    }
    try {
      long deadline = Long.parseLong(state.getProperty("retry|" + key, "0"));
      if (deadline < 0 || deadline > now + 60_000L) throw new NumberFormatException("Invalid cooldown");
      return now >= deadline;
    } catch (NumberFormatException invalid) {
      // Bad evidence never authorizes this root's prune, nor starves another root. Replace the
      // corrupt timer with one bounded, durable wait; do not repeat it on every tick/restart.
      deferred(root, now);
      return false;
    }
  }

  public synchronized boolean finish() throws IOException {
    if (retiringBuild() || !prepared() || !pendingRoots().isEmpty()
        || state.stringPropertyNames().stream().anyMatch(key -> key.startsWith(QUARANTINED))) return false;
    Files.deleteIfExists(file);
    return true;
  }

  public boolean retiringBuild() { return state.containsKey(RETIRE + "active"); }
  public String retiringActive() { return state.getProperty(RETIRE + "active"); }
  public String retiringBuilding() { return state.getProperty(RETIRE + "building"); }
  public boolean rootsJournaled() { return "true".equals(state.getProperty(RETIRE + "roots-journaled")); }

  public synchronized void markRootsJournaled() throws IOException {
    state.setProperty(RETIRE + "roots-journaled", "true");
    save();
  }

  /** Publish completed root walks before acknowledging their ordered journal versions. */
  public synchronized void completeRetirementRoots() throws IOException {
    for (Path root : pendingRoots()) state.setProperty(ROOT + PathNormalizer.normalizeKey(root), "done");
    save();
  }

  public boolean replayComplete() { return currentProof() != null && currentProof().equals(state.getProperty(RETIRE + "replayed")); }
  public boolean differencesComplete() { return currentProof()!=null && currentProof().equals(state.getProperty(RETIRE + "differences")); }

  public boolean rootEnumerated(SwitchBufferCapableQueue.SwitchBufferOp root) {
    return currentProof() != null && currentProof().equals(state.getProperty(RETIRE + "root-enum|" + root.retirementUuid()));
  }
  public synchronized void markRootEnumerated(SwitchBufferCapableQueue.SwitchBufferOp root) throws IOException {
    state.setProperty(RETIRE + "root-enum|" + root.retirementUuid(),java.util.Objects.requireNonNull(currentProof(), "Unbound current queue proof"));save();
  }
  public boolean rootWalked(SwitchBufferCapableQueue.SwitchBufferOp op) {
    return currentProof() != null && currentProof().equals(state.getProperty(rootWalkKey(op)));
  }

  /** The exact walk has committed its prune and durably admitted its files, before their writer runs. */
  public synchronized void markRootWalked(SwitchBufferCapableQueue.SwitchBufferOp op) throws IOException {
    state.setProperty(rootWalkKey(op), java.util.Objects.requireNonNull(currentProof(), "Unbound current queue proof"));
    save();
  }

  public boolean deletionCommitted(SwitchBufferCapableQueue.SwitchBufferOp op) {
    String value = state.getProperty(deletionKey(op));
    if (value == null) return false;
    try { return decodeReceipt(value).equals(op); }
    catch (IOException invalid) { throw new IllegalStateException("Unbound historical delete proof", invalid); }
  }

  public boolean deletionQueueComplete(SwitchBufferCapableQueue.SwitchBufferOp op) {
    return deletionCommitted(op) && currentProof() != null
        && currentProof().equals(state.getProperty(deletionKey(op) + "|queue"));
  }

  /** Historical A effect and CURRENT Q removal are separate even after supported DB replacement. */
  public synchronized void markDeletionCommitted(SwitchBufferCapableQueue.SwitchBufferOp receipt) throws IOException {
    state.setProperty(deletionKey(receipt), JSON.writeValueAsString(receipt));
    markDeletionQueueComplete(receipt);
  }
  public synchronized void markDeletionQueueComplete(SwitchBufferCapableQueue.SwitchBufferOp op) throws IOException {
    state.setProperty(deletionKey(op) + "|queue", java.util.Objects.requireNonNull(currentProof(), "Unbound current queue proof"));
    save();
  }

  private static String deletionKey(SwitchBufferCapableQueue.SwitchBufferOp op) {
    return rootWalkKey(op).replace(RETIRE + "walk|", RETIRE + "delete|");
  }

  private static String rootWalkKey(SwitchBufferCapableQueue.SwitchBufferOp op) {
    try {
      String identity = op.generation() + "\u0000" + op.key() + "\u0000" + op.revision() + "\u0000" + op.lastUpdatedMs();
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return RETIRE + "walk|" + java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }

  public synchronized void markDifferencesComplete() throws IOException {
    state.setProperty(RETIRE + "differences", java.util.Objects.requireNonNull(currentProof()));
    save();
  }

  /** Written before changing state.json; all A/B differences remain obligations until commit. */
  public synchronized void beginRetirement(String active, String building, java.util.Map<Path, String> paths)
      throws IOException {
    if (retiringBuild()) throw new IOException("Release retirement already checkpointed");
    state.setProperty(RETIRE + "active", active);
    state.setProperty(RETIRE + "building", building);
    for (var entry : paths.entrySet()) state.setProperty(
        RETIRE_PATH + PathNormalizer.normalizeKey(entry.getKey()), entry.getValue());
    save();
  }

  public java.util.Map<Path, String> retirementPaths() throws IOException {
    var paths = new java.util.LinkedHashMap<Path, String>();
    try {
      for (String key : state.stringPropertyNames().stream().sorted().toList()) {
        if (!key.startsWith(RETIRE_PATH)) continue;
        Path path = Path.of(key.substring(RETIRE_PATH.length()));
        if (!path.isAbsolute()) throw new IOException("Invalid release retirement path");
        paths.put(path, state.getProperty(key));
      }
    } catch (java.nio.file.InvalidPathException invalid) {
      throw new IOException("Invalid release retirement path", invalid);
    }
    return java.util.Map.copyOf(paths);
  }

  public synchronized void markReplayComplete() throws IOException {
    state.setProperty(RETIRE + "replayed", java.util.Objects.requireNonNull(currentProof(), "Unbound current queue proof"));
    save();
  }

  public synchronized void finishRetirement() throws IOException {
    for (String key : List.copyOf(state.stringPropertyNames())) {
      if (key.startsWith(RETIRE) || key.startsWith(RETIRE_PATH)) state.remove(key);
    }
    save();
  }

  private void save() throws IOException {
    var bytes = new ByteArrayOutputStream();
    state.store(bytes, "0.3.0 queued-work recovery; incomplete roots resume after restart");
    AtomicFileWrites.replaceStrict(file, bytes.toByteArray());
  }

  static Path path(Path database) {
    return database.resolveSibling("upgrade-030-recovery.properties");
  }
}
