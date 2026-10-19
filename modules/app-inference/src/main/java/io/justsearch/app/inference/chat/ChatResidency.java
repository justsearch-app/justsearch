/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The chat controller's residency surface (Spec A step 9): it unloads an idle GPU chat server when
 * another GPU user needs the memory, and decides per demand whether chat loads again, waits or is
 * refused (Spec A §4.3, T21-T26).
 */
public interface ChatResidency {

  /**
   * The demand kind of a chat request (reconciliation F8/CH1): a person's only when the request
   * says it may load ({@code allowLoad}, which only the app's chat submit sends) and comes from the
   * app's own client; every other chat request is an agent's.
   */
  static DemandKind chatDemandKind(EngineContext.ClientKind clientKind, boolean allowLoad) {
    boolean appClient = clientKind == EngineContext.ClientKind.WEBVIEW
        || clientKind == EngineContext.ClientKind.PAIRED_DEVICE;
    return allowLoad && appClient ? DemandKind.HUMAN_CHAT : DemandKind.AGENT_CHAT;
  }

  /** Reports external GPU pressure; {@link PressureSignal#NONE} clears it. Never blocks. */
  void reportPressure(PressureSignal signal);

  /** The pressure last reported. */
  PressureSignal reportedPressure();

  /** The machine's co-residence profile: on {@link Profile#P16_PLUS} a game alone unloads nothing. */
  void setProfile(Profile profile);

  /** Why chat is unloaded for another GPU user, or empty while it is not. */
  Optional<StopDetail> unloaded();

  /** A failed normal-configuration restoration exhausted its automatic retry budget. */
  default boolean vduRestorationRetriesStopped() { return false; }

  /**
   * The ordinary reload's permission and the instant before which it may not start, from the same
   * state as demand refusals. The instant is absolute and stays the same for a given wait.
   */
  record ReloadStatus(boolean allowed, Optional<Instant> notBefore) {}

  /** Empty outside a residency unload, or when this implementation has no status producer. */
  default Optional<ReloadStatus> reloadStatus() {
    return Optional.empty();
  }

  /**
   * Admits one demand against the residency state. While chat is not unloaded the stage is already
   * complete. While it is, a human or agent demand that may load starts the reload and the stage
   * completes when that start ends; a background demand waits for a load it never starts.
   *
   * @throws ChatRefusal when the demand may not load now (pressure, the dwell after an unload, or a
   *     kind that never loads)
   */
  CompletionStage<Void> demand(Demand demand) throws ChatRefusal;

  /**
   * The app's explicit "load anyway" for a person (Spec A T26): ignores pressure and the dwell. An
   * unload happens again after a confirmed clear or a more severe effective pressure level.
   *
   * @param human the request's context; only the app's own client kinds are a person
   * @throws ChatRefusal when the request is not a person's, or chat is not unloaded for pressure
   */
  CompletionStage<Void> loadAnyway(EngineContext human) throws ChatRefusal;
}
