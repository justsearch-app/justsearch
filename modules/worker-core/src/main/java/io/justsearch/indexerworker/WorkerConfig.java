/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker;

import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.RepoRootLocator;

public record WorkerConfig(
    Path dataDir,
    long telemetryFlushMs,
    String serviceVersion,
    Map<String, Object> ssotMetadata,
    String manifestHash,
    long nrtTargetMaxStaleMs) {

  /**
   * Builds the worker config from the globally-resolved config.
   *
   * <p>Public since lane F stage A item A6: the Engine composition root ({@code EngineRoot}) is now
   * a legitimate second caller. It reads the SAME {@code ConfigStore.global()} the Head resolved,
   * which is what "one config, no worker snapshot" means in practice — the snapshot tier is retired
   * at item A19, and nothing here depends on it.
   */
  public static WorkerConfig load() {
    ConfigStore cs = ConfigStore.globalOrNull();
    var rc = cs != null ? cs.get() : null;
    return load(rc);
  }

  /** Uses the exact snapshot captured for this physical index start attempt. */
  public static WorkerConfig load(io.justsearch.configuration.resolved.ResolvedConfig rc) {
    var wi = rc != null ? rc.workerIndexer() : null;

    if (wi == null) {
      throw new IllegalStateException("ConfigStore not initialized — cannot load WorkerConfig");
    }
    Path dataDir = rc.paths().dataDir();
    Integer nrt = rc.index().nrtTargetMaxStaleMs();
    long nrtTarget = nrt != null ? nrt : 500L;
    long telemetryFlush = rc.telemetry().flushMs();
    String version = wi.serviceVersion();
    Map<String, Object> metadata = new SsotCommitMetadataSource(rc).build();
    Path repoRoot = RepoRootLocator.findRepoRoot();
    String manifestHash = sha256(repoRoot.resolve("SSOT/manifests/repro/repro.v1.json"));
    return new WorkerConfig(
        dataDir, telemetryFlush, version, metadata, manifestHash, nrtTarget);
  }

  private static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = Files.readAllBytes(Objects.requireNonNull(file));
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Failed to compute SHA-256 for " + file, e);
    }
  }
}
