/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;

import tools.jackson.databind.JsonNode;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for ingesting files into the knowledge index. Requires user approval (WRITE safety level).
 *
 * <p>Accepts file or folder paths. Folders are expanded recursively to individual files. After
 * ingestion, the Worker processes files for indexing, text extraction, and embedding.
 */
/**
 * Write-tier ingest tool. It is its own
 * {@link io.justsearch.agent.api.registry.OperationHandler}: the substrate dispatches
 * {@code execute(String): OperationResult} directly against this class.
 */
public final class IngestTool implements OperationHandler {
  private static final Logger LOG = LoggerFactory.getLogger(IngestTool.class);
  static final int MAX_PATHS = 100;

  private final IngestCallback ingestCallback;
  private final ScanRootCallback scanRootCallback;
  private final AgentToolPaths.RootsView rootsView;
  private final java.util.function.Function<EngineContext, List<IngestCollectionPolicy.RootBinding>> rootBindingsSupplier;

  /**
   * Constructs the agent ingest tool. Tempdoc 418 Phase B made the {@link ScanRootCallback}
   * mandatory; tempdoc 418 Phase C sub-commit A (Slice D, 2026-04-25) deleted the legacy 1- and
   * 2-arg back-compat constructors that defaulted the callback to a local-walk fallback.
   * Production wiring lives in {@code HeadAssembly}; tests pass a stub directly.
   */
  public IngestTool(
      IngestCallback ingestCallback,
      ScanRootCallback scanRootCallback,
      java.util.function.Function<EngineContext, List<BrowseTool.RootInfo>> rootsSupplier) {
    this(ingestCallback, scanRootCallback, rootsSupplier, engineContext -> List.of());
  }

  /**
   * Tempdoc 811 (C-2a) — adds the watched-root containment authority so an ad-hoc ingest inherits an
   * in-root path's collection instead of writing an unlabeled document. The 3-arg constructor keeps
   * the pre-811 shape for tests that do not exercise tagging (every path then resolves out-of-root).
   */
  public IngestTool(
      IngestCallback ingestCallback,
      ScanRootCallback scanRootCallback,
      java.util.function.Function<EngineContext, List<BrowseTool.RootInfo>> rootsSupplier,
      java.util.function.Function<EngineContext, List<IngestCollectionPolicy.RootBinding>> rootBindingsSupplier) {
    this(
        ingestCallback,
        scanRootCallback,
        AgentToolPaths.RootsView.of(rootsSupplier),
        rootBindingsSupplier);
  }

  /** Tempdoc 877 §2.4 — the shared roots view {@code AgentToolFactory.assemble} builds once. */
  public IngestTool(
      IngestCallback ingestCallback,
      ScanRootCallback scanRootCallback,
      AgentToolPaths.RootsView rootsView,
      java.util.function.Function<EngineContext, List<IngestCollectionPolicy.RootBinding>> rootBindingsSupplier) {
    this.ingestCallback = ingestCallback;
    this.scanRootCallback = scanRootCallback;
    this.rootsView = rootsView == null ? AgentToolPaths.RootsView.of(null) : rootsView;
    this.rootBindingsSupplier = rootBindingsSupplier;
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return OperationResult.failure("No arguments provided");
    }
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      JsonNode pathsNode = args.get("paths");
      if (pathsNode == null || !pathsNode.isArray() || pathsNode.isEmpty()) {
        return OperationResult.failure("Paths array is required and must not be empty");
      }
      if (pathsNode.size() > MAX_PATHS) {
        return OperationResult.failure(
            "Too many paths: "
                + pathsNode.size()
                + " exceeds limit of "
                + MAX_PATHS
                + ". Split into smaller batches.");
      }

      // Tempdoc 811 (C-2a) — optional caller-supplied collection, validated HERE (server side of the
      // MCP boundary) rather than relying on the advertised tool schema.
      String requestedCollection;
      try {
        requestedCollection =
            IngestCollectionPolicy.normalizeRequested(ToolArgs.stringArg(args, "collection"));
      } catch (IllegalArgumentException e) {
        return OperationResult.failure(e.getMessage());
      }
      List<IngestCollectionPolicy.RootBinding> rootBindings = rootBindings(engineContext);

      // Tempdoc 418 Phase B — directories dispatch to Worker-side ScanRoot RPC; only single
      // files keep the local submitBatch path. Worker-side WorkerIngestionAuthority applies
      // the same skip rules + caller exclude_globs (empty for the agent — agent has no exclude
      // policy).
      // Tempdoc 811 (C-2a): single files are grouped by resolved collection ("" = index default) so
      // one call can mix in-root and out-of-root paths. Pre-811 documents ingested through this tool
      // carry no collection field and are not backfilled; they acquire a tag on re-index.
      Map<String, List<Path>> singleFilesByCollection = new LinkedHashMap<>();
      int singleFileCount = 0;
      int skippedCount = 0;
      int directoryAccepted = 0;
      List<String> directoryErrors = new ArrayList<>();

      for (JsonNode pathNode : pathsNode) {
        Path input = resolvePath(pathNode.asText(), engineContext);
        if (input == null || !Files.exists(input)) {
          skippedCount++;
          continue;
        }
        String collection = IngestCollectionPolicy.resolve(requestedCollection, input, rootBindings);
        if (Files.isDirectory(input)) {
          // Tempdoc 877 §2.8 — bounded; an unresponsive Worker used to hold the agent loop thread
          // here indefinitely. Sized by toolScanMs, not toolFetchMs: this call blocks until a whole
          // directory tree has been walked Worker-side.
          KnowledgeIngestResponse scanResp =
              io.justsearch.agent.AgentTimeouts.call(
                  "core_ingest_files",
                  io.justsearch.agent.AgentTimeouts.toolScanMs(),
                  () -> scanRootCallback.scanRoot(input.toString(), collection, List.of(), engineContext));
          directoryAccepted += scanResp.accepted();
          if (scanResp.error() != null && !scanResp.error().isEmpty()) {
            directoryErrors.add(input + ":" + scanResp.error());
          }
        } else if (Files.isRegularFile(input) && Files.isReadable(input)) {
          singleFilesByCollection
              .computeIfAbsent(collection == null ? "" : collection, k -> new ArrayList<>())
              .add(input);
          singleFileCount++;
        } else {
          skippedCount++;
        }
      }

      if (singleFileCount == 0 && directoryAccepted == 0 && directoryErrors.isEmpty()) {
        return OperationResult.failure("No readable files found in the provided paths");
      }
      int singleAccepted = 0;
      List<String> singleErrors = new ArrayList<>();
      for (Map.Entry<String, List<Path>> group : singleFilesByCollection.entrySet()) {
        String collection = group.getKey().isEmpty() ? null : group.getKey();
        KnowledgeIngestResponse fileResp = ingestCallback.ingest(group.getValue(), collection, engineContext);
        singleAccepted += fileResp.accepted();
        if (fileResp.error() != null && !fileResp.error().isEmpty()) {
          singleErrors.add(fileResp.error());
        }
      }
      String singleError = String.join("; ", singleErrors);

      String combinedError =
          directoryErrors.isEmpty()
              ? singleError
              : (singleError.isEmpty()
                  ? String.join("; ", directoryErrors)
                  : singleError + "; " + String.join("; ", directoryErrors));
      KnowledgeIngestResponse response =
          new KnowledgeIngestResponse(directoryAccepted + singleAccepted, combinedError);

      return OperationResult.success(formatResult(response, skippedCount));

    } catch (Exception e) {
      return AgentToolErrors.classify("core_ingest_files", "Ingest error", e);
    }
  }

  /**
   * Resolves a path string to an absolute Path: absolute input is normalized; a root-relative one
   * goes through the ONE relative→absolute algorithm ({@code AgentToolPaths.RootsView#resolveRelative}
   * — first component matched against a root NAME), and whatever that misses falls back to the
   * existence-probe of resolving under each root in turn. {@code null} when nothing resolves, which
   * {@link #execute} reports as a skipped path.
   *
   * <p>Tempdoc 877 §2.4 — this used to end in {@code p.toAbsolutePath().normalize()}, resolving an
   * unmatched relative path against the JVM's working directory. That is never what the model meant
   * (it has no idea what the Head's cwd is) and it is the one behaviour in this cluster that could
   * address a file outside every indexed root. Removed: fail closed and say "skipped" instead.
   *
   * <p><b>Every arm is existence-checked, including the name-match one.</b>
   * {@code resolveRelative} matches the first component against a root NAME and returns WITHOUT
   * touching the filesystem, so its answer is a candidate, not a verdict. Returning it
   * unconditionally lets a root-name collision beat a path that actually exists: with roots
   * {@code ("C:\A\docs","docs")} and {@code ("C:\B","B")}, the input {@code "docs/x.md"} names the
   * first root while the file lives at {@code C:\B\docs\x.md} — the model's file is real, addressed
   * correctly, and reported as "1 paths skipped". So the name match is probed like any other
   * candidate and falls through when it misses.
   */
  private Path resolvePath(String raw, EngineContext engineContext) {
    try {
      Path p = Path.of(raw);
      if (p.isAbsolute()) {
        return p.normalize();
      }
      String resolved = rootsView.resolveRelative(raw, engineContext);
      if (resolved != null) {
        Path named = Path.of(resolved).normalize();
        if (Files.exists(named)) {
          return named;
        }
      }
      for (BrowseTool.RootInfo root : rootsView.roots(engineContext)) {
        Path candidate = Path.of(root.path()).resolve(p).normalize();
        if (Files.exists(candidate)) {
          return candidate;
        }
      }
      return null;
    } catch (RuntimeException e) {
      LOG.warn("Invalid path: '{}'", raw, e);
      return null;
    }
  }

  private String formatResult(KnowledgeIngestResponse response, int skippedPaths) {
    var sb = new StringBuilder();

    if (response.error() == null || response.error().isEmpty()) {
      sb.append(
          String.format("Ingested %d files successfully.", response.accepted()));
    } else {
      sb.append(
          String.format(
              "Ingest completed: %d accepted. Error: %s",
              response.accepted(), response.error()));
    }

    if (skippedPaths > 0) {
      sb.append(String.format(" (%d paths skipped — not found or not readable)", skippedPaths));
    }

    return sb.toString();
  }

  /**
   * Best-effort watched-root lookup for collection inheritance (tempdoc 811 C-2a). A failure here
   * means every path resolves out-of-root, which is a real tag rather than the pre-811 {@code null}.
   */
  private List<IngestCollectionPolicy.RootBinding> rootBindings(EngineContext engineContext) {
    try {
      List<IngestCollectionPolicy.RootBinding> bindings = rootBindingsSupplier.apply(engineContext);
      return bindings == null ? List.of() : bindings;
    } catch (RuntimeException e) {
      LOG.debug("watched-root lookup for ingest tagging failed", e);
      return List.of();
    }
  }

  /**
   * Callback for ingesting files into the knowledge index. Tempdoc 811 (C-2a) added the {@code
   * collection} tag ({@code null} = the index default).
   */
  @FunctionalInterface
  public interface IngestCallback {
    KnowledgeIngestResponse ingest(List<Path> files, String collection, EngineContext engineContext);
  }

  /**
   * Callback for dispatching a directory scan to the Worker. Tempdoc 418 Phase B —
   * production wiring delegates to {@code KnowledgeHttpApiAdapter.scanRoot}, which calls
   * the server-streaming {@code IngestService.ScanRoot} RPC. Tests can pass a
   * local-fallback. Tempdoc 811 (C-2a) added the {@code collection} tag.
   */
  @FunctionalInterface
  public interface ScanRootCallback {
    KnowledgeIngestResponse scanRoot(String rootPath, String collection, List<String> excludeGlobs, EngineContext engineContext);
  }
}
