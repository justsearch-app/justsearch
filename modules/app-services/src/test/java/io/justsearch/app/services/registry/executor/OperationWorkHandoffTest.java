/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.services.TestEngineContexts;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperationWorkHandoffTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedWorkRemovesAllRegistrationsOnlyAtActualCompletion(boolean nested) {
    var fixture = new Fixture(nested);
    try (var handoff = fixture.handoff) {
      handoff.accept(mock(OperationAttemptRunner.AcceptanceScope.class), context -> context);
      handoff.accepted();
    }
    verifyNoInteractions(fixture.parentCancellation, fixture.parentCompletion, fixture.childCancellation);
    assertNotNull(fixture.completed.get());
    fixture.completed.get().run();
    fixture.verifyClosed(nested);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusedAcceptanceRemovesAllRegistrationsWithoutACompletionCallback(boolean nested) {
    var fixture = new Fixture(nested);
    var acceptance = mock(OperationAttemptRunner.AcceptanceScope.class);
    try (var handoff = fixture.handoff) {
      assertThrows(IllegalStateException.class, () -> handoff.accept(acceptance, context -> {
        throw new IllegalStateException("authorization refused");
      }));
    }
    verifyNoInteractions(acceptance);
    assertNull(fixture.completed.get());
    fixture.verifyClosed(nested);
  }

  private static final class Fixture {
    final EngineWorkHandle.Registration parentCancellation = mock(EngineWorkHandle.Registration.class);
    final EngineWorkHandle.Registration parentCompletion = mock(EngineWorkHandle.Registration.class);
    final EngineWorkHandle.Registration childCancellation = mock(EngineWorkHandle.Registration.class);
    final AtomicReference<Runnable> completed = new AtomicReference<>();
    final OperationWorkHandoff handoff;

    Fixture(boolean nested) {
      var admission = mock(EngineAdmissionService.class);
      var parent = mock(EngineWorkHandle.class);
      var child = mock(EngineWorkHandle.class);
      var effect = TestEngineContexts.mcp();
      var caller = nested ? effect.withWorkId(UUID.randomUUID()) : effect;
      if (nested) {
        when(admission.attach(caller)).thenReturn(parent);
        when(parent.onCancel(any())).thenReturn(parentCancellation);
        when(parent.onCompletion(any())).thenReturn(parentCompletion);
      }
      when(admission.attach(effect)).thenReturn(child);
      when(child.context()).thenReturn(effect.withWorkId(UUID.randomUUID()));
      when(child.onCancel(any())).thenReturn(childCancellation);
      when(child.onCompletion(any())).thenAnswer(call -> {
        completed.set(call.getArgument(0));
        return mock(EngineWorkHandle.Registration.class);
      });
      handoff = new OperationWorkHandoff(admission, caller, effect);
    }

    void verifyClosed(boolean nested) {
      verify(childCancellation).close();
      if (nested) {
        verify(parentCancellation).close();
        verify(parentCompletion).close();
      } else {
        verifyNoInteractions(parentCancellation, parentCompletion);
      }
    }
  }
}
