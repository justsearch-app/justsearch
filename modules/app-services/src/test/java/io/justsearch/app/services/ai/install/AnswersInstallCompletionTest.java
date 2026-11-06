/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Answers consented with a missing answer model ("Download again", the answers switch) are switched
 * on by the install's completion, once the files are on disk, never at its start; a newer answers
 * choice saved during the download wins.
 */
final class AnswersInstallCompletionTest {
  private static final String CONSENTED =
      "{\"acceptTerms\":true,\"enableChatOnCompletion\":true,\"includePackages\":[]}";

  @Test
  void consentedAnswersAreSwitchedOnWhenTheDelayedDownloadCompletes(@TempDir Path home)
      throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      rig.start(CONSENTED);
      assertEquals(Boolean.FALSE, rig.store.inspect().settings().getChatEnabled(),
          "Answers must not be switched on while the answer model is still downloading");
      var committed = rig.finish();
      assertEquals(Boolean.TRUE, committed.settings().getChatEnabled(),
          "The consented answers enable must apply once the download completed");
    }
  }

  @Test
  void anUnrelatedSaveDuringTheDownloadKeepsTheConsentedEnable(@TempDir Path home)
      throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      rig.start(CONSENTED);
      rig.save("\"ui\":{\"theme\":\"dark\"}");
      var committed = rig.finish();
      assertEquals(Boolean.TRUE, committed.settings().getChatEnabled(),
          "An unrelated save must not cancel the consented enable");
      assertEquals("dark", committed.settings().getTheme(), "Completion must use the latest settings");
    }
  }

  @Test
  void answersSwitchedOffAgainDuringTheDownloadStayOff(@TempDir Path home) throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      rig.start(CONSENTED);
      // Off -> off is deliberate: a value-only guard cannot see this newer choice.
      rig.fixture.spec().setChatEnabled(false);
      var off = rig.store.inspect();
      var committed = rig.finish();
      assertEquals(Boolean.FALSE, committed.settings().getChatEnabled(),
          "Completion must not override an explicit Off saved after the consent");
      assertEquals(off.witness(), committed.witness(), "Completion must not save another choice");
    }
  }

  @Test
  void anInstallWithoutTheConsentedEnableLeavesAnswersOff(@TempDir Path home) throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      rig.start("{\"acceptTerms\":true}");
      var committed = rig.finish();
      assertEquals(Boolean.FALSE, committed.settings().getChatEnabled());
    }
  }
}
