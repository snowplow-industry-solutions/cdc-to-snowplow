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

package com.snowplowanalytics.cdc.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SlugTest {

    @Test
    fun `typical lowercase input is unchanged`() {
        slugify("orders") shouldBe "orders"
    }

    @Test
    fun `mixed case is lowercased`() {
        slugify("OrderItems") shouldBe "orderitems"
    }

    @Test
    fun `leading and trailing whitespace is stripped`() {
        slugify("  orders  ") shouldBe "orders"
    }

    @Test
    fun `internal separator runs collapse to single hyphen`() {
        slugify("order__items") shouldBe "order-items"
    }

    @Test
    fun `underscores are treated as separators`() {
        slugify("public_orders") shouldBe "public-orders"
    }

    @Test
    fun `input with no alphanumerics returns empty string`() {
        slugify("!!!") shouldBe ""
    }
}
