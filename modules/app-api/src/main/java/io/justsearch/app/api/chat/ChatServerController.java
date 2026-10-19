/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.core.context.EngineContext;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * The single writer of the managed chat server's lifecycle (ADR-0050). Every load, unload, hold and
 * generation admission goes through it, and {@link #status()} is the one snapshot every chat
 * projection derives from.
 *
 * <p>Desired-state inputs are asynchronous and never block the caller. {@link #prepare}, {@link
 * #beginSelfTest}, {@link #holdVdu} and {@link #holdRunning} block for at most 30 seconds; callers
 * must not hold a lock that the controller thread reads.
 *
 * @param <C> the chat configuration type, owned by the implementation (it is not an app-api type)
 * @param <S> the self-test specification type, owned by the implementation
 * @param <R> the self-test result type, owned by the implementation
 */
public interface ChatServerController<C, S, R> extends AutoCloseable, ChatDeviceLoss {

  // ---- observation ----

  ChatStatus status();

  /** Delivers each published status off the caller's thread, in order, coalesced. */
  AutoCloseable subscribe(Consumer<ChatStatus> listener);

  GenerationActivity generation();

  // ---- desired-state inputs ----

  CompletionStage<Outcome> setActivation(Activation activation);

  /** The first phase of a two-phase settings commit. Blocks for at most 30 seconds. */
  PreparedChange<C> prepare(C candidate, boolean enabled, boolean restartIfOnline)
      throws ChatRefusal;

  CompletionStage<Outcome> apply(C next, ApplyPolicy policy, LoadingCause cause);

  void setProfile(Profile profile);

  /** Reports external GPU pressure; {@link PressureSignal#NONE} clears it. */
  void reportPressure(PressureSignal signal);

  /** The query encoders are warm, so the first load after an Engine start may proceed. */
  void reportQueryEncodersReady();

  // reportDeviceLost and reportResume: see ChatDeviceLoss.

  // ---- holds ----

  /** Stops the server for video-document understanding until the hold closes. Blocks at most 30 s. */
  VduHold holdVdu(String owner) throws ChatRefusal;

  /** Keeps the server running (install smoke test). Blocks for at most 30 seconds. */
  Hold holdRunning(String owner) throws ChatRefusal;

  /** Freezes convergence while a model is being acquired. */
  Hold freezeConvergence(String owner);

  /** Stops the main server while the self-test runs. Blocks for at most 30 seconds. */
  SelfTestSession<S, R> beginSelfTest(String owner) throws ChatRefusal;

  // ---- demand ----

  /** May wait (bounded by the demand) or fail with {@link ChatRefusal}. */
  CompletionStage<GenerationLease> admit(Demand demand);

  /** Never waits and never triggers a load. */
  Optional<GenerationLease> tryAdmit(Demand demand);

  /** The operator's recover action: clears a failure latch once. */
  CompletionStage<Outcome> retry(String operator);

  /** The app's explicit "load anyway" for a person; ignores pressure, not the other refusals. */
  CompletionStage<Outcome> loadAnyway(EngineContext human);

  // ---- terminal ----

  /** Stops the server for good. Interrupts any in-progress step. */
  void shutdown(StopReason reason, Duration budget);

  @Override
  void close();
}
