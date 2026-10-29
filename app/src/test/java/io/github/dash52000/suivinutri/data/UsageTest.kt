package io.github.dash52000.suivinutri.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class UsageTest {

    private val t = OffsetDateTime.of(2026, 10, 29, 21, 17, 3, 0, ZoneOffset.ofHours(1))

    @Test
    fun ligneSimple() {
        assertEquals(
            "2026-10-29T21:17:03+01:00,reponse,jour=2026-10-29;question=tupp;valeur=oui",
            Usage.formaterLigne(t, "reponse", "jour=2026-10-29;question=tupp;valeur=oui"),
        )
    }

    @Test
    fun virgulesEtRetoursNeCassentPasLeCsv() {
        assertEquals(
            "2026-10-29T21:17:03+01:00,import,Import impossible ; ligne 2;fin",
            Usage.formaterLigne(t, "import", "Import impossible , ligne 2\nfin"),
        )
    }
}
