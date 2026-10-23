# Real HotSpot fatal-report headers

These are header excerpts of four actual Linux HotSpot reports generated on 2026-10-21
with Temurin OpenJDK 25.0.4.1+1. Everything from the `SUMMARY` section onward is omitted;
absolute paths in the retained headers are replaced by `<path>`. Header types, spacing,
source locations, problematic frames, addresses and process/thread IDs are unchanged.
The internal-error form also occurs in the independent acceptor's real
`hs_err_pid-internal-real.log` (generated with AbortVMOnException).

The grammar comes from OpenJDK's [VMError::report](https://github.com/openjdk/jdk25u/blob/master/src/hotspot/share/utilities/vmError.cpp)
(`printing exception/signal name`): an OS name, `Internal Error`, or `Out of Memory Error`,
followed by a source location or hexadecimal ID. The OS names come from
[POSIX exception_name/get_signal_name](https://github.com/openjdk/jdk25u/blob/master/src/hotspot/os/posix/signals_posix.cpp)
and the [Windows exception table](https://github.com/openjdk/jdk25u/blob/master/src/hotspot/os/windows/os_windows.cpp).
POSIX includes SIGRTMIN+offset, SIGRTMIN, SIGRTMAX, and SIG<number>; all Windows names
start with EXCEPTION_. Unknown OS codes fall back to Internal Error, not a fifth header kind.
The separate numeric-location test follows the no-filename branch in that source;
its numeric variants are constructed, not represented as generated reports.

Reproduction (use an existing absolute scratch directory as TMPDIR; no Engine is started):

- All Java invocations: `-Djava.io.tmpdir=$TMPDIR -XX:-CreateCoredumpOnCrash`
  and `-XX:ErrorFile=$TMPDIR/hs_err_pid-<kind>.log`.
- `internal.txt`: run a Java main that throws
  `new RuntimeException("controlled fatal report")`, with
  `-XX:+UnlockDiagnosticVMOptions -XX:AbortVMOnException=java.lang.RuntimeException`.
- `native-oom.txt`: run `java -version` with a **process-local** RLIMIT_AS of
  384 MiB, `-Xms16m -Xmx16m -XX:CompressedClassSpaceSize=16m -XX:ReservedCodeCacheSize=16m`.
  Native metaspace reservation fails, producing Out of Memory Error (not Java heap OOM).
- `sigsegv.txt` and `realtime-signal.txt`: load a JNI library, install the SA_SIGINFO
  forwarding handler below, and raise SIGSEGV or SIGRTMIN+1 respectively. The handler calls
  HotSpot's exported signal-handler entry, so HotSpot itself writes both reports.
  On this glibc/Linux host SIGRTMIN+1 is 35. All four JVMs returned 1 and were reaped.

```c
#define _GNU_SOURCE
#include <jni.h>
#include <signal.h>
#include <dlfcn.h>
#include <stdlib.h>
static void forward(int sig, siginfo_t *info, void *context) {
  int (*handler)(int, siginfo_t*, void*, int) = dlsym(RTLD_DEFAULT, "JVM_handle_linux_signal");
  if (!handler) _Exit(90);
  handler(sig, info, context, 1);
}
JNIEXPORT void JNICALL Java_FatalProducer_crash(JNIEnv *env, jclass cls, jint sig) {
  struct sigaction action = {0};
  action.sa_sigaction = forward;
  action.sa_flags = SA_SIGINFO;
  sigemptyset(&action.sa_mask);
  if (sigaction(sig, &action, 0)) _Exit(91);
  raise(sig);
}
```

The test requires the newest real report to displace an older crash JSON, and its
classification to reach the public summary without messages, locations, frames or IDs.
Multiword classifications lose spaces (InternalError, OutOfMemoryError); a real-time signal's
plus becomes an underscore (SIGRTMIN_1). This preserves the existing public identifier allowlist.
Windows execution is unavailable here; the pre-existing Windows EXCEPTION_ACCESS_VIOLATION
fixture remains as a preservation check.
