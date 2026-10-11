/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.KeyLockedException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Tempdoc 629 (LAYER) — owns the data-encryption-key (DEK) lifecycle and exposes the read-only
 * {@link DataKeyState} the {@link io.justsearch.agent.api.encryption.StoreCipher} consumes.
 *
 * <p>State: {@code NOT_CONFIGURED} (no keystore) → {@code LOCKED} (keystore present, DEK not in
 * memory) → {@code UNLOCKED} (DEK in memory). On construction it loads the keystore; if one exists,
 * it starts LOCKED (lock-on-launch). The DEK lives in memory only while UNLOCKED and is zeroed on
 * {@link #lock()} — the honest software limit (629 §L1): it is scrapeable from RAM by same-user
 * malware during the unlocked window, so callers should lock when idle/closed.
 */
public final class DataKeyManager implements DataKeyState {

  public enum State {
    NOT_CONFIGURED,
    LOCKED,
    UNLOCKED
  }

  private final EncryptionKeystore keystore;
  private volatile KeystoreRecord record; // null = not configured
  private volatile byte[] dek; // null = locked

  /** Tempdoc 629 (LAYER) — fired on every state transition so caching stores reload/clear on
   * lock/unlock (e.g. {@code FileMemoryStore}, which eager-loads once at construction). */
  private final List<BiConsumer<State, State>> listeners = new CopyOnWriteArrayList<>();

  public DataKeyManager(EncryptionKeystore keystore) {
    this.keystore = Objects.requireNonNull(keystore, "keystore");
    this.record = keystore.load().orElse(null);
  }

  private DataKeyManager() {
    this.keystore = null;
    this.record = null;
  }

  /** Permanently NOT_CONFIGURED — for paths with no data dir (minimal/test boot). */
  public static DataKeyManager disabled() {
    return new DataKeyManager();
  }

  public synchronized State state() {
    if (record == null) {
      return State.NOT_CONFIGURED;
    }
    return dek == null ? State.LOCKED : State.UNLOCKED;
  }

  @Override
  public boolean enabled() {
    return record != null;
  }

  @Override
  public boolean locked() {
    return record != null && dek == null;
  }

  @Override
  public byte[] dek() {
    byte[] d = dek;
    if (d == null) {
      throw new KeyLockedException();
    }
    // Defensive copy: a concurrent lock() zeroes the backing array; handing out a clone keeps
    // in-flight crypto from reading a half-zeroed key (the SecretKeySpec copies it immediately).
    return d.clone();
  }

  /**
   * Tempdoc 629 (#7): the current keystore record (the passphrase- and recovery-wrapped DEK + KDF
   * params) for a portable export container, so the export decrypts offline with the passphrase or
   * recovery key. Not secret — it carries only wrapped key material. Null when not configured.
   */
  public synchronized KeystoreRecord keystoreRecord() {
    return record;
  }

  /** Subscribe to state transitions. Listener faults are isolated; the listener runs synchronously. */
  public void addListener(BiConsumer<State, State> listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  private void fire(State from, State to) {
    io.justsearch.core.status.StatusChangeCounter.process().changed(from, to);
    if (from == to) {
      return;
    }
    for (BiConsumer<State, State> l : listeners) {
      try {
        l.accept(from, to);
      } catch (RuntimeException e) {
        // a faulty listener must not break the key lifecycle
      }
    }
  }

  /** First-time setup: returns the one-time recovery key to display. Leaves the manager UNLOCKED. */
  public synchronized String setup(char[] passphrase) {
    if (keystore == null) {
      throw new IllegalStateException("at-rest encryption is not available (no data dir)");
    }
    if (record != null) {
      throw new IllegalStateException("at-rest encryption is already configured");
    }
    State before = state();
    EncryptionEnvelope.SetupResult r = EncryptionEnvelope.setup(passphrase);
    keystore.save(r.record());
    this.record = r.record();
    this.dek = EncryptionEnvelope.unlock(r.record(), passphrase);
    fire(before, state());
    return r.recoveryKey();
  }

  /** Unlock with the passphrase. @throws WrongPassphraseException on a wrong passphrase. */
  public synchronized void unlock(char[] passphrase) {
    requireConfigured();
    State before = state();
    setDek(EncryptionEnvelope.unlock(record, passphrase));
    fire(before, state());
  }

  /** Unlock with the recovery key. @throws WrongPassphraseException on a wrong key. */
  public synchronized void recover(String recoveryKey) {
    requireConfigured();
    State before = state();
    setDek(EncryptionEnvelope.recover(record, recoveryKey));
    fire(before, state());
  }

  /** Re-wrap under a new passphrase (the recovery key is preserved). Leaves the manager UNLOCKED. */
  public synchronized void changePassphrase(char[] oldPass, char[] newPass) {
    requireConfigured();
    State before = state();
    KeystoreRecord updated = EncryptionEnvelope.changePassphrase(record, oldPass, newPass);
    keystore.save(updated);
    this.record = updated;
    setDek(EncryptionEnvelope.unlock(updated, newPass));
    fire(before, state());
  }

  /**
   * Tempdoc 629 (#4): generate a NEW recovery key (re-wrapping the current DEK), returning it to show
   * once. Requires UNLOCKED (the DEK must be in memory). The passphrase wrap is untouched.
   */
  public synchronized String regenerateRecovery() {
    requireConfigured();
    byte[] d = dek;
    if (d == null) {
      throw new KeyLockedException();
    }
    EncryptionEnvelope.SetupResult r = EncryptionEnvelope.changeRecoveryKey(record, d);
    keystore.save(r.record());
    this.record = r.record();
    return r.recoveryKey();
  }

  /** Drop + zero the in-memory DEK (returns to LOCKED). No-op if not configured/already locked. */
  public synchronized void lock() {
    State before = state();
    byte[] d = dek;
    if (d != null) {
      Arrays.fill(d, (byte) 0);
    }
    dek = null;
    fire(before, state());
  }

  private void setDek(byte[] fresh) {
    byte[] old = dek;
    if (old != null) {
      Arrays.fill(old, (byte) 0);
    }
    dek = fresh;
  }

  private void requireConfigured() {
    if (record == null) {
      throw new IllegalStateException("at-rest encryption is not configured");
    }
  }
}
