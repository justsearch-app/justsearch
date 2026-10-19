/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.app.api.runtime.child.ProcessTerminator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Real, short-lived Windows executable. No shell, launcher or Tesseract installation needed. */
final class NativeTesseractFixture {
  static Path write(Path directory, String text, boolean languages) throws IOException {
    if (io.justsearch.configuration.PlatformPaths.isLinux()) return writeLinux(directory, text, languages);
    Files.createDirectories(directory);
    Path executable = directory.resolve("tesseract.exe");
    String code = """
        using System;
        using System.IO;
        using System.Diagnostics;
        class Fixture {
          static int Main(string[] args) {
            string identity = Process.GetCurrentProcess().MainModule.FileName;
            File.AppendAllText(Path.Combine(Path.GetDirectoryName(identity), "launches.log"), String.Join(" ", args) + "\\n");
            if (args.Length > 0 && args[0] == "--version") { Console.WriteLine("tesseract fixture"); return 0; }
            if (args.Length > 0 && args[0] == "--list-langs") { Console.WriteLine("List of available languages (fixture):"); LANGUAGES return 0; }
            if (args.Length > 1) File.WriteAllText(args[1] + ".txt", TEXT + " " + identity);
            Console.WriteLine(identity);
            return 0;
          }
        }
        """.replace("LANGUAGES", languages ? "Console.WriteLine(\"eng\");" : "")
        .replace("TEXT", "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    Path source = directory.resolve("Fixture.cs");
    Files.writeString(source, code, StandardCharsets.UTF_8);
    Path compiler = Path.of(System.getenv("WINDIR"), "Microsoft.NET", "Framework64", "v4.0.30319", "csc.exe");
    Process process = new ProcessBuilder(compiler.toString(), "/nologo", "/out:" + executable, source.toString())
        .redirectErrorStream(true).redirectOutput(directory.resolve("compile.log").toFile()).start();
    var held = ProcessTerminator.forCurrentOs().open(process);
    String birth = held.creationFileTime().orElseThrow();
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("fixture compiler timeout " + process.pid() + "/" + birth);
      if (process.exitValue() != 0) throw new IOException(Files.readString(directory.resolve("compile.log"), StandardCharsets.UTF_8));
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    finally { if (process.isAlive()) held.terminate(1); held.close(); }
    return executable;
  }

  private static Path writeLinux(Path directory, String text, boolean languages) throws IOException {
    Files.createDirectories(directory);
    Path executable = directory.resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract"));
    String code = """
        #include <stdio.h>
        #include <string.h>
        #include <unistd.h>
        #include <limits.h>
        int main(int argc, char **argv) {
          char image[PATH_MAX], logpath[PATH_MAX], output[PATH_MAX];
          ssize_t n = readlink("/proc/self/exe", image, sizeof(image)-1);
          if (n < 0) return 2;
          image[n] = 0;
          strcpy(logpath, image);
          char *slash = strrchr(logpath, '/');
          if (!slash) return 2;
          *slash = 0;
          if (strlen(logpath) + 14 >= sizeof(logpath)) return 2;
          strcat(logpath, "/launches.log");
          FILE *log = fopen(logpath, "a");
          if (!log) return 2;
          for (int i=1; i<argc; i++) fprintf(log, "%s%s", i>1 ? " " : "", argv[i]);
          fprintf(log, "\\n"); fclose(log);
          if (argc > 1 && !strcmp(argv[1], "--version")) { puts("tesseract fixture"); return 0; }
          if (argc > 1 && !strcmp(argv[1], "--list-langs")) { puts("List of available languages (fixture):"); LANGUAGES return 0; }
          if (argc > 2) {
            if (snprintf(output, sizeof(output), "%s.txt", argv[2]) >= sizeof(output)) return 2;
            FILE *file = fopen(output, "w");
            if (!file) return 2;
            fprintf(file, "%s %s", TEXT, image); fclose(file);
          }
          puts(image); return 0;
        }
        """.replace("LANGUAGES", languages ? "puts(\"eng\");" : "")
        .replace("TEXT", "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    Path source = directory.resolve("fixture.c");
    Files.writeString(source, code, StandardCharsets.UTF_8);
    Process process = new ProcessBuilder("cc", "-o", executable.toString(), source.toString())
        .redirectErrorStream(true).redirectOutput(directory.resolve("compile.log").toFile()).start();
    var held = ProcessTerminator.forCurrentOs().open(process);
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("fixture compiler timeout");
      if (process.exitValue() != 0) throw new IOException(Files.readString(directory.resolve("compile.log")));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw new IOException(interrupted);
    } finally { if (process.isAlive()) held.terminate(1); held.close(); }
    return executable;
  }
}
