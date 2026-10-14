/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Verified chat-file receipts: bounded metadata on configuration paths, hashing on an owned lane. */
public final class ChatArtifactIdentity {
  private static final Logger LOG = LoggerFactory.getLogger(ChatArtifactIdentity.class);
  private static final int MAX_RECEIPT_BYTES = 4096;
  private static final int MAX_PENDING = 64;
  private static final String SUFFIX = ".justsearch-identity.v1";
  private static final Object LOCK = new Object();
  private static final Map<Path, Request> PENDING = new LinkedHashMap<>();
  private static Owner owner;

  private ChatArtifactIdentity() {}

  /** Installer hooks are confined to the managed chat model names, including staging suffixes. */
  public static boolean isChatModel(Path file) {
    String name = file.getFileName().toString().replaceFirst("\\.(partial|tmp)$", "");
    for (var profile : io.justsearch.configuration.model.ChatModelProfile.values()) {
      if (name.equals(Path.of(profile.modelFile()).getFileName().toString())) return true;
    }
    return false;
  }

  /** Immutable metadata captured before verification; promotion may change only its path. */
  public record Observation(Path path, String fileKey, long size, String modified) {
    boolean sameFileVersion(Observation other) {
      return Objects.equals(fileKey, other.fileKey) && size == other.size
          && modified.equals(other.modified);
    }
  }

  private record Receipt(Observation observation, String digest) {}
  private record Request(Observation observation, String digest, AtomicBoolean submitted) {}

  /** Captures metadata only. The canonical path remains part of every durable receipt. */
  public static Observation observe(Path file) throws IOException {
    Path canonical = file.toRealPath();
    var attributes = Files.readAttributes(canonical, BasicFileAttributes.class);
    if (!attributes.isRegularFile()) throw new IOException("Chat artifact is not a regular file");
    return new Observation(canonical, Objects.toString(attributes.fileKey(), ""),
        attributes.size(), attributes.lastModifiedTime().toString());
  }

  /** No model-content read occurs here. Uncertain identity remains unmeasured until verified. */
  public static boolean matches(Path file, long expectedSize, String expectedDigest) throws IOException {
    Observation current = observe(file);
    if (current.size() != expectedSize || !validDigest(expectedDigest)) return false;
    Receipt receipt = readReceipt(current.path());
    if (receipt != null && receipt.observation().path().equals(current.path())
        && receipt.observation().sameFileVersion(current)) {
      return receipt.digest().equalsIgnoreCase(expectedDigest);
    }
    synchronized (LOCK) {
      Request previous = PENDING.get(current.path());
      if (previous == null || !previous.observation().sameFileVersion(current)
          || !previous.digest().equalsIgnoreCase(expectedDigest)) {
        if (previous == null && PENDING.size() >= MAX_PENDING) return false;
        previous = new Request(current, expectedDigest, new AtomicBoolean());
        PENDING.put(current.path(), previous);
      }
      submit(previous);
    }
    return false;
  }

  /** Called after a writer verified these bytes; a changed file never earns that verification. */
  public static void rememberVerified(Path file, long size, String digest, Observation before)
      throws IOException {
    if (!validDigest(digest) || before == null || size != before.size()) return;
    Observation after = observe(file);
    if (!before.sameFileVersion(after)) return;
    writeReceipt(new Receipt(after, digest));
  }

  /** Records bytes just produced and checksum-verified by the pack staging writer. */
  public static void rememberWritten(Path file, long size, String digest) throws IOException {
    rememberVerified(file, size, digest, observe(file));
  }

  /** Carries verified metadata across a rename, checking the destination's retained file identity. */
  public static void promoted(Path from, Path to, long size, String digest) throws IOException {
    Receipt receipt = readReceipt(from);
    if (receipt == null || !receipt.digest().equalsIgnoreCase(digest)
        || receipt.observation().size() != size) return;
    rememberVerified(to, size, digest, receipt.observation());
    Files.deleteIfExists(sidecar(from));
  }

  /** Opens one registered, bounded background lane after Engine process resources exist. */
  public static AutoCloseable start(EngineExecutorRegistry registry) {
    synchronized (LOCK) {
      if (owner != null) return () -> {};
      var limits = registry.limits(EngineExecutorSpec.Kind.BACKGROUND);
      var registration = registry.register(new EngineExecutorSpec("engine.chat-artifact-verification",
          EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM,
          1, limits.maxQueue(), 1));
      try {
        owner = new Owner(registration, registration.open(
            Thread.ofPlatform().daemon().name("chat-artifact-verification").factory()));
      } catch (RuntimeException | Error failure) {
        registration.close();
        throw failure;
      }
      PENDING.values().forEach(ChatArtifactIdentity::submit);
      return owner;
    }
  }

  private static void submit(Request request) {
    Owner selected = owner;
    if (selected == null || !request.submitted().compareAndSet(false, true)) return;
    try {
      selected.executor.execute(() -> verify(request, selected));
    } catch (RejectedExecutionException unavailable) {
      request.submitted().set(false);
      LOG.debug("Chat identity verification deferred: background lane is full");
    }
  }

  private static void verify(Request request, Owner selected) {
    try {
      if (selected.closed.get()) return;
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(request.observation().path())) {
        byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) >= 0;) {
          if (selected.closed.get() || Thread.currentThread().isInterrupted()) return;
          hash.update(buffer, 0, count);
        }
      }
      String digest = HexFormat.of().formatHex(hash.digest());
      synchronized (LOCK) {
        if (owner == selected && !selected.closed.get()) {
          rememberVerified(request.observation().path(), request.observation().size(),
              digest, request.observation());
        }
      }
    } catch (IOException | NoSuchAlgorithmException unavailable) {
      LOG.debug("Chat artifact identity remains unmeasured: {}", unavailable.getMessage());
    } finally {
      synchronized (LOCK) {
        PENDING.remove(request.observation().path(), request);
        if (owner == selected && !selected.closed.get()) {
          PENDING.values().forEach(ChatArtifactIdentity::submit);
        }
      }
    }
  }

  private static Path sidecar(Path file) {
    return file.resolveSibling(file.getFileName() + SUFFIX);
  }

  private static Receipt readReceipt(Path file) {
    Path sidecar = sidecar(file);
    if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) return null;
    try (var input = Files.newInputStream(sidecar)) {
      byte[] bytes = input.readNBytes(MAX_RECEIPT_BYTES + 1);
      if (bytes.length > MAX_RECEIPT_BYTES) return null;
      var properties = new Properties();
      properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
      if (!"1".equals(properties.getProperty("version"))) return null;
      String digest = properties.getProperty("sha256");
      if (!validDigest(digest)) return null;
      return new Receipt(new Observation(Path.of(properties.getProperty("path")),
          properties.getProperty("fileKey", ""), Long.parseLong(properties.getProperty("size")),
          Objects.requireNonNull(properties.getProperty("modified"))), digest);
    } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
      LOG.debug("Chat identity receipt ignored: {}", invalid.getMessage());
      return null;
    }
  }

  private static void writeReceipt(Receipt receipt) throws IOException {
    var observed = receipt.observation();
    if (!observed.sameFileVersion(observe(observed.path()))) return;
    var properties = new Properties();
    properties.setProperty("version", "1");
    properties.setProperty("path", observed.path().toString());
    properties.setProperty("fileKey", observed.fileKey());
    properties.setProperty("size", Long.toString(observed.size()));
    properties.setProperty("modified", observed.modified());
    properties.setProperty("sha256", receipt.digest());
    var text = new StringWriter();
    properties.store(text, null);
    byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_RECEIPT_BYTES) return;
    Path target = sidecar(observed.path());
    Path temporary = Files.createTempFile(target.getParent(), ".chat-identity-", ".tmp");
    try {
      Files.write(temporary, bytes);
      if (!observed.sameFileVersion(observe(observed.path()))) return;
      try {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally { Files.deleteIfExists(temporary); }
  }

  private static boolean validDigest(String digest) {
    return digest != null && digest.matches("(?i)[0-9a-f]{64}");
  }

  private static final class Owner implements AutoCloseable {
    private final EngineExecutorRegistry.Registration registration;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    Owner(EngineExecutorRegistry.Registration registration, ExecutorService executor) {
      this.registration = registration;
      this.executor = executor;
    }

    @Override public void close() {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (LOCK) {
        if (owner == this) owner = null;
        PENDING.clear();
      }
      registration.close();
    }
  }
}
