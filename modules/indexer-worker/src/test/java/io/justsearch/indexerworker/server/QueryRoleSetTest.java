/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.reranker.CrossEncoderReranker;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class QueryRoleSetTest {
  @Test
  void issuedLeaseKeepsQueryHandleAndWrapperAliveUntilItLeaves() throws Exception {
    var surface = querySurface();
    var owner = new QueryRoleSet(surface);
    var wrapper = owner.own(mock(CrossEncoderReranker.class));
    owner.bindReranker(wrapper);
    var issued = owner.acquire();
    var retirement = CompletableFuture.runAsync(owner::close);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    boolean retiring = false;
    while (!retiring && System.nanoTime() < deadline) {
      try {
        var probe = owner.acquire();
        probe.close();
        Thread.onSpinWait();
      } catch (IllegalStateException expected) {
        retiring = true;
      }
    }
    assertTrue(retiring);
    assertFalse(retirement.isDone());
    issued.close();
    retirement.get(2, TimeUnit.SECONDS);
    assertTrue(owner.isClosed());
    var order = inOrder(surface, wrapper);
    order.verify(surface).close();
    order.verify(wrapper).close();
    assertThrows(IllegalStateException.class, owner::acquire);
  }

  @Test
  void refusedNativeRetirementKeepsWrapperForRetry() {
    var surface = querySurface();
    doThrow(new IllegalStateException("native refused")).doNothing().when(surface).close();
    var owner = new QueryRoleSet(surface);
    var wrapper = owner.own(mock(CrossEncoderReranker.class));
    owner.bindReranker(wrapper);

    assertThrows(IllegalStateException.class, owner::close);
    assertFalse(owner.isClosed());
    verify(wrapper, org.mockito.Mockito.never()).close();

    owner.close();
    assertTrue(owner.isClosed());
    verify(wrapper).close();
  }

  private static InferenceSurface querySurface() {
    var surface = mock(InferenceSurface.class);
    when(surface.embedding()).thenReturn(Optional.empty());
    when(surface.ner()).thenReturn(Optional.empty());
    when(surface.splade()).thenReturn(Optional.empty());
    when(surface.bgeM3()).thenReturn(Optional.empty());
    return surface;
  }
}
