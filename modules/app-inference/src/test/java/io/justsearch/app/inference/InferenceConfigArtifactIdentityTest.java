/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.ArrayList;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InferenceConfigArtifactIdentityTest {
  @TempDir Path directory;

  @Test
  void configurationPreparationDoesNotStreamTheChatModel() throws Exception {
    byte[] bytes = new byte[256 * 1024];
    java.util.Arrays.fill(bytes, (byte) 7);
    Path model = Files.write(directory.resolve(ChatModelProfile.STANDARD.modelFile()), bytes)
        .toRealPath();
    Path executable = Files.writeString(directory.resolve(
        io.justsearch.configuration.NativeTargets.current().executableName("llama-server")), "fixture");
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) {
      assertTrue(executable.toFile().setExecutable(true, true));
    }
    Files.writeString(directory.resolve(ChatModelProfile.STANDARD.mmprojFile()), "projector");
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var variant = new ModelVariant(model.getFileName().toString(), ModelPrecision.GGUF,
        ExecutionProvider.LLAMA_SERVER, digest, bytes.length, null);
    var manifest = new ModelRegistry(2, "isolated verified identity fixture",
        List.of(new ModelPackage("chat", "fixture", "fixture", "",
            List.of(variant), List.of(), 0, null)));
    var configuration = TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.DATA_DIR.configKey(), directory.toString(),
        EnvRegistry.MODELS_DIR.configKey(), directory.toString(),
        EnvRegistry.LLM_MODEL_PATH.configKey(), model.toString(),
        EnvRegistry.SERVER_EXE.configKey(), executable.toString()));
    var readBytes = new AtomicLong();
    var work = new ArrayList<Runnable>();
    var executors = mock(EngineExecutorRegistry.class);
    var registration = mock(EngineExecutorRegistry.Registration.class);
    var executor = mock(ExecutorService.class);
    when(executors.limits(EngineExecutorSpec.Kind.BACKGROUND))
        .thenReturn(new EngineExecutorRegistry.Limits(1, 64));
    when(executors.register(any())).thenReturn(registration);
    when(registration.open(any())).thenReturn(executor);
    doAnswer(call -> { work.add(call.getArgument(0)); return null; })
        .when(executor).execute(any());
    try (var registry = mockStatic(ModelRegistryLoader.class, CALLS_REAL_METHODS);
        var _ = mockStatic(Files.class, invocation -> {
          if ("newInputStream".equals(invocation.getMethod().getName())
              && model.equals(invocation.getArgument(0))) {
            return new CountingInputStream(bytes, readBytes);
          }
          return invocation.callRealMethod();
        })) {
      registry.when(() -> ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json"))
          .thenReturn(manifest);
      AutoCloseable background = null;
      try {
        // This check compiles on the before revision, whose synchronous read must fail the
        // first assertion below. A missing future helper is not used as fail-before evidence.
        try {
          background = (AutoCloseable) Class.forName("io.justsearch.app.inference.ChatArtifactIdentity")
              .getMethod("start", EngineExecutorRegistry.class).invoke(null, executors);
        } catch (ClassNotFoundException beforeRevision) {
          // The old resolver still runs through its actual content-reading path below.
        }
        int beforeQueue = work.size();
        InferenceConfig initial = InferenceConfig.forPlanning(configuration, directory);
        assertTrue(readBytes.get() <= 4 * 1024,
            "configuration preparation must read bounded identity metadata, not stream the model: "
                + readBytes.get() + " model bytes");
        assertNull(initial.chatProfileId(), "unverified bytes remain unmeasured until owned work runs");
        InferenceConfig.forPlanning(configuration, directory);
        assertEquals(beforeQueue + 1, work.size(), "repeated preparation deduplicates verification");
        work.getLast().run();
        assertEquals(bytes.length, readBytes.get(), "the queued verifier reads the complete artifact");
        assertEquals("standard", InferenceConfig.forPlanning(configuration, directory).chatProfileId());
        assertEquals(bytes.length, readBytes.get(), "confirmed identity uses only its durable receipt");

        var originalTime = Files.getLastModifiedTime(model);
        Files.setLastModifiedTime(model, java.nio.file.attribute.FileTime.fromMillis(
            originalTime.toMillis() + 1000));
        assertNull(InferenceConfig.forPlanning(configuration, directory).chatProfileId());
        Runnable late = work.getLast();
        Files.setLastModifiedTime(model, java.nio.file.attribute.FileTime.fromMillis(
            originalTime.toMillis() + 2000));
        late.run();
        assertNull(InferenceConfig.forPlanning(configuration, directory).chatProfileId(),
            "a verifier returning for older metadata cannot bless the replacement version");
        Files.setLastModifiedTime(model, originalTime);
      } finally {
        if (background != null) background.close();
      }
      long afterBackground = readBytes.get();
      assertEquals("standard", InferenceConfig.forPlanning(configuration, directory).chatProfileId(),
          "a later owner lifetime reads the durable verified receipt");
      assertEquals(afterBackground, readBytes.get(), "a later owner lifetime never rehashes at boot");
    }
  }

  private static final class CountingInputStream extends FilterInputStream {
    private final AtomicLong readBytes;

    CountingInputStream(byte[] bytes, AtomicLong readBytes) {
      super(new ByteArrayInputStream(bytes));
      this.readBytes = readBytes;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) readBytes.incrementAndGet();
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = super.read(buffer, offset, length);
      if (count > 0) readBytes.addAndGet(count);
      return count;
    }
  }
}
