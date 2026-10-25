/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class WriterAndIdentityServiceAvailabilityTest extends SharedServiceAvailabilityTest {
  @Override
  @Timeout(45)
  @ParameterizedTest(name = "{0}")
  @EnumSource(value = State.class, names = WRITER_AND_IDENTITY_STATES, mode = EnumSource.Mode.MATCH_ANY)
  void sharedServiceStatesDeferHealthyFilesAndRecover(State state) throws Exception {
    super.sharedServiceStatesDeferHealthyFilesAndRecover(state);
  }
}
