/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(90)
final class PictureReadingConfigurationTest {
  @TempDir Path directory;

  @Test
  void runtimeAndProfileAppliesCannotReattachAProjectorWhileOff() throws Exception {
    try (var seam = new ManagedChatSeam(directory, 0)) {
      var manager = seam.manager();
      var off = offConfig(seam);
      ConfigStore.global().swap(off);
      var next = withProjector(seam);
      manager.applyConfig(next, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      assertNull(manager.currentConfig().mmprojPath(), "profile and runtime applies must honor the saved Off");
      manager.switchToOnlineMode();
      assertFalse(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains(next.mmprojPath().toString()));
      manager.applyConfig(next, InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
      assertNull(manager.currentConfig().mmprojPath());
      assertTrue(Files.isRegularFile(next.mmprojPath()), "switching must retain the downloaded projector");
    }
  }

  @Test
  void preparedPictureChangeWaitsForRepliesAndAbortRestoresTheProjector() throws Exception {
    try (var seam = new ManagedChatSeam(directory, 0)) {
      var manager = seam.manager();
      var next = withProjector(seam);
      manager.applyConfig(next, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      manager.switchToOnlineMode();
      long incumbentPid = SlowStartFakeLlamaServer.launches(seam.fakeHome()).getLast().pid();
      var prepared = new CompletableFuture<InferenceLifecycleManager.PreparedConfigApply>();
      try (var _ = manager.admitExchange(GenerationTracker.Kind.GENERATE, null)) {
        Thread.ofVirtual().start(() -> {
          try { prepared.complete(manager.prepareResolvedConfig(next, offConfig(seam), true)); }
          catch (Exception failure) { prepared.completeExceptionally(failure); }
        });
        ManagedChatSeam.await(() -> admissionClosed(manager), "the picture change to close admission");
        assertTrue(ProcessHandle.of(incumbentPid).map(ProcessHandle::isAlive).orElse(false),
            "the serving process must remain alive while its reply is draining");
        assertThrows(TimeoutException.class, () -> prepared.get(150, TimeUnit.MILLISECONDS),
            "a picture switch must wait for the streaming reply");
        assertEquals(1, seam.models().size(), "the server must not restart before the reply ends");
        assertEquals(next.mmprojPath(), manager.currentConfig().mmprojPath());
      }
      var candidate = prepared.get(30, TimeUnit.SECONDS);
      assertFalse(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains(next.mmprojPath().toString()),
          "the prepared managed server must run without the projector");
      candidate.abort();
      assertEquals(next.mmprojPath(), manager.currentConfig().mmprojPath(), "failed settings save retains the prior configuration");
      assertTrue(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains(next.mmprojPath().toString()));
    }
  }

  private static boolean admissionClosed(InferenceLifecycleManager manager) {
    try (var _ = manager.admitExchange(GenerationTracker.Kind.AUX, null)) { return false; }
    catch (io.justsearch.app.api.chat.GenerationUnavailableException draining) { return true; }
  }

  private InferenceConfig withProjector(ManagedChatSeam seam) throws Exception {
    var base = seam.base();
    var projector = Files.writeString(directory.resolve("retained-projector.gguf"), "projector fixture");
    return new InferenceConfig(base.serverExecutable(), base.modelPath(), projector,
        base.serverPort(), base.contextSize(), base.gpuLayers(), false);
  }

  private ResolvedConfig offConfig(ManagedChatSeam seam) {
    var base = seam.base();
    return ResolvedConfig.builder()
        .putDefault("justsearch.home", directory.toString())
        .putDefault("justsearch.data.dir", directory.resolve("data").toString())
        .putDefault("justsearch.server.exe", base.serverExecutable().toString())
        .putDefault("justsearch.llm.model_path", base.modelPath().toString())
        .putDefault("justsearch.server.port", Integer.toString(base.serverPort()))
        .putDefault("justsearch.gpu.layers", "0")
        .putDefault("justsearch.mmproj.model", "none")
        .build();
  }
}
