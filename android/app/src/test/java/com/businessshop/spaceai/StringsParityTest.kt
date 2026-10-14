package com.businessshop.spaceai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Garantiza que español (México) e inglés tengan exactamente las mismas cadenas y argumentos. */
class StringsParityTest {
    private fun load(folder: String): Map<String, String> {
        val f = listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml").map(::File).first { it.exists() }
        return Regex("<string name=\"([a-z_0-9]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun args(s: String) = Regex("%(\\d+)\\$[sd]").findAll(s).map { it.value }.toSortedSet()

    @Test fun sameKeysAndPlaceholders() {
        val en = load("values"); val es = load("values-es")
        assertEquals(en.keys - "app_name", es.keys)
        for ((k, v) in es) assertEquals("Argumentos distintos en $k", args(en.getValue(k)), args(v))
        assertTrue(en.size > 100)
    }
}
