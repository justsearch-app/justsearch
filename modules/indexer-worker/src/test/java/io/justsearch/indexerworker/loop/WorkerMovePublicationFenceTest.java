/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original MOVE contract, isolated from unrelated batch-constructor fixture changes upstream. */
class WorkerMovePublicationFenceTest extends LuceneExecutorTestBase {
  @TempDir Path temp;
  private static String key(Path path) { return PathNormalizer.normalizeKey(path); }
  @Test
  @org.junit.jupiter.api.Timeout(30)
  void RF55MoveDestinationPolicyAndIdentityAreRecheckedInsideThePublicationFence() throws Exception {
    moveDestinationPolicyRace(true, false);
  }

  @Test
  @org.junit.jupiter.api.Timeout(30)
  void RF55AllowedMovePreservesPhysicalOutcomeIdentityAndIndexedContent() throws Exception {
    moveDestinationPolicyRace(false, false);
  }

  @Test
  @org.junit.jupiter.api.Timeout(30)
  void MD55ExpiredNonSingletonMoveCannotPublishAtWorkerFence() throws Exception {
    moveDestinationPolicyRace(false, true);
  }

  private void moveDestinationPolicyRace(boolean blocked, boolean expired) throws Exception {
    Path root = Files.createDirectory(temp.resolve("move-source"));
    Path original = Files.writeString(root.resolve("public.txt"), "physical original");
    Path destination = Files.createDirectory(root.resolve("private")).resolve("moved.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[]");
    var admitted = new java.util.concurrent.CountDownLatch(1);
    var resume = new java.util.concurrent.CountDownLatch(1);
    try (var queue = new SqliteJobQueue(temp.resolve("move-jobs.db"))) {
      queue.open();
      try (var identities = new io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore(temp.resolve("move-jobs.db"));
          RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
              .atPath(temp.resolve("move-lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
        runtime.commitOps().stopCommitTimer();
        String oldHash = io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(key(original));
        String newHash = io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(key(destination));
        var identity = identities.resolve(oldHash, System.currentTimeMillis());
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, key(original), SchemaFields.DOC_UID, identity.docUid(),
            SchemaFields.PATH, key(original), SchemaFields.CONTENT, "indexed original")));
        runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
        var fence = new FileMutationFence();
        var loop = mock(IndexingLoop.class);
        org.mockito.Mockito.when(loop.withFileMutationFence(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
          // This existing seam is reached after the optimistic destination check. A policy save
          // wins before the actual file fence, while real identity/Lucene work remains untouched.
          admitted.countDown(); assertTrue(resume.await(10, java.util.concurrent.TimeUnit.SECONDS));
          java.util.function.Supplier<?> publication = call.getArgument(0);
          return fence.guard(publication);
        });
        var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
            null, null, runtime, runtime, null, 0L);
        Object owner = new Object();
        service.setMutationAdmission(new io.justsearch.indexerworker.services.WorkerMutationAdmission(owner), owner);
        service.setDocumentIdentityStore(identities);
        service.useExclusionRoots(() -> List.of(root));
        Files.move(original, destination); // The user's physical move has already succeeded.
        var maintenance = CallContext.none();
        var context = expired ? new CallContext(null, null, CallContext.CancelSignal.NEVER,
            maintenance.engineContext(), maintenance.provenance(), maintenance.childLifetime(),
            System.nanoTime() - 1) : maintenance;
        assertFalse(context.cancelled(), "the elapsed-clock check must not borrow cancellation");
        var moving = FenceRace.start("forget-policy-move", () -> service.updateDocumentPaths(
            io.justsearch.ipc.UpdatePathsRequest.newBuilder().addMappings(io.justsearch.ipc.PathMapping.newBuilder()
                .setOldPath(original.toString()).setNewPath(destination.toString())).build(), context));
        try {
          assertTrue(admitted.await(10, java.util.concurrent.TimeUnit.SECONDS));
          installed.update(config(blocked ? "[\"private/**\"]" : "[]"));
        } finally { resume.countDown(); }
        var response = moving.get();
        assertEquals(blocked || expired ? 0 : 1, response.getUpdatedCount(), expired
            ? "an elapsed non-singleton request cannot publish identity or indexed rows"
            : "a newly excluded destination cannot publish identity or indexed rows");
        runtime.commitOps().maybeRefreshBlocking();
        if (blocked || expired) {
          assertEquals(identity, identities.lookup(oldHash).orElseThrow());
          assertTrue(identities.lookup(newHash).isEmpty());
          assertEquals(null, runtime.documentFieldOps().getDocumentField(key(destination), SchemaFields.DOC_ID));
          assertEquals("indexed original", runtime.documentFieldOps().getDocumentContent(key(original)));
        } else {
          assertTrue(identities.lookup(oldHash).isEmpty());
          assertEquals(identity.docUid(), identities.lookup(newHash).orElseThrow().docUid());
          assertEquals("indexed original", runtime.documentFieldOps().getDocumentContent(key(destination)));
        }
        assertTrue(Files.exists(destination)); assertTrue(Files.notExists(original));
        assertEquals("physical original", Files.readString(destination), "forget does not reverse physical moves");
      }
    } finally { resume.countDown(); ConfigStore.restoreGlobal(installed, previous); }
  }

  private static ConfigStore install(String patterns) {
    var installed = new ConfigStore(config(patterns));
    ConfigStore.setGlobal(installed);
    return installed;
  }
  private static ResolvedConfig config(String patterns) {
    return ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns", patterns).build();
  }
}
