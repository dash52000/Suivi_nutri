package io.github.dash52000.suivinutri.repas

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Un moment d'un jour tel que l'écran Ce soir doit le montrer. */
data class EtatMoment(
    val jour: LocalDate,
    val moment: Moment,
    /** Ligne de tirage enregistrée ; null hors de la fenêtre (aujourd'hui, hier) : pas de tirage, phrase spontanée. */
    val tirage: TirageMoment?,
    /** Le moment est « demandé » : u < π avec la π enregistrée. Un soir chargé l'est toujours. */
    val demande: Boolean,
    val phrases: List<PhraseRepas>,
) {
    val reponse: Reponse? get() = tirage?.reponse
}

/** Tout ce que l'écran affiche pour un moment (calculé hors du fil principal : lit des fichiers et la table CIQUAL). */
data class VueMoment(
    val etat: EtatMoment,
    /** Par id de phrase : null = pas encore lue (en attente de la chaîne). Jamais de kcal affichée à partir d'ici. */
    val lectures: Map<String, RepasLu?>,
    /** Taps possibles sous le champ (« Comme d'hab », « Rien », « Pas maintenant »), vide si pas demandé ou phrase écrite. */
    val boutons: List<Reponse>,
    val exemple: String,
    /** Texte proposé dans le champ (midi après un tupp rempli la veille), null sinon. */
    val prerempli: String?,
    /** Une habitude du matin existe (3 phrases tirées ou plus) : un champ Matin vide veut dire « comme d'hab ». */
    val habitudeConnue: Boolean,
)

/**
 * Une phrase telle que l'écran « Phrases lues » (onglet Données) la montre. [lu] = null : pas encore lue ([modele] = null)
 * ou lue mais illisible ([modele] renseigné). Les kcal viennent de [RepasLu] : recalculées, jamais stockées.
 */
data class LignePhrase(
    val phrase: PhraseRepas,
    val lu: RepasLu?,
    /** Modèle qui a réellement répondu ; null tant que la phrase n'a aucune extraction. */
    val modele: String?,
    val erreurs: List<String>,
)

/**
 * Logique de l'écran Ce soir (étape 6, fiche SPEC sections 4, 5 et 9) : tirage des moments, enregistrement des phrases,
 * taps « Rien » / « Comme d'hab » / « Pas maintenant », quantité, midi pré-rempli, petit-déj habituel. Aucune dépendance
 * Android : l'écran Compose n'est qu'une peau sur cette classe, qui se teste avec kotlinc.
 *
 * Règles :
 *  - Un tirage n'existe que pour aujourd'hui et hier (la fenêtre du rattrapage de 8 h 30) : plus loin, on n'interroge
 *    plus, et une phrase écrite est spontanée.
 *  - Une ligne de tirage est créée à la première ouverture du moment. Tant qu'elle n'a pas de réponse, sa strate et sa π
 *    sont réévaluées (un soir qui devient chargé monte à π = 1) ; u ne change jamais. Une fois répondue, elle est figée.
 *  - Origine d'une phrase : chargée (soir à π = 1 tiré par les taps), tirée (moment demandé), sinon spontanée.
 *  - « Rien » (grignotage seulement), « Comme d'hab » (matin, avec habitude) et « Pas maintenant » ne s'enregistrent que
 *    sur un moment demandé sans phrase ; retaper le même tap l'efface. Écrire une phrase remplace un tap.
 *  - Matin, midi et soir : une seule phrase par jour (corriger = supprimer puis recréer, nouvelle id). Grignotage : autant
 *    que voulu.
 *  - Un moment demandé resté sans réponse passe à « ignoré » une fois le jour hors du rattrapage ([fermerAnciens]).
 *  - Tardive = la phrase ou le tap est donné un jour après le jour concerné (même règle que le rattrapage).
 *
 * [journal] reçoit les événements du journal d'usage (moment demandé, rempli, refusé, ignoré, tardif…), jamais un texte.
 */
class SaisieRepas(
    private val stock: StockRepas,
    private val table: () -> TableCiqual,
    private val aujourdhui: () -> LocalDate = { LocalDate.now() },
    private val maintenant: () -> Instant = { Instant.now() },
    private val nouvelId: () -> String = { UUID.randomUUID().toString() },
    private val journal: (evenement: String, detail: String) -> Unit = { _, _ -> },
) {

    // ---------- Lecture ----------

    /** Le moment [moment] du [jour], avec sa ligne de tirage à jour (créée ou réévaluée si besoin). */
    fun ouvrir(jour: LocalDate, moment: Moment, reponses: Map<String, String>): EtatMoment {
        val phrases = stock.phrasesDuJour(jour).filter { it.moment == moment }
        if (!dansLaFenetre(jour)) return EtatMoment(jour, moment, null, false, phrases)

        val taps = if (moment == Moment.SOIR) TapsSoir.depuis(reponses) else null
        val phrasesMatin = if (moment == Moment.MATIN) habitudeMatin()?.nPhrases ?: 0 else 0
        val decision = Tirage(stock.graine(), stock.probas()).decider(jour, moment, phrasesMatin, taps)

        var ligne = stock.tirage(jour, moment)
        if (ligne == null) {
            ligne = decision.versTirage()
            stock.enregistrerTirage(ligne)
            if (demande(ligne)) journal("moment_demande", detail(jour, moment, "pi=${ligne.pi}"))
        } else if (ligne.reponse == null && (ligne.strate != decision.strate || ligne.pi != decision.pi)) {
            val avant = demande(ligne)
            ligne = decision.versTirage()
            stock.enregistrerTirage(ligne)
            if (!avant && demande(ligne)) journal("moment_demande", detail(jour, moment, "pi=${ligne.pi}"))
        }
        return EtatMoment(jour, moment, ligne, demande(ligne), phrases)
    }

    /**
     * Tout l'affichage d'un moment. [reponsesVeille] sert au midi pré-rempli. Lit les fichiers et la table CIQUAL :
     * à appeler hors du fil principal.
     */
    fun vue(jour: LocalDate, moment: Moment, reponses: Map<String, String>, reponsesVeille: Map<String, String>): VueMoment {
        val etat = ouvrir(jour, moment, reponses)
        val tbl = table()
        val lectures = etat.phrases.associate { p ->
            p.id to stock.extractionRetenue(p.id)?.let { LectureRepas.lire(p, it, tbl) }
        }
        val habitude = if (moment == Moment.MATIN) habitudeMatin() else null
        val sansPhrase = etat.phrases.isEmpty()
        return VueMoment(
            etat = etat,
            lectures = lectures,
            boutons = if (etat.demande && sansPhrase) boutonsPossibles(moment, HabitudeMatin.proposable(habitude)) else emptyList(),
            exemple = Exemples.pour(moment, ContexteSaisie.depuis(reponses)),
            prerempli = if (moment == Moment.MIDI && sansPhrase) midiPrerempli(jour, reponses, reponsesVeille) else null,
            habitudeConnue = HabitudeMatin.proposable(habitude),
        )
    }

    /**
     * Les phrases écrites depuis [depuis], la plus récente d'abord (puis matin, midi, soir, grignotage), avec leur lecture.
     * Sert à l'écran « Phrases lues » : montrer ce qui est pris en compte et les kcal calculées (décision du 26/11, à la
     * demande de Damien : kcal dans Données, jamais dans Ce soir). Lit des fichiers et la table : hors du fil principal.
     */
    fun lignesPhrases(depuis: LocalDate): List<LignePhrase> {
        val c = stock.contenu()
        val tbl by lazy(table)
        return c.phrases.filter { !it.jour.isBefore(depuis) }
            .sortedWith(compareByDescending<PhraseRepas> { it.jour }.thenBy { it.moment.ordinal }.thenBy { it.horodatage })
            .map { p ->
                val e = c.extractions.filter { it.phraseId == p.id }.maxByOrNull { it.horodatage }
                LignePhrase(p, e?.let { LectureRepas.lire(p, it, tbl) }, e?.modele, e?.erreurs.orEmpty())
            }
    }

    /** Taps proposés sous un moment demandé (SPEC section 4). */
    fun boutonsPossibles(moment: Moment, habitudeConnue: Boolean): List<Reponse> = when (moment) {
        Moment.MATIN -> if (habitudeConnue) listOf(Reponse.COMME_DHAB) else emptyList()
        Moment.MIDI, Moment.SOIR -> listOf(Reponse.REFUS)
        Moment.GRIGNOTAGE -> listOf(Reponse.RIEN, Reponse.REFUS)
    }

    /**
     * Petit-déj habituel : appris des phrases du matin **tirées** déjà lues (poids 1/π avec la π enregistrée au tirage),
     * 8 semaines glissantes, au moins 3 phrases (`HabitudeMatin`). Null sans habitude.
     */
    fun habitudeMatin(): HabitudeMatin? {
        val c = stock.contenu()
        val tbl by lazy(table)
        val phrases = c.phrases.filter { it.moment == Moment.MATIN && it.origine == Origine.TIREE }.mapNotNull { p ->
            val e = c.extractions.filter { it.phraseId == p.id }.maxByOrNull { it.horodatage } ?: return@mapNotNull null
            val t = c.tirages.firstOrNull { it.jour == p.jour && it.moment == Moment.MATIN } ?: return@mapNotNull null
            val lu = LectureRepas.lire(p, e, tbl) ?: return@mapNotNull null
            val kcal = lu.kcal ?: return@mapNotNull null
            val sigma = lu.sigma ?: return@mapNotNull null
            PhraseMatin(p.jour, kcal, sigma, t.pi, p.origine)
        }
        return HabitudeMatin.apprendre(phrases, aujourdhui())
    }

    /**
     * Midi pré-rempli (3.1 B) : « Restes de [plat de la veille], assiette normale », quand le midi est un tupp et que les
     * tupp ont été remplis la veille. Le plat est l'aliment le plus lourd de la phrase du soir de la veille ; sans cette
     * phrase (pas écrite ou pas encore lue), « Restes de la veille, assiette normale ».
     */
    fun midiPrerempli(jour: LocalDate, reponses: Map<String, String>, reponsesVeille: Map<String, String>): String? {
        if (reponses["midi_quoi"] != "tupp" || reponsesVeille["tupp"] != "oui") return null
        val plat = stock.phrasesDuJour(jour.minusDays(1)).filter { it.moment == Moment.SOIR }
            .firstNotNullOfOrNull { p -> stock.extractionRetenue(p.id)?.let { LectureRepas.platPrincipal(it) } }
        return "Restes de ${plat ?: "la veille"}, assiette normale"
    }

    // ---------- Écriture ----------

    /**
     * Enregistre la phrase d'un moment. Matin, midi, soir : remplace celle du jour. Rend la phrase, ou null si le texte
     * est vide. La lecture par la chaîne se lance à part (`Extracteur`).
     */
    fun enregistrerPhrase(jour: LocalDate, moment: Moment, texte: String, reponses: Map<String, String>): PhraseRepas? {
        val propre = texte.trim()
        if (propre.isEmpty()) return null
        val etat = ouvrir(jour, moment, reponses)
        if (moment != Moment.GRIGNOTAGE) etat.phrases.forEach { stock.supprimerPhrase(it.id) }

        val ligne = etat.tirage
        val origine = when {
            ligne != null && etat.demande -> if (ligne.strate.charge) Origine.CHARGEE else Origine.TIREE
            else -> Origine.SPONTANEE
        }
        val tardive = aujourdhui().isAfter(jour)
        val phrase = PhraseRepas(nouvelId(), jour, moment, propre, origine, tardive, maintenant())
        stock.ajouterPhrase(phrase)
        if (ligne != null && etat.demande) stock.enregistrerTirage(ligne.copy(reponse = Reponse.PHRASE, tardive = tardive))
        journal("moment_rempli", detail(jour, moment, "origine=${origine.code}", tardive))
        return phrase
    }

    /** Retire une phrase. Si plus aucune phrase du moment, la réponse « phrase » du tirage retombe à « pas de réponse ». */
    fun supprimerPhrase(phraseId: String) {
        val p = stock.phrase(phraseId) ?: return
        stock.supprimerPhrase(phraseId)
        val reste = stock.phrasesDuJour(p.jour).any { it.moment == p.moment }
        val ligne = stock.tirage(p.jour, p.moment)
        if (!reste && ligne != null && ligne.reponse == Reponse.PHRASE) {
            stock.enregistrerTirage(ligne.copy(reponse = null, tardive = false))
        }
    }

    /**
     * Tap « Rien », « Comme d'hab » ou « Pas maintenant » sur un moment demandé. Retaper le même tap l'efface.
     * Faux (et rien d'écrit) si le tap n'est pas proposé ici : moment non demandé, phrase déjà écrite, tap d'un autre
     * moment, « Comme d'hab » sans habitude.
     */
    fun repondre(jour: LocalDate, moment: Moment, reponse: Reponse, reponses: Map<String, String>): Boolean {
        val etat = ouvrir(jour, moment, reponses)
        val ligne = etat.tirage ?: return false
        if (!etat.demande || etat.phrases.isNotEmpty()) return false
        val habitude = moment == Moment.MATIN && HabitudeMatin.proposable(habitudeMatin())
        if (reponse !in boutonsPossibles(moment, habitude)) return false

        val nouvelle = if (ligne.reponse == reponse) null else reponse
        val tardive = nouvelle != null && aujourdhui().isAfter(jour)
        stock.enregistrerTirage(ligne.copy(reponse = nouvelle, tardive = tardive))
        if (nouvelle != null) {
            journal(
                when (nouvelle) {
                    Reponse.REFUS -> "moment_refuse"
                    Reponse.RIEN -> "moment_rien"
                    else -> "moment_comme_dhab"
                },
                detail(jour, moment, "", tardive),
            )
        }
        return true
    }

    /** Le tap petite · normale · grosse d'une phrase ; retaper le même l'efface. */
    fun choisirQuantite(phraseId: String, quantite: Quantite) {
        val p = stock.phrase(phraseId) ?: return
        stock.definirQuantite(phraseId, if (p.quantite == quantite) null else quantite)
        journal("quantite", "moment=${p.moment.code};valeur=${if (p.quantite == quantite) "efface" else quantite.code}")
    }

    /**
     * Les moments demandés restés sans réponse, dont le jour est passé hors du rattrapage (avant hier), passent à
     * « ignoré » : NA, noté au journal. Rend leur nombre. Un moment non demandé n'a rien à fermer.
     */
    fun fermerAnciens(): Int {
        val limite = aujourdhui().minusDays(1)
        var n = 0
        for (t in stock.contenu().tirages) {
            if (t.jour.isBefore(limite) && t.reponse == null && demande(t)) {
                stock.enregistrerTirage(t.copy(reponse = Reponse.IGNORE))
                journal("moment_ignore", detail(t.jour, t.moment, ""))
                n++
            }
        }
        return n
    }

    // ---------- Aides ----------

    private fun dansLaFenetre(jour: LocalDate): Boolean = jour == aujourdhui() || jour == aujourdhui().minusDays(1)

    /** Le moment est demandé si u(graine, jour, moment) < π enregistrée (u ne dépend pas des probabilités). */
    private fun demande(t: TirageMoment): Boolean = Tirage(t.graine).u(t.jour, t.moment) < t.pi

    private fun detail(jour: LocalDate, moment: Moment, plus: String, tardive: Boolean = false): String =
        listOf("jour=$jour", "moment=${moment.code}", plus.takeIf { it.isNotEmpty() }, "tardif=${if (tardive) "oui" else "non"}")
            .filterNotNull().joinToString(";")
}
