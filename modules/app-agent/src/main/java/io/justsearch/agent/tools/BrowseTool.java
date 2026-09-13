/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;

import tools.jackson.databind.JsonNode;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.FolderBrowseRequest;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-only tool for browsing indexed folder structure. Auto-approved (no user gate).
 *
 * <p>Returns a compact text summary of folders for LLM consumption, including names, file counts,
 * and sizes.
 */
/**
 * Read-only folder-browse tool. It is its own
 * {@link io.justsearch.agent.api.registry.OperationHandler}: the substrate dispatches
 * {@code execute(String): OperationResult} directly against this class.
 */
public final class BrowseTool implements OperationHandler {
  private static final int DEFAULT_MAX_FOLDERS =
      Math.max(1, Math.min(200, resolveBrowseDefaultMaxFolders()));

  private static int resolveBrowseDefaultMaxFolders() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? cs.get().agent().browseDefaultMaxFolders() : 20;
  }
  private static final int MAX_MAX_FOLDERS = 200;

  /** Values that small LLMs commonly send when they mean "show top-level roots." */
  private static final Set<String> ROOT_SENTINELS =
      Set.of("/", ".", "..", "root", "roots", "top", "*");

  private static final int DEFAULT_MAX_FILES = 20;
  private static final int MAX_MAX_FILES = 200;

  private final BrowseCallback browseCallback;
  private final FilesCallback filesCallback;
  private final AgentToolPaths.RootsView rootsView;

  public BrowseTool(
      BrowseCallback browseCallback,
      FilesCallback filesCallback,
      java.util.function.Function<EngineContext, List<RootInfo>> rootsSupplier) {
    this(browseCallback, filesCallback, AgentToolPaths.RootsView.of(rootsSupplier));
  }

  /** Tempdoc 877 §2.4 — the shared roots view {@code AgentToolFactory.assemble} builds once. */
  public BrowseTool(
      BrowseCallback browseCallback,
      FilesCallback filesCallback,
      AgentToolPaths.RootsView rootsView) {
    this.browseCallback = browseCallback;
    this.filesCallback = filesCallback;
    this.rootsView = rootsView == null ? AgentToolPaths.RootsView.of(null) : rootsView;
  }

  /** Backward-compatible constructor without file listing support. */
  public BrowseTool(BrowseCallback browseCallback, java.util.function.Function<EngineContext, List<RootInfo>> rootsSupplier) {
    this(browseCallback, null, rootsSupplier);
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    try {
      // --- shared setup: parse all args ---
      String parentPath = null;
      int maxFolders = DEFAULT_MAX_FOLDERS;
      int maxFiles = DEFAULT_MAX_FILES;
      boolean listFiles = false;

      if (argumentsJson != null && !argumentsJson.isBlank()) {
        JsonNode args = ToolArgs.parse(argumentsJson);
        String rawParent = ToolArgs.stringArg(args, "parent_path");
        if (rawParent != null) {
          parentPath = rawParent.strip();
          if (parentPath.isEmpty() || ROOT_SENTINELS.contains(parentPath.toLowerCase())) {
            parentPath = null;
          }
        }
        maxFolders = ToolArgs.intArg(args, "max_folders", DEFAULT_MAX_FOLDERS, 1, MAX_MAX_FOLDERS);
        listFiles = ToolArgs.boolArg(args, "list_files");
        maxFiles = ToolArgs.intArg(args, "max_files", DEFAULT_MAX_FILES, 1, MAX_MAX_FILES);
      }

      // --- shared setup: resolve + validate path ---
      if (parentPath != null) {
        if (!AgentToolPaths.looksAbsolute(parentPath)) {
          String resolved = rootsView.resolveRelative(parentPath, engineContext);
          if (resolved != null) {
            parentPath = resolved;
          }
        }
        String rejection = rootsView.validate(parentPath, "parent_path", engineContext);
        if (rejection != null) {
          return OperationResult.failure(rejection);
        }
      }

      // --- branch: explicit file listing ---
      if (listFiles) {
        if (parentPath == null) {
          return OperationResult.failure(
              "list_files requires a parent_path. Omit list_files to see top-level roots.");
        }
        if (filesCallback == null) {
          return OperationResult.failure("File listing is not available.");
        }
        return executeFileList(parentPath, maxFiles, engineContext);
      }

      // --- branch: folder listing (with auto-fallback) ---
      return executeFolderList(parentPath, maxFolders, engineContext);

    } catch (Exception e) {
      return AgentToolErrors.classify("core_browse_folders", "Browse error", e);
    }
  }

  // Tempdoc 877 §2.8 — both Worker listings run under the shared fetch budget, so an unresponsive
  // Worker cannot hold the agent loop thread forever (it could, before this). The checked
  // TimeoutException rides out to execute()'s catch, which classifies it as a retryable failure.
  private OperationResult executeFolderList(String parentPath, int maxFolders, EngineContext engineContext) throws Exception {
    FolderBrowseResponse response;
    if (parentPath == null) {
      List<RootInfo> roots = rootsView.roots(engineContext);
      List<FolderBrowseResponse.Folder> folders =
          roots.stream()
              .map(r -> new FolderBrowseResponse.Folder(r.path(), r.name(), -1, -1, 0))
              .toList();
      response = new FolderBrowseResponse(folders, 0, false);
    } else {
      var request = new FolderBrowseRequest(parentPath, maxFolders);
      response =
          io.justsearch.agent.AgentTimeouts.call(
              "core_browse_folders", () -> browseCallback.listFolders(request, engineContext));
      if (response == null) {
        return OperationResult.failure("Browse returned no response");
      }
    }

    // Auto-fallback: empty folders → try files, fall back to hint on empty files
    if (response.folders().isEmpty() && parentPath != null && filesCallback != null) {
      FolderFilesResponse filesResponse = listFiles(parentPath, DEFAULT_MAX_FILES, engineContext);
      if (filesResponse != null && !filesResponse.files().isEmpty()) {
        return OperationResult.success(formatFileResults(filesResponse, parentPath, engineContext));
      }
      // files also empty — fall through to original formatResults() with hint logic
    }

    return OperationResult.success(formatResults(response, parentPath, engineContext));
  }

  private OperationResult executeFileList(String parentPath, int maxFiles, EngineContext engineContext) throws Exception {
    FolderFilesResponse filesResponse = listFiles(parentPath, maxFiles, engineContext);
    if (filesResponse == null) {
      return OperationResult.failure("File listing returned no response");
    }
    return OperationResult.success(formatFileResults(filesResponse, parentPath, engineContext));
  }

  private FolderFilesResponse listFiles(String parentPath, int maxFiles, EngineContext engineContext) throws Exception {
    return io.justsearch.agent.AgentTimeouts.call(
        "core_browse_folders",
        () -> filesCallback.listFiles(new FolderFilesRequest(parentPath, maxFiles, List.of()), engineContext));
  }

  private String formatResults(FolderBrowseResponse response, String parentPath, EngineContext engineContext) {
    List<FolderBrowseResponse.Folder> folders = response.folders();
    boolean hasParent = parentPath != null && !parentPath.isEmpty();
    if (folders.isEmpty()) {
      if (!hasParent) {
        return "No indexed folders found.";
      }
      String displayParent = toRelativePath(parentPath, engineContext);
      String msg = "No folders found under \"" + displayParent + "\".";
      if (!AgentToolPaths.looksAbsolute(parentPath)) {
        List<RootInfo> roots = rootsView.roots(engineContext);
        if (!roots.isEmpty()) {
          var hint = new StringBuilder(msg);
          hint.append(" HINT: Use a path starting with one of these root names:");
          for (RootInfo root : roots) {
            hint.append(" \"").append(root.name()).append("\"");
          }
          return hint.toString();
        }
      }
      return msg;
    }

    var sb = new StringBuilder();
    if (hasParent) {
      sb.append(String.format("Folders under \"%s\":%n", toRelativePath(parentPath, engineContext)));
    } else {
      sb.append(String.format("Top-level indexed folders:%n"));
    }

    for (int i = 0; i < folders.size(); i++) {
      var folder = folders.get(i);
      if (folder.fileCount() < 0) {
        sb.append(String.format("[%d] %s%n", i + 1, folder.name()));
      } else {
        String size = formatSize(folder.totalSizeBytes());
        sb.append(
            String.format(
                "[%d] %s (%d files, %s)%n", i + 1, folder.name(), folder.fileCount(), size));
      }
      sb.append(String.format("    Path: %s%n", toRelativePath(folder.path(), engineContext)));
    }

    sb.append(String.format("%nFound %d folders (took %dms).", folders.size(), response.tookMs()));
    if (response.truncated()) {
      sb.append(" Results truncated — increase max_folders or narrow parent_path.");
    }
    return sb.toString();
  }

  private String formatFileResults(FolderFilesResponse response, String parentPath, EngineContext engineContext) {
    List<FolderFilesResponse.FileEntry> files = response.files();
    String displayParent = toRelativePath(parentPath, engineContext);

    if (files.isEmpty()) {
      return String.format("No files found in \"%s\".", displayParent);
    }

    var sb = new StringBuilder();
    sb.append(String.format("Files in \"%s\":%n", displayParent));

    for (int i = 0; i < files.size(); i++) {
      Map<String, String> fields = files.get(i).fields();
      String filename = fields.getOrDefault("filename", "(unknown)");
      String path = fields.getOrDefault("path", "");
      String sizeStr = fields.getOrDefault("size_bytes", "");

      sb.append(String.format("[%d] %s", i + 1, filename));
      if (!sizeStr.isEmpty()) {
        try {
          sb.append(String.format(" (%s)", formatSize(Long.parseLong(sizeStr))));
        } catch (NumberFormatException e) {
          // skip size
        }
      }
      sb.append(String.format("%n"));
      if (!path.isEmpty()) {
        sb.append(String.format("    Path: %s%n", toRelativePath(path, engineContext)));
      }
    }

    sb.append(String.format("%nFound %d files", files.size()));
    if (response.totalCount() > files.size()) {
      sb.append(String.format(" (showing %d of %d total)", files.size(), response.totalCount()));
    }
    sb.append(String.format(" (took %dms).", response.tookMs()));
    return sb.toString();
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /**
   * Converts an absolute path to a relative path by stripping the indexed root prefix. Returns the
   * path unchanged if no root matches or if roots are unavailable.
   */
  String toRelativePath(String absolutePath, EngineContext engineContext) {
    List<RootInfo> roots = rootsView.roots(engineContext);
    if (roots.isEmpty()) {
      return absolutePath;
    }
    try {
      Path absPath = Path.of(absolutePath).normalize();
      for (RootInfo root : roots) {
        Path rootPath = Path.of(root.path()).normalize();
        if (absPath.startsWith(rootPath)) {
          Path relative = rootPath.relativize(absPath);
          if (relative.toString().isEmpty()) {
            return root.name();
          }
          return root.name() + "/" + relative.toString().replace('\\', '/');
        }
      }
    } catch (InvalidPathException e) {
      // Fall through
    }
    return absolutePath;
  }

  /** Lightweight root info returned by the roots supplier. */
  public record RootInfo(String path, String name) {}

  /** Callback for browsing the indexed folder structure. */
  @FunctionalInterface
  public interface BrowseCallback {
    FolderBrowseResponse listFolders(FolderBrowseRequest request, EngineContext engineContext);
  }

  /** Callback for listing individual files in a folder. */
  @FunctionalInterface
  public interface FilesCallback {
    FolderFilesResponse listFiles(FolderFilesRequest request, EngineContext engineContext);
  }
}
