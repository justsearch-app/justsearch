/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The default file system as a platform without directory handles sees it: every operation goes to
 * the real file system, but a directory stream is a plain {@link DirectoryStream}, never a {@code
 * SecureDirectoryStream} (as on Windows). {@link #onOpenDirectory} runs when a directory is opened,
 * before the stream is returned: what happens there happens after the caller's last check by name.
 * {@link #onMove} runs before each move and may throw what the file system would. Test-only; it
 * touches only the temporary folders the test gives it.
 */
final class NoDirectoryHandlesFileSystem extends FileSystem {
  private final FileSystem real;
  private final Provider provider = new Provider();
  /** Runs with the real path of each directory opened. */
  volatile Consumer<Path> onOpenDirectory = dir -> {};
  /** Runs before each move, with its real paths and its options. */
  volatile MoveHook onMove = (source, target, options) -> {};

  /** What happens to a move before it reaches the real file system. */
  @FunctionalInterface
  interface MoveHook {
    void beforeMove(Path source, Path target, CopyOption... options) throws IOException;
  }

  NoDirectoryHandlesFileSystem(FileSystem real) {
    this.real = real;
  }

  /** {@code path} (of the real file system) on this one. */
  Path wrap(Path path) {
    return path == null ? null : new Wrapped(path);
  }

  private Path unwrap(Path path) {
    if (path instanceof Wrapped wrapped) return wrapped.real;
    if (path != null && path.getFileSystem() == real) return path;
    throw new ProviderMismatchException();
  }

  @Override public FileSystemProvider provider() { return provider; }
  @Override public void close() {}
  @Override public boolean isOpen() { return true; }
  @Override public boolean isReadOnly() { return false; }
  @Override public String getSeparator() { return real.getSeparator(); }

  @Override
  public Iterable<Path> getRootDirectories() {
    return () -> {
      Iterator<Path> roots = real.getRootDirectories().iterator();
      return new Iterator<>() {
        @Override public boolean hasNext() { return roots.hasNext(); }
        @Override public Path next() { return wrap(roots.next()); }
      };
    };
  }

  @Override public Iterable<FileStore> getFileStores() { return real.getFileStores(); }
  @Override public Set<String> supportedFileAttributeViews() { return real.supportedFileAttributeViews(); }
  @Override public Path getPath(String first, String... more) { return wrap(real.getPath(first, more)); }

  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    PathMatcher matcher = real.getPathMatcher(syntaxAndPattern);
    return path -> matcher.matches(unwrap(path));
  }

  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    return real.getUserPrincipalLookupService();
  }

  @Override public WatchService newWatchService() { throw new UnsupportedOperationException(); }

  private final class Wrapped implements Path {
    private final Path real;

    private Wrapped(Path real) {
      this.real = real;
    }

    @Override public FileSystem getFileSystem() { return NoDirectoryHandlesFileSystem.this; }
    @Override public boolean isAbsolute() { return real.isAbsolute(); }
    @Override public Path getRoot() { return wrap(real.getRoot()); }
    @Override public Path getFileName() { return wrap(real.getFileName()); }
    @Override public Path getParent() { return wrap(real.getParent()); }
    @Override public int getNameCount() { return real.getNameCount(); }
    @Override public Path getName(int index) { return wrap(real.getName(index)); }
    @Override public Path subpath(int begin, int end) { return wrap(real.subpath(begin, end)); }
    @Override public boolean startsWith(Path other) { return real.startsWith(unwrapAny(other)); }
    @Override public boolean endsWith(Path other) { return real.endsWith(unwrapAny(other)); }
    @Override public Path normalize() { return wrap(real.normalize()); }
    @Override public Path resolve(Path other) { return wrap(real.resolve(unwrapAny(other))); }
    @Override public Path relativize(Path other) { return wrap(real.relativize(unwrap(other))); }
    @Override public URI toUri() { return real.toUri(); }
    @Override public Path toAbsolutePath() { return wrap(real.toAbsolutePath()); }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
      return wrap(real.toRealPath(options));
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
        WatchEvent.Modifier... modifiers) {
      throw new UnsupportedOperationException();
    }

    @Override public int compareTo(Path other) { return real.compareTo(unwrap(other)); }
    @Override public boolean equals(Object other) { return other instanceof Wrapped w && real.equals(w.real); }
    @Override public int hashCode() { return real.hashCode(); }
    @Override public String toString() { return real.toString(); }

    /** A relative path of the real file system (say {@code Path.of("x")}) resolves as its text. */
    private Path unwrapAny(Path other) {
      if (other instanceof Wrapped wrapped) return wrapped.real;
      if (other.getFileSystem() == NoDirectoryHandlesFileSystem.this.real) return other;
      return NoDirectoryHandlesFileSystem.this.real.getPath(other.toString());
    }
  }

  private final class Provider extends FileSystemProvider {
    private FileSystemProvider real() {
      return real.provider();
    }

    @Override public String getScheme() { return "no-directory-handles"; }
    @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
    @Override public FileSystem getFileSystem(URI uri) { return NoDirectoryHandlesFileSystem.this; }
    @Override public Path getPath(URI uri) { return wrap(real().getPath(uri)); }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
        FileAttribute<?>... attrs) throws IOException {
      return real().newByteChannel(unwrap(path), options, attrs);
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options,
        FileAttribute<?>... attrs) throws IOException {
      return real().newFileChannel(unwrap(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
        DirectoryStream.Filter<? super Path> filter) throws IOException {
      Path opened = unwrap(dir);
      DirectoryStream<Path> stream = real().newDirectoryStream(opened, entry -> filter.accept(wrap(entry)));
      onOpenDirectory.accept(opened);
      return new DirectoryStream<>() {
        @Override
        public Iterator<Path> iterator() {
          Iterator<Path> entries = stream.iterator();
          return new Iterator<>() {
            @Override public boolean hasNext() { return entries.hasNext(); }
            @Override public Path next() { return wrap(entries.next()); }
          };
        }

        @Override
        public void close() throws IOException {
          stream.close();
        }
      };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
      real().createDirectory(unwrap(dir), attrs);
    }

    @Override public void delete(Path path) throws IOException { real().delete(unwrap(path)); }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
      real().copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
      onMove.beforeMove(unwrap(source), unwrap(target), options);
      real().move(unwrap(source), unwrap(target), options);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
      real().createLink(unwrap(link), unwrap(existing));
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
      real().createSymbolicLink(unwrap(link), unwrap(target), attrs);
    }

    @Override public Path readSymbolicLink(Path link) throws IOException { return wrap(real().readSymbolicLink(unwrap(link))); }
    @Override public boolean isSameFile(Path a, Path b) throws IOException { return real().isSameFile(unwrap(a), unwrap(b)); }
    @Override public boolean isHidden(Path path) throws IOException { return real().isHidden(unwrap(path)); }
    @Override public FileStore getFileStore(Path path) throws IOException { return real().getFileStore(unwrap(path)); }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
      real().checkAccess(unwrap(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
        LinkOption... options) {
      return real().getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
        LinkOption... options) throws IOException {
      return real().readAttributes(unwrap(path), type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
        throws IOException {
      return real().readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
        throws IOException {
      real().setAttribute(unwrap(path), attribute, value, options);
    }
  }
}
