package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(
    packages = "io.justsearch",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class IndexWriterOwnershipTest {

  /**
   * Modules allowed to depend on {@code org.apache.lucene} classes. Every other module must stay
   * Lucene-free. Adding an entry here requires updating {@link
   * #luceneOwnerAllowlistSizeIsControlled()} and documenting the reason.
   */
  private static final String[] LUCENE_OWNER_PACKAGES = {
    "io.justsearch.adapters.lucene..", // Primary Lucene adapter (index read/write)
    "io.justsearch.indexerworker..", // index component owns Lucene lifecycle
  };

  private static final DescribedPredicate<JavaConstructorCall> TARGETS_INDEX_WRITER =
      new DescribedPredicate<>("target owner is IndexWriter") {
        @Override
        public boolean test(JavaConstructorCall call) {
          return call.getTargetOwner().isAssignableTo(IndexWriter.class);
        }
      };

  @ArchTest
  static final ArchRule luceneIndexWriterOwnedByAdapters =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch..")
          .and()
          .resideOutsideOfPackage("io.justsearch.adapters.lucene..")
          .should()
          .callConstructorWhere(TARGETS_INDEX_WRITER)
          .because(
              "IndexWriter construction implies write-lock ownership, "
                  + "which only adapters-lucene may hold");

  @ArchTest
  static final ArchRule onlyLuceneOwnersMayDependOnLuceneClasses =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch..")
          .and()
          .resideOutsideOfPackage(LUCENE_OWNER_PACKAGES[0])
          .and()
          .resideOutsideOfPackage(LUCENE_OWNER_PACKAGES[1])
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.lucene..")
          .as("only adapters-lucene and indexer-worker may depend on Lucene classes")
          .because(
              "Lucene is an implementation detail of the index layer; "
                  + "non-owner modules must use in-process port or service abstractions");

  @Test
  void luceneOwnerAllowlistSizeIsControlled() {
    assertEquals(
        2,
        LUCENE_OWNER_PACKAGES.length,
        "Adding a Lucene owner requires updating this assertion and documenting the reason");
  }
}
