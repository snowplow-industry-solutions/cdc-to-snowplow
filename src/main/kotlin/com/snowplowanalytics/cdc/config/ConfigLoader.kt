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

package com.snowplowanalytics.cdc.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Parses a YAML config file into a validated [Config] in five ordered stages:
 *   1. File read
 *   2. YAML → JsonNode tree (syntax check only)
 *   3. Env-var interpolation (${VAR} substitution)
 *   4. JsonNode tree → Config POJO (type binding)
 *   5. Semantic validation (business rules)
 *
 * Any stage failure throws [ConfigException] with one or more [ConfigError] entries. Later stages
 * do not run if an earlier stage fails — the exception is thrown immediately. Semantic errors
 * (Stage 5) are accumulated so the operator sees all problems in one shot.
 *
 * Env interpolation runs post-parse (Stage 3 not Stage 1) so that env values may contain
 * YAML-active characters like `:`, `{`, `"` without breaking YAML parsing.
 */
// `object` is a Kotlin singleton — one instance for the lifetime of the classloader. Equivalent
// to a class with a private constructor + a static INSTANCE field in Java.
object ConfigLoader {

    // ObjectMapper is Jackson's main entry point. Created once and reused — it is thread-safe
    // after configuration.
    // YAMLFactory makes it parse YAML instead of JSON.
    // registerKotlinModule() enables data class support: Jackson can call Kotlin's primary
    // constructor directly rather than requiring a no-arg constructor + setters.
    // SNAKE_CASE naming strategy maps YAML keys like `slot_name` to Kotlin fields like `slotName`
    // automatically — operators write snake_case YAML while code stays camelCase.
    // FAIL_ON_UNKNOWN_PROPERTIES = true turns unknown keys into errors (caught as UnrecognizedPropertyException).
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, false)

    private val validator = ConfigValidator()
    private val interpolator = EnvInterpolator()

    fun load(path: Path): Config {
        // Stage 1: file read
        val raw = try {
            path.readText()
        } catch (e: IOException) {
            throw ConfigException(
                listOf(ConfigError.FileError(path.toString(), e.message ?: e::class.simpleName.orEmpty())),
                source = path.toString(),
            )
        }
        return loadFromString(raw, path.toString())
    }

    /**
     * Stages 2–5 of config loading from an in-memory YAML body. [source] is a human-readable
     * label for error messages (a file path, or e.g. "env:CDC_CONFIG").
     */
    fun loadFromString(raw: String, source: String? = null): Config {
        // Guard a blank body up front. A whitespace-only string parses to a Jackson MissingNode,
        // which would otherwise reach Stage 4 and surface as an opaque "Cannot deserialize ...
        // [Unavailable value]" binding error. Fail fast with an operator-readable message instead.
        if (raw.isBlank()) {
            throw ConfigException(
                listOf(ConfigError.FileError(source ?: "source", "configuration is empty")),
                source = source,
            )
        }

        // Stage 2: YAML -> JsonNode
        // readTree() parses to a generic tree (JsonNode hierarchy) rather than directly to a
        // typed POJO. This two-step approach lets us mutate the tree for env interpolation
        // (Stage 3) before committing to type binding (Stage 4).
        val node: JsonNode = try {
            mapper.readTree(raw)
        } catch (e: JsonProcessingException) {
            val loc = e.location
            throw ConfigException(
                listOf(
                    ConfigError.YamlSyntax(
                        message = e.originalMessage ?: e.message ?: "yaml parse error",
                        line = loc?.lineNr?.takeIf { it > 0 },
                        column = loc?.columnNr?.takeIf { it > 0 },
                    )
                ),
                source = source,
            )
        }

        // Stage 3: env interpolation
        val envErrors = interpolator.interpolate(node)
        if (envErrors.isNotEmpty()) {
            throw ConfigException(envErrors, source = source)
        }

        // Stage 4: tree -> Config
        // treeToValue() binds a JsonNode tree to a typed POJO. We catch the Jackson exception
        // hierarchy from most-specific to least-specific so each error gets the right ConfigError
        // subtype. Order matters: MissingKotlinParameterException IS-A MismatchedInputException,
        // so it must be caught first.
        val config: Config = try {
            mapper.treeToValue(node, Config::class.java)
        } catch (e: UnrecognizedPropertyException) {
            throw ConfigException(
                listOf(
                    ConfigError.UnknownKey(
                        path = pathFromException(e),
                        line = e.location?.lineNr?.takeIf { it > 0 },
                    )
                ),
                source = source,
            )
        } catch (e: MismatchedInputException) {
            throw ConfigException(
                listOf(translateBindingError(e)),
                source = source,
            )
        } catch (e: JsonMappingException) {
            throw ConfigException(
                listOf(
                    ConfigError.InvalidValue(
                        path = pathFromException(e),
                        message = e.originalMessage ?: e.message ?: "invalid value",
                        line = e.location?.lineNr?.takeIf { it > 0 },
                    )
                ),
                source = source,
            )
        }

        // Stage 5: semantic validation
        val errors = validator.validate(config)
        if (errors.isNotEmpty()) {
            throw ConfigException(errors, source = source)
        }
        return config
    }

    // @Suppress("DEPRECATION") because MissingKotlinParameterException is marked deprecated in
    // jackson-module-kotlin 2.18, but it remains the public API for detecting missing required
    // Kotlin constructor parameters — there is no non-deprecated replacement yet.
    @Suppress("DEPRECATION")
    private fun translateBindingError(e: MismatchedInputException): ConfigError {
        val fieldPath = pathFromException(e)
        val msg = e.originalMessage ?: e.message ?: "invalid value"
        // Discriminate by exception TYPE, not by message text. An earlier version checked
        // whether the message contained "missing", which misclassified type errors whose
        // invalid value happened to contain the word "missing" (e.g. port: "missing-value").
        return when (e) {
            is MissingKotlinParameterException ->
                ConfigError.MissingField(fieldPath, e.location?.lineNr?.takeIf { it > 0 })
            else ->
                ConfigError.InvalidValue(fieldPath, msg, e.location?.lineNr?.takeIf { it > 0 })
        }
    }

    // Jackson's JsonMappingException carries a path as a list of Reference objects — each
    // reference is either a field name (object key) or an index (array element). This helper
    // reconstructs the dotted-bracket notation used in ConfigError (e.g. "tables[0].primary_key").
    private fun pathFromException(e: JsonMappingException): String {
        if (e.path.isEmpty()) return "<root>"
        val sb = StringBuilder()
        e.path.forEachIndexed { i, ref ->
            if (ref.fieldName == null) {
                sb.append("[").append(ref.index).append("]")
            } else {
                if (i > 0 && sb.isNotEmpty() && sb.last() != ']') sb.append('.')
                else if (i > 0) sb.append('.')
                sb.append(ref.fieldName)
            }
        }
        return sb.toString()
    }

}
