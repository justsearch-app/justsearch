/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import io.justsearch.configuration.PlatformPaths;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.time.ZonedDateTime;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Current Windows local time, independent of the JVM's cached default zone. */
public final class WindowsLocalTime {
  private static final Logger log = LoggerFactory.getLogger(WindowsLocalTime.class);
  private WindowsLocalTime() {}

  public record Snapshot(int weekday, int minute) {
    public Snapshot {
      if (weekday < 1 || weekday > 7 || minute < 0 || minute > 1439) {
        throw new IllegalArgumentException("Invalid local time snapshot");
      }
    }
  }

  public static Snapshot read() {
    return read(PlatformPaths.isWindows(),
        WindowsLocalTime::readNative, () -> {
          var now = ZonedDateTime.now();
          return new Snapshot(now.getDayOfWeek().getValue(), now.getHour() * 60 + now.getMinute());
        });
  }

  static Snapshot read(boolean windows, Supplier<Snapshot> nativeRead, Supplier<Snapshot> fallback) {
    if (windows) {
      try { return java.util.Objects.requireNonNull(nativeRead.get()); }
      catch (RuntimeException | LinkageError failure) {
        log.debug("GetLocalTime unavailable; using JVM zone: {}", failure.getMessage());
      }
    }
    return fallback.get();
  }

  static Snapshot fromWindows(int sundayBasedDay, int hour, int minute) {
    if (sundayBasedDay < 0 || sundayBasedDay > 6 || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
      throw new IllegalArgumentException("Invalid SYSTEMTIME");
    }
    return new Snapshot(sundayBasedDay == 0 ? 7 : sundayBasedDay, hour * 60 + minute);
  }

  private static Snapshot readNative() {
    try (var arena = Arena.ofConfined()) {
      var kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena);
      var getLocalTime = Linker.nativeLinker().downcallHandle(
          kernel32.find("GetLocalTime").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
      // SYSTEMTIME: eight WORDs. Weekday, hour and minute come from this one native snapshot.
      var buffer = arena.allocate(16, 2);
      getLocalTime.invokeExact(buffer);
      return fromWindows(Short.toUnsignedInt(buffer.get(ValueLayout.JAVA_SHORT, 4)),
          Short.toUnsignedInt(buffer.get(ValueLayout.JAVA_SHORT, 8)),
          Short.toUnsignedInt(buffer.get(ValueLayout.JAVA_SHORT, 10)));
    } catch (Throwable failure) {
      throw new IllegalStateException("GetLocalTime failed", failure);
    }
  }
}
