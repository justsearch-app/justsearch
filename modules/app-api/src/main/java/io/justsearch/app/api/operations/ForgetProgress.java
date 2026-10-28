/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.app.api.settings.SettingsWitness;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded evidence of an owned step; no path, prose, removal count or implicit completion. */
public record ForgetProgress(int version, Phase phase, boolean admitted, String installedToken,
    SettingsWitness settingsBase, String priorFileWitness, String installedFileWitness,
    boolean withdrawalRequested, Reason reason) {
  public enum Phase { PREPARED, ADMITTED, POLICY_ARMED, WITHDRAWAL_ARMED, POLICY_INSTALLED, REMOVAL_UNCONFIRMED,
    WAITING_FOR_PRODUCER, READY, SUPERSEDED, WITHDRAWN }
  public enum Reason { NONE, AUTHORITY_UNAVAILABLE, TRANSITION_ACTIVE, SCOPE_CHANGED,
    POLICY_UNKNOWN, OLD_PRODUCER, ACCEPTANCE_CHANGED, REMOVAL_FAILED, REMOVAL_UNKNOWN,
    SETTINGS_UNCOMMITTED, SUPERSEDED, WITHDRAWN }
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
      .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
      .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  public ForgetProgress {
    if (version != 1) throw new IllegalArgumentException("Unsupported forget progress");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(reason, "reason");
    if (phase == Phase.PREPARED && admitted
        || phase != Phase.PREPARED && phase != Phase.WITHDRAWN && !admitted
        || installedToken != null && !java.util.UUID.fromString(installedToken).toString().equals(installedToken)
        || (phase == Phase.POLICY_ARMED || phase == Phase.WITHDRAWAL_ARMED)
            && (settingsBase == null || priorFileWitness == null
            || installedFileWitness == null)
        || (phase == Phase.WITHDRAWAL_ARMED || phase == Phase.WITHDRAWN) && !withdrawalRequested
        || priorFileWitness != null && !priorFileWitness.matches("absent|[a-f0-9]{64}")
        || installedFileWitness != null && !installedFileWitness.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid forget progress evidence");
    }
  }

  public static ForgetProgress prepared() {
    return new ForgetProgress(1, Phase.PREPARED, false, null, null, null, null, false, Reason.NONE);
  }
  public String cursor() {
    String cursor = JSON.writeValueAsString(this);
    if (cursor.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
      throw new IllegalArgumentException("Forget progress exceeds checkpoint bound");
    }
    return cursor;
  }
  public static ForgetProgress read(String cursor) {
    if (cursor == null) return prepared();
    try { return JSON.readValue(cursor, ForgetProgress.class); }
    catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid forget progress"); }
  }
  public ForgetProgress with(Phase next, Reason why) {
    return new ForgetProgress(1, next, true, installedToken, settingsBase,
        priorFileWitness, installedFileWitness, withdrawalRequested, why);
  }

  public ForgetProgress requestWithdrawal() {
    return new ForgetProgress(1, phase, admitted, installedToken, settingsBase,
        priorFileWitness, installedFileWitness, true, reason);
  }
}
