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

package com.snowplowanalytics.cdc.testutil

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.DockerClientFactory

/**
 * JUnit 5 extension: skips a `@Testcontainers` test class when the Docker daemon is unreachable.
 *
 * Apply via `@ExtendWith(RequiresDocker::class)` alongside `@Testcontainers`. The extension fires
 * once per class (BeforeAll) and uses JUnit's [Assumptions] API to abort cleanly — tests are
 * reported as SKIPPED (not FAILED) and downstream orchestrators (e.g. sandcastle AFK) won't
 * burn retry iterations on environment-level Docker failures.
 *
 * The Docker availability check is wrapped in a try/catch because [DockerClientFactory.instance]
 * can throw on some misconfigurations rather than returning false; we treat any throw as "not
 * available" and skip.
 */
class RequiresDocker : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        val available = try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (t: Throwable) {
            false
        }
        Assumptions.assumeTrue(
            available,
            "Docker daemon unreachable — skipping Testcontainers test ${context.displayName}",
        )
    }
}
