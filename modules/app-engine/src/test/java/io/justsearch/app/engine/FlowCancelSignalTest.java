/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class FlowCancelSignalTest {
  @Test
  void cancellationNotifiesNativeChildGroupAndProducerSubscriptionExactlyOnce() {
    FlowCancelSignal signal = new FlowCancelSignal();
    AtomicInteger childGroup = new AtomicInteger();
    AtomicInteger producerSubscription = new AtomicInteger();
    AtomicInteger late = new AtomicInteger();
    signal.onCancel(childGroup::incrementAndGet);
    signal.onCancel(producerSubscription::incrementAndGet);

    signal.cancel();
    signal.cancel();
    signal.onCancel(late::incrementAndGet);

    assertEquals(1, childGroup.get());
    assertEquals(1, producerSubscription.get());
    assertEquals(1, late.get());
  }
}
