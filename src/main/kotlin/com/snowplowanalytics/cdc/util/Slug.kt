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

// Matches one or more consecutive non-alphanumeric characters (anything that's not a-z,
// A-Z, or 0-9). Used to collapse separator runs into a single '-'.
private val SEPARATOR_RUN = Regex("[^A-Za-z0-9]+")

/**
 * Turns arbitrary input into a lowercase hyphen-separated slug suitable for filenames or URL
 * components. Pure function; see `2026-05-02-slug-utility-design.md` §3 for the full contract.
 *
 * Examples:
 *   slugify("OrderItems")   == "orderitems"
 *   slugify("public_orders") == "public-orders"
 *   slugify("!!!")          == ""
 */
fun slugify(input: String): String =
    input
        .lowercase()
        .replace(SEPARATOR_RUN, "-")
        .trim('-')
