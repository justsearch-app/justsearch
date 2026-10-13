/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import io.justsearch.configuration.SystemAccess;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The Engine's own Windows job: created with {@code CreateJobObjectW(NULL, NULL)}, limited to
 * exactly {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, and joined by the Engine process itself.
 *
 * <ul>
 *   <li><b>Exactly 0x2000.</b> No breakaway ({@code 0x800}, {@code 0x1000}), so no child can leave
 *       the job, including under Node's job, which allows silent breakaway. No {@code
 *       DIE_ON_UNHANDLED_EXCEPTION} ({@code 0x400}), so Windows Error Reporting still dumps a
 *       crashing Engine.
 *   <li><b>The handle is never closed.</b> Closing it would end the Engine with its children. It
 *       lives in a global-arena cell for the life of the process; process death closes it, and that
 *       is what ends the children. The handle is not inheritable (no security attributes), because
 *       an inherited copy in a child would keep the job open after the Engine dies. No API exposes
 *       it, and Engine code never calls {@code TerminateJobObject} on it.
 *   <li><b>Each chat server has its own nested job</b> ({@link #createChat}). What an exited chat
 *       root leaves running there is ended through that nested job's handle alone ({@link
 *       ChatProcess#endScope}): kill-on-close is set on it and the handle is closed. The Engine's
 *       job is never touched.
 *   <li><b>{@code GetLastError} is captured by the downcall</b> ({@code captureCallState}), so the
 *       JVM cannot overwrite it between the failing call and the read.
 * </ul>
 */
public final class WindowsEngineJob implements EngineContainment {
  /** {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, and the only limit this job sets. */
  static final int KILL_ON_JOB_CLOSE = 0x2000;
  /** {@code JobObjectExtendedLimitInformation}. */
  private static final int EXTENDED_LIMIT_INFORMATION_CLASS = 9;
  /** Win64 {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION}: basic limits 64, I/O counters 48, 4 SIZE_T. */
  private static final int EXTENDED_LIMIT_INFORMATION_SIZE = 144;
  /** {@code BasicLimitInformation.LimitFlags} follows two {@code LARGE_INTEGER}s. */
  private static final int LIMIT_FLAGS_OFFSET = 16;

  /** The job handle, held for the life of the process and never closed. */
  private static final MemorySegment JOB_HANDLE = Arena.global().allocate(ValueLayout.ADDRESS);

  private final Status status;
  private final Optional<String> reason;
  private WindowsJobWitness witness;
  private String witnessFailure;

  private WindowsEngineJob(Status status, Optional<String> reason) {
    this.status = status;
    this.reason = reason;
  }

  /** Creates the job and joins it. Returns UNAVAILABLE with {@code <call>:<error>} on failure. */
  static WindowsEngineJob install() {
    try {
      return createAndJoin();
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      return unavailable(failure.getClass().getSimpleName());
    }
  }

  private static WindowsEngineJob createAndJoin() throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    Linker.Option captureLastError = Linker.Option.captureCallState("GetLastError");
    StructLayout stateLayout = Linker.Option.captureStateLayout();
    VarHandle lastError =
        stateLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    MethodHandle create = bind(linker, kernel, "CreateJobObjectW",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        captureLastError);
    MethodHandle configure = bind(linker, kernel, "SetInformationJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        captureLastError);
    MethodHandle assign = bind(linker, kernel, "AssignProcessToJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        captureLastError);
    MethodHandle currentProcess = linker.downcallHandle(
        kernel.find("GetCurrentProcess").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
    MethodHandle close = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    try (Arena call = Arena.ofConfined()) {
      MemorySegment state = call.allocate(stateLayout);
      MemorySegment job =
          (MemorySegment) create.invokeExact(state, MemorySegment.NULL, MemorySegment.NULL);
      if (job.address() == 0) return failed("CreateJobObjectW", lastError, state);

      MemorySegment limits = call.allocate(EXTENDED_LIMIT_INFORMATION_SIZE, 8);
      limits.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, KILL_ON_JOB_CLOSE);
      int configured = (int) configure.invokeExact(state, job, EXTENDED_LIMIT_INFORMATION_CLASS,
          limits, EXTENDED_LIMIT_INFORMATION_SIZE);
      if (configured == 0) {
        WindowsEngineJob result = failed("SetInformationJobObject", lastError, state);
        int unused = (int) close.invokeExact(job); // not joined yet: closing ends nothing
        return result;
      }

      // Associate while inactive, before the Engine or any of its helpers can enter the job.
      WindowsJobWitness witness = null;
      String witnessFailure = null;
      try {
        witness = WindowsJobWitness.associate(job);
      } catch (VirtualMachineError fatal) {
        int _ = (int) close.invokeExact(job);
        throw fatal;
      } catch (Throwable failure) {
        // Accounting may degrade, but the kill-on-close backstop must still be installed.
        witnessFailure = failure.getClass().getSimpleName();
      }
      MemorySegment self = (MemorySegment) currentProcess.invokeExact();
      int joined = (int) assign.invokeExact(state, job, self);
      if (joined == 0) {
        WindowsEngineJob result = failed("AssignProcessToJobObject", lastError, state);
        int unused = (int) close.invokeExact(job); // assignment failed: we are not in it
        if (witness != null) witness.close();
        return result;
      }
      JOB_HANDLE.set(ValueLayout.ADDRESS, 0, job);
      WindowsEngineJob result = new WindowsEngineJob(Status.CONTAINED, Optional.empty());
      result.witness = witness;
      result.witnessFailure = witnessFailure;
      if (witness != null) witness.start();
      return result;
    }
  }

  private static WindowsEngineJob failed(String call, VarHandle lastError, MemorySegment state) {
    return unavailable(call + ":" + (int) lastError.get(state, 0L));
  }

  private static WindowsEngineJob unavailable(String reason) {
    return new WindowsEngineJob(Status.UNAVAILABLE, Optional.of(reason));
  }

  private static MethodHandle bind(Linker linker, SymbolLookup kernel, String name,
      FunctionDescriptor descriptor, Linker.Option option) {
    return linker.downcallHandle(kernel.find(name)
        .orElseThrow(() -> new IllegalStateException("missing kernel32!" + name)), descriptor, option);
  }

  @Override
  public Status status() {
    return status;
  }

  @Override
  public Optional<String> unavailableReason() {
    return reason;
  }

  @Override
  public void recordProcesses(io.justsearch.app.api.runtime.child.LedgerSink ledger,
      io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity owner) {
    io.justsearch.app.api.runtime.child.OwnedHelper.recordTo(ledger, owner);
    if (witness != null) witness.attach(ledger, owner);
    else if (witnessFailure != null) ledger.append(
        io.justsearch.app.api.runtime.ledger.LedgerRecordType.LEDGER_GAP,
        java.util.Map.of("reason", "WRITE_FAILED", "count", 1, "since", java.time.Instant.now(),
            "source", "job_witness_unavailable", "failure", witnessFailure));
  }

  @Override
  public void finishProcessRecording(java.time.Duration max) {
    if (witness != null) witness.finish(max);
  }

  /**
   * {@code IsProcessInJob(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, pid), engineJob)}. The
   * caller holds the child (a live {@link Process}), so its PID cannot be reused under the query.
   * The process handle is closed; the job handle is only read.
   */
  @Override
  public Optional<Boolean> contains(ProcessHandle child) {
    if (status != Status.CONTAINED || child == null) return Optional.empty();
    MemorySegment job = JOB_HANDLE.get(ValueLayout.ADDRESS, 0);
    if (job.address() == 0) return Optional.empty();
    try {
      return Membership.query(job, child.pid());
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      return Optional.empty();
    }
  }

  /** The downcalls {@link #contains} needs, bound on first use. */
  private static final class Membership {
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final MethodHandle OPEN;
    private static final MethodHandle IN_JOB;
    private static final MethodHandle CLOSE;

    static {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
      OPEN = linker.downcallHandle(kernel.find("OpenProcess").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT));
      IN_JOB = linker.downcallHandle(kernel.find("IsProcessInJob").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      CLOSE = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    static Optional<Boolean> query(MemorySegment job, long pid) throws Throwable {
      if (pid <= 0 || pid > 0xFFFF_FFFFL) return Optional.empty();
      MemorySegment process =
          (MemorySegment) OPEN.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) pid);
      if (process.address() == 0) return Optional.empty();
      try (Arena call = Arena.ofConfined()) {
        MemorySegment result = call.allocate(ValueLayout.JAVA_INT);
        int ok = (int) IN_JOB.invokeExact(process, job, result);
        if (ok == 0) return Optional.empty();
        return Optional.of(result.get(ValueLayout.JAVA_INT, 0) != 0);
      } finally {
        int unused = (int) CLOSE.invokeExact(process);
      }
    }
  }

  @Override
  public String toString() {
    return "WindowsEngineJob[" + status + reason.map(r -> ", " + r).orElse("") + "]";
  }

  /**
   * Creates a chat root suspended, joins its own nested job, and leaves resumption to the control
   * after it has recorded and registered the root. No instruction can execute outside that job.
   * The command line is the one {@code java.lang.ProcessImpl} builds for the same argv, and like
   * the JDK it names no application, so Windows itself runs a {@code .cmd} or {@code .bat} through
   * {@code cmd.exe}. The nested job sets no limit until {@link ChatProcess#endScope}; the Engine's
   * job still contains it.
   */
  public static ChatProcess createChat(ProcessBuilder builder) throws IOException {
    try {
      return ChatProcess.create(builder);
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      throw failure instanceof IOException io ? io : new IOException("Cannot contain chat launch", failure);
    }
  }

  /** A process with an exact, on-demand kernel membership query independent of its root's exit. */
  public static final class ChatProcess extends Process {
    private static final Cleaner CLEANER = Cleaner.create();
    /** NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT | UNICODE_ENVIRONMENT | SUSPENDED. */
    private static final int CREATION_FLAGS = 0x08080404;
    /**
     * How long a membership read retries while the job's active count disagrees with its listed
     * live members. Windows reports a process exited before its job stops counting it, longer
     * the more memory it frees.
     */
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final MemorySegment process;
    private MemorySegment primaryThread;
    private MemorySegment group;
    private boolean verifiedEmpty;
    /** The nested job was closed with kill-on-close set: Windows ends every member. */
    private boolean ended;
    private final ProcessHandle identity;
    private final InputStream stdout;
    private final InputStream stderr;
    private final OutputStream stdin;

    private ChatProcess(MemorySegment process, MemorySegment thread, MemorySegment group,
        long pid, InputStream stdout, InputStream stderr, OutputStream stdin) {
      this.process = process;
      this.primaryThread = thread;
      this.group = group;
      this.identity = ProcessHandle.of(pid).orElseThrow();
      // Creation is recorded while suspended; no PID lookup is ever used to terminate it.
      if (identity.info().startInstant().isEmpty()) {
        throw new IllegalStateException("Chat root creation time unavailable");
      }
      this.stdout = stdout;
      this.stderr = stderr;
      this.stdin = stdin;
      CLEANER.register(this, () -> Kernel.close(process));
    }

    private static ChatProcess create(ProcessBuilder builder) throws Throwable {
      if (ValueLayout.ADDRESS.byteSize() != 8) throw new IOException("Chat containment requires Win64");
      String line = launchCommandLine(builder);
      MemorySegment group = Kernel.pointer("CreateJobObjectW", MemorySegment.NULL, MemorySegment.NULL);
      List<MemorySegment> handles = new ArrayList<>();
      List<java.io.Closeable> streams = new ArrayList<>();
      MemorySegment process = MemorySegment.NULL;
      MemorySegment thread = MemorySegment.NULL;
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment security = arena.allocate(24, 8);
        security.set(ValueLayout.JAVA_INT, 0, 24);
        security.set(ValueLayout.JAVA_INT, 16, 1);
        MemorySegment[] input = Kernel.pipe(arena, security);
        handles.addAll(List.of(input));
        MemorySegment[] output;
        if (builder.redirectOutput().type() == ProcessBuilder.Redirect.Type.APPEND) {
          MemorySegment file = Kernel.pointer("CreateFileW",
              Kernel.wide(arena, builder.redirectOutput().file().getAbsolutePath()),
              0x100004, 7, security, 4, 0x80, MemorySegment.NULL);
          output = new MemorySegment[] {MemorySegment.NULL, file};
          handles.add(file);
        } else {
          output = Kernel.pipe(arena, security);
          handles.addAll(List.of(output));
        }
        MemorySegment[] error = builder.redirectErrorStream() ? output : Kernel.pipe(arena, security);
        if (error != output) handles.addAll(List.of(error));
        Kernel.integer("SetHandleInformation", input[1], 1, 0);
        if (output[0].address() != 0) Kernel.integer("SetHandleInformation", output[0], 1, 0);
        if (error[0].address() != 0) Kernel.integer("SetHandleInformation", error[0], 1, 0);

        // Only the three standard handles are inherited, even during concurrent helper launches.
        MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
        Kernel.call("InitializeProcThreadAttributeList", MemorySegment.NULL, 1, 0, size);
        MemorySegment attributes = arena.allocate(size.get(ValueLayout.JAVA_LONG, 0), 8);
        Kernel.integer("InitializeProcThreadAttributeList", attributes, 1, 0, size);
        MemorySegment result = arena.allocate(24, 8);
        try {
          List<MemorySegment> inherited = List.of(input[0], output[1], error[1]).stream().distinct().toList();
          MemorySegment allowed = arena.allocate(inherited.size() * 8L, 8);
          for (int i = 0; i < inherited.size(); i++) allowed.set(ValueLayout.ADDRESS, i * 8L, inherited.get(i));
          Kernel.integer("UpdateProcThreadAttribute", attributes, 0, 0x20002L, allowed,
              allowed.byteSize(), MemorySegment.NULL, MemorySegment.NULL);
          MemorySegment startup = arena.allocate(112, 8);
          startup.set(ValueLayout.JAVA_INT, 0, 112);
          startup.set(ValueLayout.JAVA_INT, 60, 0x100);
          startup.set(ValueLayout.ADDRESS, 80, input[0]);
          startup.set(ValueLayout.ADDRESS, 88, output[1]);
          startup.set(ValueLayout.ADDRESS, 96, error[1]);
          startup.set(ValueLayout.ADDRESS, 104, attributes);
          String environment = builder.environment().entrySet().stream()
              .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
              .map(e -> e.getKey() + "=" + e.getValue() + '\0').collect(java.util.stream.Collectors.joining()) + '\0';
          MemorySegment directory = builder.directory() == null ? MemorySegment.NULL
              : Kernel.wide(arena, builder.directory().getAbsolutePath());
          int created = (int) Kernel.call("CreateProcessW", MemorySegment.NULL,
              Kernel.wide(arena, line), MemorySegment.NULL, MemorySegment.NULL,
              1, CREATION_FLAGS, Kernel.wide(arena, environment), directory, startup, result);
          if (created == 0) {
            throw cannotRun(builder, "CreateProcess" + Kernel.describe(Kernel.lastError()));
          }
          process = result.get(ValueLayout.ADDRESS, 0);
          thread = result.get(ValueLayout.ADDRESS, 8);
        } finally {
          Kernel.call("DeleteProcThreadAttributeList", attributes);
        }
        long pid = Integer.toUnsignedLong(result.get(ValueLayout.JAVA_INT, 16));
        Kernel.integer("AssignProcessToJobObject", group, process);
        InputStream stdout = output[0].address() == 0 ? InputStream.nullInputStream() : new PipeInput(output[0]);
        streams.add(stdout); handles.remove(output[0]);
        InputStream stderr = error == output || error[0].address() == 0
            ? InputStream.nullInputStream() : new PipeInput(error[0]);
        streams.add(stderr);
        if (error != output) handles.remove(error[0]);
        OutputStream stdin = new PipeOutput(input[1]);
        streams.add(stdin); handles.remove(input[1]);
        Kernel.close(input[0]); handles.remove(input[0]);
        Kernel.close(output[1]); handles.remove(output[1]);
        if (error != output) { Kernel.close(error[1]); handles.remove(error[1]); }
        return new ChatProcess(process, thread, group, pid, stdout, stderr, stdin);
      } catch (Throwable failure) {
        if (process.address() != 0) {
          // This is our still-suspended creation handle, never a PID lookup or process tree.
          try { Kernel.integer("TerminateProcess", process, 1); }
          catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
          finally { Kernel.close(process); }
        }
        Kernel.close(thread);
        Kernel.close(group);
        for (MemorySegment handle : handles) Kernel.close(handle);
        for (java.io.Closeable stream : streams) {
          try { stream.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
        }
        throw failure;
      }
    }

    /**
     * The command line for {@code builder}, refused as {@code ProcessBuilder.start} refuses it: a NUL
     * character with its own message, an argument the quoting rules reject in the "Cannot run
     * program" shape.
     */
    private static String launchCommandLine(ProcessBuilder builder) throws IOException {
      for (String argument : builder.command()) {
        if (argument.indexOf('\0') >= 0) throw new IOException("invalid null character in command");
      }
      try {
        return commandLine(builder.command());
      } catch (IllegalArgumentException rejected) {
        IOException failure = cannotRun(builder, rejected.getMessage());
        failure.initCause(rejected);
        throw failure;
      }
    }

    /**
     * A launch failure worded as {@code ProcessBuilder.start} words it, so logs and status read the
     * same whichever way a child was started: {@code Cannot run program "<program>"}, the working
     * directory when one is set, then the cause.
     */
    private static IOException cannotRun(ProcessBuilder builder, String cause) {
      return new IOException("Cannot run program \"" + builder.command().getFirst() + "\""
          + (builder.directory() == null ? "" : " (in directory \"" + builder.directory() + "\")")
          + ": " + cause);
    }

    /**
     * The command line {@code java.lang.ProcessImpl} builds on Windows for {@code arguments}, by the
     * same rules: by default ({@code jdk.lang.Process.allowAmbiguousCommands} not {@code false}) an
     * argument is quoted only when it is empty or holds white space, its text is kept as it is, and
     * the backslashes before the closing quote are doubled; the executable path is normalised and
     * quoted only when it needs it. With the property {@code false} the executable is always quoted
     * and arguments are checked as the JDK checks them, {@code .cmd} and {@code .bat} strictest.
     */
    static String commandLine(List<String> arguments) {
      for (String argument : arguments) {
        if (argument.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL character in chat command");
      }
      // Read through the configuration funnel; a blank value is the default there and, as in the
      // JDK, anything but "false" keeps the legacy rules.
      boolean ambiguousAllowed = !"false".equalsIgnoreCase(
          SystemAccess.sysProp("jdk.lang.Process.allowAmbiguousCommands", "true"));
      if (ambiguousAllowed) {
        String executable = new java.io.File(arguments.getFirst()).getPath();
        if (quoted(Rules.LEGACY, executable)) executable = '"' + executable + '"';
        return join(Rules.LEGACY, executable, arguments);
      }
      List<String> argv = arguments;
      String executable = Rules.unquote(argv.getFirst());
      if (executable.indexOf('"') >= 0) {
        // As the JDK does, rejoin the argv and split it again at white space outside quotes.
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher token = TOKEN.matcher(String.join(" ", arguments) + " ");
        while (token.find()) tokens.add(token.group());
        argv = tokens;
        executable = Rules.unquote(argv.getFirst());
        if (executable.indexOf('"') >= 0) {
          throw new IllegalArgumentException(
              "Executable name has embedded quote, split the arguments: " + executable);
        }
      }
      executable = new java.io.File(executable).getPath();
      String name = new java.io.File(executable).getName().toUpperCase(java.util.Locale.ROOT);
      Rules rules = name.endsWith(".EXE") || name.indexOf('.') < 0 ? Rules.WIN32_SAFE : Rules.CMD_BAT;
      return join(rules, '"' + executable + '"', argv);
    }

    private static final java.util.regex.Pattern TOKEN =
        java.util.regex.Pattern.compile("[^\\s\"]+|\"[^\"]*\"");

    /** The JDK's argument checks, from the strictest (an implicit {@code cmd.exe} run) to legacy. */
    private enum Rules {
      CMD_BAT("\"<>&|^"), WIN32_SAFE("\"<>"), LEGACY("");

      final String special;

      Rules(String special) {
        this.special = special;
      }

      static String unquote(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
            ? text.substring(1, text.length() - 1) : text;
      }
    }

    private static String join(Rules rules, String executable, List<String> argv) {
      StringBuilder line = new StringBuilder(executable);
      for (String argument : argv.subList(1, argv.size())) {
        line.append(' ');
        if (quoted(rules, argument)) {
          line.append('"');
          if (rules == Rules.WIN32_SAFE) {
            for (int index = 0; index < argument.length(); index++) {
              char character = argument.charAt(index);
              if (character == '"') {
                line.append("\\".repeat(trailingBackslashes(rules, argument, index) + 1));
              }
              line.append(character);
            }
          } else {
            line.append(argument);
          }
          if (rules != Rules.CMD_BAT) {
            line.append("\\".repeat(trailingBackslashes(rules, argument, argument.length())));
          }
          line.append('"');
        } else if (rules == Rules.WIN32_SAFE && argument.length() > 2
            && argument.startsWith("\"") && argument.endsWith("\"")) {
          line.append(argument);
          line.insert(line.length() - 1,
              "\\".repeat(trailingBackslashes(rules, argument, argument.length() - 1)));
        } else {
          line.append(argument);
        }
      }
      return line.toString();
    }

    /** Whether the JDK quotes {@code argument}; throws where the JDK refuses it. */
    private static boolean quoted(Rules rules, String argument) {
      if (argument.isEmpty()) return true;
      String inner = Rules.unquote(argument);
      boolean alreadyQuoted = !inner.equals(argument);
      boolean innerQuote = inner.indexOf('"') >= 0;
      if (rules == Rules.CMD_BAT && innerQuote) {
        throw new IllegalArgumentException("Argument has embedded quote, use the explicit CMD.EXE call.");
      }
      if (rules == Rules.WIN32_SAFE && alreadyQuoted && innerQuote) {
        throw new IllegalArgumentException("Malformed argument has embedded quote: " + inner);
      }
      if (alreadyQuoted) return false;
      for (int index = 0; index < argument.length(); index++) {
        char character = argument.charAt(index);
        if (Character.isLetterOrDigit(character)) continue;
        if (Character.isSpaceChar(character) || Character.isWhitespace(character)
            || rules.special.indexOf(character) >= 0) {
          return true;
        }
      }
      return false;
    }

    /** The backslashes just before {@code end}; a batch file does not treat them as escapes. */
    private static int trailingBackslashes(Rules rules, CharSequence text, int end) {
      if (rules == Rules.CMD_BAT) return 0;
      int start = end;
      while (start > 0 && text.charAt(start - 1) == '\\') start--;
      return end - start;
    }

    /** Resumes only after the control has retained the job and root identity. */
    public synchronized void resume() throws IOException {
      try {
        int result = (int) Kernel.call("ResumeThread", primaryThread);
        if (result == -1) throw new IOException("ResumeThread failed");
        Kernel.close(primaryThread);
        primaryThread = MemorySegment.NULL;
      } catch (Throwable failure) {
        throw failure instanceof IOException io ? io : new IOException(failure);
      }
    }

    /** Reads job membership afresh. A failed query throws only for this attempt, never latches. */
    public synchronized List<ProcessHandle> scopeDescendants() {
      boolean interrupted = false;
      try (Arena arena = Arena.ofConfined()) {
        if (group.address() == 0) {
          if (verifiedEmpty || ended) return List.of();
          throw new IOException("Chat membership handle closed before empty scope was verified");
        }
        int capacity = 16;
        long settleStarted = System.nanoTime();
        while (capacity <= 1_048_576) {
          MemorySegment ids = arena.allocate(8L + 8L * capacity, 8);
          int success = (int) Kernel.call("QueryInformationJobObject", group, 3, ids,
              (int) ids.byteSize(), MemorySegment.NULL);
          int assigned = ids.get(ValueLayout.JAVA_INT, 0);
          int returned = ids.get(ValueLayout.JAVA_INT, 4);
          if (assigned > capacity || returned < assigned) { capacity = Math.max(capacity * 2, assigned); continue; }
          if (success == 0) throw new IOException("Job membership unavailable");
          List<ProcessHandle> descendants = new ArrayList<>();
          for (int i = 0; i < returned; i++) {
            long pid = ids.get(ValueLayout.JAVA_LONG, 8L + i * 8L);
            if (pid == identity.pid()) continue;
            ProcessHandle member = ProcessHandle.of(pid).orElse(null);
            if (member == null || !member.isAlive()) continue;
            if (member.info().startInstant().isEmpty()) throw new IOException("Job member identity unavailable");
            Optional<Boolean> inScope = Membership.query(group, pid);
            // A member that exited between the listing and this query is not a survivor; the
            // active-count comparison below decides whether the listing is complete.
            if (inScope.isEmpty() && member.isAlive()) throw new IOException("Job member unreadable: " + pid);
            if (inScope.orElse(false) && member.isAlive()) descendants.add(member);
          }
          MemorySegment accounting = arena.allocate(48, 8);
          Kernel.integer("QueryInformationJobObject", group, 1, accounting, 48, MemorySegment.NULL);
          int active = accounting.get(ValueLayout.JAVA_INT, 40);
          int represented = descendants.size() + (isAlive() ? 1 : 0);
          if (active == represented) {
            if (active == 0) verifiedEmpty = true;
            return List.copyOf(descendants);
          }
          // A member exited or forked during mapping. Never turn that race into an empty scope.
          if (System.nanoTime() - settleStarted >= SETTLE_NANOS) {
            throw new IOException("Job membership changed during verification (active=" + active
                + ", represented=" + represented + ")");
          }
          // Windows publishes a process exit before its job stops counting it. Retry this
          // on-demand verification, bounded in time; there is no background polling.
          try { TimeUnit.MILLISECONDS.sleep(5); }
          catch (InterruptedException interrupt) { interrupted = true; }
        }
        throw new IOException("Job membership list exceeds capacity");
      } catch (VirtualMachineError fatal) {
        throw fatal;
      } catch (Throwable failure) {
        throw new IllegalStateException("chat descendants unverified: job membership query unavailable", failure);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }

    /**
     * Ends every process still in this root's own job: kill-on-close is set on the nested job and
     * its only handle, which this process holds, is closed, so Windows terminates each member. The
     * job is the identity; no PID, image name or parent is used. The control calls this only after
     * the root has exited and each member has been recorded. Membership reads empty afterwards.
     */
    public synchronized void endScope() throws IOException {
      if (group.address() == 0) {
        if (verifiedEmpty || ended) return;
        throw new IOException("Chat membership handle closed before its scope was ended");
      }
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment limits = arena.allocate(EXTENDED_LIMIT_INFORMATION_SIZE, 8);
        limits.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, KILL_ON_JOB_CLOSE);
        Kernel.integer("SetInformationJobObject", group, EXTENDED_LIMIT_INFORMATION_CLASS, limits,
            EXTENDED_LIMIT_INFORMATION_SIZE);
        Kernel.integer("CloseHandle", group);
        group = MemorySegment.NULL;
        ended = true;
      } catch (VirtualMachineError fatal) {
        throw fatal;
      } catch (Throwable failure) {
        throw failure instanceof IOException io ? io : new IOException("Cannot end chat scope", failure);
      }
    }

    /** Closes the query handle without terminating members; the Engine's parent job owns that. */
    public synchronized void releaseScope() {
      Kernel.close(primaryThread);
      primaryThread = MemorySegment.NULL;
      Kernel.close(group);
      group = MemorySegment.NULL;
    }

    @Override public OutputStream getOutputStream() { return stdin; }
    @Override public InputStream getInputStream() { return stdout; }
    @Override public InputStream getErrorStream() { return stderr; }
    @Override public long pid() { return identity.pid(); }
    @Override public ProcessHandle toHandle() { return identity; }
    @Override public boolean isAlive() { return !waitNative(0); }
    @Override public int exitValue() {
      if (isAlive()) throw new IllegalThreadStateException("Chat process is alive");
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment code = arena.allocate(ValueLayout.JAVA_INT);
        Kernel.integer("GetExitCodeProcess", process, code);
        return code.get(ValueLayout.JAVA_INT, 0);
      } catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    @Override public int waitFor() throws InterruptedException {
      while (!waitNative(1000)) { if (Thread.interrupted()) throw new InterruptedException(); }
      return exitValue();
    }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (Thread.interrupted()) throw new InterruptedException();
      return waitNative((int) Math.min(0x7FFF_FFFFL, Math.max(0, unit.toMillis(timeout))));
    }
    private boolean waitNative(int milliseconds) {
      try {
        int result = (int) Kernel.call("WaitForSingleObject", process, milliseconds);
        if (result == 0) return true;
        if (result == 258) return false;
        throw new IllegalStateException("Process wait failed");
      } catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    @Override public void destroy() { destroyForcibly(); }
    @Override public Process destroyForcibly() {
      try { if (isAlive()) Kernel.integer("TerminateProcess", process, 1); }
      catch (Throwable failure) { throw new IllegalStateException(failure); }
      return this;
    }
  }

  private static final class PipeInput extends InputStream {
    private final NativeHandle handle;
    private final Cleaner.Cleanable release;
    PipeInput(MemorySegment raw) {
      handle = new NativeHandle(raw);
      release = ChatProcess.CLEANER.register(this, handle);
    }
    @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) return 0;
      MemorySegment raw = handle.value.get();
      if (raw.address() == 0) return -1;
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment buffer = arena.allocate(length);
        MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
        int success = (int) Kernel.call("ReadFile", raw, buffer, length, count, MemorySegment.NULL);
        if (success == 0) {
          if (Kernel.LAST_ERROR.get() == 109 || Kernel.LAST_ERROR.get() == 38) return -1;
          throw new IOException("ReadFile failed: " + Kernel.LAST_ERROR.get());
        }
        int read = count.get(ValueLayout.JAVA_INT, 0);
        if (read == 0) return -1;
        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, bytes, offset, read);
        return read;
      } catch (Throwable failure) { throw new IOException(failure); }
      finally { java.lang.ref.Reference.reachabilityFence(this); }
    }
    @Override public void close() { release.clean(); }
  }

  private static final class PipeOutput extends OutputStream {
    private final NativeHandle handle;
    private final Cleaner.Cleanable release;
    PipeOutput(MemorySegment raw) {
      handle = new NativeHandle(raw);
      release = ChatProcess.CLEANER.register(this, handle);
    }
    @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
      MemorySegment raw = handle.value.get();
      if (raw.address() == 0) throw new IOException("stdin closed");
      if (length == 0) return;
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE, java.util.Arrays.copyOfRange(bytes, offset, offset + length));
        MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
        Kernel.integer("WriteFile", raw, data, length, count, MemorySegment.NULL);
        if (count.get(ValueLayout.JAVA_INT, 0) != length) throw new IOException("Incomplete pipe write");
      } catch (Throwable failure) { throw failure instanceof IOException io ? io : new IOException(failure); }
      finally { java.lang.ref.Reference.reachabilityFence(this); }
    }
    @Override public void close() { release.clean(); }
  }

  /** A forgotten pipe is released by its stream's cleaner; explicit close stays idempotent. */
  private static final class NativeHandle implements Runnable {
    private final java.util.concurrent.atomic.AtomicReference<MemorySegment> value;
    NativeHandle(MemorySegment handle) {
      value = new java.util.concurrent.atomic.AtomicReference<>(handle);
    }
    @Override public void run() { Kernel.close(value.getAndSet(MemorySegment.NULL)); }
  }

  /** Reuses the Engine's FFM/kernel32 binding and captured-error convention for child jobs. */
  private static final class Kernel {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final StructLayout STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERROR = STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    private static final ThreadLocal<Integer> LAST_ERROR = ThreadLocal.withInitial(() -> 0);
    private static final Map<String, MethodHandle> FUNCTIONS = new ConcurrentHashMap<>();
    private static final ValueLayout A = ValueLayout.ADDRESS;
    private static final ValueLayout I = ValueLayout.JAVA_INT;
    private static final ValueLayout L = ValueLayout.JAVA_LONG;
    private static final Map<String, FunctionDescriptor> DESCRIPTORS = Map.ofEntries(
        Map.entry("CreateJobObjectW", FunctionDescriptor.of(A, A, A)),
        Map.entry("CreatePipe", FunctionDescriptor.of(I, A, A, A, I)),
        Map.entry("CreateFileW", FunctionDescriptor.of(A, A, I, I, A, I, I, A)),
        Map.entry("SetHandleInformation", FunctionDescriptor.of(I, A, I, I)),
        Map.entry("InitializeProcThreadAttributeList", FunctionDescriptor.of(I, A, I, I, A)),
        Map.entry("UpdateProcThreadAttribute", FunctionDescriptor.of(I, A, I, L, A, L, A, A)),
        Map.entry("DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(A)),
        Map.entry("CreateProcessW", FunctionDescriptor.of(I, A, A, A, A, I, I, A, A, A, A)),
        Map.entry("AssignProcessToJobObject", FunctionDescriptor.of(I, A, A)),
        Map.entry("SetInformationJobObject", FunctionDescriptor.of(I, A, I, A, I)),
        Map.entry("ResumeThread", FunctionDescriptor.of(I, A)),
        Map.entry("QueryInformationJobObject", FunctionDescriptor.of(I, A, I, A, I, A)),
        Map.entry("WaitForSingleObject", FunctionDescriptor.of(I, A, I)),
        Map.entry("GetExitCodeProcess", FunctionDescriptor.of(I, A, A)),
        Map.entry("TerminateProcess", FunctionDescriptor.of(I, A, I)),
        Map.entry("ReadFile", FunctionDescriptor.of(I, A, A, I, A, A)),
        Map.entry("WriteFile", FunctionDescriptor.of(I, A, A, I, A, A)),
        Map.entry("CloseHandle", FunctionDescriptor.of(I, A)),
        Map.entry("FormatMessageW", FunctionDescriptor.of(I, I, A, I, I, A, I, A)));
    /** FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, as the JDK asks. */
    private static final int SYSTEM_MESSAGE = 0x1200;
    /** The JDK's buffer for the system text, in UTF-16 units. */
    private static final int MESSAGE_UNITS = 256;
    static Object call(String name, Object... arguments) throws Throwable {
      MethodHandle function = FUNCTIONS.computeIfAbsent(name, n -> bind(LINKER, LIBRARY, n,
          DESCRIPTORS.get(n), Linker.Option.captureCallState("GetLastError")));
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment state = arena.allocate(STATE);
        List<Object> parameters = new ArrayList<>();
        parameters.add(state);
        parameters.addAll(List.of(arguments));
        Object result = function.invokeWithArguments(parameters);
        LAST_ERROR.set((int) ERROR.get(state, 0L));
        return result;
      }
    }
    static int lastError() {
      return LAST_ERROR.get();
    }
    /**
     * The error as the JDK's Windows launcher reports it after the function name:
     * {@code " error=<code>, <system text>"}, the text without its final line break and full stop,
     * or {@code " failed, error=<code>"} when Windows has no text for it.
     */
    static String describe(int code) {
      String text = "";
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment buffer = arena.allocate(MESSAGE_UNITS * 2L, 2);
        int units = (int) call("FormatMessageW", SYSTEM_MESSAGE, MemorySegment.NULL, code, 0,
            buffer, MESSAGE_UNITS, MemorySegment.NULL);
        if (units > 0) {
          char[] chars = new char[units];
          MemorySegment.copy(buffer, ValueLayout.JAVA_CHAR, 0, chars, 0, units);
          text = new String(chars);
          if (units > 3) {
            if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
            if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
          }
        }
      } catch (VirtualMachineError fatal) {
        throw fatal;
      } catch (Throwable unavailable) {
        text = "";
      }
      return text.isEmpty() ? " failed, error=" + code : " error=" + code + ", " + text;
    }
    static int integer(String name, Object... arguments) throws Throwable {
      int result = (int) call(name, arguments);
      if (result == 0) throw new IOException(name + " failed: " + LAST_ERROR.get());
      return result;
    }
    static MemorySegment pointer(String name, Object... arguments) throws Throwable {
      MemorySegment result = (MemorySegment) call(name, arguments);
      if (result.address() == 0 || result.address() == -1) throw new IOException(name + " failed: " + LAST_ERROR.get());
      return result;
    }
    static MemorySegment wide(Arena arena, String value) {
      byte[] bytes = (value + '\0').getBytes(StandardCharsets.UTF_16LE);
      MemorySegment string = arena.allocate(bytes.length, 2);
      MemorySegment.copy(bytes, 0, string, ValueLayout.JAVA_BYTE, 0, bytes.length);
      return string;
    }
    static MemorySegment[] pipe(Arena arena, MemorySegment security) throws Throwable {
      MemorySegment read = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment write = arena.allocate(ValueLayout.ADDRESS);
      integer("CreatePipe", read, write, security, 0);
      return new MemorySegment[] {read.get(ValueLayout.ADDRESS, 0), write.get(ValueLayout.ADDRESS, 0)};
    }
    static void close(MemorySegment handle) {
      if (handle.address() == 0) return;
      try { call("CloseHandle", handle); } catch (Throwable ignored) { /* best effort release */ }
    }
  }
}
