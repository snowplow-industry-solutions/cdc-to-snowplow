/*
 * Copyright 2026 Snowplow Analytics Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowplowanalytics.cdc.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/** A file to write, addressed by its path relative to the output root. */
data class ScaffoldFile(val relativePath: String, val content: String)

/** Thrown for output-directory state violations and write failures (after rollback). */
class ScaffoldOutputError(message: String, cause: Throwable? = null) : Exception(message, cause)

object OutputWriter {
    /**
     * Read-only validation of the output directory state: throws [ScaffoldOutputError] if the path
     * is a file, a non-writable dir, or a non-empty dir. Does NOT create the directory. Safe to call
     * before any expensive work (e.g. a DB connection) so scaffold fails fast on a doomed --output.
     */
    fun validateState(root: Path) {
        if (!root.exists()) return
        if (root.isRegularFile()) throw ScaffoldOutputError("output path $root exists and is not a directory")
        if (!root.isDirectory()) throw ScaffoldOutputError("output path $root is not a directory")
        if (!Files.isWritable(root)) throw ScaffoldOutputError("output directory $root is not writable")
        val nonEmpty = Files.list(root).use { it.findAny().isPresent }
        if (nonEmpty) throw ScaffoldOutputError(
            "Output directory $root is not empty.\n" +
                "Scaffold never overwrites existing files. Re-run with a fresh --output path,\n" +
                "then diff the result against your committed files manually.",
        )
    }

    /**
     * Validates the output dir state, then writes all [files]. On any write failure, rolls back:
     * removes everything written; if this call created the root, removes it too. Best-effort
     * rollback — a cleanup failure is appended to the original error, never hides it.
     */
    fun write(outputRoot: Path, files: List<ScaffoldFile>) {
        val createdRoot = validateAndPrepareRoot(outputRoot)
        val written = mutableListOf<Path>()
        try {
            for (f in files) {
                val target = outputRoot.resolve(f.relativePath)
                target.parent?.createDirectories()
                target.writeText(f.content)
                written.add(target)
            }
        } catch (e: Exception) {
            val rollbackErr = rollback(outputRoot, written, createdRoot)
            val base = "failed writing scaffold output: ${e.message}"
            throw ScaffoldOutputError(if (rollbackErr == null) base else "$base; additionally, $rollbackErr", e)
        }
    }

    /** @return true if this call created the output root (relevant to rollback). */
    private fun validateAndPrepareRoot(root: Path): Boolean {
        validateState(root)
        if (!root.exists()) { root.createDirectories(); return true }
        return false
    }

    /** @return null on clean rollback, else a description of what could not be removed. */
    private fun rollback(root: Path, written: List<Path>, createdRoot: Boolean): String? {
        return try {
            if (createdRoot) {
                root.toFile().deleteRecursively()
            } else {
                written.sortedByDescending { it.toString().length }.forEach { Files.deleteIfExists(it) }
                Files.walk(root).use { stream ->
                    stream.sorted(Comparator.reverseOrder())
                        .filter { it != root && it.isDirectory() }
                        .forEach { dir -> if (Files.list(dir).use { !it.findAny().isPresent }) Files.deleteIfExists(dir) }
                }
            }
            null
        } catch (e: Exception) {
            "rollback failed to clean $root; manual cleanup needed (${e.message})"
        }
    }
}
