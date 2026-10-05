package conventions

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * Fingerprint of the model assets that asset-gated tests discover at run time (tempdoc 965, J3).
 *
 * `ModelDirTestResolver` walks up to eight parent directories from a test's `user.dir` looking for
 * `models/...`, then falls back to `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`. Those files are not task
 * inputs, so a cached green from a run where the models were absent (the tests skipped) was replayed
 * after the models appeared or changed. This value makes them inputs without hashing gigabytes.
 *
 * Every `models/` directory on the same walk counts, and so does its mere presence
 * (`findRepoRootByMarker` matches an empty one), plus the override directory when set. Each file
 * contributes its relative path, size and the content of its first and last [SAMPLE_BYTES]; small
 * files are hashed whole. Modification times are left out so that two checkouts with the same files
 * share cache entries. Session caches that ONNX Runtime writes beside the models (`*.optimized`,
 * `*.opt-meta`) are left out because running the tests creates them. The value is computed when the
 * task's inputs are fingerprinted, so it is re-read on every build, including configuration-cache
 * hits.
 *
 * Trade-off: a same-size edit confined to the middle of a file larger than twice [SAMPLE_BYTES] is
 * not seen. Model files are replaced whole, never patched in place.
 */
abstract class TestModelAssetsFingerprint : ValueSource<String, TestModelAssetsFingerprint.Params> {
  interface Params : ValueSourceParameters {
    /** The directory the walk starts from: the test task's working directory (`user.dir`). */
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
      if (probe.exists()) {
        digest.update("level$level:present\n".toByteArray())
        if (probe.isDirectory) hashTree(probe, digest)
      }
      candidate = dir.parentFile
    }
    val override = parameters.overrideDir.getOrElse("")
    if (override.isNotBlank() && File(override).isDirectory) {
      digest.update("override:\n".toByteArray())
      hashTree(File(override), digest)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun hashTree(root: File, digest: MessageDigest) {
    root.walkTopDown()
        .maxDepth(MAX_TREE_DEPTH)
        .filter { it.isFile && GENERATED_SUFFIXES.none { suffix -> it.name.endsWith(suffix) } }
        .map { it.relativeTo(root).invariantSeparatorsPath to it }
        .sortedBy { it.first }
        .forEach { (rel, file) ->
          val length = file.length()
          digest.update("$rel|$length\n".toByteArray())
          try {
            sample(file, length, digest)
          } catch (_: IOException) {
            // Locked by a writer, deleted or shrunk mid-walk: never match an earlier run, so the
            // tests rerun instead of the build failing on a file the tests may not even read.
            digest.update("unreadable:${UUID.randomUUID()}\n".toByteArray())
          }
        }
  }

  private fun sample(file: File, length: Long, digest: MessageDigest) {
    RandomAccessFile(file, "r").use { raf ->
      val buffer = ByteArray(SAMPLE_BYTES)
      if (length <= 2L * SAMPLE_BYTES) {
        var read = raf.read(buffer)
        while (read > 0) {
          digest.update(buffer, 0, read)
          read = raf.read(buffer)
        }
        return
      }
      raf.readFully(buffer)
      digest.update(buffer)
      raf.seek(length - SAMPLE_BYTES)
      raf.readFully(buffer)
      digest.update(buffer)
    }
  }

  private companion object {
    /** Same bound as `ModelDirTestResolver.MAX_WALK_DEPTH`. */
    const val MAX_WALK_DEPTH = 8

    const val SAMPLE_BYTES = 64 * 1024

    /** Bounds the walk if a junction or symlink under models/ forms a loop. */
    const val MAX_TREE_DEPTH = 16

    /** `OnnxSessionCache` suffixes; the CUDA variants end the same way. */
    val GENERATED_SUFFIXES = listOf(".optimized", ".opt-meta")
  }
}
