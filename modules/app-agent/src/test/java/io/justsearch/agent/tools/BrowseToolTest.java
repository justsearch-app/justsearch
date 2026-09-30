package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// Windows-specific: asserts Windows path-form semantics (drive-letter absolutes,
// backslash/unix-slash handling, rooted-path resolution). The tool logic is
// cross-platform; these assertions encode Windows behavior, so they run on the
// windows-native lane (tempdoc 668 option B).
@Tag("windows")
class BrowseToolTest {

  // The shared fixtures supply real roots that cover the parent paths these tests browse, so the
  // path validation they now pass through is exercised rather than short-circuited by an empty
  // roots list. Each test asserts exactly what it asserted before (formatting, capping,
  // truncation, hints); only the paths moved from unrooted ("/docs") to rooted ("D:\data\docs").
  private static final List<BrowseTool.RootInfo> FIXTURE_ROOTS =
      List.of(
          new BrowseTool.RootInfo("D:\\data", "data"),
          new BrowseTool.RootInfo("D:\\Documents", "Documents"));

  private static BrowseTool browseOnly(BrowseTool.BrowseCallback cb) {
    return new BrowseTool(cb, context -> FIXTURE_ROOTS);
  }

  @Test
  void executeWithParentPath() {
    var capturedParent = new AtomicReference<String>();
    var tool =
        browseOnly(
            (req, context) -> {
              capturedParent.set(req.parentPath());
              return new FolderBrowseResponse(
                  List.of(new FolderBrowseResponse.Folder("/docs/sub", "sub", 3, 512, 0)),
                  2,
                  false);
            });

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals("D:\\data\\docs", capturedParent.get());
    assertTrue(result.message().contains("sub"));
  }

  @Test
  void executeWithMaxFolders() {
    var capturedMax = new AtomicReference<Integer>();
    var tool =
        browseOnly(
            (req, context) -> {
              capturedMax.set(req.maxFolders());
              return new FolderBrowseResponse(List.of(), 1, false);
            });

    tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\", \"max_folders\": 25}", EngineContextTestFixtures.AGENT_LOOP);
    assertEquals(25, capturedMax.get());
  }

  @Test
  void executeMaxFoldersCapped() {
    var capturedMax = new AtomicReference<Integer>();
    var tool =
        browseOnly(
            (req, context) -> {
              capturedMax.set(req.maxFolders());
              return new FolderBrowseResponse(List.of(), 1, false);
            });

    tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\", \"max_folders\": 999}", EngineContextTestFixtures.AGENT_LOOP);
    assertEquals(200, capturedMax.get(), "max_folders should be capped at 200");
  }

  @Test
  void executeWithNullResponse() {
    var tool = browseOnly((req, context) -> null);
    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("no response"));
  }

  @Test
  void executeWithEmptyFoldersUnderParent() {
    var tool = browseOnly((req, context) -> new FolderBrowseResponse(List.of(), 3, false));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\empty\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("No folders found under"));
    assertTrue(result.message().contains("/empty"));
  }

  @Test
  void executeWithTruncatedResults() {
    var tool =
        browseOnly(
            (req, context) ->
                new FolderBrowseResponse(
                    List.of(new FolderBrowseResponse.Folder("/a", "a", 1, 100, 0)),
                    1,
                    true));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\root\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("truncated"), "Should mention truncation: " + result.message());
  }

  @Test
  void executeInvalidJson() {
    var tool = browseOnly((req, context) -> new FolderBrowseResponse(List.of(), 0, false));
    OperationResult result = tool.execute("not json", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("error"));
  }

  @Test
  void formatsSizesReadably() {
    var tool =
        browseOnly(
            (req, context) ->
                new FolderBrowseResponse(
                    List.of(
                        new FolderBrowseResponse.Folder("/small", "small", 1, 512, 0),
                        new FolderBrowseResponse.Folder("/medium", "medium", 5, 2_500_000, 0),
                        new FolderBrowseResponse.Folder("/large", "large", 100, 3_000_000_000L, 0)),
                    3,
                    false));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\root\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("512 B"), "Should format bytes: " + result.message());
    assertTrue(result.message().contains("MB"), "Should format megabytes: " + result.message());
    assertTrue(result.message().contains("GB"), "Should format gigabytes: " + result.message());
  }

  // --- Roots listing tests ---

  @Test
  void executeListsRootsWhenNoParentPath() {
    var tool =
        new BrowseTool(
            (req, context) -> {
              fail("Should not call browseCallback for roots");
              return null;
            },
            context ->
                List.of(
                    new BrowseTool.RootInfo("D:\\Documents", "Documents"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result = tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("Documents"), "Should contain root name: " + result.message());
    assertTrue(result.message().contains("Projects"), "Should contain root name: " + result.message());
    assertTrue(
        result.message().contains("Top-level"), "Should say top-level: " + result.message());
    // Paths should be relative (root name only), not absolute
    assertTrue(
        result.message().contains("Path: Documents"),
        "Should show relative path: " + result.message());
    assertFalse(
        result.message().contains("D:\\Documents"),
        "Should NOT contain absolute path: " + result.message());
  }

  @Test
  void executeListsRootsWhenNullArgs() {
    var tool =
        new BrowseTool(
            (req, context) -> {
              fail("Should not call browseCallback for roots");
              return null;
            },
            context -> List.of(new BrowseTool.RootInfo("D:\\Docs", "Docs")));

    OperationResult result = tool.execute(null, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("Docs"));
  }

  @Test
  void rootSentinelsUseRootsSupplier() {
    var tool =
        new BrowseTool(
            (req, context) -> {
              fail("Should not call browseCallback for sentinel");
              return null;
            },
            context -> List.of(new BrowseTool.RootInfo("D:\\Docs", "Docs")));

    for (String sentinel : List.of("/", ".", "..", "root", "roots", "top", "*")) {
      OperationResult result = tool.execute("{\"parent_path\": \"" + sentinel + "\"}", EngineContextTestFixtures.AGENT_LOOP);
      assertTrue(result.success(), "Failed for sentinel: " + sentinel);
      assertTrue(
          result.message().contains("Docs"), "Missing root for sentinel '" + sentinel + "': " + result.message());
    }
  }

  @Test
  void emptyRootsReturnsNoIndexedFolders() {
    var tool =
        new BrowseTool(
            (req, context) -> {
              fail("Should not call browseCallback");
              return null;
            },
            context -> List.of());

    OperationResult result = tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("No indexed folders"),
        "Should report no folders: " + result.message());
  }

  @Test
  void relativePathRejected_showsRootNames() {
    var tool =
        new BrowseTool(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            context ->
                List.of(
                    new BrowseTool.RootInfo("D:\\Documents", "Documents"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    // "docs" doesn't match root names "Documents" or "Projects", so still rejected
    OperationResult result = tool.execute("{\"parent_path\": \"docs/explanation\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success(), "Unresolvable relative path should be rejected: " + result.message());
    assertTrue(
        result.message().contains("not an absolute path"),
        "Should explain rejection: " + result.message());
  }

  @Test
  void absolutePathEmptyResults_noHint() {
    var tool =
        browseOnly((req, context) -> new FolderBrowseResponse(List.of(), 1, false));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\Documents\\\\nonexistent\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("No folders found"), "Should report no folders: " + result.message());
    assertFalse(result.message().contains("HINT"), "Should NOT contain HINT: " + result.message());
  }

  // --- Path validation against roots ---

  @Test
  void relativeParentPath_rejected_whenRootsAvailable() {
    var tool =
        new BrowseTool(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            context ->
                List.of(
                    new BrowseTool.RootInfo("D:\\Documents", "Documents"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result = tool.execute("{\"parent_path\": \"docs/explanation\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success(), "Relative path should be rejected: " + result.message());
    assertTrue(result.message().contains("not an absolute path"), result.message());
    assertTrue(result.message().contains("D:\\Documents"), result.message());
  }

  @Test
  void unixSlashParentPath_rejected_whenRootsAvailable() {
    var tool =
        new BrowseTool(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            context -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result = tool.execute("{\"parent_path\": \"/how-to\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success(), "Unix-style /path should be rejected: " + result.message());
    assertTrue(result.message().contains("not an absolute path"), result.message());
  }

  @Test
  void absoluteOutOfRootsParentPath_rejected() {
    var tool =
        new BrowseTool(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            context -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result =
        tool.execute("{\"parent_path\": \"C:\\\\other\\\\path\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success(), "Out-of-root path should be rejected: " + result.message());
    assertTrue(result.message().contains("not under any indexed root"), result.message());
  }

  @Test
  void validRootedParentPath_accepted() {
    var capturedParent = new AtomicReference<String>();
    var tool =
        new BrowseTool(
            (req, context) -> {
              capturedParent.set(req.parentPath());
              return new FolderBrowseResponse(List.of(), 1, false);
            },
            context -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result =
        tool.execute("{\"parent_path\": \"D:\\\\Documents\\\\subfolder\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "Valid rooted path should be accepted: " + result.message());
    assertEquals("D:\\Documents\\subfolder", capturedParent.get());
  }

  @Test
  void rootsDoNotShowFileCountOrSize() {
    var tool =
        new BrowseTool(
            (req, context) -> {
              fail("Should not call browseCallback");
              return null;
            },
            context -> List.of(new BrowseTool.RootInfo("D:\\Docs", "Docs")));

    OperationResult result = tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertFalse(
        result.message().contains("files,"),
        "Roots should not show file count: " + result.message());
  }

  // --- Relative path resolution tests ---

  @Test
  void relativeParentPath_resolvedByRootName() {
    var capturedParent = new AtomicReference<String>();
    var tool =
        new BrowseTool(
            (req, context) -> {
              capturedParent.set(req.parentPath());
              return new FolderBrowseResponse(List.of(), 1, false);
            },
            context -> List.of(new BrowseTool.RootInfo("D:\\data\\docs", "docs")));

    OperationResult result = tool.execute("{\"parent_path\": \"docs/explanation\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "Relative path matching root name should resolve: " + result.message());
    assertEquals("D:\\data\\docs\\explanation", capturedParent.get());
  }

  @Test
  void relativeParentPath_rootNameOnly_resolvesToRoot() {
    var capturedParent = new AtomicReference<String>();
    var tool =
        new BrowseTool(
            (req, context) -> {
              capturedParent.set(req.parentPath());
              return new FolderBrowseResponse(List.of(), 1, false);
            },
            context -> List.of(new BrowseTool.RootInfo("D:\\data\\docs", "docs")));

    OperationResult result = tool.execute("{\"parent_path\": \"docs\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "Root name alone should resolve: " + result.message());
    assertEquals("D:\\data\\docs", capturedParent.get());
  }

  @Test
  void outputShowsRelativePaths() {
    var tool =
        new BrowseTool(
            (req, context) ->
                new FolderBrowseResponse(
                    List.of(
                        new FolderBrowseResponse.Folder(
                            "D:\\data\\docs\\explanation", "explanation", 5, 1024, 0)),
                    2,
                    false),
            context -> List.of(new BrowseTool.RootInfo("D:\\data\\docs", "docs")));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("Path: docs/explanation"),
        "Should show relative path: " + result.message());
    assertFalse(
        result.message().contains("D:\\data\\docs\\explanation"),
        "Should NOT show absolute path: " + result.message());
    assertTrue(
        result.message().contains("Folders under \"docs\""),
        "Header should show relative parent: " + result.message());
  }

  @Test
  void toRelativePath_noRoots_returnsAbsolute() {
    // Constructs its own empty-roots tool rather than using browseOnly(): this test's subject IS
    // the no-roots case, which the shared fixture no longer represents.
    var tool = new BrowseTool((req, context) -> new FolderBrowseResponse(List.of(), 0, false), context -> List.of());
    assertEquals(
        "D:\\data\\docs",
        tool.toRelativePath("D:\\data\\docs", EngineContextTestFixtures.AGENT_LOOP));
  }

  @Test
  void toRelativePath_rootMatch_returnsRelative() {
    var tool =
        new BrowseTool(
            (req, context) -> new FolderBrowseResponse(List.of(), 0, false),
            context -> List.of(new BrowseTool.RootInfo("D:\\data\\docs", "docs")));
    assertEquals(
        "docs", tool.toRelativePath("D:\\data\\docs", EngineContextTestFixtures.AGENT_LOOP));
    assertEquals(
        "docs/explanation",
        tool.toRelativePath(
            "D:\\data\\docs\\explanation", EngineContextTestFixtures.AGENT_LOOP));
  }

  // --- File listing tests ---

  private static BrowseTool browseAndFiles(
      BrowseTool.BrowseCallback browseCb, BrowseTool.FilesCallback filesCb) {
    return new BrowseTool(browseCb, filesCb, context -> FIXTURE_ROOTS);
  }

  @Test
  void autoFallback_emptyFolders_listsFiles() {
    var filesCalled = new AtomicReference<>(false);
    var tool =
        browseAndFiles(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            (req, context) -> {
              filesCalled.set(true);
              return new FolderFilesResponse(
                  List.of(
                      new FolderFilesResponse.FileEntry(
                          "doc1", Map.of("filename", "overview.md", "size_bytes", "1024")),
                      new FolderFilesResponse.FileEntry(
                          "doc2", Map.of("filename", "guide.md", "size_bytes", "2048"))),
                  2,
                  3);
            });

    OperationResult result =
        tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\\\\explanation\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(filesCalled.get(), "Files callback should be called on auto-fallback");
    assertTrue(result.message().contains("overview.md"), "Should list file name: " + result.message());
    assertTrue(result.message().contains("guide.md"), "Should list file name: " + result.message());
    assertTrue(result.message().contains("Found 2 files"), "Should show file count: " + result.message());
  }

  @Test
  void listFilesExplicit_skipsFolderBrowse() {
    var browseCalled = new AtomicReference<>(false);
    var tool =
        browseAndFiles(
            (req, context) -> {
              browseCalled.set(true);
              return new FolderBrowseResponse(
                  List.of(new FolderBrowseResponse.Folder("/sub", "sub", 5, 100, 0)), 1, false);
            },
            (req, context) ->
                new FolderFilesResponse(
                    List.of(
                        new FolderFilesResponse.FileEntry(
                            "doc1", Map.of("filename", "readme.md", "size_bytes", "512"))),
                    1,
                    2));

    OperationResult result =
        tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\", \"list_files\": true}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertFalse(browseCalled.get(), "Folder browse should NOT be called when list_files=true");
    assertTrue(result.message().contains("readme.md"), "Should list file: " + result.message());
  }

  @Test
  void autoFallback_noFilesCallback_showsNoFolders() {
    // Backward-compat: null filesCallback preserves original "No folders found" behavior
    var tool = browseOnly((req, context) -> new FolderBrowseResponse(List.of(), 1, false));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\empty\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("No folders found"),
        "Should show original message: " + result.message());
  }

  @Test
  void fileOutput_showsRelativePaths() {
    var tool =
        new BrowseTool(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            (req, context) ->
                new FolderFilesResponse(
                    List.of(
                        new FolderFilesResponse.FileEntry(
                            "doc1",
                            Map.of(
                                "filename", "overview.md",
                                "path", "D:\\data\\docs\\explanation\\overview.md",
                                "size_bytes", "1024"))),
                    1,
                    2),
            context -> List.of(new BrowseTool.RootInfo("D:\\data\\docs", "docs")));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\\\\explanation\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("Path: docs/explanation/overview.md"),
        "Should show relative path: " + result.message());
    assertFalse(
        result.message().contains("D:\\data"),
        "Should NOT show absolute path: " + result.message());
  }

  @Test
  void fileOutput_formatsSizesReadably() {
    var tool =
        browseAndFiles(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            (req, context) ->
                new FolderFilesResponse(
                    List.of(
                        new FolderFilesResponse.FileEntry(
                            "small", Map.of("filename", "tiny.txt", "size_bytes", "256")),
                        new FolderFilesResponse.FileEntry(
                            "medium", Map.of("filename", "doc.pdf", "size_bytes", "2500000"))),
                    2,
                    3));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\docs\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("256 B"), "Should format bytes: " + result.message());
    assertTrue(result.message().contains("MB"), "Should format megabytes: " + result.message());
  }

  @Test
  void autoFallback_emptyFoldersAndFiles_showsOriginalHint() {
    // Both folders AND files are empty — should fall through to original "No folders found" hint
    var tool =
        browseAndFiles(
            (req, context) -> new FolderBrowseResponse(List.of(), 1, false),
            (req, context) -> new FolderFilesResponse(List.of(), 0, 1));

    OperationResult result = tool.execute("{\"parent_path\": \"D:\\\\data\\\\nonexistent\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("No folders found"),
        "Should show original no-folders message, not 'No files found': " + result.message());
    assertFalse(
        result.message().contains("No files found"),
        "Should NOT show file-listing empty message: " + result.message());
  }

  @Test
  void listFilesExplicit_withoutParentPath_returnsError() {
    var tool =
        browseAndFiles(
            (req, context) -> new FolderBrowseResponse(List.of(), 0, false),
            (req, context) -> new FolderFilesResponse(List.of(), 0, 0));

    OperationResult result = tool.execute("{\"list_files\": true}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success(), "list_files without parent_path should fail: " + result.message());
    assertTrue(
        result.message().contains("parent_path"),
        "Error should mention parent_path: " + result.message());
  }
}
