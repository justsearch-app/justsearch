/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.agent.tools.FileOperation;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 877 §2.1 — the catalog {@code Interface} is the ONE declaration of an agent tool's
 * arguments. {@code AgentOperationEmitter} projects {@code op.intf().inputs()} to the model and
 * {@code OperationInputSchemaValidator} enforces it before dispatch, so a key the tool reads but the
 * catalog does not declare is a key the model can never send.
 *
 * <p>Each tool used to carry a second, unread schema constant that drifted from
 * the catalog silently — those are deleted. This test is what replaces them: it holds the catalog
 * against the tool SOURCE, so a tool that starts reading a new argument key without declaring it
 * goes red instead of shipping a silent no-op key.
 *
 * <p>Two assertions per tool. (1) The declared property set equals an explicitly written expected
 * set — any addition or removal has to be made deliberately, here. (2) Every argument key the tool
 * source reads is either declared or listed in {@link #UNDECLARED_ON_PURPOSE} with the reason.
 *
 * <p><b>What the source scan actually reaches</b>, stated honestly because a subset check that goes
 * vacuous is worse than no check: it matches the CONVENTIONAL spellings only — reads off a node
 * named {@code args} (a tool's top-level arguments) or {@code opNode} (one file-operations item),
 * directly or through a helper taking that node as its first argument. A read off any other
 * {@code JsonNode} variable is invisible to it: {@code SearchTool.parsePipelineArg} reads nine keys
 * off a parameter named {@code node} and this scan sees none of them (they are withheld from the
 * model anyway — see {@code pipeline} in {@link #UNDECLARED_ON_PURPOSE}). So the scan is a
 * convention check, not a proof, and the ANCHOR assertions are what keep a rename of {@code args}
 * or {@code opNode} from turning the whole subset check silently green.
 */
final class AgentToolCatalogContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Path TOOL_SOURCE_DIR =
      Path.of("modules", "app-agent", "src", "main", "java", "io", "justsearch", "agent", "tools");

  /**
   * Argument keys a tool reads that the catalog deliberately does NOT declare, and why. Every entry
   * was verified against the source it names; an addition here is a decision to hide a key from the
   * model, not a way to quiet this test.
   */
  private static final Map<String, String> UNDECLARED_ON_PURPOSE =
      Map.of(
          "docIds",
              "server-injected: AgentToolDispatcher.scopeToolCall merges the run's docIds scope"
                  + " into the search tool's arguments for every call in a scoped run, so the model"
                  + " never chooses it (SearchTool.extractDocIds documents the same).",
          "mode",
              "internal retrieval lever, withheld per tempdoc 868 §B.4. SearchTool.execute still"
                  + " honours it because it is the shape the agent.searchDefaultMode config default"
                  + " flows through, but it is not a capability the model should steer.",
          "pipeline",
              "internal fine-grained retrieval lever, withheld per tempdoc 868 §B.4. Parsing is"
                  + " kept (SearchTool.parsePipelineArg) but no production caller supplies it"
                  + " today — only SearchToolTest does.",
          "path",
              "file-operations only: an accepted ALIAS for the declared `destination`, because"
                  + " small local models routinely emit `path` (notably for MKDIR). Declaring the"
                  + " alias would invite it; FileOperationsTool.parseOperations accepts it anyway.");

  /** Reads on the top-level arguments node, e.g. {@code args.has("query")}. */
  private static final Pattern DIRECT_ARG_READ =
      Pattern.compile("(?<![A-Za-z0-9_])args\\.(?:has|get|path)\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

  /** Reads through an arg helper, e.g. {@code intArg(args, "offset_chars", 0)}. */
  private static final Pattern HELPER_ARG_READ =
      Pattern.compile(
          "(?<![A-Za-z0-9_])[A-Za-z_][A-Za-z0-9_]*\\(\\s*args\\s*,\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

  /** Reads on one element of the file-operations {@code operations} array. */
  private static final Pattern DIRECT_ITEM_READ =
      Pattern.compile(
          "(?<![A-Za-z0-9_])opNode\\.(?:has|get|path)\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

  private static final Pattern HELPER_ITEM_READ =
      Pattern.compile(
          "(?<![A-Za-z0-9_])[A-Za-z_][A-Za-z0-9_]*\\(\\s*opNode\\s*,"
              + "\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

  // -------------------------------------------------------------------------------------------
  // Per-tool contracts
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("core.search-index declares exactly its model-facing arguments")
  void searchIndexContract() {
    assertContract(
        AgentToolsOperationCatalog.SEARCH_INDEX,
        "SearchTool.java",
        Set.of("query", "limit", "path_prefix"),
        "query");
  }

  @Test
  @DisplayName("core.read-document declares exactly its model-facing arguments")
  void readDocumentContract() {
    assertContract(
        AgentToolsOperationCatalog.READ_DOCUMENT,
        "ReadDocumentTool.java",
        Set.of("path", "offset_chars", "max_chars"),
        "path");
  }

  @Test
  @DisplayName("core.browse-folders declares exactly its model-facing arguments")
  void browseFoldersContract() {
    assertContract(
        AgentToolsOperationCatalog.BROWSE_FOLDERS,
        "BrowseTool.java",
        Set.of("parent_path", "list_files", "max_folders", "max_files"),
        "parent_path");
  }

  @Test
  @DisplayName("core.ingest-files declares exactly its model-facing arguments")
  void ingestFilesContract() {
    assertContract(
        AgentToolsOperationCatalog.INGEST_FILES,
        "IngestTool.java",
        Set.of("paths", "collection"),
        "paths");
  }

  @Test
  @DisplayName("core.file-operations declares exactly its model-facing arguments, items included")
  void fileOperationsContract() {
    // The item keys (`op`, `source`, `destination`) are declared inside operations.items.properties
    // rather than at the top level, so they join the declared set here — parseOperations reads them
    // from an array element, and the model authors them in the same call.
    assertContract(
        AgentToolsOperationCatalog.FILE_OPERATIONS,
        "FileOperationsTool.java",
        Set.of("operations", "conflict_strategy", "explanation"),
        "operations",
        "op");
  }

  @Test
  @DisplayName("877 §2.1 — the ingest schema still advertises the optional collection tag")
  void ingestFilesAdvertisesTheOptionalCollectionTag() {
    // Moved from IngestToolTest, which asserted it against the deleted tool-local schema constant.
    // Tempdoc 811 (C-2a) added the key; this is the schema the model reads it from.
    JsonNode properties = inputs(AgentToolsOperationCatalog.INGEST_FILES).get("properties");
    assertTrue(
        properties.has("collection"),
        "the model can only tag an ad-hoc ingest if the declared schema advertises `collection`");
    assertEquals(
        new ObjectMapper().valueToTree(List.of("string", "null")),
        properties.get("collection").get("type"),
        "C2 prepared ingestion accepts a named collection or an explicitly untagged request");
  }

  @Test
  @DisplayName("877 §3.1 — every FileOperation.OpType is named in the declared op description")
  void fileOperationsOpDescriptionNamesEveryOpType() {
    JsonNode op =
        inputs(AgentToolsOperationCatalog.FILE_OPERATIONS)
            .get("properties")
            .get("operations")
            .get("items")
            .get("properties")
            .get("op");
    String description = op.get("description").asText();
    for (FileOperation.OpType t : FileOperation.OpType.values()) {
      assertTrue(
          description.contains(t.name()),
          "an op type the model is not told about is an op type it will never use. `"
              + t.name()
              + "` is missing from the declared description: "
              + description);
    }
    // The values ride the DESCRIPTION, not an `enum` keyword, and that is load-bearing rather than
    // stylistic: this schema is enforced before dispatch, while parseOperations deliberately
    // upper-cases `op` because small local models emit "mkdir"
    // (FileOperationsToolTest#executeCaseInsensitiveOpType pins that as intended). An `enum` would
    // reject those calls at the dispatch boundary while that unit test — which calls execute()
    // directly, bypassing the validator — stayed green. This assertion exists so nobody
    // "tightens" it back without reading why.
    assertTrue(
        op.get("enum") == null,
        "no `enum` keyword on `op`: it is enforced pre-dispatch and would narrow the"
            + " case-insensitive parsing FileOperationsTool deliberately performs");
    assertTrue(
        inputs(AgentToolsOperationCatalog.FILE_OPERATIONS)
                .get("properties")
                .get("conflict_strategy")
                .get("enum")
            == null,
        "no `enum` keyword on `conflict_strategy`: same case-insensitive parsing, same reason");
  }

  @Test
  @DisplayName("877 §2.1 — the operations batch limit rides the description, not a `maxItems`")
  void fileOperationsBatchLimitRidesTheDescription() {
    // What FileOperationsToolTest#schemaBatchLimitMatchesConstant used to assert — that the schema
    // states MAX_BATCH_SIZE — cannot be asserted here: the catalog description is BUILT by
    // interpolating that same constant (AgentToolsOperationCatalog.fileOperations()), so a
    // `contains(String.valueOf(MAX_BATCH_SIZE))` check is true for every possible value of it. That
    // is the same tautology shape as the deleted `MAX_EXPANDED_FILES >= 1000` assertion, and a
    // tautology in a contract test is worse than a gap: it reads as coverage. Interpolation is the
    // stronger guarantee anyway — one author, no second speller to drift.
    //
    // The real, falsifiable invariant is the choice of MECHANISM: the limit rides the DESCRIPTION
    // rather than a `maxItems` keyword, because this schema is enforced before dispatch and
    // FileOperationsTool.execute already refuses an oversized batch with an actionable "split into
    // smaller batches" message that a generic validator error would pre-empt.
    JsonNode operations =
        inputs(AgentToolsOperationCatalog.FILE_OPERATIONS).get("properties").get("operations");
    assertTrue(
        operations.get("maxItems") == null,
        "no `maxItems` keyword: it is enforced, and would replace the tool's actionable"
            + " over-size message with a generic validation error");
  }

  // -------------------------------------------------------------------------------------------
  // Machinery
  // -------------------------------------------------------------------------------------------

  private void assertContract(
      OperationRef ref, String toolSourceFile, Set<String> expectedTopLevel, String anchorKey) {
    assertContract(ref, toolSourceFile, expectedTopLevel, anchorKey, null);
  }

  /**
   * @param itemAnchorKey a key the tool must be seen reading off an ARRAY ELEMENT, or {@code null}
   *     for tools with no item schema. The top-level anchor cannot stand in for it: the item
   *     patterns are keyed to the literal identifier {@code opNode}, so renaming that loop variable
   *     would make both item patterns match nothing while the top-level anchor still passed — and
   *     the item half of the subset check would go vacuous without a red test anywhere.
   */
  private void assertContract(
      OperationRef ref,
      String toolSourceFile,
      Set<String> expectedTopLevel,
      String anchorKey,
      String itemAnchorKey) {
    JsonNode intf = inputs(ref); // parses, or this throws — a malformed schema is a real failure
    Set<String> declaredTopLevel = propertyNames(intf.get("properties"));
    assertEquals(
        new TreeSet<>(expectedTopLevel),
        new TreeSet<>(declaredTopLevel),
        ref.value()
            + ": the declared property set changed. That is a change to what the model can send —"
            + " update this expectation deliberately, and check the tool honours the new shape.");

    Set<String> declared = new LinkedHashSet<>(declaredTopLevel);
    declared.addAll(itemPropertyNames(intf));

    Path source = toolSource(toolSourceFile);
    String body = read(source);
    Set<String> read = readKeys(body);
    assertTrue(
        read.contains(anchorKey),
        "the source-scan found no read of '"
            + anchorKey
            + "' in "
            + toolSourceFile
            + " — the extraction patterns no longer match this tool (a renamed arguments variable?),"
            + " so the subset check below would pass vacuously");

    if (itemAnchorKey != null) {
      Set<String> itemRead = readKeys(body, DIRECT_ITEM_READ, HELPER_ITEM_READ);
      assertTrue(
          itemRead.contains(itemAnchorKey),
          "the source-scan found no ITEM read of '"
              + itemAnchorKey
              + "' in "
              + toolSourceFile
              + " — the item extraction patterns no longer match (a renamed `opNode` loop"
              + " variable?), so the item half of the subset check below would pass vacuously"
              + " while the top-level anchor kept it looking green");
    }

    Map<String, String> undeclared = new LinkedHashMap<>();
    for (String key : read) {
      if (!declared.contains(key) && !UNDECLARED_ON_PURPOSE.containsKey(key)) {
        undeclared.put(key, "read by " + toolSourceFile + " but declared nowhere");
      }
    }
    assertTrue(
        undeclared.isEmpty(),
        ref.value()
            + ": "
            + toolSourceFile
            + " reads argument keys the catalog does not declare: "
            + undeclared.keySet()
            + ". The catalog Interface is the only schema the model is shown and the only one the"
            + " input validator enforces, so an undeclared key can never arrive. Declare it, or add"
            + " it to UNDECLARED_ON_PURPOSE with the reason it is server-injected or withheld.");
  }

  private static Set<String> readKeys(String source) {
    return readKeys(source, DIRECT_ARG_READ, HELPER_ARG_READ, DIRECT_ITEM_READ, HELPER_ITEM_READ);
  }

  private static Set<String> readKeys(String source, Pattern... patterns) {
    Set<String> keys = new LinkedHashSet<>();
    for (Pattern p : List.of(patterns)) {
      Matcher m = p.matcher(source);
      while (m.find()) {
        keys.add(m.group(1));
      }
    }
    return keys;
  }

  private static Set<String> propertyNames(JsonNode properties) {
    Set<String> names = new LinkedHashSet<>();
    if (properties != null) {
      names.addAll(properties.propertyNames());
    }
    return names;
  }

  /** Property names declared on an array property's item schema (file-operations' `operations`). */
  private static Set<String> itemPropertyNames(JsonNode intf) {
    Set<String> names = new LinkedHashSet<>();
    JsonNode properties = intf.get("properties");
    if (properties == null) {
      return names;
    }
    for (String name : properties.propertyNames()) {
      JsonNode items = properties.get(name).get("items");
      if (items != null) {
        names.addAll(propertyNames(items.get("properties")));
      }
    }
    return names;
  }

  private static JsonNode inputs(OperationRef ref) {
    return MAPPER.readTree(operation(ref).intf().inputs());
  }

  private static Operation operation(OperationRef ref) {
    return new AgentToolsOperationCatalog()
        .definitions().stream()
            .filter(o -> o.id().equals(ref))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no operation declared for " + ref.value()));
  }

  private static String read(Path source) {
    try {
      return Files.readString(source, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("failed to read tool source " + source, e);
    }
  }

  /**
   * Resolves a tool source file by walking up from the working directory to the repo root (the
   * directory holding {@code settings.gradle.kts}). Skips rather than passes when the source cannot
   * be found: a vacuous green here would be exactly the drift this test exists to catch.
   */
  private static Path toolSource(String fileName) {
    Path root = repoRoot();
    Assumptions.assumeTrue(
        root != null,
        "repo root (the directory containing settings.gradle.kts) not found above "
            + new File("").getAbsolutePath()
            + " — cannot scan the agent-tool sources, so this contract is unverified here");
    Path source = root.resolve(TOOL_SOURCE_DIR).resolve(fileName);
    Assumptions.assumeTrue(
        Files.isRegularFile(source),
        "agent-tool source not found at " + source + " — this contract is unverified here");
    return source;
  }

  private static Path repoRoot() {
    Path dir = new File("").getAbsoluteFile().toPath();
    while (dir != null) {
      if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
