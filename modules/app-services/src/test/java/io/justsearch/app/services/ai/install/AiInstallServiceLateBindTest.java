package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins publication and release of the late-bound index bootstrap used by install guidance. */
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
        "setKnowledgeServer must publish the bootstrap to subsequent install invocations");
    assertSame(ks, svc.knowledgeServerForTest(), "late-bind must store the provided bootstrap");
  }

  /** Late-bind: non-null → null is allowed (index shutdown should propagate). */
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
            + " than consulting a stale bootstrap.");
  }
}
