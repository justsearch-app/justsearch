package io.justsearch.adapters.lucene.runtime;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 912 item 2 — makes the commit-trigger census an invariant instead of a dated audit.
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
   * Every allowlisted class, with the reason it is allowed. Adding a name here is a deliberate
   * statement that the commit it makes is invisible to {@code commitCount} and to the
   * {@code index.runtime.commit_total} attribution, and that this is acceptable for that site.
   *
   * <ul>
   *   <li>{@code CommitOps} — IS the funnel. Its {@code w.commit()} is the commit every counted
   *       path routes through.
   *   <li>{@code RuntimeSession} — two lifecycle commits: {@code materializeEmptyIndex} (creates
   *       an empty index so the Head can report {@code indexAvailable}) and the session-teardown
   *       {@code writer().close()}, which commits implicitly because Lucene's {@code
   *       commitOnClose} defaults to true.
   *   <li>{@code ComponentsFactory} — the fresh-writable bootstrap {@code commit()}, which makes a
   *       neutral zero-doc index durable before a session exists, plus the open-failure cleanup
   *       {@code close()}, whose implicit commit likewise occurs before a session can count it.
   * </ul>
   */
  private static final String[] ALLOWED = {"CommitOps", "RuntimeSession", "ComponentsFactory"};

  @Test
  void onlyTheFunnelAndTheNamedLifecycleSitesTouchIndexWriterCommitOrClose() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.justsearch.adapters.lucene..")
            .and()
            .doNotHaveSimpleName(ALLOWED[0])
            .and()
            .doNotHaveSimpleName(ALLOWED[1])
            .and()
            .doNotHaveSimpleName(ALLOWED[2])
            .should()
            .callMethodWhere(
                target(owner(assignableTo(IndexWriter.class)))
                    .and(target(name("commit")).or(target(name("close")))))
            .because(
                "a durable commit outside CommitOps.commitAndTrack is invisible to"
                    + " RuntimeSession.commitCount and to index.runtime.commit_total, so the"
                    + " commit-reason attribution silently under-counts (tempdoc 912 §A2/§C.4)."
                    + " Route the commit through commitAndTrack(CommitReason), or add the class"
                    + " to CommitFunnelArchTest.ALLOWED with the reason it cannot be");

    rule.check(runtimeClasses());
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
}
