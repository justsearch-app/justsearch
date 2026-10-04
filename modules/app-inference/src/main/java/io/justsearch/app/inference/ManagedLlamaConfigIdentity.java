/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Stable identities for declared llama configuration and the realized launch command. */
public final class ManagedLlamaConfigIdentity {
  private static final String FIXED_FLAGS_VERSION = "llama-fixed-flags-v1";

  private ManagedLlamaConfigIdentity() {}

  public static String declaredHash(InferenceConfig cfg, ResolvedConfig rc, int effectiveGpuLayers) {
    return hash(
        List.of(
            ManagedChild.normalizePath(cfg.serverExecutable()),
            ManagedChild.normalizePath(cfg.modelPath()),
            cfg.mmprojPath() == null ? "" : ManagedChild.normalizePath(cfg.mmprojPath()),
            Integer.toString(cfg.serverPort()),
            Integer.toString(effectiveGpuLayers),
            Boolean.toString(cfg.vduMode()),
            Integer.toString(rc.ai().contextSize()),
            Boolean.toString(rc.ai().useThinking()),
            Integer.toString(rc.ai().reasoningBudget()),
            Integer.toString(rc.ai().llmSlots()),
            rc.ai().llmKvType(),
            FIXED_FLAGS_VERSION));
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
