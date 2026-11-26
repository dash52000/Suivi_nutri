package io.github.dash52000.suivinutri.repas

import android.content.Context

/**
 * Charge la table CIQUAL embarquée (`assets/ciqual.tsv`, produite par `tools/ciqual_xml_vers_tsv.py`).
 * Lue une seule fois par processus (275 Ko, 3 341 aliments). Seul fichier du paquet qui touche à Android :
 * vérifié par la compilation de la CI, pas par les tests locaux (la logique est dans [TableCiqual]).
 */
object CiqualAssets {
    const val FICHIER = "ciqual.tsv"

    @Volatile
    private var table: TableCiqual? = null

    fun table(context: Context): TableCiqual =
        table ?: synchronized(this) {
            table ?: context.applicationContext.assets.open(FICHIER).bufferedReader(Charsets.UTF_8)
                .use { TableCiqual.depuisTexte(it.readText()) }
                .also { table = it }
        }
}
