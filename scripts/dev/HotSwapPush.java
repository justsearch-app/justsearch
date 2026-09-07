///usr/bin/env java --add-modules jdk.jdi --source 25 "$0" "$@"; exit $?
//
// HotSwapPush — Pushes recompiled .class files to a running JVM via JDWP.
//
// Phase 0 hot-reload trigger for JustSearch dev workflow (tempdoc 305).
// Connects to the Worker's JDWP agent and redefines classes that changed
// since the last push. Uses a marker file to track timestamps.
//
// Usage:
//   java --add-modules jdk.jdi scripts/dev/HotSwapPush.java <port> <classesDir> \
//       [<identityEntry> [<expectedRepoRoot>]]
//
// Example:
//   java --add-modules jdk.jdi scripts/dev/HotSwapPush.java 5005 \
//       modules/worker-services/build/classes/java/main \
//       F:/repo/modules/worker-services/build/classes/java/main \
//       F:/repo
//
// Tempdoc 844 §4.2 R3 — target identity. Without <identityEntry> this tool attaches to
// "whatever listens on 127.0.0.1:<port>" and redefines classes there by NAME only, so a
// second agent's bytecode silently lands in the first agent's Worker (§5.6 case (c)).
// When <identityEntry> is given it must appear on the ATTACHED VM's own classpath, read
// back over JDI (PathSearchingVirtualMachine) — i.e. the VM itself confirms it was launched
// from the tree we are pushing from. The dev-runner records that entry in run.json, and at the
// time this was written WorkerSpawner put the same absolute path first on the Worker classpath
// (R4), so the two sides agreed by construction. Item A11 later deleted WorkerSpawner along with
// the Worker child process, so that second side no longer exists as described here; what (if
// anything) re-establishes classpath identity on the current, merged process is not asserted by
// this comment. Anything else is refused before a single class is redefined.
//
// (System properties would be the obvious identity channel, but JDI cannot read one without
// invoking System.getProperty on a thread suspended BY AN EVENT — a debugger-grade dance this
// tool has no reason to perform. The classpath entry carries the same value and is readable
// from the attached VM directly.)
//
// Tempdoc 844 F3 — <expectedRepoRoot> separates two causes the tool used to conflate. When the
// identity entry is absent from the attached VM's classpath, the VM may belong to ANOTHER tree
// (exit 5, the cross-tree case) or to THIS tree yet have been launched from a stale distribution
// built before the hot-reload classpath existed (exit 6). The live 2026-08-19 run hit the second
// and was told the first, with all 183 of the VM's classpath entries under the expected tree.
// Without <expectedRepoRoot> the two cannot be told apart, and the refusal says so instead of
// asserting a cause.
//
// Exit codes (the caller distinguishes "nothing to do" from "did nothing"):
//   0  at least one class was redefined
//   1  usage / attach / redefine failure
//   3  no changed class files since the last push — nothing was pushed
//   4  changed classes existed but NONE is loaded in the target VM — nothing was redefined
//   5  target identity could not be confirmed — refused to push (cross-tree / unknown)
//   6  the VM is from the expected tree but lacks the hot-reload classes dir on its classpath
//      (stale distribution) — refused to push
//
// Machine-readable stdout lines: CHANGED <n>, REDEFINED <n>, NOT_LOADED <n>,
// IDENTITY_OK <entry>, BASEDIR <dir>. REDEFINED is printed only AFTER redefineClasses returned
// successfully (844 F2: printing it beforehand made a failed push report classes as redefined).
//
// Prerequisites:
//   - Worker running with JUSTSEARCH_DEV_DEBUG_PORT=<port>
//   - Classes recompiled: ./gradlew :modules:worker-services:classes
//
// Limitations (standard HotSwap on Temurin):
//   - Only method body changes are supported
//   - Adding/removing methods, fields, or constructors is rejected
//   - Structural changes stay unsupported (tempdoc 844 R7): the caller reports
//     structuralChangeDetected rather than pretending they landed.

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class HotSwapPush {

    private static final int EXIT_FAILURE = 1;
    private static final int EXIT_NOTHING_CHANGED = 3;
    private static final int EXIT_NONE_LOADED = 4;
    private static final int EXIT_IDENTITY_REFUSED = 5;
    private static final int EXIT_CLASSPATH_ABSENT = 6;

    /** Outcome of the identity check: confirmed, or refused for one of two distinct reasons. */
    private enum Identity { CONFIRMED, REFUSED_CROSS_TREE, REFUSED_CLASSPATH_ABSENT }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                "Usage: java --add-modules jdk.jdi HotSwapPush.java <port> <classesDir>"
                + " [<identityEntry> [<expectedRepoRoot>]]");
            System.exit(EXIT_FAILURE);
        }

        int port = Integer.parseInt(args[0]);
        Path classesDir = Path.of(args[1]).toAbsolutePath().normalize();
        Path identityEntry = args.length >= 3 && !args[2].isBlank()
            ? Path.of(args[2]).toAbsolutePath().normalize()
            : null;
        Path expectedRepoRoot = args.length >= 4 && !args[3].isBlank()
            ? Path.of(args[3]).toAbsolutePath().normalize()
            : null;

        if (!Files.isDirectory(classesDir)) {
            System.err.println("Classes directory does not exist: " + classesDir);
            System.err.println("Run: ./gradlew :modules:worker-services:classes");
            System.exit(EXIT_FAILURE);
        }

        // Marker file lives outside the classes dir to survive Gradle clean builds.
        Path markerFile = classesDir.getParent().resolve(".hotswap-marker");

        // Determine cutoff time (last successful push)
        long cutoff = Files.exists(markerFile)
            ? Files.getLastModifiedTime(markerFile).toMillis()
            : 0;

        // Find .class files modified since last push
        List<Path> changed = new ArrayList<>();
        try (var stream = Files.walk(classesDir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                  .filter(p -> {
                      try {
                          return Files.getLastModifiedTime(p).toMillis() > cutoff;
                      } catch (IOException e) {
                          return false;
                      }
                  })
                  .forEach(changed::add);
        }

        System.out.printf("CHANGED %d%n", changed.size());
        if (changed.isEmpty()) {
            System.out.println("No changed classes since last push.");
            System.exit(EXIT_NOTHING_CHANGED);
        }

        System.out.printf("Found %d changed class file(s), connecting to 127.0.0.1:%d...%n",
            changed.size(), port);

        // Connect to JDWP agent
        AttachingConnector connector = Bootstrap.virtualMachineManager()
            .attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("SocketAttach connector not found"));

        Map<String, Connector.Argument> connArgs = connector.defaultArguments();
        connArgs.get("hostname").setValue("127.0.0.1");
        connArgs.get("port").setValue(String.valueOf(port));

        VirtualMachine vm;
        try {
            vm = connector.attach(connArgs);
        } catch (IOException e) {
            System.err.println("Failed to connect to JDWP agent on port " + port);
            System.err.println("Is the Worker running with JUSTSEARCH_DEV_DEBUG_PORT=" + port + "?");
            System.exit(EXIT_FAILURE);
            return; // unreachable, satisfies compiler
        }

        int exitCode = EXIT_FAILURE;
        try {
            Identity identity = confirmIdentity(vm, identityEntry, expectedRepoRoot, port);
            if (identity != Identity.CONFIRMED) {
                vm.dispose();
                System.exit(identity == Identity.REFUSED_CLASSPATH_ABSENT
                    ? EXIT_CLASSPATH_ABSENT
                    : EXIT_IDENTITY_REFUSED);
            }

            Map<ReferenceType, byte[]> redefinitions = new LinkedHashMap<>();
            List<String> notLoaded = new ArrayList<>();

            for (Path classFile : changed) {
                // Convert file path to class name: io/justsearch/Foo.class -> io.justsearch.Foo
                Path relative = classesDir.relativize(classFile);
                String className = relative.toString()
                    .replace(File.separatorChar, '.')
                    .replace('/', '.')
                    .replaceAll("\\.class$", "");

                List<ReferenceType> types = vm.classesByName(className);
                if (types.isEmpty()) {
                    notLoaded.add(className);
                    continue;
                }

                byte[] bytecode = Files.readAllBytes(classFile);
                redefinitions.put(types.get(0), bytecode);
            }

            System.out.printf("NOT_LOADED %d%n", notLoaded.size());

            if (redefinitions.isEmpty()) {
                System.out.println("REDEFINED 0");
                // Tempdoc 844 §5.6 #4: this used to exit 0 AND touch the marker, so the call
                // reported success, the next call saw "nothing changed", and no bytecode had
                // ever moved. It is a distinct outcome, not a success.
                System.out.printf("None of the %d changed class(es) are loaded in the target VM.%n",
                    changed.size());
                exitCode = EXIT_NONE_LOADED;
            } else {
                try {
                    vm.redefineClasses(redefinitions);
                    // Tempdoc 844 F2: REDEFINED is printed HERE, after the redefinition returned,
                    // not before the call. It used to be printed up front, so the live structural
                    // failure below reported "REDEFINED 3" while nothing had been replaced. A JVMTI
                    // redefinition is atomic (all classes or none), so the failure paths print 0.
                    System.out.printf("REDEFINED %d%n", redefinitions.size());
                    System.out.printf("Redefined %d class(es):%n", redefinitions.size());
                    for (ReferenceType type : redefinitions.keySet()) {
                        System.out.println("  " + type.name());
                    }
                    exitCode = 0;
                } catch (UnsupportedOperationException e) {
                    System.out.println("REDEFINED 0");
                    System.err.println("HotSwap not supported by target VM: " + e.getMessage());
                    System.err.println(
                        "If you added/removed methods or fields, standard HotSwap cannot apply them"
                        + " - restart the stack.");
                    System.exit(EXIT_FAILURE);
                } catch (Exception e) {
                    // Common case: structural change rejected by standard HotSwap
                    System.out.println("REDEFINED 0");
                    System.err.println("HotSwap failed: " + e.getMessage());
                    System.err.println(
                        "If you added/removed methods or fields, standard HotSwap cannot apply them"
                        + " - restart the stack.");
                    System.exit(EXIT_FAILURE);
                }
            }

            if (!notLoaded.isEmpty()) {
                System.out.printf("Skipped %d class(es) not loaded in target VM.%n", notLoaded.size());
            }
        } finally {
            vm.dispose();
        }

        // Update the marker ONLY when bytecode actually moved. Touching it after a no-op push
        // would hide the same classes from the next attempt (tempdoc 844 §5.6 #4).
        if (exitCode == 0) {
            Files.writeString(markerFile, String.valueOf(System.currentTimeMillis()));
        }
        System.exit(exitCode);
    }

    /**
     * Confirms the attached VM is the one the caller meant, by reading the VM's OWN classpath
     * back over JDI and requiring {@code identityEntry} to be on it. Returns CONFIRMED when the
     * entry is there - or when no identity token was supplied at all, which the caller must treat
     * as unverified. Otherwise it prints the refusal and reports WHICH refusal it is: the VM
     * belongs to another tree (REFUSED_CROSS_TREE), or it belongs to {@code expectedRepoRoot} but
     * carries no hot-reload classes dir (REFUSED_CLASSPATH_ABSENT - a stale distribution).
     * Both refuse the push; only the explanation and the remedy differ (tempdoc 844 F3).
     */
    private static Identity confirmIdentity(
            VirtualMachine vm, Path identityEntry, Path expectedRepoRoot, int port) {
        if (identityEntry == null) {
            System.err.println(
                "IDENTITY_UNVERIFIED no identity entry supplied - pushing to whatever answers on port "
                + port);
            return Identity.CONFIRMED;
        }
        if (!(vm instanceof PathSearchingVirtualMachine psvm)) {
            System.err.println(
                "IDENTITY_REFUSED the target VM does not expose its classpath over JDWP, so it cannot"
                + " be identified. Refusing to redefine classes in an unidentified VM on port " + port
                + ".");
            return Identity.REFUSED_CROSS_TREE;
        }
        List<String> classPath;
        try {
            classPath = psvm.classPath();
        } catch (Exception e) {
            System.err.println(
                "IDENTITY_REFUSED could not read the target VM's classpath (" + e + "). Refusing to"
                + " redefine classes in an unidentified VM on port " + port + ".");
            return Identity.REFUSED_CROSS_TREE;
        }
        for (String entry : classPath) {
            try {
                if (Path.of(entry).toAbsolutePath().normalize().equals(identityEntry)) {
                    System.out.printf("IDENTITY_OK %s%n", identityEntry);
                    try {
                        System.out.printf("BASEDIR %s%n", psvm.baseDirectory());
                    } catch (Exception ignored) {
                        // Best-effort context line only.
                    }
                    return Identity.CONFIRMED;
                }
            } catch (InvalidPathException ignored) {
                // A classpath entry that is not a usable path cannot be the one we want.
            }
        }

        // The entry is absent. WHY it is absent is a separate question, and this tool used to
        // assert the answer it had not checked (tempdoc 844 F3).
        long sameRoot = expectedRepoRoot == null
            ? 0
            : classPath.stream().filter(e -> isUnderRoot(e, expectedRepoRoot)).count();
        if (sameRoot > 0) {
            System.err.println(
                "IDENTITY_CLASSPATH_ABSENT the VM on port " + port + " WAS launched from the expected"
                + " tree (" + sameRoot + " of " + classPath.size() + " classpath entries are under "
                + expectedRepoRoot + "), but the hot-reload classes dir is NOT on its classpath:\n  "
                + identityEntry
                + "\nThat is a stale distribution - the launcher predates the hot-reload classpath, so"
                + " the process cannot see that directory at all. Refusing to push.\nRemedy: rebuild"
                + " the dist in that tree (gradlew.bat :modules:ui:installDist) and restart the"
                + " stack.");
            return Identity.REFUSED_CLASSPATH_ABSENT;
        }
        System.err.println(
            "IDENTITY_REFUSED the VM on port " + port + " was NOT launched from the tree this push"
            + " comes from"
            + (expectedRepoRoot == null
                ? " (no expected repo root was supplied, so a stale-distribution case cannot be ruled"
                  + " out here)"
                : " - none of its classpath entries are under " + expectedRepoRoot)
            + ". Expected this entry on its classpath:\n  " + identityEntry
            + "\nThe VM reports " + classPath.size() + " classpath entrie(s), first few:");
        classPath.stream().limit(5).forEach(e -> System.err.println("  " + e));
        System.err.println(
            "Refusing to push - this is the cross-tree injection tempdoc 844 section 5.6 case (c)"
            + " describes.");
        return Identity.REFUSED_CROSS_TREE;
    }

    /**
     * True when {@code entry} lies inside {@code root}. Entries under {@code root/.claude/worktrees}
     * are excluded: a worktree is a DIFFERENT tree that merely lives under the main checkout's path,
     * so counting its jars as "under main" would report a genuine cross-tree VM as same-tree.
     */
    private static boolean isUnderRoot(String entry, Path root) {
        try {
            Path p = Path.of(entry).toAbsolutePath().normalize();
            if (!p.startsWith(root)) {
                return false;
            }
            String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
            return !rel.startsWith(".claude/worktrees/");
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
