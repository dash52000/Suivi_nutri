package io.github.dash52000.suivinutri.llm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Aucune clé API dans le dépôt (fiche SPEC section 9, point 10) : le build échoue si une source, une ressource, un
 * script Gradle ou le workflow contient une chaîne de la forme d'une clé Gemini (`AIza…`) ou Groq (`gsk_…`).
 * Les motifs sont assemblés en morceaux pour que ce fichier ne se détecte pas lui-même.
 */
class AucuneCleTest {

    private val motifs = listOf(
        Regex("A" + "Iza" + "[0-9A-Za-z_\\-]{30,}"),
        Regex("g" + "sk_" + "[0-9A-Za-z]{20,}"),
    )

    private val extensions = setOf("kt", "kts", "java", "xml", "yml", "yaml", "json", "properties", "md", "txt", "gradle", "pro")

    /** Racine du dépôt : Gradle lance les tests depuis `app/`, kotlinc depuis la racine. */
    private fun racine(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").exists()) return d
            d = d.parentFile
        }
        error("racine du dépôt introuvable depuis ${File("").absolutePath}")
    }

    @Test
    fun aucuneCleDansLeDepot() {
        val r = racine()
        val fichiers = r.walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", ".idea") }
            .filter { it.isFile && (it.extension in extensions || it.name == "gradlew") }
            .toList()
        assertTrue("trop peu de fichiers lus (${fichiers.size}) : mauvais dossier ?", fichiers.size > 20)
        assertTrue("workflow non lu", fichiers.any { it.path.contains(".github") })

        val trouves = fichiers.flatMap { f ->
            f.readLines().withIndex().filter { (_, ligne) -> motifs.any { it.containsMatchIn(ligne) } }
                .map { (i, _) -> "${f.relativeTo(r)}:${i + 1}" }
        }
        assertTrue("Clé API possible dans le dépôt (à retirer, puis révoquer la clé) : $trouves", trouves.isEmpty())
    }

    @Test
    fun lesMotifsDetectentBienUneCle() {
        val faussesCles = listOf(
            "A" + "Iza" + "Sy" + "0".repeat(33),
            "g" + "sk_" + "a1B2".repeat(13),
        )
        faussesCles.forEach { cle -> assertTrue(cle, motifs.any { it.containsMatchIn("val k = \"$cle\"") }) }
    }
}
