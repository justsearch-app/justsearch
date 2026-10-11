/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Stable identities for declared llama configuration and the realized launch command.
 *
 * <p>The declared hash means "the same model and launch flags" (reconciliation ruling CH2). It
 * excludes the HTTP port: every managed launch binds a fresh loopback port (ADR-0051 decision 2),
 * so a port in the hash would make two launches of one configuration look different and break the
 * strict-apply, rollback and recovery comparisons.
 */
public final class ManagedLlamaConfigIdentity {
  private static final String FIXED_FLAGS_VERSION = "llama-fixed-flags-v1";

  private ManagedLlamaConfigIdentity() {}

  public static String declaredHash(InferenceConfig cfg, ResolvedConfig rc, int effectiveGpuLayers) {
    List<String> declared = new java.util.ArrayList<>(
        List.of(
            ManagedChild.normalizePath(cfg.serverExecutable()),
            ManagedChild.normalizePath(cfg.modelPath()),
            cfg.mmprojPath() == null ? "" : ManagedChild.normalizePath(cfg.mmprojPath()),
            Integer.toString(effectiveGpuLayers),
            Boolean.toString(cfg.vduMode()),
            Integer.toString(cfg.promptCacheMiB()),
            Integer.toString(rc.ai().contextSize()),
            Boolean.toString(rc.ai().useThinking()),
            Integer.toString(rc.ai().reasoningBudget()),
            Integer.toString(rc.ai().llmSlots()),
            rc.ai().llmKvType(),
            FIXED_FLAGS_VERSION));
    // Spec E step E7b: the projector kept on the CPU changes the launch line. Appended only then,
    // so every identity recorded before the plan existed is unchanged.
    if (cfg.mmprojPath() != null && !rc.ai().mmprojOffload()) declared.add("no-mmproj-offload");
    return hash(declared);
  }

  public static String realizedArgvHash(List<String> argv) {
    return hash(argv);
  }

  private static String hash(List<String> values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
