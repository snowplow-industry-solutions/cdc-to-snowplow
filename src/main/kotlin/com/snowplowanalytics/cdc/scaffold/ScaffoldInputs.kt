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

import com.snowplowanalytics.cdc.config.TableKey
import java.net.URI

/** Thrown for any malformed scaffold CLI input; carries a human-readable, named message. */
class ScaffoldInputError(message: String) : Exception(message)

/** Non-secret connection coordinates lifted from --connection; the password is never carried here. */
data class ScaffoldConnectionCoords(
    val hostname: String,
    val port: Int,
    val database: String,
    val username: String,
)

/** Parsed --connection: the JDBC URL to dial, the non-secret coords for the generated config, and the password. */
data class ScaffoldConnection(
    val jdbcUrl: String,
    val coords: ScaffoldConnectionCoords,
    val password: String,
    val passwordFromUri: Boolean,
)

object ScaffoldInputs {

    fun parseTables(raw: String): List<TableKey> {
        val seen = mutableSetOf<TableKey>()
        val out = mutableListOf<TableKey>()
        raw.split(",").forEach { part ->
            val entry = part.trim()
            if (entry.isEmpty()) throw ScaffoldInputError("--tables contains an empty entry; check for stray commas")
            val dots = entry.count { it == '.' }
            if (dots > 1) throw ScaffoldInputError("invalid table reference '$entry': too many '.'")
            val key = if (dots == 1) {
                val (s, t) = entry.split(".")
                if (s.isEmpty() || t.isEmpty()) throw ScaffoldInputError("invalid table reference '$entry'")
                TableKey(s, t)
            } else TableKey("public", entry)
            if (!seen.add(key)) throw ScaffoldInputError("table $key listed twice")
            out += key
        }
        if (out.isEmpty()) throw ScaffoldInputError("--tables must name at least one table")
        return out
    }

    /** [env] maps an env-var name to its value (null if unset); injected for testability. */
    fun parseConnection(raw: String, env: (String) -> String?): ScaffoldConnection {
        val normalized = when {
            raw.startsWith("jdbc:postgresql://") -> raw.removePrefix("jdbc:")
            raw.startsWith("postgresql://") -> raw
            raw.startsWith("postgres://") -> "postgresql://" + raw.removePrefix("postgres://")
            else -> throw ScaffoldInputError(
                "unsupported --connection '$raw': expected postgres://, postgresql://, or jdbc:postgresql://",
            )
        }
        val uri = try { URI(normalized) } catch (e: Exception) {
            throw ScaffoldInputError("malformed --connection URI: ${e.message}")
        }
        val host = uri.host ?: throw ScaffoldInputError("--connection is missing a host (if your password contains '@' or ':', percent-encode it — '@'→'%40', ':'→'%3A')")
        val port = if (uri.port == -1) 5432 else uri.port
        val database = uri.path.removePrefix("/").ifEmpty {
            throw ScaffoldInputError("--connection is missing a database name (the path after the host)")
        }
        val userInfo = uri.userInfo
        val uriUser = userInfo?.substringBefore(":")?.takeIf { it.isNotEmpty() }
        val uriPass = userInfo?.substringAfter(":", "")?.takeIf { it.isNotEmpty() }

        val username = uriUser ?: env("PGUSER")
            ?: throw ScaffoldInputError("Postgres username missing: include it in --connection or set PGUSER")
        val passwordFromUri = uriPass != null
        val password = uriPass ?: env("PGPASSWORD")
            ?: throw ScaffoldInputError(
                "Postgres password missing: embed it in the --connection URI or set the PGPASSWORD env var",
            )

        return ScaffoldConnection(
            jdbcUrl = "jdbc:postgresql://$host:$port/$database",
            coords = ScaffoldConnectionCoords(host, port, database, username),
            password = password,
            passwordFromUri = passwordFromUri,
        )
    }
}
