/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TargetTabsTest {
  private fun lang(code: String) = Language(code, code, code, Script.LATIN, singleScript(Script.LATIN), code)

  private val en = lang("en")
  private val es = lang("es")
  private val fr = lang("fr")
  private val uk = lang("uk")

  private fun tabs(
    vararg codes: String,
    active: String,
  ): TargetTabs {
    val list = codes.map(::lang)
    val activeLang = lang(active)
    val i = list.indexOf(activeLang)
    return TargetTabs(list.take(i), activeLang, list.drop(i + 1))
  }

  @Test
  fun `select an existing tab switches without reordering`() {
    val result = tabs("en", "es", "fr", active = "es").select(fr)
    assertEquals(listOf(en, es, fr), result.tabs)
    assertEquals(fr, result.active)
  }

  @Test
  fun `select a new language replaces the active tab in place`() {
    val result = tabs("en", "es", "fr", active = "es").select(uk)
    assertEquals(listOf(en, uk, fr), result.tabs)
    assertEquals(uk, result.active)
  }

  @Test
  fun `select the active language is a no-op`() {
    val start = tabs("en", "es", active = "es")
    assertSame(start, start.select(es))
  }

  @Test
  fun `add appends a new tab and focuses it`() {
    val result = tabs("en", "es", active = "en").add(fr)
    assertEquals(listOf(en, es, fr), result.tabs)
    assertEquals(fr, result.active)
  }

  @Test
  fun `add an existing language just switches to it`() {
    val result = tabs("en", "es", active = "en").add(es)
    assertEquals(listOf(en, es), result.tabs)
    assertEquals(es, result.active)
  }

  @Test
  fun `remove the active tab moves focus to the next neighbour`() {
    val result = tabs("en", "es", "fr", active = "es").remove(es)
    assertEquals(listOf(en, fr), result.tabs)
    assertEquals(fr, result.active)
  }

  @Test
  fun `remove the active last tab falls back to the previous neighbour`() {
    val result = tabs("en", "es", active = "es").remove(es)
    assertEquals(listOf(en), result.tabs)
    assertEquals(en, result.active)
  }

  @Test
  fun `remove a non-active tab keeps the active one`() {
    val result = tabs("en", "es", "fr", active = "fr").remove(es)
    assertEquals(listOf(en, fr), result.tabs)
    assertEquals(fr, result.active)
  }

  @Test
  fun `remove the only tab is a no-op`() {
    val start = TargetTabs.of(en)
    assertSame(start, start.remove(en))
  }

  @Test
  fun `selecting an existing earlier tab switches without dropping the others`() {
    val result = tabs("en", "es", active = "es").select(en)
    assertEquals(listOf(en, es), result.tabs)
    assertEquals(en, result.active)
  }
}
