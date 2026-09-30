package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 374 alpha.17 R3 regression: pins the late-bind seam that
 * {@link AiInstallService#setKnowledgeServer} adds.
 *
 * <p>Pre-alpha.17 the {@code knowledgeServer} field was {@code final} and set once
 * at construction. {@code LocalApiServer} constructed {@code AiInstallController}
 * with {@code b.knowledgeServer == null} (HeadlessApp passes null at api-builder
 * time and late-binds via {@code apiServer.lateBindKnowledgeServer}), so the field
 * was null forever. {@code tryRestartWorkerBestEffort} silently early-returned at
 * end of Install AI. The post-Install-AI worker restart never fired, the boot-1
 * worker JVM kept its empty ORT native_path, and ONNX encoders stayed broken
 * until the user manually relaunched the app. Round-7 sandbox confirmed: zero
 * "Restarting worker" log lines in {@code engine.log.boot1} (then named
 * {@code headless-backend.log.boot1}; renamed at lane F stage A item A16) after the
 * {@code alpha.14 fix B: ORT native path set} message. The fix made the field
 * volatile and added a setter that {@code lateBindKnowledgeServer} now calls.
 *
 * <p>This test would have failed if {@code setKnowledgeServer} were removed or
 * if the field were reverted to {@code final}. It does NOT exercise
 * {@code tryRestartWorkerBestEffort} end-to-end (that needs a real
 * {@code KnowledgeServerBootstrap}, which is {@code final} and not test-friendly);
 * the wiring is exercised in the sandbox round-8 validation instead.
 */
final class AiInstallServiceLateBindTest {

  @TempDir Path tmp;

  /** Late-bind: null → non-null mutates the field. */
  @Test
  void setKnowledgeServer_replacesInitialNull() {
    AiInstallService svc =
        new AiInstallService(
            /* appFacade */ null,
            /* settingsStore */ null,
            /* knowledgeServer */ null,
            /* policyService */ null,
            /* aiHomeDir */ tmp);

    assertNull(svc.knowledgeServerForTest(), "field starts null when constructor passes null");

    KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    svc.setKnowledgeServer(ks);

    assertNotNull(
        svc.knowledgeServerForTest(),
        "setKnowledgeServer must mutate the volatile field — pre-alpha.17 the"
            + " field was final and tryRestartWorkerBestEffort silently no-op'd"
            + " for the entire process lifetime (374 alpha.17 R3).");
    assertSame(ks, svc.knowledgeServerForTest(), "late-bind must store the provided bootstrap");
  }

  /** Late-bind: non-null → null is allowed (worker shutdown should propagate). */
  @Test
  void setKnowledgeServer_acceptsNullToReleaseReference() {
    AiInstallService svc =
        new AiInstallService(null, null, null, null, tmp);
    KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    svc.setKnowledgeServer(ks);

    svc.setKnowledgeServer(null);

    assertNull(
        svc.knowledgeServerForTest(),
        "setKnowledgeServer(null) must clear the reference so a subsequent"
            + " Install AI invocation falls back to the silent no-op rather"
            + " than dereferencing a stale spawner.");
  }
}
