/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The foreground gauge's operation set, checked against the labels the ops layer ACTUALLY passes.
 *
 * <p><b>Why this file exists.</b> {@code ForegroundLoadGate}'s set was written by mirroring
 * {@code ForegroundLoadInterceptor}'s gRPC method names — {@code "Search"}, {@code "FetchDocuments"}
 * — while the seam it sits on passes lower-camel operation labels. It never matched once, so from
 * item A6 until the review caught it {@code ForegroundLoad} read zero on every call and
 * {@code IndexingPacing} never yielded to a waiting search. Both existing suites were green
 * throughout, because both fed the gate the vocabulary the gate itself declared. That is the
 * {@code wrong-gate} case: a drift pin that compares a set against itself cannot detect drift.
 *
 * <p>So this pin reads {@code SearchRpcOps.java}'s SOURCE and extracts the operation labels from the
 * call sites, then compares them with the gate's set. A source scan rather than reflection because
 * the labels are string literals at call sites, not a declared constant — there is no runtime
 * object to ask. It is a weaker mechanism than a shared constant and is not pretending otherwise;
 * what it buys is that the two sides can no longer drift silently, which is the only property that
 * was missing.
 *
 * <p>Extracting the labels into a constant both modules import would be better. It is not
 * available: {@code SearchRpcOps} lives in {@code app-services} and the gate in {@code app-engine},
 * and the dependency runs the wrong way for the gate to be the source of truth.
 */
@DisplayName("foreground gauge <-> SearchRpcOps label parity")
final class ForegroundGateMatchesSearchOpsTest {

  private static final Path SEARCH_RPC_OPS =
      Path.of(
          "..", "app-services", "src", "main", "java", "io", "justsearch", "app", "services",
          "worker", "SearchRpcOps.java");

  /**
   * Operations that reach the seam but must NOT count as foreground load.
   *
   * <p>{@code listAllDocumentIds} is a bulk enumeration used by maintenance paths, not a user
   * waiting on an answer; counting it would make a background sweep throttle indexing. Its sibling
   * exclusion {@code indexStatus} lives on the ingest ops class and never reaches this file.
   */
  private static final Set<String> DELIBERATE_EXCLUSIONS = Set.of("listAllDocumentIds");

  /** The operation-label argument of an execute*Rpc call: a lower-camel string literal on its own line. */
  private static final Pattern LABEL = Pattern.compile("^\\s+\"([a-z][A-Za-z]*)\",$", Pattern.MULTILINE);

  private static Set<String> labelsPassedBySearchRpcOps() throws Exception {
    assertTrue(
        Files.isRegularFile(SEARCH_RPC_OPS),
        "SearchRpcOps.java not found at " + SEARCH_RPC_OPS.toAbsolutePath()
            + " — if it moved, this pin must follow it rather than silently scanning nothing");
    String src = Files.readString(SEARCH_RPC_OPS);
    Set<String> labels = new LinkedHashSet<>();
    Matcher m = LABEL.matcher(src);
    while (m.find()) {
      labels.add(m.group(1));
    }
    return labels;
  }

  @Test
  @DisplayName("the gate counts every search-family operation, and only those")
  void gateSetEqualsSearchOpsLabelsMinusExclusions() throws Exception {
    Set<String> passed = labelsPassedBySearchRpcOps();

    // Vacuous-pass guard: a renamed file or a reformatted call site would yield an empty scan, and
    // an empty scan makes the comparison below pass over nothing.
    assertTrue(
        passed.size() >= 10,
        "the scan found only " + passed + " — it is matching call sites, not the whole file, so a "
            + "small result means the pattern broke, not that the ops layer shrank");

    Set<String> expected = new LinkedHashSet<>(passed);
    expected.removeAll(DELIBERATE_EXCLUSIONS);

    assertEquals(
        expected,
        new LinkedHashSet<>(ForegroundLoadGate.foregroundOperations()),
        "the gate's set and the labels SearchRpcOps passes have diverged. A label the ops layer "
            + "passes but the gate does not know is a search that no longer counts as foreground "
            + "load, so indexing stops yielding to it. A label the gate declares but nothing "
            + "passes is dead weight — and a set made ENTIRELY of those is the original defect: "
            + "the gate held gRPC method names while the seam passed operation labels, and read "
            + "zero for three items without a single test failing.");
  }

  @Test
  @DisplayName("every exclusion is a label that really is passed, not a stale name")
  void exclusionsAreRealLabels() throws Exception {
    Set<String> passed = labelsPassedBySearchRpcOps();
    for (String excluded : DELIBERATE_EXCLUSIONS) {
      assertTrue(
          passed.contains(excluded),
          "'" + excluded + "' is declared as a deliberate exclusion, but SearchRpcOps does not pass "
              + "it. An exclusion for an operation that does not exist reads as a considered "
              + "decision while excluding nothing.");
    }
  }
}
