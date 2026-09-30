package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 875 C.3 — the argument scope. Every adverse precondition must produce "not covered" (which
 * costs a confirmation), never a silent "covered" (`green-masked-destructive`).
 */
@DisplayName("IndexedRootGrantScope")
class IndexedRootGrantScopeTest {

  private static final OperationRef GOVERNED = new OperationRef("core.ingest-files");
  private static final OperationRef UNGOVERNED = new OperationRef("core.search-index");

  private static IndexedRootGrantScope scopeBoundTo(Path... roots) {
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    scope.bindIndexedRoots(engineContext -> List.of(roots));
    return scope;
  }

  private static String argsFor(Path... paths) {
    StringBuilder sb = new StringBuilder("{\"paths\":[");
    for (int i = 0; i < paths.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(paths[i].toAbsolutePath().toString().replace("\\", "\\\\")).append('"');
    }
    return sb.append("]}").toString();
  }

  @Test
  @DisplayName("an operation outside the governed set is covered unconditionally, even unbound")
  void ungovernedOperationIsAlwaysCovered() {
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    assertTrue(
        scope.coversArguments(op(UNGOVERNED), "{\"query\":\"anything\"}", io.justsearch.app.services.TestEngineContexts.agent()),
        "containment is not a defined concept for a non-filesystem operation");
  }

  @Test
  @DisplayName("every paths entry inside an indexed root ⇒ covered")
  void inRootPathsAreCovered(@TempDir Path root) throws Exception {
    Path a = Files.createFile(root.resolve("a.txt"));
    Path b = Files.createDirectory(root.resolve("sub"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertTrue(scope.coversArguments(op(GOVERNED), argsFor(a, b), io.justsearch.app.services.TestEngineContexts.agent()));
  }

  @Test
  @DisplayName("one out-of-root entry poisons the whole invocation ⇒ not covered")
  void anyOutOfRootPathIsNotCovered(@TempDir Path base) throws Exception {
    Path root = Files.createDirectory(base.resolve("indexed"));
    Path outside = Files.createDirectory(base.resolve("elsewhere"));
    Path inside = Files.createFile(root.resolve("ok.txt"));
    Path escape = Files.createFile(outside.resolve("secret.txt"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertFalse(scope.coversArguments(op(GOVERNED), argsFor(escape), io.justsearch.app.services.TestEngineContexts.agent()), "a wholly out-of-root ingest");
    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(inside, escape), io.justsearch.app.services.TestEngineContexts.agent()),
        "one out-of-root entry is enough — containment must hold for EVERY path");
  }

  // ── Adverse preconditions (green-masked-destructive): each must be a confirm, not a proceed ──────

  @Test
  @DisplayName("adverse: the roots supplier is never bound ⇒ not covered")
  void unboundSupplierIsNotCovered(@TempDir Path root) throws Exception {
    Path file = Files.createFile(root.resolve("a.txt"));
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));

    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(file), io.justsearch.app.services.TestEngineContexts.agent()),
        "unbound ⇒ containment unprovable ⇒ a wiring regression costs a prompt, not a silent grant");
  }

  @Test
  @DisplayName("adverse: the roots supplier throws ⇒ not covered")
  void throwingSupplierIsNotCovered(@TempDir Path root) throws Exception {
    Path file = Files.createFile(root.resolve("a.txt"));
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    scope.bindIndexedRoots(
        engineContext -> {
          throw new IllegalStateException("Worker unavailable");
        });

    assertFalse(scope.coversArguments(op(GOVERNED), argsFor(file), io.justsearch.app.services.TestEngineContexts.agent()));
  }

  @Test
  @DisplayName("adverse: the roots supplier returns empty (or null) ⇒ not covered")
  void emptyRootsAreNotCovered(@TempDir Path root) throws Exception {
    Path file = Files.createFile(root.resolve("a.txt"));
    IndexedRootGrantScope empty = new IndexedRootGrantScope(Set.of(GOVERNED));
    empty.bindIndexedRoots(engineContext -> List.of());
    IndexedRootGrantScope nullish = new IndexedRootGrantScope(Set.of(GOVERNED));
    nullish.bindIndexedRoots(engineContext -> null);

    assertFalse(empty.coversArguments(op(GOVERNED), argsFor(file), io.justsearch.app.services.TestEngineContexts.agent()), "no roots ⇒ nothing is contained");
    assertFalse(nullish.coversArguments(op(GOVERNED), argsFor(file), io.justsearch.app.services.TestEngineContexts.agent()), "a null root list too");
  }

  @Test
  @DisplayName("adverse: unreadable / pathless arguments ⇒ not covered")
  void unusableArgumentsAreNotCovered(@TempDir Path root) {
    IndexedRootGrantScope scope = scopeBoundTo(root);
    Operation op = op(GOVERNED);

    assertFalse(scope.coversArguments(op, null, io.justsearch.app.services.TestEngineContexts.agent()), "null args");
    assertFalse(scope.coversArguments(op, "   ", io.justsearch.app.services.TestEngineContexts.agent()), "blank args");
    assertFalse(scope.coversArguments(op, "{not-json", io.justsearch.app.services.TestEngineContexts.agent()), "unparseable args");
    assertFalse(scope.coversArguments(op, "[1,2,3]", io.justsearch.app.services.TestEngineContexts.agent()), "args that are not an object");
    assertFalse(scope.coversArguments(op, "{\"collection\":\"x\"}", io.justsearch.app.services.TestEngineContexts.agent()), "no paths key");
    assertFalse(scope.coversArguments(op, "{\"paths\":\"a\"}", io.justsearch.app.services.TestEngineContexts.agent()), "paths is not an array");
    assertFalse(scope.coversArguments(op, "{\"paths\":[]}", io.justsearch.app.services.TestEngineContexts.agent()), "an empty paths array proves nothing");
    assertFalse(scope.coversArguments(op, "{\"paths\":[123]}", io.justsearch.app.services.TestEngineContexts.agent()), "a non-string entry");
    assertFalse(scope.coversArguments(op, "{\"paths\":[\"  \"]}", io.justsearch.app.services.TestEngineContexts.agent()), "a blank entry");
  }

  @Test
  @DisplayName("containment is canonicalized, so a sibling with a shared name prefix is not inside")
  void siblingPrefixIsNotInside(@TempDir Path parent) throws Exception {
    Path root = Files.createDirectory(parent.resolve("docs"));
    Path sibling = Files.createDirectory(parent.resolve("docs-private"));
    Path secret = Files.createFile(sibling.resolve("s.txt"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(secret), io.justsearch.app.services.TestEngineContexts.agent()),
        "startsWith is path-element-wise after realpath, not a string prefix");
  }

  @Test
  @DisplayName("a link straddling the root boundary is not covered — realpath, not normalize()")
  void linkOutOfRootIsNotCovered(@TempDir Path base) throws Exception {
    // Without this case, both containment tests above pass under a plain normalize()+startsWith,
    // so the toRealPath() defense this class hand-copies is asserted only in its javadoc and a
    // later edit could drop it with nothing red (`green-masked-destructive`). Ported from
    // AgentToolPathsTest#rejectsPathThatOnlyLooksInRootBeforeSymlinkResolution.
    Path root = Files.createDirectory(base.resolve("indexed"));
    Path outside = Files.createDirectory(base.resolve("elsewhere"));
    Path secret = Files.createFile(outside.resolve("secret.txt"));

    Path link = root.resolve("link");
    linkDirectory(link, outside);

    Path escaping = link.resolve("secret.txt");
    assertTrue(Files.exists(escaping), "Precondition: the escaping path resolves to the secret");
    assertTrue(
        escaping.normalize().startsWith(root),
        "Precondition: the escaping path looks in-root before link resolution");

    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(escaping), io.justsearch.app.services.TestEngineContexts.agent()),
        "A link cannot straddle a root boundary into a durable grant: " + secret);
  }

  /**
   * Creates {@code link} pointing at {@code target}. Windows refuses {@code createSymbolicLink}
   * without developer mode / SeCreateSymbolicLink, so it falls back to a directory JUNCTION, which
   * needs no privilege and is the escape vector a real user is most likely to have on disk. Aborts
   * only the calling test if neither is available.
   */
  private static void linkDirectory(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return;
    } catch (IOException | UnsupportedOperationException e) {
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
        Assumptions.abort("Platform cannot create symbolic links: " + e.getMessage());
      }
    }
    try {
      Process p =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
              .redirectErrorStream(true)
              .start();
      if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
        p.destroy();
        Assumptions.abort("Platform cannot create a directory junction either");
      }
    } catch (IOException e) {
      Assumptions.abort("Platform cannot create a directory junction either: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Assumptions.abort("Interrupted while creating a directory junction");
    }
  }

  @Test
  @DisplayName("a not-yet-existing path is canonicalized via its closest existing ancestor")
  void nonExistentPathResolvesThroughClosestExistingAncestor(@TempDir Path base) throws Exception {
    Path root = Files.createDirectory(base.resolve("indexed"));
    Path outside = Files.createDirectory(base.resolve("elsewhere"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertTrue(
        scope.coversArguments(op(GOVERNED), argsFor(root.resolve("not").resolve("there.txt")), io.justsearch.app.services.TestEngineContexts.agent()),
        "in-root but absent ⇒ still provably inside via the ancestor's real path");
    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(outside.resolve("not").resolve("there.txt")), io.justsearch.app.services.TestEngineContexts.agent()),
        "out-of-root and absent ⇒ still outside");
  }

  private static Operation op(OperationRef id) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }
}
