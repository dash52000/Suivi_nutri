package io.github.dash52000.suivinutri.repas

import android.content.Context
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.llm.Chaine
import io.github.dash52000.suivinutri.llm.ChaineReglee
import io.github.dash52000.suivinutri.llm.CoffreCles
import io.github.dash52000.suivinutri.llm.Extracteur
import io.github.dash52000.suivinutri.llm.HttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Colle Android de l'étape 6 : une seule instance de [SaisieRepas] et d'[Extracteur] par processus, branchées sur
 * `repas.json`, la table CIQUAL embarquée, la chaîne de modèles réglée, le coffre des clés et le journal d'usage.
 * Seul fichier de ce paquet (avec `CiqualAssets`) à toucher à Android pour l'écran Ce soir : vérifié par la compilation
 * de la CI, pas par les tests locaux.
 *
 * Les lectures par la chaîne partent dans un scope propre à l'appli (elles durent jusqu'à 60 s) : quitter l'onglet ne
 * les coupe pas. Quand une lecture se termine, [version] change et l'écran se recharge.
 */
object ServicesRepas {

    const val FICHIER = "repas.json"

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _version = MutableStateFlow(0)

    /** Change à chaque fin de lecture : l'écran relit alors ses phrases. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _enCours = MutableStateFlow<Set<String>>(emptySet())

    /** Ids des phrases en cours de lecture (pour « Lecture en cours… » à la place de « Relancer »). */
    val enCours: StateFlow<Set<String>> = _enCours.asStateFlow()

    private class Services(val saisie: SaisieRepas, val extracteur: Extracteur)

    @Volatile
    private var services: Services? = null

    /** La lecture des phrases n'existe que si au moins une clé est saisie (sans clé : appli identique à la v1.17). */
    fun disponible(context: Context): Boolean = !CoffreCles(context.applicationContext).aucune()

    fun saisie(context: Context): SaisieRepas = services(context).saisie

    /** Lit la phrase en arrière-plan ; l'écran se recharge à la fin. Sans effet si la phrase est déjà lue. */
    fun lireEnArriere(context: Context, phraseId: String) {
        val s = services(context)
        portee.launch {
            _enCours.update { it + phraseId }
            try {
                s.extracteur.lire(phraseId)
            } catch (e: Exception) {
                Usage.log(context.applicationContext, "llm_erreur", "type=${e.javaClass.simpleName}")
            } finally {
                _enCours.update { it - phraseId }
                _version.update { it + 1 }
            }
        }
    }

    /** Reprend les phrases restées en attente (hors ligne, budget épuisé…), une à la fois, en s'arrêtant au premier échec. */
    fun traiterEnAttente(context: Context) {
        val s = services(context)
        portee.launch {
            try {
                if (s.extracteur.traiterEnAttente() > 0) _version.update { it + 1 }
            } catch (e: Exception) {
                Usage.log(context.applicationContext, "llm_erreur", "type=${e.javaClass.simpleName}")
            }
        }
    }

    private fun services(context: Context): Services =
        services ?: synchronized(this) { services ?: construire(context.applicationContext).also { services = it } }

    private fun construire(ctx: Context): Services {
        val stock = StockRepas(File(ctx.filesDir, FICHIER))
        val journal = { evenement: String, detail: String -> Usage.log(ctx, evenement, detail) }
        val table = { CiqualAssets.table(ctx) }
        val coffre = CoffreCles(ctx)
        return Services(
            saisie = SaisieRepas(stock, table, journal = journal),
            extracteur = Extracteur(
                stock = stock,
                versionCiqual = { table().version },
                lecteur = { texte, moment -> Chaine(HttpTransport, coffre).lire(texte, moment, ChaineReglee.lire(ctx)) },
                journal = journal,
            ),
        )
    }
}
