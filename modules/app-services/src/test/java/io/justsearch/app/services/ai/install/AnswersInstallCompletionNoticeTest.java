/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The completion enable tells answer-server reconciliation (a settings write that changes answers
 * does the same), and it is stamped as the latest answers choice; a skipped enable tells nothing.
 */
final class AnswersInstallCompletionNoticeTest {
  private static final String CONSENTED = "{\"acceptTerms\":true,\"enableChatOnCompletion\":true}";

  @Test
  void theCompletionEnableNotifiesReconciliationOnceAfterItIsSaved(@TempDir Path home)
      throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      var notices = new AtomicInteger();
      var savedWhenNotified = new AtomicInteger(-1);
      rig.installer.setChatIntentChanged(() -> {
        notices.incrementAndGet();
        savedWhenNotified.set(Boolean.TRUE.equals(rig.store.inspect().settings().getChatEnabled()) ? 1 : 0);
      });
      rig.start(CONSENTED);
      assertEquals(0, notices.get(), "Nothing is switched on while downloading");
      var committed = rig.finish();
      assertEquals(1, notices.get());
      assertEquals(1, savedWhenNotified.get(), "Reconciliation must see the saved enable");
      assertEquals(committed.witness().acceptedRevision(),
          committed.settings().getChatPreferenceRevision());
    }
  }

  @Test
  void aNewerOffChoiceIsStampedAndTheSkippedEnableNotifiesNothing(@TempDir Path home)
      throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      var notices = new AtomicInteger();
      rig.installer.setChatIntentChanged(notices::incrementAndGet);
      rig.start(CONSENTED);
      long before = rig.store.inspect().witness().acceptedRevision();
      rig.fixture.spec().setChatEnabled(false);
      var off = rig.store.inspect();
      assertEquals(before + 1, off.settings().getChatPreferenceRevision(),
          "The answers switch stamps a same-value choice");
      rig.finish();
      assertEquals(0, notices.get());
    }
  }
}
