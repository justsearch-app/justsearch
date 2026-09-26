/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class WorkerMutationAdmissionTest {

  @Test
  void finalFenceDrainsIssuedEffectAndRefusesAWaiterAfterBPublication() throws Exception {
    Object a = new Object();
    Object b = new Object();
    var admission = new WorkerMutationAdmission(a);
    try (var ignored = admission.enter(a)) {
      assertNull(admission.beginFinalFence(a, 0));
    }

    CompletableFuture<Boolean> lateA;
    try (var fence = admission.beginFinalFence(a, 1_000)) {
      assertNotNull(fence);
      var started = new CountDownLatch(1);
      lateA = CompletableFuture.supplyAsync(() -> {
        started.countDown();
        try (var ignored = admission.enter(a)) { return false; }
        catch (IllegalStateException refused) { return true; }
      });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      fence.install(b);
      fence.certifySuccessor();
      // The A waiter remains behind this write lease until the publication owner releases it.
    }
    assertTrue(lateA.get(1, TimeUnit.SECONDS));
    assertThrows(IllegalStateException.class, () -> admission.enter(a));
    try (var successor = admission.enter(b)) { assertNotNull(successor); }
  }

  @Test
  void precommitAbandonmentKeepsAOwner() throws Exception {
    Object a = new Object();
    var admission = new WorkerMutationAdmission(a);
    try (var fence = admission.beginFinalFence(a, 1_000)) { assertNotNull(fence); }
    try (var stillA = admission.enter(a)) { assertNotNull(stillA); }
  }

  @Test
  void committedButUncertifiedSuccessorRefusesBothMutationOwners() throws Exception {
    Object a = new Object();
    Object b = new Object();
    var admission = new WorkerMutationAdmission(a);
    try (var fence = admission.beginFinalFence(a, 1_000)) {
      fence.install(b);
    }
    assertThrows(IllegalStateException.class, () -> admission.enter(a));
    assertThrows(IllegalStateException.class, () -> admission.enter(b));
  }
}
