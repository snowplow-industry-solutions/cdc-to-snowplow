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

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class CdcSourceSchemaResourceTest {
    @Test
    fun `loads the canonical cdc_source schema from the classpath`() {
        val text = CdcSourceSchemaResource.read()
        text shouldContain "\"name\": \"cdc_source\""
        text shouldContain "\"vendor\": \"com.snowplowanalytics\""
        text shouldContain "column_fingerprint"
    }

    @Test
    fun `relative iglu path under schemas dir is stable`() {
        CdcSourceSchemaResource.RELATIVE_PATH shouldBe "com.snowplowanalytics/cdc_source/jsonschema/1-0-0"
    }
}
