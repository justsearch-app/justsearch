/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.GenerationActivity;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Spec A step 9 on the chat controller: residency under external GPU pressure (T21-T26, T40, U13,
 * U17-U19, CH7; reconciliation v2 F6, F8/CH1, F18/P3). The dwell runs on a hand-driven clock.
 */
final class ChatResidencyTest {

  private static final Duration DWELL = ChatServerControllerImpl.RELOAD_DWELL;

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final ResidencyActuator actuator = new ResidencyActuator();
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private ChatServerControllerImpl controller;

  @AfterEach
  void tearDown() {
    if (controller != null) controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private ChatServerControllerImpl controller() {
    controller = new ChatServerControllerImpl(executors, actuator, ChatActuationRouter.DRAIN_BOUND,
        spec -> SelfTestRun.passed(), ChatServerControllerImpl.BOOT_GATE_TIMEOUT, clock::get);
    return controller;
  }

  /** A controller whose GPU server runs and is idle. */
  private ChatServerControllerImpl running() throws Exception {
    var c = controller();
    c.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(actuator.running.get());
    return c;
  }

  /** A controller that unloaded its idle server for {@code signal}; the dwell starts now. */
  private ChatServerControllerImpl unloadedFor(PressureSignal signal) throws Exception {
    var c = running();
    c.reportPressure(signal);
    barrier(c);
    assertTrue(c.unloaded().isPresent(), "unloaded for " + signal);
    return c;
  }

  /** Waits until every step queued so far, and its completion callbacks, has run. */
  private static void barrier(ChatServerControllerImpl c) throws Exception {
    c.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> null));
  }

  private void advance(Duration by) {
    clock.addAndGet(by.toNanos());
  }

  private static EngineContext context(EngineContext.ClientKind kind) {
    return new EngineContext(kind, "client-1", Optional.empty(), Optional.empty(), "tier", "http",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  }

  private static Demand demand(DemandKind kind, EngineContext.ClientKind client) {
    return new Demand(kind, context(client), Duration.ofSeconds(30));
  }

  private static ChatRefusal refused(ChatServerControllerImpl c, Demand demand) {
    return assertThrows(ChatRefusal.class, () -> c.demand(demand), demand.toString());
  }

  private List<String> unloads() {
    return actuator.log.stream().filter(entry -> entry.startsWith("unload:")).toList();
  }

  private List<String> loadsAfterUnload() {
    List<String> after = new ArrayList<>();
    boolean seen = false;
    for (String entry : actuator.log) {
      if (entry.startsWith("unload:")) seen = true;
      else if (seen && entry.startsWith("up:")) after.add(entry);
    }
    return after;
  }

  // ==================== U19 and T21 ====================

  @Test
  void u19NoPressureNeverUnloadsAnIdleServer() throws Exception {
    var c = running();
    c.reportPressure(PressureSignal.NONE);
    actuator.activity.edge(false);
    advance(Duration.ofMinutes(30));
    c.reportPressure(PressureSignal.NONE);
    barrier(c);
    assertEquals(List.of(), unloads());
    assertTrue(actuator.running.get());
    assertEquals(Optional.empty(), c.unloaded());
  }

  @Test
  void t21AnIdleGpuServerUnloadsUnderPressureWithAVisibleReason() throws Exception {
    var c = running();
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy"), unloads());
    assertFalse(actuator.running.get());
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
    assertEquals(PressureSignal.EXTERNAL_MEMORY, c.reportedPressure());
    var policy = actuator.policies.getLast();
    assertEquals(StopReason.RESIDENCY_PRESSURE, policy.reason());
    assertEquals(ChatDrainPolicy.Waiting.IF_IDLE, policy.waiting(),
        "an unload never waits for a reply; it only runs while idle");
  }

  @Test
  void aCpuServerIsNeverUnloadedForGpuPressure() throws Exception {
    actuator.gpu.set(false);
    var c = running();
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of(), unloads());
    assertEquals(Optional.empty(), c.unloaded());
  }

  // ==================== P3 / O4: the profile ====================

  @Test
  void p3OnSixteenGigabytesAGameAloneDoesNotUnloadButAnotherAppDoes() throws Exception {
    var c = running();
    c.setProfile(Profile.P16_PLUS);
    c.reportPressure(PressureSignal.GAME);
    barrier(c);
    assertEquals(List.of(), unloads(), "GAME on P16_PLUS leaves chat loaded");
    assertTrue(actuator.running.get());
    assertEquals(Optional.empty(), c.unloaded());
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy"), unloads(), "EXTERNAL_MEMORY always unloads");
  }

  @Test
  void untilTheProfileIsKnownAGameAloneLeavesChatLoadedButAnotherAppUnloadsIt() throws Exception {
    var c = running();
    c.reportPressure(PressureSignal.GAME);
    barrier(c);
    assertEquals(List.of(), unloads(), "UNRESOLVED keeps today's behaviour for a game (Spec E Q8)");
    assertTrue(actuator.running.get());
    assertEquals(Optional.empty(), c.unloaded());
    assertFalse(c.vduMayStart(), "bulk VDU work still does not start during a game");
    var human = c.demand(demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.WEBVIEW));
    assertTrue(human.toCompletableFuture().isDone(), "chat keeps serving during the game");
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy"), unloads(), "another app's memory need always unloads");
  }

  @Test
  void aGameUnloadsChatBelowSixteenGigabytesWithTheGameModeDetail() throws Exception {
    var c = running();
    c.setProfile(Profile.P12);
    c.reportPressure(PressureSignal.GAME);
    barrier(c);
    assertEquals(List.of("unload:game_mode"), unloads());
    assertEquals(Optional.of(StopDetail.GAME_MODE), c.unloaded());
  }

  // ==================== T22: a reply in progress defers the unload ====================

  @Test
  void t22AReplyInProgressDefersTheUnloadToTheNextIdleEdge() throws Exception {
    var c = running();
    actuator.activity.generating.set(true);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of(), unloads(), "a reply in progress is never cut");
    assertTrue(actuator.running.get());

    actuator.activity.edge(false);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy"), unloads(), "the idle edge resumes the unload");
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
  }

  @Test
  void aLeaseThatOpensBeforeTheUnloadDrainsMakesItWaitForTheNextIdleEdge() throws Exception {
    var c = running();
    actuator.generateAtDrain.set(true); // a request admitted between the decision and the drain
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of(), unloads());
    assertTrue(actuator.running.get());
    assertEquals(Optional.empty(), c.unloaded());

    actuator.activity.edge(false);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy"), unloads());
  }

  @Test
  void pressureThatClearsBeforeTheStopLeavesChatLoaded() throws Exception {
    var c = running();
    actuator.beforeUnloadBody = () -> c.reportPressure(PressureSignal.NONE);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of(), unloads());
    assertTrue(actuator.running.get());
    assertEquals(Optional.empty(), c.unloaded());
  }

  // ==================== F6: nothing but a demand reloads ====================

  @Test
  void f6AnUnloadedChatStaysUnloadedOverTenMinutesOfForwardedActivations() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.reportPressure(PressureSignal.NONE);
    for (int minute = 0; minute <= 10; minute++) {
      var activation = c.setActivation(
          new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START)).toCompletableFuture();
      var failure = assertThrows(ExecutionException.class, () -> activation.get(5, TimeUnit.SECONDS));
      assertInstanceOf(ChatActuationRouter.SupersededException.class, failure.getCause());
      advance(Duration.ofMinutes(1));
    }
    barrier(c);
    assertEquals(List.of(), loadsAfterUnload(), "chatEnabled=true does not reload an unloaded chat");
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
  }

  // ==================== U18: the dwell ====================

  @Test
  void u18NoDemandReloadsWithinTwoMinutesOfAPressureUnload() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.reportPressure(PressureSignal.NONE);
    advance(Duration.ofSeconds(60));

    var human = refused(c, demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.WEBVIEW));
    assertEquals(RefusalReason.RELOAD_BACKOFF, human.reason());
    assertEquals(Optional.of(Duration.ofSeconds(60)), human.retryAfter());
    assertTrue(human.forceAllowed(), "T24: a person may load anyway");
    var agent = refused(c, demand(DemandKind.AGENT_CHAT, EngineContext.ClientKind.MCP_CLIENT));
    assertEquals(RefusalReason.RELOAD_BACKOFF, agent.reason());
    assertEquals(Optional.of(Duration.ofSeconds(60)), agent.retryAfter());
    assertFalse(agent.forceAllowed(), "T25: an agent is never offered a forced load");
    barrier(c);
    assertEquals(List.of(), loadsAfterUnload());

    advance(DWELL.minusSeconds(60));
    c.demand(demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.WEBVIEW))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(List.of("up:RESIDENCY_RELOAD"), loadsAfterUnload(),
        "T23: with the pressure gone and the dwell over, the demand reloads");
    assertEquals(Optional.empty(), c.unloaded());
    assertTrue(actuator.running.get());
  }

  @Test
  void anAgentDemandReloadsOnceThePressureIsGoneAndTheDwellIsOver() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.reportPressure(PressureSignal.NONE);
    advance(DWELL);
    c.demand(demand(DemandKind.AGENT_CHAT, EngineContext.ClientKind.MCP_CLIENT))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(List.of("up:RESIDENCY_RELOAD"), loadsAfterUnload());
  }

  // ==================== U17: the demand matrix under pressure ====================

  @Test
  void u17UnderPressureEveryDemandKindIsRefusedOrWaitsAndNoneLoads() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    advance(DWELL.plusSeconds(1));

    var human = refused(c, demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.WEBVIEW));
    assertEquals(RefusalReason.GPU_BUSY, human.reason());
    assertTrue(human.forceAllowed(), "T24");
    assertEquals(Optional.of(ChatServerControllerImpl.PRESSURE_RETRY_AFTER), human.retryAfter());

    var paired = refused(c, demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.PAIRED_DEVICE));
    assertTrue(paired.forceAllowed(), "a paired device is the app too");

    for (var client : List.of(EngineContext.ClientKind.MCP_CLIENT, EngineContext.ClientKind.CLI,
        EngineContext.ClientKind.INTERNAL, EngineContext.ClientKind.SUPERVISOR)) {
      var claimed = refused(c, demand(DemandKind.HUMAN_CHAT, client));
      assertEquals(RefusalReason.GPU_BUSY, claimed.reason(), client.name());
      assertFalse(claimed.forceAllowed(),
          "F8/CH1: HUMAN_CHAT from " + client + " is an agent and never forced");
    }

    var agent = refused(c, demand(DemandKind.AGENT_CHAT, EngineContext.ClientKind.MCP_CLIENT));
    assertEquals(RefusalReason.GPU_BUSY, agent.reason());
    assertFalse(agent.forceAllowed());
    assertEquals(Optional.of(ChatServerControllerImpl.PRESSURE_RETRY_AFTER), agent.retryAfter(),
        "T25: the agent is told when to ask again");

    for (DemandKind never : List.of(DemandKind.ASSIST, DemandKind.AUX)) {
      var refusal = refused(c, demand(never, EngineContext.ClientKind.WEBVIEW));
      assertEquals(RefusalReason.GPU_BUSY, refusal.reason(), never.name());
      assertFalse(refusal.forceAllowed(), never.name());
    }

    var background = c.demand(demand(DemandKind.BACKGROUND, EngineContext.ClientKind.INTERNAL))
        .toCompletableFuture();
    barrier(c);
    assertFalse(background.isDone(), "background work waits and never triggers a load");
    assertEquals(List.of(), loadsAfterUnload(), "no demand under pressure loads");
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
  }

  @Test
  void u17WithinTheDwellAnAgentUnderPressureIsToldToWaitForTheDwell() throws Exception {
    var c = running();
    c.setProfile(Profile.P12);
    c.reportPressure(PressureSignal.GAME);
    barrier(c);
    assertEquals(Optional.of(StopDetail.GAME_MODE), c.unloaded());
    advance(Duration.ofSeconds(10));
    var agent = refused(c, demand(DemandKind.AGENT_CHAT, EngineContext.ClientKind.MCP_CLIENT));
    assertEquals(RefusalReason.GAME_MODE, agent.reason());
    assertEquals(Optional.of(DWELL.minusSeconds(10)), agent.retryAfter());
  }

  @Test
  void underPressureTheRefusalCountsDownTheRunningWaitInsteadOfRestartingIt() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    advance(DWELL.plusSeconds(1));
    var agent = demand(DemandKind.AGENT_CHAT, EngineContext.ClientKind.MCP_CLIENT);

    assertEquals(Optional.of(ChatServerControllerImpl.PRESSURE_RETRY_AFTER),
        refused(c, agent).retryAfter(), "pressure with no wait set yet keeps today's value");
    advance(Duration.ofSeconds(10));
    assertEquals(Optional.of(Duration.ofSeconds(20)), refused(c, agent).retryAfter(),
        "a refusal within the running wait says the seconds left of that wait");
    advance(Duration.ofMillis(19_500));
    assertEquals(Optional.of(Duration.ofSeconds(1)), refused(c, agent).retryAfter(),
        "the seconds left are rounded up and at least one");
    advance(Duration.ofMillis(500));
    assertEquals(Optional.of(ChatServerControllerImpl.PRESSURE_RETRY_AFTER),
        refused(c, agent).retryAfter(), "pressure that outlasts the wait starts the next one");
  }

  @Test
  void u17AssistAndAuxNeverLoadEvenWithThePressureGone() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.reportPressure(PressureSignal.NONE);
    advance(DWELL);
    for (DemandKind never : List.of(DemandKind.ASSIST, DemandKind.AUX)) {
      var refusal = refused(c, demand(never, EngineContext.ClientKind.WEBVIEW));
      assertEquals(RefusalReason.GPU_BUSY, refusal.reason(), never.name());
    }
    barrier(c);
    assertEquals(List.of(), loadsAfterUnload());
  }

  @Test
  void aWaitingBackgroundDemandEndsWithTheReloadAPersonStarts() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    var background = c.demand(demand(DemandKind.BACKGROUND, EngineContext.ClientKind.INTERNAL))
        .toCompletableFuture();
    c.reportPressure(PressureSignal.NONE);
    advance(DWELL);
    c.demand(demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.WEBVIEW))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    background.get(5, TimeUnit.SECONDS);
  }

  @Test
  void switchingChatOffEndsWaitingBackgroundDemandsAsOff() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    var background = c.demand(demand(DemandKind.BACKGROUND, EngineContext.ClientKind.INTERNAL))
        .toCompletableFuture();
    c.setActivation(new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    var failure = assertThrows(ExecutionException.class, () -> background.get(5, TimeUnit.SECONDS));
    assertEquals(RefusalReason.OFF, assertInstanceOf(ChatRefusal.class, failure.getCause()).reason());
    assertEquals(Optional.empty(), c.unloaded(), "a chat switched off is OFF, not unloaded");
  }

  @Test
  void aDemandWhileChatRunsGoesStraightThrough() throws Exception {
    var c = running();
    c.reportPressure(PressureSignal.NONE);
    var stage = c.demand(demand(DemandKind.AGENT_CHAT, EngineContext.ClientKind.MCP_CLIENT))
        .toCompletableFuture();
    assertTrue(stage.isDone());
    stage.get();
  }

  // ==================== T26: load anyway ====================

  @ParameterizedTest(name = "aClearedUnloadDoesNotOverrideAnUnrelatedLaterEpisode({arguments})")
  @CsvSource({"P8,EXTERNAL_MEMORY,gpu_busy", "P12,EXTERNAL_MEMORY,gpu_busy",
      "P16_PLUS,EXTERNAL_MEMORY,gpu_busy", "NO_GPU,EXTERNAL_MEMORY,gpu_busy",
      "UNRESOLVED,EXTERNAL_MEMORY,gpu_busy", "P8,GAME,game_mode", "P12,GAME,game_mode"})
  void aClearedUnloadDoesNotOverrideAnUnrelatedLaterEpisode(
      Profile profile, PressureSignal cause, String reason) throws Exception {
    var c = running();
    c.setProfile(profile);
    c.reportPressure(cause);
    barrier(c);
    c.reportPressure(PressureSignal.NONE);
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);

    advance(Duration.ofHours(2));
    c.reportPressure(cause);
    barrier(c);
    assertEquals(List.of("unload:" + reason, "unload:" + reason), unloads(),
        "a cleared cause cannot hide another application's much later pressure episode");
    assertFalse(actuator.running.get());
  }

  @ParameterizedTest(name = "aClearedGameIsNeverRememberedDuringTheReloadWait({arguments})")
  @CsvSource({"P8,GAME,game_mode", "P12,GAME,game_mode",
      "P8,EXTERNAL_MEMORY,gpu_busy", "P12,EXTERNAL_MEMORY,gpu_busy"})
  void aClearedGameIsNeverRememberedDuringTheReloadWait(
      Profile profile, PressureSignal returning, String reason) throws Exception {
    var c = running();
    c.setProfile(profile);
    c.reportPressure(PressureSignal.GAME);
    barrier(c);
    c.reportPressure(PressureSignal.NONE);
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    advance(Duration.ofSeconds(1));
    c.reportPressure(returning);
    barrier(c);
    assertEquals(List.of("unload:game_mode", "unload:" + reason), unloads(),
        "a game that cleared before the person's choice cannot cover a new game or memory user");
  }

  @Test
  void theMemoryReturnWindowStartsWhenTheReloadIsReadyAndExpiresWithoutAReturn() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.reportPressure(PressureSignal.NONE);
    actuator.beforeActivationBody = () -> advance(Duration.ofHours(1));
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    advance(Duration.ofSeconds(14));
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    advance(Duration.ofHours(1));
    barrier(c);
    assertEquals(1, unloads().size(), "a timely return remains overridden until a sampled clear");

    c.reportPressure(PressureSignal.NONE);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(2, unloads().size(), "a sampled clear ends the active override");
    c.reportPressure(PressureSignal.NONE);
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    advance(Duration.ofSeconds(15));
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy", "unload:gpu_busy", "unload:gpu_busy"), unloads(),
        "pressure first seen at the 15-second boundary belongs to a new episode");
  }

  @ParameterizedTest(name = "ignoringGamesUnderAnotherProfileDoesNotClearThePersonsOverride({arguments})")
  @CsvSource({"P8,P16_PLUS", "P8,NO_GPU", "P8,UNRESOLVED",
      "P12,P16_PLUS", "P12,NO_GPU", "P12,UNRESOLVED"})
  void ignoringGamesUnderAnotherProfileDoesNotClearThePersonsOverride(
      Profile initial, Profile ignoresGames) throws Exception {
    var c = running();
    c.setProfile(initial);
    c.reportPressure(PressureSignal.GAME);
    barrier(c);
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    c.setProfile(ignoresGames);
    c.setProfile(initial);
    barrier(c);
    assertEquals(List.of("unload:game_mode"), unloads(),
        "changing a profile is not evidence that the sampled game ended");
    assertTrue(actuator.running.get());

    c.setProfile(ignoresGames);
    c.reportPressure(PressureSignal.NONE);
    c.setProfile(initial);
    c.reportPressure(PressureSignal.GAME);
    barrier(c);
    assertEquals(List.of("unload:game_mode", "unload:game_mode"), unloads(),
        "an actual sampled clear ends the choice even while games are ignored");
  }

  @Test
  void loadAnywayRecordsPressureThatEscalatesBeforeItAcquiresTheLock() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.setProfile(Profile.P12);
    var observed = new CountDownLatch(1);
    var releaseRead = new CountDownLatch(1);
    var result = new CompletableFuture<Void>();
    // Block the actuator observation, then hold the controller lock until the person is waiting
    // for it. This orders the sampled escalation between observation and the locked decision.
    actuator.beforeRunningRead = () -> {
      observed.countDown();
      await(releaseRead);
    };
    var person = Thread.ofPlatform().name("load-anyway-race").start(() -> {
      try {
        c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        result.complete(null);
      } catch (Exception failure) {
        result.completeExceptionally(failure);
      }
    });
    try {
      assertTrue(observed.await(5, TimeUnit.SECONDS));
      var monitorField = ChatServerControllerImpl.class.getDeclaredField("monitor");
      monitorField.setAccessible(true);
      synchronized (monitorField.get(c)) {
        releaseRead.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (person.getState() != Thread.State.BLOCKED && System.nanoTime() < until) {
          Thread.sleep(1);
        }
        assertEquals(Thread.State.BLOCKED, person.getState(), "the person awaits the controller lock");
        c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
      }
      result.get(5, TimeUnit.SECONDS);
      c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
      barrier(c);
      assertEquals(List.of("unload:gpu_busy"), unloads(),
          "load anyway records the effective game level present when the decision is made");
      assertTrue(actuator.running.get());
    } finally {
      releaseRead.countDown();
      person.join(TimeUnit.SECONDS.toMillis(5));
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture release timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @ParameterizedTest(name = "loadAnywaySurvivesOverlappingPressureAtTheSameEffectiveLevel({arguments})")
  @CsvSource({"P8,GAME,game_mode", "P12,GAME,game_mode",
      "P16_PLUS,EXTERNAL_MEMORY,gpu_busy", "NO_GPU,EXTERNAL_MEMORY,gpu_busy",
      "UNRESOLVED,EXTERNAL_MEMORY,gpu_busy"})
  void loadAnywaySurvivesOverlappingPressureAtTheSameEffectiveLevel(
      Profile profile, PressureSignal first, String reason) throws Exception {
    var c = running();
    c.setProfile(profile);
    c.reportPressure(first);
    barrier(c);
    assertEquals(List.of("unload:" + reason), unloads());
    var refusal = refused(c, demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.WEBVIEW));
    assertEquals(first == PressureSignal.GAME ? RefusalReason.GAME_MODE : RefusalReason.GPU_BUSY,
        refusal.reason());
    assertTrue(refusal.forceAllowed());
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);

    c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
    actuator.activity.edge(false);
    barrier(c);
    assertEquals(List.of("unload:" + reason), unloads(),
        "adding the other source does not revoke the effective pressure the person dismissed");
    assertTrue(actuator.running.get());

    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(1, unloads().size(), "a lower effective pressure is covered too");
    c.reportPressure(PressureSignal.NONE);
    c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of("unload:" + reason, "unload:" + reason), unloads(),
        "a confirmed clear ends the override; the next episode uses the effective reason");
  }

  @Test
  void profileDowngradeKeepsTheOverrideUntilEffectivePressureClears() throws Exception {
    var c = running();
    c.setProfile(Profile.P12);
    c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
    barrier(c);
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    c.setProfile(Profile.P16_PLUS);
    barrier(c);
    assertEquals(List.of("unload:game_mode"), unloads(), "a lower effective level remains covered");
    c.reportPressure(PressureSignal.GAME); // this profile acts on NONE
    c.reportPressure(PressureSignal.GAME_AND_EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of("unload:game_mode", "unload:gpu_busy"), unloads());
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    c.setProfile(Profile.P12);
    barrier(c);
    assertEquals(List.of("unload:game_mode", "unload:gpu_busy", "unload:game_mode"), unloads(),
        "a profile change that makes the effective level more severe ends the memory override");
  }

  @Test
  void t26APersonLoadsAnywayAndOnlyANewPressureEdgeUnloadsAgain() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.loadAnyway(context(EngineContext.ClientKind.WEBVIEW)).toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    assertEquals(List.of("up:RESIDENCY_RELOAD"), loadsAfterUnload(),
        "load anyway skips the pressure and the dwell");
    assertEquals(Optional.empty(), c.unloaded());

    // The same pressure, reported again, does not unload what the person loaded.
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    actuator.activity.edge(false);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy"), unloads());
    assertTrue(actuator.running.get());

    // A new pressure edge does.
    c.reportPressure(PressureSignal.NONE);
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    barrier(c);
    assertEquals(List.of("unload:gpu_busy", "unload:gpu_busy"), unloads());
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
  }

  @Test
  void f8OnlyAPersonMayLoadAnyway() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    for (var client : List.of(EngineContext.ClientKind.MCP_CLIENT, EngineContext.ClientKind.CLI,
        EngineContext.ClientKind.INTERNAL, EngineContext.ClientKind.SUPERVISOR)) {
      var refusal = assertThrows(ChatRefusal.class, () -> c.loadAnyway(context(client)));
      assertEquals(RefusalReason.REFUSED, refusal.reason(), client.name());
    }
    barrier(c);
    assertEquals(List.of(), loadsAfterUnload());
  }

  // ==================== CH7: a start under pressure ====================

  @Test
  void ch7AGpuStartUnderPressureIsRefusedIntoUnloadedWithoutADwell() throws Exception {
    var c = controller();
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    var start = c.setActivation(
        new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START)).toCompletableFuture();
    var failure = assertThrows(ExecutionException.class, () -> start.get(5, TimeUnit.SECONDS));
    assertInstanceOf(ChatActuationRouter.SupersededException.class, failure.getCause());
    barrier(c);
    assertFalse(actuator.running.get(), "nothing was loaded");
    assertEquals(List.of("unload:gpu_busy"), actuator.log, "the step only records why");
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());

    // Nothing was unloaded, so there is no dwell: with the pressure gone a demand loads at once.
    c.reportPressure(PressureSignal.NONE);
    c.demand(demand(DemandKind.AGENT_CHAT, EngineContext.ClientKind.MCP_CLIENT))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(List.of("unload:gpu_busy", "up:RESIDENCY_RELOAD"), actuator.log);
  }

  // ==================== T40 and U13: VDU under pressure ====================

  @Test
  void t40AnOpenVduHoldIsAskedToFinishUnderPressureAndTheIdleServerThenUnloads() throws Exception {
    var c = controller();
    var hold = c.holdVdu("vdu-batch");
    assertFalse(hold.interruptRequested());
    c.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    assertTrue(hold.interruptRequested(), "T40: the batch ends early under pressure");
    barrier(c);
    assertEquals(List.of(), unloads(), "the hold keeps the server until it closes");
    hold.close();
    barrier(c);
    assertEquals(List.of("unload:gpu_busy"), unloads());
    assertEquals(Optional.of(StopDetail.GPU_BUSY), c.unloaded());
  }

  @Test
  void u13NoVduPassStartsUnderPressure() throws Exception {
    var c = controller();
    assertTrue(c.vduMayStart());
    c.reportPressure(PressureSignal.GAME);
    assertFalse(c.vduMayStart(), "U13: not under pressure");
    assertEquals(RefusalReason.GAME_MODE,
        assertThrows(ChatRefusal.class, () -> c.holdVdu("vdu-batch")).reason());
    c.reportPressure(PressureSignal.NONE);
    assertTrue(c.vduMayStart());
  }

  @Test
  void u13NoVduPassStartsWhileChatIsUnloadedForAnotherGpuUser() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.reportPressure(PressureSignal.NONE);
    assertFalse(actuator.running.get());
    assertFalse(c.vduMayStart(), "U13: a VDU pass would load what the unload freed");
  }

  @Test
  void aRunningHoldStartsAnUnloadedServerAndClearsTheLatch() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    c.reportPressure(PressureSignal.NONE);
    var smoke = c.holdRunning("smoke-test");
    try (smoke) {
      assertTrue(actuator.running.get());
      assertEquals(Optional.empty(), c.unloaded(), "a hold that starts the server outranks the latch");
    }
  }

  @Test
  void afterShutdownADemandIsRefusedAsStopping() throws Exception {
    var c = unloadedFor(PressureSignal.EXTERNAL_MEMORY);
    var background = c.demand(demand(DemandKind.BACKGROUND, EngineContext.ClientKind.INTERNAL))
        .toCompletableFuture();
    c.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    assertEquals(RefusalReason.ENGINE_STOPPING,
        refused(c, demand(DemandKind.HUMAN_CHAT, EngineContext.ClientKind.WEBVIEW)).reason());
    var failure = assertThrows(ExecutionException.class, () -> background.get(5, TimeUnit.SECONDS));
    assertEquals(RefusalReason.ENGINE_STOPPING,
        assertInstanceOf(ChatRefusal.class, failure.getCause()).reason());
    assertTrue(actuator.activity.listeners.isEmpty(), "the edge subscription is closed");
  }

  // ==================== the stand-in ====================

  /** The in-flight signal, driven by hand; edges are delivered on the caller's thread. */
  static final class FakeActivity implements GenerationActivity {
    final AtomicBoolean generating = new AtomicBoolean();
    final List<EdgeListener> listeners = new CopyOnWriteArrayList<>();
    final AtomicLong epoch = new AtomicLong();

    void edge(boolean nowGenerating) {
      generating.set(nowGenerating);
      if (nowGenerating) epoch.incrementAndGet();
      for (EdgeListener listener : listeners) listener.onEdge(nowGenerating, epoch.get());
    }

    @Override public boolean generating() {
      return generating.get();
    }

    @Override public int inFlight() {
      return generating.get() ? 1 : 0;
    }

    @Override public int queued() {
      return 0;
    }

    @Override public long idleSinceNanos() {
      return generating.get() ? Long.MIN_VALUE : 0L;
    }

    @Override public long epoch() {
      return epoch.get();
    }

    @Override public AutoCloseable onEdge(EdgeListener listener) {
      listeners.add(listener);
      return () -> listeners.remove(listener);
    }
  }

  /** A GPU chat server stand-in with the manager's residency, activation and VDU steps. */
  static final class ResidencyActuator implements ChatActuator {
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final List<ChatDrainPolicy> policies = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean running = new AtomicBoolean();
    final AtomicBoolean gpu = new AtomicBoolean(true);
    final AtomicBoolean generateAtDrain = new AtomicBoolean();
    final FakeActivity activity = new FakeActivity();
    volatile Runnable beforeUnloadBody;
    volatile Runnable beforeActivationBody;
    volatile Runnable beforeRunningRead;

    @Override
    public DrainHold drain(Duration bound) {
      throw new UnsupportedOperationException("steps drain by policy");
    }

    @Override
    public DrainHold drain(ChatDrainPolicy policy) {
      policies.add(policy);
      if (generateAtDrain.getAndSet(false)) activity.generating.set(true);
      if (policy.waiting() == ChatDrainPolicy.Waiting.IF_IDLE && activity.generating()) {
        throw new IllegalStateException("a reply is in progress");
      }
      return () -> { };
    }

    private static <T> T busy(Exception failure) throws ModeTransitionException {
      throw new ModeTransitionException(ModeTransitionException.Reason.ALREADY_TRANSITIONING,
          failure.getMessage());
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          "mode", null, null,
          drained -> {
            Runnable hook = beforeActivationBody;
            beforeActivationBody = null;
            if (hook != null) hook.run();
            running.set(activation.active());
            log.add((activation.active() ? "up:" : "down:") + activation.reason());
            return null;
          });
    }

    @Override
    public ChatActuation<ResidencyUnload> residencyUnload(
        StopDetail detail, BooleanSupplier stillWanted) {
      return ChatActuation.draining(ChatActuation.Kind.RESIDENCY_UNLOAD,
          notIdle -> ResidencyUnload.BUSY,
          drained -> {
            Runnable hook = beforeUnloadBody;
            beforeUnloadBody = null;
            if (hook != null) hook.run();
            if (!stillWanted.getAsBoolean()) return ResidencyUnload.NOT_WANTED;
            running.set(false);
            log.add("unload:" + detail.token());
            return ResidencyUnload.UNLOADED;
          });
    }

    @Override
    public ChatActuation<Void> vduEnter() {
      return ChatActuation.<Void>draining(ChatActuation.Kind.VDU_ENTER, ResidencyActuator::busy,
          drained -> {
            log.add("vdu-enter");
            return null;
          });
    }

    @Override
    public ChatActuation<Void> vduExit() {
      return ChatActuation.<Void>draining(ChatActuation.Kind.VDU_EXIT, ResidencyActuator::busy,
          drained -> {
            log.add("vdu-exit");
            return null;
          });
    }

    @Override
    public GenerationActivity generationActivity() {
      return activity;
    }

    @Override
    public boolean serverRunning() {
      Runnable hook = beforeRunningRead;
      beforeRunningRead = null;
      if (hook != null) hook.run();
      return running.get();
    }

    @Override
    public boolean gpuChat() {
      return gpu.get();
    }

    @Override
    public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      try {
        return step.run(drained);
      } finally {
        if (drained != null) drained.close();
      }
    }

    @Override
    public void terminalStop(
        StopReason reason, Duration timeout) {
      running.set(false);
    }
  }
}
