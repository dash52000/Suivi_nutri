package io.github.dash52000.suivinutri.llm

import java.io.IOException

/** Issue d'un essai sur un maillon (ou d'un maillon sauté). */
enum class Issue(val code: String) {
    OK("ok"),
    JSON_INVALIDE("json_invalide"),
    SURCHARGE("surcharge"),          // 503 et autres 5xx
    QUOTA("quota"),                  // 429
    DELAI("delai"),                  // délai de connexion ou de réponse dépassé
    ERREUR_RESEAU("erreur_reseau"),  // coupure en cours d'échange
    CLE_A_REVOIR("cle_a_revoir"),    // 401, 403 : tout le fournisseur est sauté pour cette phrase
    MODELE_RETIRE("modele_retire"),  // 404 : ce modèle est sauté
    REQUETE_REFUSEE("requete_refusee"), // 400 : pas de bascule
    CODE_INATTENDU("code_inattendu"),
    HORS_LIGNE("hors_ligne"),
    SANS_CLE("sans_cle"),
    FOURNISSEUR_SAUTE("fournisseur_saute"),
    BUDGET_EPUISE("budget_epuise"),
    ;

    /** Ces issues méritent un nouvel essai sur le même modèle (fiche SPEC section 7). */
    val reessayable: Boolean get() = this == JSON_INVALIDE || this == SURCHARGE || this == QUOTA || this == DELAI || this == ERREUR_RESEAU

    /** Nouvel essai après l'attente de 2 s (pas pour un JSON invalide : le serveur va bien). */
    val attendreAvant: Boolean get() = reessayable && this != JSON_INVALIDE
}

/** Trace d'un essai, pour le journal d'usage : jamais d'en-tête, de clé, ni de corps de réponse. */
data class Tentative(
    val maillon: Maillon,
    val essai: Int,
    val issue: Issue,
    val codeHttp: Int? = null,
    val dureeMs: Long = 0,
    val detail: String = "",
) {
    fun pourJournal(): String = buildString {
        append("fournisseur=").append(maillon.fournisseur.name.lowercase())
        append(";modele=").append(maillon.modele)
        append(";essai=").append(essai)
        append(";issue=").append(issue.code)
        codeHttp?.let { append(";http=").append(it) }
        append(";duree_ms=").append(dureeMs)
        if (detail.isNotEmpty()) append(";detail=").append(detail.take(120))
    }
}

enum class RaisonEchec(val code: String) {
    AUCUNE_CLE("aucune_cle"),
    HORS_LIGNE("hors_ligne"),
    BUDGET_EPUISE("budget_epuise"),
    REQUETE_REFUSEE("requete_refusee"),
    TOUT_EN_ECHEC("tout_en_echec"),
}

sealed interface ResultatChaine {
    val tentatives: List<Tentative>

    /** Fournisseurs dont la clé a été refusée (401, 403) : « clé à revoir ». */
    val clesARevoir: Set<Fournisseur> get() = tentatives.filter { it.issue == Issue.CLE_A_REVOIR }.map { it.maillon.fournisseur }.toSet()

    /** Modèles qui ont répondu 404 : « modèle retiré ». */
    val modelesRetires: List<String> get() = tentatives.filter { it.issue == Issue.MODELE_RETIRE }.map { it.maillon.modele }

    /**
     * Lecture réussie. [modeleRepondu] = nom renvoyé par l'API quand il existe (un nom retiré peut être redirigé vers
     * un autre modèle), sinon le nom demandé. [jsonBrut] est stocké tel quel avec la version du prompt.
     */
    data class Reussie(
        val extraction: ExtractionLue,
        val jsonBrut: String,
        val maillon: Maillon,
        val modeleRepondu: String,
        val versionPrompt: Int,
        override val tentatives: List<Tentative>,
    ) : ResultatChaine {
        /** Maillons quittés avant celui qui a répondu. */
        val bascules: List<Maillon> get() = tentatives.map { it.maillon }.distinct().filter { it != maillon }
    }

    /** Lecture impossible pour l'instant : la phrase reste en attente (NA) et sera relancée à l'ouverture suivante. */
    data class Echouee(val raison: RaisonEchec, override val tentatives: List<Tentative>) : ResultatChaine
}

/** Résultat du bouton « Tester les modèles » pour un maillon. */
data class ResultatTest(val tentative: Tentative, val modeleRepondu: String?) {
    val repond: Boolean get() = tentative.issue == Issue.OK
}

/**
 * Chaîne de bascule entre modèles (fiche SPEC section 7, architecture section 4).
 *
 * - 503 (et 5xx), 429, délai dépassé, coupure : un nouvel essai sur le même modèle après [attenteMs], puis maillon suivant.
 * - JSON invalide (ou non conforme au schéma) : un nouvel essai tout de suite, puis maillon suivant.
 * - 401, 403 : tous les maillons de ce fournisseur sont sautés pour cette phrase (clé à revoir).
 * - 404 : ce modèle est sauté (modèle retiré).
 * - 400 : pas de bascule, la chaîne s'arrête (erreur de requête de notre côté : un autre maillon n'y changerait rien).
 * - Hors ligne : arrêt immédiat. Budget de [budgetMs] épuisé : les maillons restants sont sautés.
 * Dans ces cas d'arrêt, la phrase reste en attente.
 *
 * L'horloge et l'attente sont injectées (fausse horloge dans les tests). Bloquant : à lancer hors du fil principal.
 */
class Chaine(
    private val transport: Transport,
    private val cles: SourceCles,
    private val horloge: () -> Long = { System.nanoTime() / 1_000_000 },
    private val attendre: (Long) -> Unit = { Thread.sleep(it) },
    val budgetMs: Long = 60_000,
    val delaiConnexionMs: Long = 5_000,
    val delaiReponseMs: Long = 20_000,
    val attenteMs: Long = 2_000,
) {
    /** En dessous de ce temps restant, un essai n'a aucune chance d'aboutir : le budget est considéré épuisé. */
    val restantMinMs: Long = 1_000

    fun lire(texte: String, moment: String, maillons: List<Maillon>): ResultatChaine {
        val tentatives = mutableListOf<Tentative>()
        val echeance = horloge() + budgetMs
        val fournisseursSautes = mutableSetOf<Fournisseur>()
        var auMoinsUnAppel = false

        for (maillon in maillons) {
            if (maillon.fournisseur in fournisseursSautes) {
                tentatives += Tentative(maillon, 0, Issue.FOURNISSEUR_SAUTE)
                continue
            }
            val cle = cles.cle(maillon.fournisseur)?.trim()
            if (cle.isNullOrEmpty()) {
                tentatives += Tentative(maillon, 0, Issue.SANS_CLE)
                continue
            }

            for (essai in 1..2) {
                val restant = echeance - horloge()
                if (restant < restantMinMs) {
                    tentatives += Tentative(maillon, essai, Issue.BUDGET_EPUISE)
                    return ResultatChaine.Echouee(RaisonEchec.BUDGET_EPUISE, tentatives)
                }
                auMoinsUnAppel = true
                val (t, reussite) = essayer(maillon, essai, cle, texte, moment, restant)
                tentatives += t
                if (reussite != null) {
                    return ResultatChaine.Reussie(
                        extraction = reussite.first,
                        jsonBrut = reussite.second,
                        maillon = maillon,
                        modeleRepondu = reussite.third ?: maillon.modele,
                        versionPrompt = Schema.VERSION_PROMPT,
                        tentatives = tentatives,
                    )
                }
                when (t.issue) {
                    Issue.HORS_LIGNE -> return ResultatChaine.Echouee(RaisonEchec.HORS_LIGNE, tentatives)
                    Issue.REQUETE_REFUSEE -> return ResultatChaine.Echouee(RaisonEchec.REQUETE_REFUSEE, tentatives)
                    Issue.CLE_A_REVOIR -> fournisseursSautes += maillon.fournisseur
                    else -> Unit
                }
                if (!t.issue.reessayable || essai == 2) break
                if (t.issue.attendreAvant) {
                    if (echeance - horloge() < attenteMs + restantMinMs) {
                        tentatives += Tentative(maillon, essai + 1, Issue.BUDGET_EPUISE)
                        return ResultatChaine.Echouee(RaisonEchec.BUDGET_EPUISE, tentatives)
                    }
                    attendre(attenteMs)
                }
            }
        }
        return ResultatChaine.Echouee(if (auMoinsUnAppel) RaisonEchec.TOUT_EN_ECHEC else RaisonEchec.AUCUNE_CLE, tentatives)
    }

    /**
     * « Tester les modèles » : la phrase fixe [Schema.PHRASE_TEST], un appel par maillon, sans nouvel essai ni bascule,
     * pour voir ceux qui répondent avec un JSON valide. Jamais une vraie phrase.
     */
    fun tester(maillons: List<Maillon>): List<ResultatTest> = maillons.map { maillon ->
        val cle = cles.cle(maillon.fournisseur)?.trim()
        if (cle.isNullOrEmpty()) {
            ResultatTest(Tentative(maillon, 0, Issue.SANS_CLE), null)
        } else {
            val (t, reussite) = essayer(maillon, 1, cle, Schema.PHRASE_TEST, Schema.MOMENT_TEST, delaiReponseMs)
            ResultatTest(t, reussite?.third ?: if (t.issue == Issue.OK) maillon.modele else null)
        }
    }

    /** Un appel. Rend la trace et, si réussi, (extraction, JSON brut, modèle ayant répondu). */
    private fun essayer(
        maillon: Maillon,
        essai: Int,
        cle: String,
        texte: String,
        moment: String,
        restantMs: Long,
    ): Pair<Tentative, Triple<ExtractionLue, String, String?>?> {
        val debut = horloge()
        fun trace(issue: Issue, code: Int? = null, detail: String = "") =
            Tentative(maillon, essai, issue, code, horloge() - debut, detail)

        val requete = Fournisseurs.requete(maillon, cle, texte, moment)
        val reponse = try {
            transport.envoyer(requete, minOf(delaiConnexionMs, restantMs), minOf(delaiReponseMs, restantMs))
        } catch (e: DelaiDepasse) {
            return trace(Issue.DELAI) to null
        } catch (e: HorsLigne) {
            return trace(Issue.HORS_LIGNE, detail = e.message.orEmpty()) to null
        } catch (e: IOException) {
            return trace(Issue.ERREUR_RESEAU, detail = e.javaClass.simpleName) to null
        }

        val code = reponse.code
        val issue = when {
            code == 200 -> null
            Fournisseurs.estSortieNonConforme(maillon.fournisseur, code, reponse.corps) -> Issue.JSON_INVALIDE
            Fournisseurs.estCleRefusee(maillon.fournisseur, code, reponse.corps) -> Issue.CLE_A_REVOIR
            code == 400 -> Issue.REQUETE_REFUSEE
            code == 401 || code == 403 -> Issue.CLE_A_REVOIR
            code == 404 -> Issue.MODELE_RETIRE
            code == 429 -> Issue.QUOTA
            code in 500..599 -> Issue.SURCHARGE
            else -> Issue.CODE_INATTENDU
        }
        if (issue != null) {
            return trace(issue, code, if (issue == Issue.JSON_INVALIDE) "sortie non conforme (fournisseur)" else "") to null
        }

        val lue = Fournisseurs.lireReponse(maillon.fournisseur, reponse.corps)
        val brut = lue.texte ?: return trace(Issue.JSON_INVALIDE, code, "réponse sans texte") to null
        return when (val l = LectureJson.lire(brut)) {
            is Lecture.Valide -> trace(Issue.OK, code) to Triple(l.extraction, LectureJson.sansBalises(brut), lue.modeleRepondu)
            is Lecture.Invalide -> trace(Issue.JSON_INVALIDE, code, l.raison) to null
        }
    }
}
