/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Normalized, snapshot-bound configuration actually applied by the extraction and admission
 * owners.
 *
 * <p>The values in this record are passed directly to the extraction factory and skip-policy
 * singleton. They are also the diagnostic projection of those owners, so no digest-only defaults
 * are reconstructed elsewhere. Settings unused by {@link ExtractionSandboxFactory.Mode#IN_PROCESS}
 * are represented as {@code null}.
 */
public record ExtractionConfiguration(
    OcrRoutingConfig ocr,
    TikaExtractionPolicy tikaPolicy,
    ExtractionSandboxFactory.Mode sandboxMode,
    SandboxCommandOrigin sandboxCommandOrigin,
    List<String> sandboxCommand,
    String sandboxHeap,
    ExtractionSandboxFactory.PoolSettings sandboxPool,
    IngestionSkipPolicy ingestionSkipPolicy) {

  public ExtractionConfiguration {
    Objects.requireNonNull(ocr, "ocr");
    Objects.requireNonNull(tikaPolicy, "tikaPolicy");
    Objects.requireNonNull(sandboxMode, "sandboxMode");
    Objects.requireNonNull(sandboxCommandOrigin, "sandboxCommandOrigin");
    sandboxCommand = sandboxCommand == null ? null : List.copyOf(sandboxCommand);
    Objects.requireNonNull(ingestionSkipPolicy, "ingestionSkipPolicy");
    if (sandboxMode == ExtractionSandboxFactory.Mode.IN_PROCESS
        && (sandboxCommandOrigin != SandboxCommandOrigin.NONE
            || sandboxCommand != null
            || sandboxHeap != null
            || sandboxPool != null)) {
      throw new IllegalArgumentException("in-process extraction has no child sandbox settings");
    }
    if (sandboxMode != ExtractionSandboxFactory.Mode.IN_PROCESS
        && (sandboxCommandOrigin == SandboxCommandOrigin.NONE
            || sandboxCommand == null
            || sandboxPool == null)) {
      throw new IllegalArgumentException("process-routed extraction requires command and pool");
    }
    if (sandboxCommandOrigin == SandboxCommandOrigin.BUILT_IN && sandboxHeap == null) {
      throw new IllegalArgumentException("built-in sandbox command requires effective heap");
    }
    if (sandboxCommandOrigin == SandboxCommandOrigin.OPERATOR && sandboxHeap != null) {
      throw new IllegalArgumentException("operator sandbox command does not apply sandbox heap");
    }
  }

  /** Stable identity of the command source; built-in argv may contain an ephemeral argfile path. */
  public enum SandboxCommandOrigin {
    NONE,
    BUILT_IN,
    OPERATOR
  }

  /** Applies the existing owner normalizers to one immutable resolved snapshot. */
  public static ExtractionConfiguration capture(
      ResolvedConfig snapshot, int maxOcrWorkers, IngestionSkipPolicy ingestionSkipPolicy) {
    Objects.requireNonNull(snapshot, "snapshot");
    ResolvedConfig.Extraction declared = snapshot.extraction();
    OcrRoutingConfig ocr = OcrRoutingConfig.from(snapshot.ocr()).withWorkerLimit(maxOcrWorkers);
    TikaExtractionPolicy tikaPolicy = TikaExtractionPolicy.fromWorkerLimits(snapshot.worker());
    ExtractionSandboxFactory.Mode mode = parseMode(declared.sandboxMode());
    if (mode == ExtractionSandboxFactory.Mode.IN_PROCESS) {
      return new ExtractionConfiguration(
          ocr,
          tikaPolicy,
          mode,
          SandboxCommandOrigin.NONE,
          null,
          null,
          null,
          ingestionSkipPolicy);
    }

    String rawCommand = declared.sandboxCommand();
    boolean builtInCommand = rawCommand == null || rawCommand.isBlank();
    String heap = builtInCommand
        ? ExtractionSandboxCommand.heapSpec(tikaPolicy, declared.sandboxHeap())
        : null;
    List<String> command = builtInCommand
        ? ExtractionSandboxCommand.defaultCommand(tikaPolicy, declared.sandboxHeap())
        : ExtractionSandboxCommand.tokenize(rawCommand);
    ExtractionSandboxFactory.PoolSettings pool =
        new ExtractionSandboxFactory.PoolSettings(
            declared.sandboxPoolSize() == null ? 0 : declared.sandboxPoolSize(),
            declared.sandboxMaxRequestsPerChild() == null
                ? 0
                : declared.sandboxMaxRequestsPerChild());
    return new ExtractionConfiguration(
        ocr,
        tikaPolicy,
        mode,
        builtInCommand ? SandboxCommandOrigin.BUILT_IN : SandboxCommandOrigin.OPERATOR,
        command,
        heap,
        pool,
        ingestionSkipPolicy);
  }

  private static ExtractionSandboxFactory.Mode parseMode(String raw) {
    String mode = raw == null ? "" : raw.trim();
    if (mode.isEmpty() || "auto".equalsIgnoreCase(mode)) {
      return ExtractionSandboxFactory.Mode.AUTO;
    }
    if ("in_process".equalsIgnoreCase(mode)) {
      return ExtractionSandboxFactory.Mode.IN_PROCESS;
    }
    if ("process".equalsIgnoreCase(mode)) {
      return ExtractionSandboxFactory.Mode.PROCESS;
    }
    throw new IllegalStateException(
        "Unknown JUSTSEARCH_EXTRACTION_SANDBOX_MODE='"
            + mode
            + "': expected 'auto', 'in_process' or 'process'");
  }
}
