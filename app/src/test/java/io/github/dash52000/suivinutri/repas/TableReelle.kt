package io.github.dash52000.suivinutri.repas

import java.io.File

/** La vraie table CIQUAL embarquée (`assets/ciqual.tsv`), chargée une fois pour les tests (Gradle lance les tests depuis `app/`). */
object TableReelle {
    val table: TableCiqual by lazy {
        val f = listOf(File("src/main/assets/ciqual.tsv"), File("app/src/main/assets/ciqual.tsv")).first { it.exists() }
        TableCiqual.depuisTexte(f.readText(Charsets.UTF_8))
    }
}
