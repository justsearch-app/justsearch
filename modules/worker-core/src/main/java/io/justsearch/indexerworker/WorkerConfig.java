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
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.RepoRootLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record WorkerConfig(
    String host,
    int port,
    long deadlineMs,
    int queueSize,
    int maxInFlightBytes,
    Path dataDir,
    String collection,
    long telemetryFlushMs,
    String serviceVersion,
    Map<String, Object> ssotMetadata,
    String manifestHash,
    long nrtTargetMaxStaleMs,
    String backpressureMode) {

  private static final Logger log = LoggerFactory.getLogger(WorkerConfig.class);

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
    var wi = rc != null ? rc.workerIndexer() : null;

    if (wi == null) {
      throw new IllegalStateException("ConfigStore not initialized — cannot load WorkerConfig");
    }
    String host = wi.host();
    int port = wi.port();
    long deadlineMs = wi.deadlineMs();
    int queueSize = wi.queueSize();
    int maxInFlightBytes = wi.maxInFlightBytes();
    String backpressureMode = wi.backpressureMode();
    Path dataDir = rc.paths().dataDir();
    String collection = rc.search().collection();
    Integer nrt = rc.index().nrtTargetMaxStaleMs();
    long nrtTarget = nrt != null ? nrt : 500L;
    long telemetryFlush = rc.telemetry().flushMs();
    String version = EnvRegistry.INDEXER_WORKER_VERSION.getString("0.1.0-dev");
    Map<String, Object> metadata = new SsotCommitMetadataSource().build();
    Path repoRoot = RepoRootLocator.findRepoRoot();
    String manifestHash = sha256(repoRoot.resolve("SSOT/manifests/repro/repro.v1.json"));
    log.info(
        "Loaded indexer worker config host={} port={} queueSize={} maxInFlightBytes={}"
            + " deadlineMs={} collection={}",
        host, port, queueSize, maxInFlightBytes, deadlineMs, collection);
    return new WorkerConfig(
        host, port, deadlineMs, queueSize, maxInFlightBytes, dataDir, collection,
        telemetryFlush, version, metadata, manifestHash, nrtTarget, backpressureMode);
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
