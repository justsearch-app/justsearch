/* SPDX-License-Identifier: Apache-2.0 */
// Linux real-Engine proof. Run after :modules:ui:installDist:
// JUSTSEARCH_ENGINE_INCARNATION=manifest-proof/1 java --add-modules jdk.jdi \
//   -cp 'modules/ui/build/install/ui/lib/*' \
//   scripts/supervisor-conformance/RuntimeManifestRecovery.java <boot-quit|boot-failure|children|handoff> <work>
// JDI breakpoints are external barriers: no timing cut or product fault hook.
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import io.justsearch.app.api.runtime.child.LinuxProcessIdentity;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RuntimeManifestRecovery {
  static final ObjectMapper JSON = new ObjectMapper();
  static Path repo = Path.of("").toAbsolutePath();
  static Path work;
  static Path data;
  static Path manifest;
  static String extractor;
  static String lastHealth = "";

  static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (!condition.getAsBoolean()) {
      require(System.nanoTime() < deadline, message);
      Thread.sleep(20); // poll the named observable, never choose a cut by elapsed time
    }
  }

  static JsonNode read(Path file) {
    try { return JSON.readTree(file.toFile()); }
    catch (Exception unavailable) { return JSON.createObjectNode(); }
  }

  static String quote(String value) {
    require(!value.contains("|"), "JDI fixture arguments cannot contain its quote delimiter");
    return '|' + value + '|';
  }

  static final class Engine implements AutoCloseable {
    final VirtualMachine vm;
    final Process process;
    final Path output;
    final Thread stdout;
    final Thread stderr;
    final String method;
    final long logOffset;
    boolean attached = true;

    Engine(String name, String type, String method, boolean brokenExtractor) throws Exception {
      this.method = method;
      Path engineLog = data.resolve("logs/engine.log");
      logOffset = Files.exists(engineLog) ? Files.size(engineLog) : 0;
      var connector = Bootstrap.virtualMachineManager().defaultConnector();
      var args = connector.defaultArguments();
      // The JDI launcher does not implement backslash escaping. Use a delimiter distinct from
      // the extraction command's own double quotes so that it stays a single JVM property.
      args.get("quote").setValue("|");
      args.get("main").setValue("io.justsearch.ui.HeadlessApp");
      String options = "-Xmx512m -cp " + quote(repo.resolve("modules/ui/build/install/ui/lib/*").toString())
          + " -Djustsearch.eval.mode=true -Djustsearch.prod=true -Djustsearch.api.port=0"
          + " -Djustsearch.ai.embed.enabled=false -Djustsearch.ner.enabled=false"
          + " -Djustsearch.splade.enabled=false -Djustsearch.rerank.enabled=false"
          + " -Djustsearch.rerank.chunks.enabled=false"
          + " " + quote("-Djava.io.tmpdir=" + work)
          + " " + quote("-Duser.home=" + work.resolve("home"))
          + " " + quote("-Djustsearch.data.dir=" + data)
          + " " + quote("-Djustsearch.index.base.path=" + work.resolve("index"))
          + " " + quote("-Djustsearch.extraction.sandbox.mode=" + (brokenExtractor ? "process" : "in_process"));
      if (brokenExtractor) options += " " + quote("-Djustsearch.extraction.sandbox.command=" + extractor);
      args.get("options").setValue(options);
      vm = connector.launch(args);
      process = vm.process();
      output = work.resolve(name + ".log");
      Files.createFile(output);
      stdout = Thread.ofPlatform().start(() -> copy(process.getInputStream()));
      stderr = Thread.ofPlatform().start(() -> copy(process.getErrorStream()));
      if (type != null) {
        var request = vm.eventRequestManager().createClassPrepareRequest();
        request.addClassFilter(type);
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        request.enable();
      }
    }

    void copy(java.io.InputStream stream) {
      try (stream; var destination = Files.newOutputStream(output, StandardOpenOption.APPEND)) {
        stream.transferTo(destination);
      } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    LocatableEvent barrier() throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
      for (;;) {
        require(process.isAlive(), "Engine exited before " + method + ": " + Files.readString(output));
        require(System.nanoTime() < deadline, "Engine did not reach " + method + ": " + output);
        EventSet events = vm.eventQueue().remove(1000);
        if (events == null) continue;
        for (var event : events) {
          if (event instanceof ClassPrepareEvent prepared) {
            for (var target : prepared.referenceType().methodsByName(method)) {
              var breakpoint = vm.eventRequestManager().createBreakpointRequest(target.location());
              breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
              breakpoint.enable();
            }
          }
          if (event instanceof BreakpointEvent stopped && stopped.location().method().name().equals(method)) return stopped;
        }
        events.resume();
      }
    }

    void resume() {
      // No later barrier is needed for this launch. Detach so queued debugger events cannot
      // suspend the Engine while the harness waits for API readiness or orderly shutdown.
      vm.dispose();
      attached = false;
    }

    String logText() throws java.io.IOException {
      Path engineLog = data.resolve("logs/engine.log");
      if (!Files.exists(engineLog)) return "";
      byte[] bytes = Files.readAllBytes(engineLog);
      int start = (int) Math.min(logOffset, bytes.length);
      return new String(bytes, start, bytes.length - start, java.nio.charset.StandardCharsets.UTF_8);
    }

    void exited(int code) throws Exception {
      require(process.waitFor(90, TimeUnit.SECONDS), "Engine did not exit: " + output);
      stdout.join(); stderr.join();
      require(process.exitValue() == code, "Engine exit=" + process.exitValue() + ": " + Files.readString(output));
    }

    void kill() throws Exception {
      process.destroyForcibly(); // this launch's retained Process handle, never a rediscovered PID
      require(process.waitFor(10, TimeUnit.SECONDS), "owned Engine did not die");
      stdout.join(); stderr.join();
    }

    public void close() throws Exception {
      if (!process.isAlive()) return;
      try {
        if (!attached) return;
        vm.suspend();
        StringBuilder threads = new StringBuilder();
        for (var thread : vm.allThreads()) {
          threads.append(thread.name()).append(" status=").append(thread.status()).append('\n');
          for (var frame : thread.frames()) threads.append("  ").append(frame.location()).append('\n');
        }
        Files.writeString(output.resolveSibling(output.getFileName() + ".threads"), threads);
      } finally { kill(); }
    }
  }

  static void request(String reason) throws Exception {
    Files.createDirectories(data.resolve("runtime"));
    Files.writeString(data.resolve("runtime/shutdown-request.v1.json"), JSON.writeValueAsString(
        Map.of("schemaVersion", 1, "reason", reason, "deadlineEpochMs", System.currentTimeMillis() + 30000,
            "incarnation", "manifest-proof/1", "requestNonce", "fixture-" + reason)));
  }

  static void boot(boolean fail) throws Exception {
    Process survivor = new ProcessBuilder("/bin/sh", "-c", "printf ready; read -r hold")
        .redirectErrorStream(true).start();
    try {
      require(survivor.getInputStream().readNBytes(5).length == 5, "fixture survivor ready");
      var identity = io.justsearch.app.api.runtime.ManagedChild.fromProcess(survivor,
          io.justsearch.app.api.runtime.ManagedChild.Kind.EXTRACTION, null, null, null, null);
      for (boolean withChild : List.of(false, true)) {
        for (String state : List.of("pending", "incomplete", "ready")) {
          Files.writeString(manifest, JSON.writeValueAsString(Map.of("schemaVersion", 2,
              "pid", 2147483647, "children", withChild ? List.of(identity) : List.of(),
              "shutdownHandoff", Map.of("state", state))));
          String evidence = Files.readString(manifest);
          if (!fail) request("quit");
          try (var engine = new Engine("boot-" + fail + "-" + state + "-" + withChild,
              fail ? "io.justsearch.app.api.runtime.child.DefaultChildControl" : null,
              "openForEngine", false)) {
            if (fail) {
              var at = engine.barrier();
              ClassType exception = (ClassType) engine.vm.classesByName("java.lang.RuntimeException").getFirst();
              var constructor = exception.methodsByName("<init>", "()V").getFirst();
              at.thread().stop(exception.newInstance(at.thread(), constructor, List.of(), ObjectReference.INVOKE_SINGLE_THREADED));
            }
            engine.resume();
            engine.exited(fail ? 1 : 0);
            require(Files.exists(manifest) && Files.readString(manifest).equals(evidence),
                "R4e: unpublished boot erased predecessor " + state + " evidence");
            require(engine.logText().contains("Previous Head session ended uncleanly")
                == (withChild || !state.equals("ready")),
                "R5: wrong crash classification for " + state + ", children=" + withChild);
            require(survivor.isAlive(), "unpublished boot leaves recorded survivors untouched");
            require(Files.getPosixFilePermissions(manifest).equals(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
                "R6: retained evidence stays owner-only");
          }
        }
      }
    } finally {
      survivor.destroyForcibly();
      require(survivor.waitFor(10, TimeUnit.SECONDS), "fixture survivor cleanup");
    }
  }

  static boolean hasChild(long pid) {
    for (var child : read(manifest).path("children")) if (child.path("pid").asLong() == pid) return true;
    return false;
  }

  static boolean apiReady() {
    int port = read(manifest).path("head").path("apiPort").asInt();
    if (port <= 0) return false;
    try (var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      var builder = java.net.http.HttpRequest.newBuilder(
          java.net.URI.create("http://127.0.0.1:" + port + "/api/health"))
          .timeout(Duration.ofSeconds(2));
      String token = read(manifest).path("head").path("sessionToken").asString();
      if (token != null && !token.isBlank()) builder.header("X-JustSearch-Session", token);
      int status = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
      String observed = "health HTTP " + status;
      if (!observed.equals(lastHealth)) System.out.println(observed);
      lastHealth = observed;
      return status == 200;
    } catch (Exception unavailable) {
      String observed = unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage();
      if (!observed.equals(lastHealth)) System.out.println(observed);
      lastHealth = observed;
      return false;
    }
  }

  static void children() throws Exception {
    Path helperScript = work.resolve("surviving-extractor.py");
    Path helperReady = work.resolve("extractor-ready");
    Path helperIdentityFile = work.resolve("extractor-identity.json");
    // Deliberately simulate a helper that escapes the Linux parent-death signal. The Engine's
    // existing survivor policy must handle it; production containment is not changed by this test.
    Files.writeString(helperScript, "import ctypes,json,os,pathlib,threading\n"
        + "identity={'kind':'linux-proc-v1','pid':os.getpid(),'bootId':pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip(),'startTicks':pathlib.Path('/proc/self/stat').read_text().rsplit(')',1)[1].split()[19]}\n"
        + "pathlib.Path(" + JSON.writeValueAsString(helperIdentityFile.toString()) + ").write_text(json.dumps(identity))\n"
        + "assert ctypes.CDLL(None).prctl(1,0)==0\n"
        + "pathlib.Path(" + JSON.writeValueAsString(helperReady.toString()) + ").write_text('ready')\n"
        + "threading.Event().wait()\n");
    extractor = "python3 " + JSON.writeValueAsString(helperScript.toString());
    LinuxProcessIdentity helperIdentity = null;
    Process unrelated = new ProcessBuilder("/bin/sh", "-c", "printf ready; read -r hold")
        .redirectErrorStream(true).start();
    try {
      require(unrelated.getInputStream().readNBytes(5).length == 5, "unrelated process ready");
      long helperPid;
      try (var first = new Engine("child-before-registration",
          "io.justsearch.app.api.runtime.child.DefaultChildControl", "register", true)) {
        first.barrier();
        await(() -> Files.exists(helperReady),
            "extractor did not clear its parent-death signal");
        helperIdentity = LinuxProcessIdentity.fromJson(read(helperIdentityFile)).orElseThrow();
        helperPid = helperIdentity.pid();
        require(!hasChild(helperPid), "cut must be before child registration");
        String liveEvidence = Files.readString(manifest);
        try (var losing = new Engine("competing-live-owner", null, null, false)) {
          losing.resume(); losing.exited(2);
          require(Files.readString(manifest).equals(liveEvidence),
              "a competing Engine must leave the live owner's manifest intact");
          require(ProcessHandle.of(helperPid).orElseThrow().isAlive(), "live owner's helper untouched");
        }
        Files.writeString(data.resolve("runtime/exit-ledger/children.v1.jsonl"),
            "\n".repeat(1024 * 1024), StandardOpenOption.APPEND);
        first.kill();
      }
      for (int boot = 2; boot <= 3; boot++) {
        try (var next = new Engine("child-recovery-" + boot,
            "io.justsearch.ui.runtime.ManagedChildReconciler", "reconcile", false)) {
          next.barrier();
          require(hasChild(helperPid), "R4/R4d: ledger-only survivor lost at ownership seed " + boot);
          require(ProcessHandle.of(helperPid).orElseThrow().isAlive(), "survivor is still alive");
          require(unrelated.isAlive(), "unrelated process untouched");
          if (boot == 2) {
            var publisherType = next.vm.classesByName("io.justsearch.ui.runtime.RuntimeManifestPublisher").getFirst();
            var publisher = publisherType.instances(1).getFirst();
            require(((com.sun.jdi.BooleanValue) publisher.getValue(
                publisherType.fieldByName("uncleanPreviousShutdown"))).value(),
                "R4: real successor must classify its predecessor as crashed");
            require(Files.readString(data.resolve("runtime/exit-ledger/children.v1.1.jsonl"))
                .contains("owner_died_alive"), "real Engine must record the survivor");
            Files.writeString(data.resolve("runtime/exit-ledger/children.v1.jsonl"),
                "\n".repeat(1024 * 1024), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
          } else {
            require(Files.exists(data.resolve("runtime/exit-ledger/children.v1.1.jsonl")),
                "the real Engine writer rotated the ledger");
            // Evict only the older generation containing the original start. Retention's byte
            // bound is unchanged; this isolates recovery from its already-evicted input cheaply.
            Files.delete(data.resolve("runtime/exit-ledger/children.v1.2.jsonl"));
          }
          next.kill(); // second cut: ownership was seeded, reconciliation has not finished
        }
        Files.delete(manifest); // isolate the ledger input after both real writer rotations
      }
      try (var finalEngine = new Engine("child-final-recovery", null, null, false)) {
        finalEngine.resume();
        await(() -> LinuxProcessIdentity.observe(helperPid).presence() == ProcessTerminator.IdentityPresence.GONE,
            "existing boot fallback did not end the proven survivor");
        await(RuntimeManifestRecovery::apiReady, "final Engine did not become API ready");
        require(unrelated.isAlive(), "unrelated process survived actual Engine recovery");
        request("quit");
        finalEngine.exited(0);
        require(!Files.exists(manifest), "R6: terminal graceful shutdown removes the manifest");
      }
    } finally {
      if (helperIdentity == null) helperIdentity = LinuxProcessIdentity.fromJson(read(helperIdentityFile)).orElse(null);
      if (helperIdentity != null) {
        var handle = ProcessHandle.of(helperIdentity.pid());
        if (handle.isPresent() && handle.get().isAlive()) {
          var held = ProcessTerminator.forCurrentOs().open(handle.get(), helperIdentity);
          try {
            held.terminate(ProcessTerminator.forCurrentOs().ourCode());
            require(held.awaitExit(Duration.ofSeconds(10)), "identity-checked extractor cleanup");
          } finally { held.close(); }
        }
      }
      unrelated.destroyForcibly(); unrelated.waitFor();
    }
  }

  static void handoff() throws Exception {
    try (var first = new Engine("planned-restart", null, null, false)) {
      first.resume();
      await(RuntimeManifestRecovery::apiReady, "real Engine did not become API ready");
      request("restart");
      first.exited(4);
    }
    require(read(manifest).path("shutdownHandoff").path("state").asString().equals("ready"),
        "real graceful restart must retain a ready handoff");
    String evidence = Files.readString(manifest);
    String token = read(manifest).path("head").path("sessionToken").asString();
    require(!token.isEmpty(), "positive control: predecessor published a credential");
    Path history = data.resolve("runtime/instances").resolve(read(manifest).path("instanceId").asString());
    for (String file : List.of("manifest.json", "manifest.log.ndjson")) {
      require(!Files.readString(history.resolve(file)).contains(token), "R6: history redacts the token");
    }
    require(Files.getPosixFilePermissions(manifest).equals(
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
        "R6: retained handoff token stays owner-only");
    request("quit");
    try (var successor = new Engine("planned-successor-boot-quit", null, null, false)) {
      successor.resume(); successor.exited(0);
      require(!successor.logText().contains("Previous Head session ended uncleanly"),
          "R5: completed graceful handoff was narrated as a crash");
      require(Files.exists(manifest) && Files.readString(manifest).equals(evidence),
          "R4e: boot quit must retain real predecessor handoff");
    }
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 2, "scenario and fresh work directory required");
    require("manifest-proof/1".equals(System.getenv("JUSTSEARCH_ENGINE_INCARNATION")),
        "set JUSTSEARCH_ENGINE_INCARNATION=manifest-proof/1");
    work = Path.of(args[1]).toAbsolutePath();
    require(!Files.exists(work), "fixture work directory must be fresh");
    Files.createDirectories(work.resolve("home"));
    data = work.resolve("data");
    Files.createDirectories(data.resolve("runtime"));
    manifest = data.resolve("runtime/manifest.json");
    switch (args[0]) {
      case "boot-quit" -> boot(false);
      case "boot-failure" -> boot(true);
      case "children" -> children();
      case "handoff" -> handoff();
      default -> throw new IllegalArgumentException(args[0]);
    }
    System.out.println("PASS real-Engine " + args[0] + " " + work);
  }
}
