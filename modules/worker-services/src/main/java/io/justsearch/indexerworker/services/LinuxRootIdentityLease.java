/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A held Linux directory, with mount and UUID evidence bracketed by directory identity checks. */
final class LinuxRootIdentityLease implements RootIdentityLease {
  private static final Path MOUNTINFO = Path.of("/proc/self/mountinfo");
  private static final Path UUIDS = Path.of("/dev/disk/by-uuid");
  private static final Pattern UUID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9-]{0,127}");
  private static final Pattern TYPE = Pattern.compile("[a-zA-Z0-9_.-]+");
  private static final int BLOCK_DEVICE = 0060000;
  private final Path root;
  private final int descriptor;
  private final RootIdentity identity;
  private final Mount mount;
  private boolean closed;

  private LinuxRootIdentityLease(Path root, int descriptor, Observation observed) {
    this.root = root;
    this.descriptor = descriptor;
    this.identity = observed.identity();
    this.mount = observed.mount();
  }

  static RootIdentityLease open(Path root) throws IOException {
    int descriptor = Native.open(root);
    boolean retained = false;
    try {
      Observation observed = observe(root, descriptor);
      LinuxRootIdentityLease lease = new LinuxRootIdentityLease(root, descriptor, observed);
      retained = true;
      return lease;
    } finally {
      if (!retained) Native.close(descriptor);
    }
  }

  @Override
  public RootIdentity identity() {
    return identity;
  }

  @Override
  public void requireCurrent() throws IOException {
    if (closed) throw new IOException("Linux root witness is closed");
    Observation current = observe(root, descriptor);
    identity.requireSame(current.identity(), root);
    if (!Objects.equals(mount, current.mount())) {
      throw new IOException("Reconciliation root mount changed: " + root);
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      Native.close(descriptor);
    }
  }

  private record Observation(RootIdentity identity, Mount mount) {}

  private static Observation observe(Path root, int descriptor) throws IOException {
    List<Mount> before = readMounts();
    Path held = Path.of("/proc/self/fd/" + descriptor);
    BasicFileAttributes heldAttributes = Files.readAttributes(held, BasicFileAttributes.class);
    Object fileKey = heldAttributes.fileKey();
    if (!heldAttributes.isDirectory() || fileKey == null) {
      throw new IOException("Linux root has no held directory identity: " + root);
    }
    long device = ((Number) Files.getAttribute(held, "unix:dev")).longValue();
    Path real = root.toRealPath();
    requireHeld(root, fileKey, device, real);
    Mount mount = selectMount(before, real);
    String volume = mount != null && mount.device().equals(deviceNumber(device))
        ? volume(mount, UUIDS, device) : null;
    // A replaced mount, a retargeted UUID symlink or a renamed/replaced directory must not
    // publish mixed evidence. The held fd prevents inode reuse throughout this observation.
    String after = mount != null && mount.device().equals(deviceNumber(device))
        ? volume(mount, UUIDS, device) : null;
    if (!Objects.equals(volume, after)) {
      throw new IOException("Linux root UUID changed while reading its identity: " + root);
    }
    if (!Objects.equals(mount, selectMount(readMounts(), real))) {
      throw new IOException("Linux root mount changed while reading its identity: " + root);
    }
    requireHeld(root, fileKey, device, real);
    return new Observation(
        new RootIdentity(real, new RootIdentity.LinuxFileIdentity(fileKey, volume)), mount);
  }

  private static void requireHeld(Path root, Object fileKey, long device, Path real)
      throws IOException {
    BasicFileAttributes current = Files.readAttributes(root, BasicFileAttributes.class);
    long currentDevice = ((Number) Files.getAttribute(root, "unix:dev")).longValue();
    if (!current.isDirectory() || !fileKey.equals(current.fileKey()) || device != currentDevice
        || !real.equals(root.toRealPath())) {
      throw new IOException("Linux reconciliation root is no longer the held directory: " + root);
    }
  }

  /** Mount IDs are only observation evidence, never persistent identity. */
  record Mount(String id, String parent, String device, Path filesystemRoot, Path point,
      String type) {}

  private static List<Mount> readMounts() throws IOException {
    return parseMounts(Files.readAllLines(MOUNTINFO));
  }

  static List<Mount> parseMounts(List<String> lines) throws IOException {
    List<Mount> mounts = new ArrayList<>();
    for (String line : lines) {
      String[] fields = line.split(" ");
      int separator = 6;
      while (separator < fields.length && !fields[separator].equals("-")) separator++;
      if (fields.length < 10 || separator + 3 >= fields.length
          || !fields[0].matches("[0-9]+") || !fields[1].matches("[0-9]+")
          || !fields[2].matches("[0-9]+:[0-9]+")) {
        throw new IOException("Malformed Linux mountinfo entry");
      }
      Path filesystemRoot = Path.of(unescape(fields[3]));
      Path point = Path.of(unescape(fields[4]));
      if (!filesystemRoot.isAbsolute() || !point.isAbsolute()) {
        throw new IOException("Nonabsolute Linux mountinfo path");
      }
      mounts.add(new Mount(fields[0], fields[1], fields[2], filesystemRoot, point,
          fields[separator + 1]));
    }
    return mounts;
  }

  private static String unescape(String field) throws IOException {
    StringBuilder decoded = new StringBuilder();
    for (int i = 0; i < field.length(); i++) {
      char c = field.charAt(i);
      if (c != '\\') {
        decoded.append(c);
        continue;
      }
      if (i + 3 >= field.length()) throw new IOException("Truncated Linux mountinfo escape");
      String escape = field.substring(i + 1, i + 4);
      decoded.append(switch (escape) {
        case "040" -> ' ';
        case "011" -> '\t';
        case "012" -> '\n';
        case "134" -> '\\';
        default -> throw new IOException("Unknown Linux mountinfo escape");
      });
      i += 3;
    }
    return decoded.toString();
  }

  static Mount selectMount(List<Mount> mounts, Path directory) {
    Mount selected = null;
    boolean ambiguous = false;
    for (Mount candidate : mounts) {
      if (!directory.startsWith(candidate.point())) continue;
      if (selected == null || candidate.point().toString().length() > selected.point().toString().length()) {
        selected = candidate;
        ambiguous = false;
      } else if (candidate.point().equals(selected.point())) {
        ambiguous = true;
      }
    }
    return ambiguous ? null : selected;
  }

  static String volume(Mount mount, Path uuidDirectory, long device) throws IOException {
    String uuid = uuidForDevice(uuidDirectory, device);
    return uuid == null ? null : encodeVolume(uuid, mount.type(), mount.filesystemRoot());
  }

  private static String uuidForDevice(Path directory, long device) throws IOException {
    if (!Files.isDirectory(directory)) return null;
    String found = null;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        if (!Files.isSymbolicLink(entry)) continue;
        Map<String, Object> attributes;
        try {
          attributes = Files.readAttributes(entry, "unix:rdev,mode");
        } catch (IOException unavailable) {
          // A dangling/unreadable alias could be the target device: uncertainty cannot prove it.
          return null;
        }
        int mode = ((Number) attributes.get("mode")).intValue();
        long targetDevice = ((Number) attributes.get("rdev")).longValue();
        if ((mode & 0170000) != BLOCK_DEVICE || targetDevice != device) continue;
        String uuid = entry.getFileName().toString();
        if (!UUID.matcher(uuid).matches() || found != null) return null;
        found = uuid.toLowerCase(Locale.ROOT);
      }
    } catch (IOException unavailable) {
      return null;
    }
    return found;
  }

  static String encodeVolume(String uuid, String type, Path filesystemRoot) {
    if (!UUID.matcher(uuid).matches() || !TYPE.matcher(type).matches()
        || !filesystemRoot.isAbsolute()) {
      throw new IllegalArgumentException("Invalid Linux filesystem identity");
    }
    String root = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(filesystemRoot.toString().getBytes(StandardCharsets.UTF_8));
    return "linux-volume-v1:" + uuid.toLowerCase(Locale.ROOT) + ":" + type + ":" + root;
  }

  static boolean validVolume(String value) {
    String[] fields = value.split(":", -1);
    if (fields.length != 4 || !fields[0].equals("linux-volume-v1")
        || !UUID.matcher(fields[1]).matches() || !TYPE.matcher(fields[2]).matches()) return false;
    try {
      String root = new String(Base64.getUrlDecoder().decode(fields[3]), StandardCharsets.UTF_8);
      return value.equals(encodeVolume(fields[1], fields[2], Path.of(root)));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static String deviceNumber(long device) {
    long major = (device >>> 8 & 0xfffL) | (device >>> 32 & 0xfffff000L);
    long minor = (device & 0xffL) | (device >>> 12 & 0xffffff00L);
    return major + ":" + minor;
  }

  /** libc open/close only; directory attributes are read through the retained proc fd. */
  private static final class Native {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle OPEN = LINKER.downcallHandle(
        LINKER.defaultLookup().find("open").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE = LINKER.downcallHandle(
        LINKER.defaultLookup().find("close").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private Native() {}

    static int open(Path root) throws IOException {
      try (Arena arena = Arena.ofConfined()) {
        // O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC; read-only. Never inherited by helpers.
        int fd = (int) OPEN.invokeExact(arena.allocateFrom(root.toString()), 0x10000 | 0x20000 | 0x80000);
        if (fd < 0) throw new IOException("Cannot hold Linux directory: " + root);
        return fd;
      } catch (IOException failure) {
        throw failure;
      } catch (Throwable failure) {
        throw new IOException("Cannot open Linux root witness: " + root, failure);
      }
    }

    static void close(int fd) throws IOException {
      try {
        int result = (int) CLOSE.invokeExact(fd);
        if (result != 0) throw new IOException("Cannot close Linux root witness");
      } catch (IOException failure) {
        throw failure;
      } catch (Throwable failure) {
        throw new IOException("Cannot close Linux root witness", failure);
      }
    }
  }
}
