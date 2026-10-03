/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OperationAdmissionClosedException;
import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.InstallPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The stage sequence, driven entirely through fakes.
 *
 * <p>Everything asserted here used to be reachable only by downloading ~7 GB onto Windows: that the
 * stages run in capability order, that a stage which delivered nothing does not restart the Worker
 * (the restart is a user-visible search blip), that a cancellation mid-run leaves the stages that
 * already finished standing, and that each stage's op-lease is taken and released exactly once —
 * including when the stage fails.
 */
final class StagedAcquisitionTest {

  private static InstallPlan.PlannedDownload dl(String packageId, String targetPath, long size) {
    return new InstallPlan.PlannedDownload(
        packageId, "https://example.invalid/" + targetPath, targetPath, "sha", size, true);
  }

  private static InstallStage.Slice slice(InstallStage stage, InstallPlan.PlannedDownload... downloads) {
    long bytes = 0L;
    Set<String> packages = new java.util.LinkedHashSet<>();
    for (InstallPlan.PlannedDownload d : downloads) {
      bytes += d.sizeBytes();
      packages.add(d.packageId());
    }
    return new InstallStage.Slice(stage, List.of(downloads), bytes, packages);
  }

  private static AcquisitionScheduler.Summary summary(
      boolean cancelled, int installed, int failed, long bytes) {
    return new AcquisitionScheduler.Summary(cancelled, installed, failed, bytes);
  }

  private static ConfigurationStage.Applied applied(boolean cancelled) {
    return new ConfigurationStage.Applied(cancelled, Set.of(), Set.of(), Set.of());
  }

  /** Records every stage transition in the order it arrived. */
  private static final class RecordingListener implements StagedAcquisition.Listener {
    final List<String> events = new ArrayList<>();
    long bankedBytes;

    @Override
    public void onStageStarted(InstallStage stage) {
      events.add("started:" + stage.id());
    }

    @Override
    public void onStageAcquired(InstallStage stage, AcquisitionScheduler.Summary summary) {
      bankedBytes += summary.acquiredBytes();
      events.add("acquired:" + stage.id() + ":" + summary.acquiredBytes());
    }

    @Override
    public void onStageEnded(InstallStage stage, StagedAcquisition.StageState state) {
      events.add("ended:" + stage.id() + ":" + state.id());
    }
  }

  /** Records lease registrations and releases per stage. */
  private static final class RecordingLeases implements StagedAcquisition.LeaseRegistrar {
    final List<String> events = new ArrayList<>();
    final Map<String, Long> estimateInputBytes = new LinkedHashMap<>();
    InstallStage refuseFrom;

    @Override
    public StagedAcquisition.Lease register(InstallStage.Slice slice) {
      if (refuseFrom != null && refuseFrom == slice.stage()) {
        throw new OperationAdmissionClosedException("prep-1", "app update consented");
      }
      events.add("register:" + slice.stage().id());
      estimateInputBytes.put(slice.stage().id(), slice.bytes());
      return success -> events.add("release:" + slice.stage().id() + ":" + success);
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("stages run in order; each takes and releases its own lease")
  void runsStagesInOrderWithOneLeaseEach() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();
    List<String> acquired = new ArrayList<>();
    List<String> configured = new ArrayList<>();

    boolean completed =
        new StagedAcquisition(
                s -> {
                  acquired.add(s.stage().id());
                  return summary(false, s.downloads().size(), 0, s.bytes());
                },
                (s, sum) -> {
                  configured.add(s.stage().id());
                  return applied(false);
                },
                leases,
                listener,
                () -> false)
            .run(
                List.of(
                    slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100)),
                    slice(InstallStage.ENRICHMENT, dl("splade", "s/model.onnx", 20)),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 500))),
                null);

    assertTrue(completed);
    assertEquals(List.of("core", "enrichment", "chat"), acquired);
    assertEquals(List.of("core", "enrichment", "chat"), configured);
    assertEquals(
        List.of(
            "register:core", "release:core:true",
            "register:enrichment", "release:enrichment:true",
            "register:chat", "release:chat:true"),
        leases.events,
        "one lease per stage, each released when its own stage ends");
    assertEquals(620L, listener.bankedBytes, "every stage's bytes are banked once");
  }

  @Test
  @Timeout(10)
  @DisplayName("the lease registered on the calling thread is used for the first stage, not re-registered")
  void firstStageUsesThePreRegisteredLease() {
    RecordingLeases leases = new RecordingLeases();
    List<String> preRegistered = new ArrayList<>();

    new StagedAcquisition(
            s -> summary(false, 1, 0, s.bytes()),
            (s, sum) -> applied(false),
            leases,
            new RecordingListener(),
            () -> false)
        .run(
            List.of(
                slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100)),
                slice(InstallStage.ENRICHMENT, dl("splade", "s/model.onnx", 20)),
                slice(InstallStage.CHAT)),
            success -> preRegistered.add("release:" + success));

    assertEquals(
        List.of("register:enrichment", "release:enrichment:true"),
        leases.events,
        "core reuses the handle registered before the install thread started");
    assertEquals(List.of("release:true"), preRegistered, "and releases it when core ends");
  }

  @Test
  @Timeout(10)
  @DisplayName("a stage with nothing to acquire is skipped: no lease, no configuration, no configuration application")
  void emptyStageNeitherConfiguresNorRestarts() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();
    List<String> configured = new ArrayList<>();
    AtomicBoolean workerRestarted = new AtomicBoolean(false);

    boolean completed =
        new StagedAcquisition(
                s -> summary(false, s.downloads().size(), 0, s.bytes()),
                (s, sum) -> {
                  configured.add(s.stage().id());
                  // The production binding hands the restart through this gate; running it here is
                  // what makes "no restart" an assertion about the real gate rather than about the
                  // fake. The verdict is asserted against the side effect rather than discarded:
                  // the gate saying "restarted" while nothing restarted (or the reverse) would be a
                  // gate that lies, and that is exactly what this test exists to catch.
                  boolean restartFired =
                      StagedAcquisition.restartGate(
                              sum, () -> workerRestarted.compareAndSet(false, true))
                          .getAsBoolean();
                  assertEquals(
                      restartFired,
                      workerRestarted.get(),
                      s.stage().id() + ": the gate's verdict must match whether a restart happened");
                  return applied(false);
                },
                leases,
                listener,
                () -> false)
            .run(
                List.of(
                    slice(InstallStage.CORE),
                    slice(InstallStage.ENRICHMENT),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 500))),
                null);

    assertTrue(completed);
    assertEquals(List.of("chat"), configured, "only the stage that had files configures anything");
    assertEquals(
        List.of("register:chat", "release:chat:true"),
        leases.events,
        "an empty stage takes no lease of its own");
    assertEquals(
        List.of(
            "ended:core:skipped",
            "ended:enrichment:skipped",
            "started:chat",
            "acquired:chat:500",
            "ended:chat:completed"),
        listener.events);
    assertTrue(workerRestarted.get(), "the stage that DID deliver still restarts the Worker");
  }

  /**
   * Tempdoc 840 U2. The refusal has to happen BEFORE the lease, not merely before the transfer: an
   * op-lease blocks upgrade preparation, and holding one for a download that was never attempted
   * would stall an update on nothing. It also must not abandon the run — each stage is measured
   * against its own size, so a disk that cannot hold the chat model can still leave the retrieval
   * core that an earlier stage already placed.
   */
  @Test
  @Timeout(10)
  @DisplayName("a blocked stage takes no lease, acquires nothing, and does not stop later stages")
  void blockedStageIsRefusedBeforeItsLease() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();
    List<String> acquired = new ArrayList<>();

    boolean completed =
        new StagedAcquisition(
                s -> {
                  acquired.add(s.stage().id());
                  return summary(false, s.downloads().size(), 0, s.bytes());
                },
                (s, sum) -> applied(false),
                leases,
                listener,
                () -> false,
                s -> InstallStage.CHAT == s.stage() ? "no room for the chat model" : null)
            .run(
                List.of(
                    slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100)),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 6_000))),
                null);

    assertTrue(completed, "a refusal is not a cancellation — the run reached its own end");
    assertEquals(List.of("core"), acquired, "the blocked stage fetched nothing");
    assertEquals(
        List.of("register:core", "release:core:true"),
        leases.events,
        "the blocked stage never took out an op-lease");
    assertEquals(
        List.of("started:core", "acquired:core:100", "ended:core:completed", "ended:chat:blocked"),
        listener.events,
        "core completes; chat is reported blocked without ever starting");
  }

  /**
   * Tempdoc 840 R1. Every stage empty is a SUPPORTED outcome, not a degenerate one: it is what a
   * pre-staged {@code JUSTSEARCH_MODELS_DIR} produces (the constructor honours that env var so a
   * pre-populated dir yields zero downloads) and what a repair on an already-complete machine
   * produces. Such a run used to apply NOTHING — every configuration call site hung off a stage
   * with files to fetch — so no ONNX path was written, no system property latched, no ConfigStore
   * rebuilt and no index component reconfigured, while the run reported itself completed.
   */
  @Test
  @Timeout(10)
  @DisplayName("a run where no stage acquired anything still configures once, and still restarts the Worker")
  void runWithNoAcquisitionStillConfiguresTerminally() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();
    List<String> configured = new ArrayList<>();
    AtomicInteger terminalPasses = new AtomicInteger();
    AtomicBoolean workerRestarted = new AtomicBoolean(false);

    boolean completed =
        new StagedAcquisition(
                s -> summary(false, s.downloads().size(), 0, s.bytes()),
                (s, sum) -> {
                  configured.add(s.stage().id());
                  return applied(false);
                },
                () -> {
                  terminalPasses.incrementAndGet();
                  // The production binding hands the restart in UNGATED here, unlike a stage's,
                  // which goes through restartGate. Running it is what makes "the Worker is
                  // restarted" an assertion about the wiring rather than about the fake.
                  workerRestarted.set(true);
                  return applied(false);
                },
                leases,
                listener,
                () -> false,
                StagedAcquisition.Precondition.open())
            .run(
                List.of(
                    slice(InstallStage.CORE),
                    slice(InstallStage.ENRICHMENT),
                    slice(InstallStage.CHAT)),
                null);

    assertTrue(completed);
    assertTrue(configured.isEmpty(), "no stage had files, so no stage configured");
    assertEquals(1, terminalPasses.get(), "the run still owes exactly one configuration pass");
    assertTrue(
        workerRestarted.get(),
        "pre-staged models are new to the WORKER even though this run fetched none of them, and"
            + " there is no encoder hot-reload");
    assertEquals(
        List.of("ended:core:skipped", "ended:enrichment:skipped", "ended:chat:skipped"),
        listener.events);
    assertTrue(leases.events.isEmpty(), "an empty stage still takes no lease of its own");
  }

  /**
   * The other half of R1, and the reason the terminal pass is conditional: a run in which ANY stage
   * configured must not pay for a second, run-wide pass — that would restart the Worker again for
   * nothing, and re-run the configuration list a fourth time.
   */
  @Test
  @Timeout(10)
  @DisplayName("a run where one stage did configure owes no terminal pass")
  void mixedRunDoesNotAlsoConfigureTerminally() {
    List<String> configured = new ArrayList<>();
    AtomicInteger terminalPasses = new AtomicInteger();

    boolean completed =
        new StagedAcquisition(
                s -> summary(false, s.downloads().size(), 0, s.bytes()),
                (s, sum) -> {
                  configured.add(s.stage().id());
                  return applied(false);
                },
                () -> {
                  terminalPasses.incrementAndGet();
                  return applied(false);
                },
                new RecordingLeases(),
                new RecordingListener(),
                () -> false,
                StagedAcquisition.Precondition.open())
            .run(
                List.of(
                    slice(InstallStage.CORE),
                    slice(InstallStage.ENRICHMENT),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 500))),
                null);

    assertTrue(completed);
    assertEquals(List.of("chat"), configured, "only the stage that had files configures anything");
    assertEquals(0, terminalPasses.get(), "and the run owes no extra pass on top of it");
  }

  /** A terminal pass that hits a cancellation checkpoint ends the run, exactly as a stage's does. */
  @Test
  @Timeout(10)
  @DisplayName("a cancellation inside the terminal configuration stops the run")
  void terminalConfigurationCancellationStopsTheRun() {
    boolean completed =
        new StagedAcquisition(
                s -> summary(false, 0, 0, 0),
                (s, sum) -> applied(false),
                () -> applied(true),
                new RecordingLeases(),
                new RecordingListener(),
                () -> false,
                StagedAcquisition.Precondition.open())
            .run(List.of(slice(InstallStage.CORE), slice(InstallStage.CHAT)), null);

    assertFalse(completed);
  }

  @Test
  @Timeout(10)
  @DisplayName("a stage that placed nothing does not restart the Worker")
  void restartGateRefusesWhenNothingWasInstalled() {
    AtomicInteger restarts = new AtomicInteger();

    assertFalse(
        StagedAcquisition.restartGate(summary(false, 0, 3, 0), () -> restarts.incrementAndGet() > 0)
            .getAsBoolean(),
        "three failures and no installs is not a delivery");
    assertEquals(0, restarts.get(), "the restart must not even be attempted — it is a search blip");

    assertTrue(
        StagedAcquisition.restartGate(summary(false, 1, 2, 10), () -> restarts.incrementAndGet() > 0)
            .getAsBoolean(),
        "one placed file is a new capability the Worker has to be restarted onto");
    assertEquals(1, restarts.get());
  }

  @Test
  @Timeout(10)
  @DisplayName("a process-wide configuration latch applies once, at the stage that first satisfies its guard")
  void applyOncePerRunAppliesAtTheFirstStageThatCan() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean inputPresent = new AtomicBoolean(false);
    // Stands in for applySettings: falls out of its own guard until the chat model is on disk.
    java.util.function.BooleanSupplier step =
        StagedAcquisition.applyOncePerRun(
            () -> {
              attempts.incrementAndGet();
              return inputPresent.get();
            });

    assertFalse(step.getAsBoolean(), "core: the guard is not satisfied yet");
    assertFalse(step.getAsBoolean(), "enrichment: still not");
    assertEquals(2, attempts.get(), "a step that fell out of its guard is attempted again");

    inputPresent.set(true);
    assertTrue(step.getAsBoolean(), "chat: the model landed, so it applies");
    assertEquals(3, attempts.get());

    assertFalse(step.getAsBoolean(), "and never applies a second time");
    assertEquals(3, attempts.get(), "nor is the underlying step even invoked again");
  }

  @Test
  @Timeout(10)
  @DisplayName("a stage whose acquisition failed still releases its lease, as a failure")
  void failedStageReleasesItsLease() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();

    boolean completed =
        new StagedAcquisition(
                s ->
                    s.stage() == InstallStage.ENRICHMENT
                        ? summary(false, 0, 1, 0)
                        : summary(false, 1, 0, s.bytes()),
                (s, sum) -> applied(false),
                leases,
                listener,
                () -> false)
            .run(
                List.of(
                    slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100)),
                    slice(InstallStage.ENRICHMENT, dl("splade", "s/model.onnx", 20)),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 500))),
                null);

    assertTrue(completed, "one stage's failure does not end the run — the later stages still run");
    assertEquals(
        List.of(
            "register:core", "release:core:true",
            "register:enrichment", "release:enrichment:false",
            "register:chat", "release:chat:true"),
        leases.events);
    assertTrue(listener.events.contains("ended:enrichment:failed"));
    assertTrue(listener.events.contains("ended:chat:completed"), "chat still ran after the failure");
  }

  @Test
  @Timeout(10)
  @DisplayName("cancelling mid-stage leaves the completed stages intact and never starts the later ones")
  void cancelMidStageKeepsEarlierStages() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();
    List<String> configured = new ArrayList<>();

    boolean completed =
        new StagedAcquisition(
                s ->
                    s.stage() == InstallStage.ENRICHMENT
                        // Cancelled part-way: the item it had placed is banked, the rest never ran.
                        ? summary(true, 1, 0, 20)
                        : summary(false, 1, 0, s.bytes()),
                (s, sum) -> {
                  configured.add(s.stage().id());
                  return applied(false);
                },
                leases,
                listener,
                () -> false)
            .run(
                List.of(
                    slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100)),
                    slice(InstallStage.ENRICHMENT, dl("splade", "s/model.onnx", 20), dl("ner", "n/model.onnx", 30)),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 500))),
                null);

    assertFalse(completed, "the run stops");
    assertEquals(
        List.of("core"),
        configured,
        "core's configuration stands; the cancelled stage does not get one");
    assertEquals(
        List.of(
            "started:core",
            "acquired:core:100",
            "ended:core:completed",
            "started:enrichment",
            "acquired:enrichment:20",
            "ended:enrichment:cancelled"),
        listener.events,
        "core stays completed and chat is never started");
    assertEquals(120L, listener.bankedBytes, "the bytes the cancelled stage DID place still count");
    assertEquals(
        List.of(
            "register:core", "release:core:true",
            "register:enrichment", "release:enrichment:false"),
        leases.events,
        "the cancelled stage still releases its lease");
  }

  @Test
  @Timeout(10)
  @DisplayName("a cancellation raised outside the scheduler stops the run at the same boundary")
  void externalCancellationStopsTheRun() {
    RecordingListener listener = new RecordingListener();
    AtomicBoolean cancelled = new AtomicBoolean(false);

    boolean completed =
        new StagedAcquisition(
                s -> {
                  cancelled.set(true); // e.g. the op-lease drain callback fired during the transfer
                  return summary(false, 1, 0, s.bytes());
                },
                (s, sum) -> applied(false),
                new RecordingLeases(),
                listener,
                cancelled::get)
            .run(List.of(slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100))), null);

    assertFalse(completed);
    assertTrue(listener.events.contains("ended:core:cancelled"));
  }

  @Test
  @Timeout(10)
  @DisplayName("a cancellation checkpoint inside the configuration stops the run")
  void configurationCancellationStopsTheRun() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();

    boolean completed =
        new StagedAcquisition(
                s -> summary(false, 1, 0, s.bytes()),
                (s, sum) -> applied(true),
                leases,
                listener,
                () -> false)
            .run(
                List.of(
                    slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100)),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 500))),
                null);

    assertFalse(completed);
    assertEquals(
        List.of("register:core", "release:core:false"),
        leases.events,
        "chat is never reached, so it never takes a lease");
  }

  @Test
  @Timeout(10)
  @DisplayName("admission frozen between stages ends the run without losing the earlier stages")
  void admissionClosedBetweenStagesEndsTheRun() {
    RecordingListener listener = new RecordingListener();
    RecordingLeases leases = new RecordingLeases();
    leases.refuseFrom = InstallStage.CHAT;

    boolean completed =
        new StagedAcquisition(
                s -> summary(false, 1, 0, s.bytes()),
                (s, sum) -> applied(false),
                leases,
                listener,
                () -> false)
            .run(
                List.of(
                    slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 100)),
                    slice(InstallStage.ENRICHMENT),
                    slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 500))),
                null);

    assertFalse(completed, "an upgrade preparation owns the barrier — the run stops here");
    assertTrue(listener.events.contains("ended:core:completed"), "core's work stands");
    assertTrue(listener.events.contains("ended:chat:cancelled"));
    assertFalse(
        listener.events.contains("started:chat"), "the refused stage never begins acquiring");
  }

  @Test
  @Timeout(10)
  @DisplayName("the lease estimate is sized from the stage's own bytes, not the whole run's")
  void leasesAreSizedPerStage() {
    RecordingLeases leases = new RecordingLeases();

    new StagedAcquisition(
            s -> summary(false, 1, 0, s.bytes()),
            (s, sum) -> applied(false),
            leases,
            new RecordingListener(),
            () -> false)
        .run(
            List.of(
                slice(InstallStage.CORE, dl("embedding", "e/model.onnx", 1_300_000_000L)),
                slice(InstallStage.ENRICHMENT, dl("splade", "s/model.onnx", 500_000_000L)),
                slice(InstallStage.CHAT, dl("chat", "c/chat.gguf", 5_000_000_000L))),
            null);

    assertEquals(
        Map.of("core", 1_300_000_000L, "enrichment", 500_000_000L, "chat", 5_000_000_000L),
        leases.estimateInputBytes,
        "each registration sees only its own stage's bytes");
  }

  /** Guards the assumption the stage mapping is derived from, not a behaviour of this class. */
  @Test
  @DisplayName("the tier lookup the production binding passes is the registry's, not a copy")
  void tierLookupIsAFunctionSeam() {
    Function<String, CapabilityTier> lookup =
        Map.of("embedding", CapabilityTier.RETRIEVAL_CORE)::get;
    List<InstallStage.Slice> slices =
        InstallStage.partition(List.of(dl("embedding", "e/model.onnx", 1)), lookup);
    assertEquals(InstallStage.CORE, slices.get(0).stage());
    assertFalse(slices.get(0).isEmpty());
  }
}
