/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 875 C.3 — the production {@link DurableGrantScope}: the containment a filesystem-reaching
 * operation's durable grant was granted against is <b>the indexed roots</b>.
 *
 * <p>Governs exactly the operations it is constructed with (the wiring site passes
 * {@code AgentToolsOperationCatalog.INGEST_FILES}); every other operation is in scope by definition,
 * because containment is not a defined concept for it. The governed set is injected rather than
 * imported so this class carries no catalog knowledge and no package cycle
 * ({@code services.intent} ← {@code services.registry.operations}).
 *
 * <p>For a governed operation, every entry of the {@code paths} argument array must canonicalize
 * inside an indexed root. Out of scope ⇒ the grant does not apply ⇒ the gate falls through to the
 * capsule path ⇒ the user gets a confirm dialog that NAMES the path. The out-of-root ingest capability
 * (811 C-2a) is preserved; only the blanket-consent shortcut is removed.
 *
 * <p><b>The failure mode of this component is a prompt, never a silent ingest.</b> Supplier unbound,
 * supplier throwing, roots empty, arguments unparseable, {@code paths} missing / not an array / empty,
 * a non-string or blank entry, a path that cannot be canonicalized — each returns {@code false}, which
 * costs one confirmation. There is no path through this class that returns {@code true} without having
 * proven containment for every entry.
 */
public final class IndexedRootGrantScope implements DurableGrantScope {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(IndexedRootGrantScope.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final Set<OperationRef> governedOperations;

  /**
   * The live indexed roots. {@code null} until {@link #bindIndexedRoots} runs — the Worker-backed
   * indexing service does not exist at substrate-init time, so the scope is constructed unbound and
   * bound later at agent-tool registration. Unbound reads as "cannot prove containment" ⇒ a confirm.
   */
  private volatile java.util.function.Function<EngineContext, List<Path>> indexedRoots;

  /**
   * @param governedOperations the operations whose durable grants are bounded by the indexed roots;
   *     anything outside this set is covered unconditionally.
   */
  public IndexedRootGrantScope(Set<OperationRef> governedOperations) {
    this.governedOperations = Set.copyOf(Objects.requireNonNull(governedOperations, "governedOperations"));
  }

  /**
   * Late-bind the live indexed-root lookup. Before this runs, every governed invocation is treated as
   * unprovable containment (a confirm), so a wiring regression costs a prompt, not a silent grant.
   */
  public void bindIndexedRoots(java.util.function.Function<EngineContext, List<Path>> roots) {
    this.indexedRoots = roots;
  }

  @Override
  public boolean coversArguments(Operation op, String argumentsJson, EngineContext engineContext) {
    if (op == null || !governedOperations.contains(op.id())) {
      return true; // containment is not a defined concept for this operation
    }
    List<Path> roots = currentRoots(engineContext);
    if (roots.isEmpty()) {
      // Unbound / throwing / empty roots — the adverse precondition. Cannot prove containment.
      return false;
    }
    List<String> paths = parsePaths(argumentsJson);
    if (paths.isEmpty()) {
      return false;
    }
    for (String candidate : paths) {
      if (!isWithinRoots(candidate, roots)) {
        return false;
      }
    }
    return true;
  }

  /** The bound roots, or an empty list for every unavailability — unbound, throwing, null, empty. */
  private List<Path> currentRoots(EngineContext engineContext) {
    java.util.function.Function<EngineContext, List<Path>> supplier = this.indexedRoots;
    if (supplier == null) {
      return List.of();
    }
    try {
      List<Path> roots = supplier.apply(engineContext);
      if (roots == null) {
        return List.of();
      }
      return roots.stream().filter(Objects::nonNull).toList();
    } catch (RuntimeException e) {
      LOG.warn("Indexed-root lookup failed; durable grant treated as out-of-scope (a confirm): {}", e.toString());
      return List.of();
    }
  }

  /**
   * The {@code paths} argument array as strings, or an empty list for every shape this scope cannot
   * read as a list of paths (unparseable JSON, missing / non-array / empty {@code paths}, a non-string
   * or blank entry). Empty ⇒ the caller fails closed.
   */
  private static List<String> parsePaths(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return List.of();
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(argumentsJson);
    } catch (Exception e) {
      return List.of(); // unparseable arguments prove nothing — fail closed
    }
    if (root == null || !root.isObject()) {
      return List.of();
    }
    JsonNode paths = root.get("paths");
    if (paths == null || !paths.isArray() || paths.size() == 0) {
      return List.of();
    }
    List<String> out = new ArrayList<>(paths.size());
    for (int i = 0; i < paths.size(); i++) {
      JsonNode entry = paths.get(i);
      if (entry == null || !entry.isString()) {
        return List.of();
      }
      String value = entry.asString();
      if (value == null || value.isBlank()) {
        return List.of();
      }
      out.add(value);
    }
    return List.copyOf(out);
  }

  // WHY a private copy rather than a call into the reference implementation: the canonical
  // containment check is `FileOperationExecutor.isWithinRoots` / `resolveClosestExistingAncestor`
  // (modules/app-agent/.../tools/FileOperationExecutor.java:111-150), which is package-private in a
  // DIFFERENT module (app-agent) — not reachable from app-services. Tempdoc 877 centralises the path
  // helpers only WITHIN app-agent, so there is no shared home to call into either. The semantics are
  // mirrored deliberately: canonicalize through the closest EXISTING ancestor's real path (a
  // not-yet-existing path cannot be `toRealPath()`d) before `startsWith`, so a symlink or junction
  // cannot straddle a root boundary; any IOException fails closed.
  private static boolean isWithinRoots(String candidate, List<Path> roots) {
    try {
      Path resolved = resolveClosestExistingAncestor(Path.of(candidate));
      for (Path root : roots) {
        // Tempdoc 875 §E S4: canonicalize per root and skip the unresolvable ones (a detached
        // share, an ACL-blocked mount). Aborting the loop on one such root would cost a confirm for
        // a path sitting squarely inside a later, good root. Skipping only shrinks the covered set.
        Path rootReal;
        try {
          rootReal = root.toRealPath();
        } catch (IOException | RuntimeException e) {
          LOG.warn("Skipping unresolvable indexed root while scoping a durable grant: {}", root);
          continue;
        }
        if (resolved.startsWith(rootReal)) {
          return true;
        }
      }
      return false;
    } catch (IOException | RuntimeException e) {
      // RuntimeException covers InvalidPathException from a malformed argument string.
      LOG.warn(
          "Cannot canonicalize {} against the indexed roots ({}); treated as out-of-scope (a confirm)",
          candidate,
          e.toString());
      return false;
    }
  }

  private static Path resolveClosestExistingAncestor(Path path) throws IOException {
    Path abs = path.toAbsolutePath().normalize();
    if (Files.exists(abs)) {
      return abs.toRealPath();
    }
    List<Path> missingSegments = new ArrayList<>();
    Path current = abs;
    while (current != null && !Files.exists(current)) {
      missingSegments.add(current.getFileName());
      current = current.getParent();
    }
    if (current == null) {
      return abs;
    }
    Path resolved = current.toRealPath();
    for (int i = missingSegments.size() - 1; i >= 0; i--) {
      Path segment = missingSegments.get(i);
      if (segment != null) {
        resolved = resolved.resolve(segment);
      }
    }
    return resolved;
  }
}
