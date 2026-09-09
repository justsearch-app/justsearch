package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.Mode;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the REAL {@link OfflineCoordinator} (tempdoc 737 task 7f rewrite).
 *
 * <p>Pre-737 this suite drove a hand-copied {@code TestableOfflineCoordinator} and asserted switch
 * counts on a bespoke stub — it never exercised the production class. It now drives the real
 * coordinator through the procedure API: a real (unstarted) {@link RuntimeReconciler} wrapping the
 * package's {@link StubInferenceLifecycleManager}. The reconciler thread is deliberately NOT started
 * — {@link RuntimeReconciler#procedureRequireEngine(boolean)} is synchronous, so phase-time engine
 * control is deterministic; post-procedure return-to-spec (which needs the thread) is covered by
 * {@code RuntimeReconcilerTest}. Each test's original INTENT is preserved (noted per test); the
 * observable moved from a copy's counters to the real stub's {@code getOnline/IndexingSwitchCount}
 * (which the coordinator now reaches only via {@code procedureRequireEngine}).
 */
@DisplayName("OfflineCoordinator")
class OfflineCoordinatorTest {

    private StubInferenceLifecycleManager inferenceManager;
    private RuntimeReconciler reconciler;
    private VduBatchProcessor vduBatchProcessor;
    private KnowledgeClient knowledgeClient;
    private VduCapabilityState capabilityState;
    private OfflineCoordinator coordinator;

    @BeforeEach
    void setUp() {
        inferenceManager = new StubInferenceLifecycleManager();
        // Real reconciler, UNSTARTED — procedureRequireEngine is synchronous. spec chatEnabled=false
        // (null settings store): irrelevant here since post-procedure convergence needs the thread.
        reconciler =
            new RuntimeReconciler(
                inferenceManager,
                inferenceManager::getCurrentMode,
                () -> false,
                null,
                null,
                new RuntimeSpecStore(null),
                new RuntimeGpuLease());
        vduBatchProcessor = mock(VduBatchProcessor.class);
        when(vduBatchProcessor.processPendingFiles()).thenReturn(0);
        knowledgeClient = mock(KnowledgeClient.class);
        capabilityState = new VduCapabilityState();
        coordinator =
            new OfflineCoordinator(
                inferenceManager, reconciler, vduBatchProcessor, () -> knowledgeClient, capabilityState);
    }

    @Nested
    @DisplayName("Phase Sequencing")
    class PhaseSequencing {

        // INTENT: VDU phase (engine up) runs before the embedding phase (park to indexing).
        @Test
        @DisplayName("runs VDU phase before embedding phase when both have pending work")
        void runsVduBeforeEmbeddings() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(10);
            inferenceManager.withMode(Mode.OFFLINE);

            coordinator.startOfflineProcessing();

            // VDU phase ran (engine brought up via procedure), then embeddings parked to indexing.
            verify(vduBatchProcessor).processPendingFiles();
            assertEquals(1, inferenceManager.getOnlineSwitchCount(), "engine brought up for VDU via procedure");
            assertEquals(1, inferenceManager.getIndexingSwitchCount(), "parked to indexing for embeddings");
        }

        // INTENT: no VDU work → VDU phase (and its engine-up request) is skipped.
        @Test
        @DisplayName("skips VDU phase when no pending VDU files")
        void skipsVduWhenNoPending() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(0);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(10);

            coordinator.startOfflineProcessing();

            verify(vduBatchProcessor, never()).processPendingFiles();
            assertEquals(0, inferenceManager.getOnlineSwitchCount(), "no engine-up request without VDU work");
        }

        // INTENT: no VDU work clears a stale AI-offline blocker.
        @Test
        @DisplayName("clears VDU capability blocker when no VDU work is pending")
        void clearsVduCapabilityWhenNoPending() {
            when(knowledgeClient.recoverVduProcessing(any())).thenReturn(0);
            when(knowledgeClient.countPendingVdu(any())).thenReturn(0);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(0);
            capabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);

            coordinator.startOfflineProcessing();

            assertNull(capabilityState.snapshot().blockedReason());
            verify(vduBatchProcessor, never()).processPendingFiles();
        }

        // INTENT: no embeddings → no park-to-indexing request.
        @Test
        @DisplayName("skips embedding phase when no pending embeddings")
        void skipsEmbeddingsWhenNoPending() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(0);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(0);

            coordinator.startOfflineProcessing();

            assertEquals(0, inferenceManager.getIndexingSwitchCount(), "no park-to-indexing request");
        }

        // INTENT: embedding count is re-queried after VDU (VDU marks docs for re-embedding).
        @Test
        @DisplayName("re-queries embedding count after VDU phase")
        void requeriesEmbeddingsAfterVdu() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);
            // First query 0 (before VDU), second 5 (VDU generated re-embeddings).
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(0, 5);

            coordinator.startOfflineProcessing();

            verify(knowledgeClient, times(2)).countPendingEmbeddings(any());
            assertEquals(1, inferenceManager.getIndexingSwitchCount(), "parks to indexing for newly pending embeddings");
        }
    }

    @Nested
    @DisplayName("Recovery")
    class Recovery {

        // INTENT: recovery of PROCESSING-stuck docs runs exactly once at the start.
        @Test
        @DisplayName("calls recoverVduProcessing at start")
        void callsRecoveryAtStart() {
            when(knowledgeClient.recoverVduProcessing(any())).thenReturn(3);

            coordinator.startOfflineProcessing();

            verify(knowledgeClient, times(1)).recoverVduProcessing(any());
        }

        // INTENT: zero recovered does not abort the run.
        @Test
        @DisplayName("continues processing even if recovery finds no stuck documents")
        void continuesWithZeroRecovered() {
            when(knowledgeClient.recoverVduProcessing(any())).thenReturn(0);
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);

            coordinator.startOfflineProcessing();

            verify(vduBatchProcessor).processPendingFiles();
        }
    }

    @Nested
    @DisplayName("Concurrent Guard")
    class ConcurrentGuard {

        // INTENT: only one run proceeds when two start concurrently.
        @Test
        @DisplayName("prevents concurrent processing")
        void preventsConcurrentProcessing() throws InterruptedException {
            AtomicInteger startCount = new AtomicInteger(0);
            CountDownLatch processingStarted = new CountDownLatch(1);
            CountDownLatch canFinish = new CountDownLatch(1);

            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);
            when(vduBatchProcessor.processPendingFiles())
                .thenAnswer(
                    inv -> {
                        startCount.incrementAndGet();
                        processingStarted.countDown();
                        canFinish.await(5, TimeUnit.SECONDS);
                        return 0;
                    });

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                @SuppressWarnings("FutureReturnValueIgnored")
                var unused1 = executor.submit(coordinator::startOfflineProcessing);
                assertTrue(processingStarted.await(1, TimeUnit.SECONDS), "First processing should start");

                @SuppressWarnings("FutureReturnValueIgnored")
                var unused2 = executor.submit(coordinator::startOfflineProcessing);
                Thread.sleep(100);

                assertEquals(1, startCount.get(), "Only one processing should have started");

                canFinish.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }

        // INTENT: a second run proceeds after the first completes.
        @Test
        @DisplayName("allows sequential processing")
        void allowsSequentialProcessing() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);

            coordinator.startOfflineProcessing();
            coordinator.startOfflineProcessing();

            verify(vduBatchProcessor, times(2)).processPendingFiles();
        }

        // INTENT: isProcessing reflects an in-flight run.
        @Test
        @DisplayName("isProcessing returns true during processing")
        void isProcessingReturnsTrueDuringProcessing() throws InterruptedException {
            CountDownLatch processingStarted = new CountDownLatch(1);
            CountDownLatch canFinish = new CountDownLatch(1);

            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);
            when(vduBatchProcessor.processPendingFiles())
                .thenAnswer(
                    inv -> {
                        processingStarted.countDown();
                        canFinish.await(5, TimeUnit.SECONDS);
                        return 0;
                    });

            assertFalse(coordinator.isProcessing(), "Should not be processing initially");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                @SuppressWarnings("FutureReturnValueIgnored")
                var unused = executor.submit(coordinator::startOfflineProcessing);
                assertTrue(processingStarted.await(1, TimeUnit.SECONDS));

                assertTrue(coordinator.isProcessing(), "Should be processing during execution");

                canFinish.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

                assertFalse(coordinator.isProcessing(), "Should not be processing after completion");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Engine Control (procedure-scoped)")
    class EngineControl {

        // INTENT: engine is brought up for VDU when not already online — now via the procedure.
        @Test
        @DisplayName("requests engine up for VDU when not already online")
        void requestsEngineUpForVdu() {
            inferenceManager.withMode(Mode.OFFLINE);
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);

            coordinator.startOfflineProcessing();

            assertEquals(1, inferenceManager.getOnlineSwitchCount(), "procedureRequireEngine(true) drove the switch");
        }

        // INTENT: no redundant engine-up when already online (realized-state read, R4).
        @Test
        @DisplayName("skips engine-up request when already in Online mode")
        void skipsEngineUpWhenAlreadyOnline() {
            inferenceManager.withMode(Mode.ONLINE);
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);

            coordinator.startOfflineProcessing();

            assertEquals(0, inferenceManager.getOnlineSwitchCount(), "already online → no engine-up request");
        }

        // INTENT: VDU phase is skipped (blocked) if the engine-up request fails; embeddings still run.
        @Test
        @DisplayName("skips VDU phase when engine-up request fails")
        void skipsVduWhenEngineUpFails() {
            inferenceManager.withMode(Mode.OFFLINE);
            inferenceManager.withFailOnlineTransition(true);
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(10);

            coordinator.startOfflineProcessing();

            verify(vduBatchProcessor, never()).processPendingFiles();
            assertEquals(
                VduCapabilityState.REASON_AI_OFFLINE,
                capabilityState.snapshot().blockedReason(),
                "engine-up failure blocks VDU with the AI-offline reason");
            assertEquals(1, inferenceManager.getIndexingSwitchCount(), "embedding phase still runs");
        }

        // INTENT: a park-to-indexing failure is handled gracefully (no throw).
        @Test
        @DisplayName("handles park-to-indexing failure gracefully")
        void handlesIndexingParkFailure() {
            inferenceManager.withFailIndexingTransition(true);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(10);

            assertDoesNotThrow(() -> coordinator.startOfflineProcessing());
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethods {

        @Test
        @DisplayName("hasPendingWork returns true when VDU pending")
        void hasPendingWorkWithVdu() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(5);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(0);

            assertTrue(coordinator.hasPendingWork());
        }

        @Test
        @DisplayName("hasPendingWork returns true when embeddings pending")
        void hasPendingWorkWithEmbeddings() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(0);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(10);

            assertTrue(coordinator.hasPendingWork());
        }

        @Test
        @DisplayName("hasPendingWork returns false when nothing pending")
        void hasPendingWorkWithNothing() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(0);
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(0);

            assertFalse(coordinator.hasPendingWork());
        }

        @Test
        @DisplayName("getPendingVduCount delegates to client")
        void getPendingVduCountDelegates() {
            when(knowledgeClient.countPendingVdu(any())).thenReturn(42);
            assertEquals(42, coordinator.getPendingVduCount());
        }

        @Test
        @DisplayName("getPendingEmbeddingCount delegates to client")
        void getPendingEmbeddingCountDelegates() {
            when(knowledgeClient.countPendingEmbeddings(any())).thenReturn(99);
            assertEquals(99, coordinator.getPendingEmbeddingCount());
        }
    }
}
