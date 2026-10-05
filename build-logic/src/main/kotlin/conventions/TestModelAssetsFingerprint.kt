package conventions

import java.io.File
import java.security.MessageDigest
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * Fingerprint of the model assets that asset-gated tests discover at run time (tempdoc 965, J3).
 *
 * `ModelDirTestResolver` walks up to eight parent directories from a test's `user.dir` looking for
 * `models/...`, then falls back to `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`. Those files are not task
 * inputs, so a cached green from a run where the models were absent (the tests skipped) was replayed
 * after the models appeared or changed. This value makes them inputs without hashing gigabytes: it
 * lists each file's relative path, size and modification time under every `models/` directory on
 * the same walk, plus the override directory when set. It is computed when the task's
 * inputs are fingerprinted, so it is re-read on every build, including configuration-cache hits.
 */
abstract class TestModelAssetsFingerprint : ValueSource<String, TestModelAssetsFingerprint.Params> {
  interface Params : ValueSourceParameters {
    /** The project directory the walk starts from (a test's `user.dir`). */
    val startDir: Property<String>

    /** The `JUSTSEARCH_EMBED_ONNX_MODEL_PATH` override, or empty. */
    val overrideDir: Property<String>
  }

  override fun obtain(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // The resolver keeps walking when a nearer models/ lacks the required files (a worktree's own
    // models/ holds only small tracked files; the assets sit in the main checkout further up), so
    // every models/ directory on the walk is part of the fingerprint.
    var candidate: File? = File(parameters.startDir.get())
    for (level in 0 until MAX_WALK_DEPTH) {
      val dir = candidate ?: break
      val probe = File(dir, "models")
      digest.update("level$level:".toByteArray())
      if (probe.isDirectory) stat(probe, digest)
      candidate = dir.parentFile
    }
    val override = parameters.overrideDir.getOrElse("")
    digest.update("override:".toByteArray())
    if (override.isNotBlank() && File(override).isDirectory) stat(File(override), digest)
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun stat(root: File, digest: MessageDigest) {
    root.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath to it }
        .sortedBy { it.first }
        .forEach { (rel, file) -> digest.update("$rel|${file.length()}|${file.lastModified()}\n".toByteArray()) }
  }

  private companion object {
    /** Same bound as `ModelDirTestResolver.MAX_WALK_DEPTH`. */
    const val MAX_WALK_DEPTH = 8
  }
}
