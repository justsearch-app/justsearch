package io.justsearch.adapters.lucene.runtime;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 912 item 2 — makes the commit-trigger census and writer ownership invariant instead of
 * a dated audit.
 *
 * <p>The census enumerated every path that reaches {@link CommitOps#commitAndTrack(CommitReason)}
 * — the funnel that increments the per-reason counter, resets {@code pendingDocs}, fires
 * {@code TelemetryEvents.onCommit} and notifies the {@code CommitCompletedListener}. The lifecycle
 * exceptions are documented below; this rule rejects commit owners outside that explicit set
 * instead of silently making the attribution wrong (`audit-without-test`).
 *
 * <p>Scope limit, stated rather than implied: ArchUnit sees this module's classes, and
 * {@code IndexWriter} write access is confined to this module. The one bypass that used to live
 * outside that reach — {@code KnowledgeServerMigrationOps} in {@code modules/indexer-worker}
 * calling the low-level {@code CommitOps.commit()} — is closed by construction rather than by this
 * rule: {@code commit()} is package-private, so no other module can call it at all (tempdoc 915,
 * closing 912 §D.2). Making the bypass impossible beats widening an allowlist.
 */
class CommitFunnelArchTest {

  private static JavaClasses runtimeClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.justsearch.adapters.lucene");
  }

  /**
   * Every commit allowlisted class, with the reason it is allowed. Adding a name here is a
   * deliberate statement that the commit it makes is invisible to {@code commitCount} and to the
   * {@code index.runtime.commit_total} attribution, and that this is acceptable for that site.
   *
   * <ul>
   *   <li>{@code CommitOps} — IS the funnel. Its {@code w.commit()} is the commit every counted
   *       path routes through.
   *   <li>{@code RuntimeSession} — the {@code materializeEmptyIndex} lifecycle bootstrap commit
   *       (creates an empty index so the Head can report {@code indexAvailable}). Runtime close is
   *       allowed by a separate lifecycle rule, but must configure {@code commitOnClose(false)} and
   *       route any covering commit through the explicit {@code CommitOps} funnel.
   *   <li>{@code ComponentsFactory} — the fresh-writable bootstrap {@code commit()}, which makes a
   *       neutral zero-doc index durable before a session exists. Its open-failure cleanup close
   *       is allowed only as lifecycle cleanup and must not rely on an implicit commit.
   * </ul>
   */
  private static final String[] COMMIT_ALLOWED = {
    "CommitOps", "RuntimeSession", "ComponentsFactory"
  };
  private static final String[] CLOSE_ALLOWED = {"RuntimeSession", "ComponentsFactory"};

  private static ArchRule logicalWriterMutationRule() {
    return noClasses()
        .that()
        .resideInAPackage("io.justsearch.adapters.lucene..")
        .and()
        .doNotHaveSimpleName("WritePathOps")
        .should()
        .callMethodWhere(
            target(owner(assignableTo(IndexWriter.class)))
                .and(
                    target(name("addDocument"))
                        .or(target(name("addDocuments")))
                        .or(target(name("updateDocument")))
                        .or(target(name("updateDocuments")))
                        .or(target(name("softUpdateDocument")))
                        .or(target(name("softUpdateDocuments")))
                        .or(target(name("deleteDocuments")))
                        .or(target(name("tryDeleteDocument")))
                        .or(target(name("tryUpdateDocValue")))
                        .or(target(name("updateNumericDocValue")))
                        .or(target(name("updateBinaryDocValue")))
                        .or(target(name("updateDocValues")))
                        .or(target(name("deleteAll")))
                        .or(target(name("addIndexes")))
                        .or(target(name("forceMerge")))
                        .or(target(name("forceMergeDeletes")))))
        .because(
            "all logical IndexWriter mutations, including forceMerge* maintenance, must stay in"
                + " package-private WritePathOps; callers use IndexingCoordinator so the writer"
                + " barrier and mutation ownership remain centralized");
  }

  @Test
  void onlyWritePathOpsOwnsLogicalWriterMutations() {
    logicalWriterMutationRule().check(runtimeClasses());
  }

  @Test
  void onlyTheFunnelAndBootstrapSitesCommit() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.justsearch.adapters.lucene..")
            .and()
            .doNotHaveSimpleName(COMMIT_ALLOWED[0])
            .and()
            .doNotHaveSimpleName(COMMIT_ALLOWED[1])
            .and()
            .doNotHaveSimpleName(COMMIT_ALLOWED[2])
            .should()
            .callMethodWhere(
                target(owner(assignableTo(IndexWriter.class))).and(target(name("commit"))))
            .because(
                "a durable commit outside CommitOps.commitAndTrack is invisible to"
                    + " RuntimeSession.commitCount and to index.runtime.commit_total, so the"
                    + " commit-reason attribution silently under-counts (tempdoc 912 §A2/§C.4)."
                    + " Route the commit through commitAndTrack(CommitReason), or add the class"
                    + " to CommitFunnelArchTest.COMMIT_ALLOWED only for an explicit bootstrap");

    rule.check(runtimeClasses());
  }

  @Test
  void onlyNamedLifecycleOwnersCloseIndexWriter() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.justsearch.adapters.lucene..")
            .and()
            .doNotHaveSimpleName(CLOSE_ALLOWED[0])
            .and()
            .doNotHaveSimpleName(CLOSE_ALLOWED[1])
            .should()
            .callMethodWhere(
                target(owner(assignableTo(IndexWriter.class))).and(target(name("close"))))
            .because(
                "IndexWriter.close is lifecycle cleanup owned only by RuntimeSession or"
                    + " ComponentsFactory; runtime close must use commitOnClose(false) and an"
                    + " explicit CommitOps covering commit");

    rule.check(runtimeClasses());
  }

  @Test
  void logicalWriterMutationRuleRejectsTheForbiddenFixture() {
    AssertionError violation =
        assertThrows(
            AssertionError.class,
            () -> logicalWriterMutationRule().check(forbiddenFixtureClasses()));
    assertTrue(
        violation.getMessage() != null
            && violation.getMessage().contains("ForbiddenMutationFixture")
            && violation.getMessage().contains("deleteAll")
            && violation.getMessage().contains("updateNumericDocValue"),
        "ArchUnit must report both imported raw-mutation calls");
  }

  /**
   * The low-level {@code CommitOps.commit()} commits without touching the counter or telemetry —
   * it exists for {@code commitAndTrack} to build on. Nothing else in this module may call it, so
   * the funnel stays the only in-module way to produce a counted commit.
   */
  @Test
  void theLowLevelCommitIsReachedOnlyFromTheFunnel() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.justsearch.adapters.lucene..")
            .and()
            .doNotHaveSimpleName("CommitOps")
            .should()
            .callMethodWhere(
                target(owner(assignableTo(CommitOps.class))).and(target(name("commit"))))
            .because(
                "CommitOps.commit() is the uncounted primitive; callers want"
                    + " commitAndTrack(CommitReason) so the commit is attributed");

    rule.check(runtimeClasses());
  }

  private static JavaClasses forbiddenFixtureClasses() {
    // Keep the production scan test-excluded; this separately imported class is deliberately
    // forbidden so the mutation rule's predicate cannot silently become vacuous.
    return new ClassFileImporter().importClasses(ForbiddenMutationFixture.class);
  }

  private static final class ForbiddenMutationFixture {
    static void rawMutation(IndexWriter writer) throws IOException {
      writer.deleteAll();
      writer.updateNumericDocValue(new Term("fixture", "id"), "value", 1L);
    }
  }
}
