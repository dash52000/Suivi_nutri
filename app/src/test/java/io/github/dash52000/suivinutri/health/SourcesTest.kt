package io.github.dash52000.suivinutri.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SourcesTest {

    private data class Mesure(val paquet: String, val instant: Instant, val kg: Double)

    private val matin = Instant.parse("2026-11-02T06:10:00Z")

    @Test
    fun withingsPrefereeMemePlusTard() {
        val garmin = Mesure("com.garmin.android.apps.connectmobile", matin, 87.7)
        val withings = Mesure("com.withings.wiscale2", matin.plusSeconds(600), 87.65)
        val choisie = Sources.preferer(listOf(garmin, withings), { it.paquet }, { it.instant })
        assertEquals(withings, choisie)
    }

    @Test
    fun aSourceEgaleLaPlusMatinale() {
        val tot = Mesure("com.garmin.android.apps.connectmobile", matin, 87.7)
        val tard = Mesure("com.garmin.android.apps.connectmobile", matin.plusSeconds(30000), 88.4)
        assertEquals(tot, Sources.preferer(listOf(tard, tot), { it.paquet }, { it.instant }))
        assertNull(Sources.preferer(emptyList<Mesure>(), { it.paquet }, { it.instant }))
    }

    @Test
    fun libelles() {
        assertEquals("withings", Sources.libelle("com.withings.wiscale2"))
        assertEquals("garmin", Sources.libelle("com.garmin.android.apps.connectmobile"))
    }
}
