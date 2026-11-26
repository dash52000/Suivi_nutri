package io.github.dash52000.suivinutri.repas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

/** `repas.json` vient d'une version de l'appli plus récente que celle-ci : on ne le lit pas (et on ne l'écrase pas). */
class VersionInconnue(val version: Int) : Exception("repas.json en version $version : plus récent que cette appli.")

/** `repas.json` n'est pas du JSON valide ou n'a pas la forme attendue. Le fichier n'est pas modifié. */
class FormatIllisible(cause: Throwable) : Exception("repas.json illisible : ${cause.message}", cause)

/** Réglages du tirage : graine fixe (créée une fois), table des π en vigueur, jour du dernier recalcul de Neyman. */
data class ReglagesTirage(
    val graine: Long? = null,
    val probas: Map<Strate, Double>? = null,
    val dernierRecalcul: LocalDate? = null,
)

data class ContenuRepas(
    val reglages: ReglagesTirage = ReglagesTirage(),
    val phrases: List<PhraseRepas> = emptyList(),
    val tirages: List<TirageMoment> = emptyList(),
    val extractions: List<Extraction> = emptyList(),
)

/**
 * Stockage des phrases, tirages et extractions dans `repas.json` (architecture section 3).
 *
 * Comme `Journal` : petit volume (~1 500 phrases par an), fichier lu et écrit en entier, écriture atomique (fichier
 * temporaire synchronisé puis renommage). Reçoit un [File], pas le `Context` : testable sans Android.
 *
 * - Clés naturelles : phrase = id (UUID) ; tirage = (jour, moment) ; extraction = (phraseId, versionPrompt,
 *   versionCiqual). Écrire une clé existante la remplace.
 * - Plusieurs extractions d'une même phrase peuvent coexister (prompt ou table CIQUAL changés) : la plus récente fait foi.
 * - Aliments et boissons ne sont jamais stockés à part : ils se relisent depuis `jsonBrut` + CIQUAL.
 * - Le fichier porte `version` (absent = 1). Une version plus récente lève [VersionInconnue] sans rien écrire.
 * - Les clés API n'ont aucun lien avec ce fichier : elles restent dans `CoffreCles`.
 * - Sortie déterministe (listes triées) : deux sauvegardes du même contenu sont identiques octet pour octet.
 */
class StockRepas(private val fichier: File) {

    // ---------- Lecture ----------

    @Synchronized
    fun contenu(): ContenuRepas {
        if (!fichier.exists()) return ContenuRepas()
        return lire(fichier.readText(Charsets.UTF_8))
    }

    fun reglages(): ReglagesTirage = contenu().reglages

    fun phrase(id: String): PhraseRepas? = contenu().phrases.firstOrNull { it.id == id }

    fun phrasesDuJour(jour: LocalDate): List<PhraseRepas> =
        contenu().phrases.filter { it.jour == jour }.sortedWith(compareBy({ it.moment.ordinal }, { it.horodatage }))

    fun tirage(jour: LocalDate, moment: Moment): TirageMoment? =
        contenu().tirages.firstOrNull { it.jour == jour && it.moment == moment }

    /** Premier jour où un moment a été soumis au tirage (départ du compte de 4 semaines avant le premier recalcul). */
    fun premierTirage(): LocalDate? = contenu().tirages.minOfOrNull { it.jour }

    /** Extraction qui fait foi pour une phrase : la plus récente. Null = pas encore lue. */
    fun extractionRetenue(phraseId: String): Extraction? =
        contenu().extractions.filter { it.phraseId == phraseId }.maxByOrNull { it.horodatage }

    /** Phrases sans aucune extraction : relues à l'ouverture suivante (hors ligne, chaîne en échec, appli fermée). */
    fun phrasesEnAttente(): List<PhraseRepas> {
        val c = contenu()
        val lues = c.extractions.map { it.phraseId }.toSet()
        return c.phrases.filter { it.id !in lues }.sortedWith(compareBy({ it.jour }, { it.moment.ordinal }, { it.horodatage }))
    }

    /** Table des π en vigueur (valeurs de départ si jamais recalculée). */
    fun probas(): ProbasTirage = ProbasTirage(reglages().probas ?: emptyMap())

    // ---------- Écriture ----------

    @Synchronized
    fun ajouterPhrase(p: PhraseRepas) {
        val c = contenu()
        ecrire(c.copy(phrases = c.phrases.filter { it.id != p.id } + p))
    }

    @Synchronized
    fun enregistrerTirage(t: TirageMoment) {
        val c = contenu()
        ecrire(c.copy(tirages = c.tirages.filter { !(it.jour == t.jour && it.moment == t.moment) } + t))
    }

    @Synchronized
    fun enregistrerExtraction(e: Extraction) {
        val c = contenu()
        ecrire(c.copy(extractions = c.extractions.filter { cle(it) != cle(e) } + e))
    }

    /** Graine fixe du tirage : créée au premier appel avec [generer], puis toujours la même (et sauvegardée). */
    @Synchronized
    fun graine(generer: () -> Long = { SecureRandom().nextLong() }): Long {
        val c = contenu()
        c.reglages.graine?.let { return it }
        val g = generer()
        ecrire(c.copy(reglages = c.reglages.copy(graine = g)))
        return g
    }

    /** Nouvelle table de π après un recalcul de Neyman effectué le [recalculLe]. */
    @Synchronized
    fun enregistrerProbas(probas: ProbasTirage, recalculLe: LocalDate) {
        val c = contenu()
        ecrire(c.copy(reglages = c.reglages.copy(probas = probas.table(), dernierRecalcul = recalculLe)))
    }

    // ---------- Sauvegarde ----------

    fun exportJson(): String = texte(contenu())

    /**
     * Fusionne une sauvegarde : pour une même clé, la sauvegarde l'emporte ; ses réglages aussi. Renvoie le nombre de
     * phrases importées. Un texte illisible ou d'une version inconnue lève une exception **avant** toute écriture.
     */
    @Synchronized
    fun importJson(texte: String): Int {
        val importe = lire(texte)
        val actuel = contenu()
        val fusion = ContenuRepas(
            reglages = ReglagesTirage(
                graine = importe.reglages.graine ?: actuel.reglages.graine,
                probas = importe.reglages.probas ?: actuel.reglages.probas,
                dernierRecalcul = importe.reglages.dernierRecalcul ?: actuel.reglages.dernierRecalcul,
            ),
            phrases = (actuel.phrases.associateBy { it.id } + importe.phrases.associateBy { it.id }).values.toList(),
            tirages = (actuel.tirages.associateBy { it.jour to it.moment } + importe.tirages.associateBy { it.jour to it.moment }).values.toList(),
            extractions = (actuel.extractions.associateBy { cle(it) } + importe.extractions.associateBy { cle(it) }).values.toList(),
        )
        ecrire(fusion)
        return importe.phrases.size
    }

    // ---------- Fichier ----------

    private fun cle(e: Extraction) = Triple(e.phraseId, e.versionPrompt, e.versionCiqual)

    private fun ecrire(c: ContenuRepas) {
        val dossier = fichier.absoluteFile.parentFile
        dossier?.mkdirs()
        val tmp = File(dossier, fichier.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(texte(c).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), fichier.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), fichier.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---------- Format JSON ----------

    private companion object {
        const val VERSION = 1
        val joli = Json { prettyPrint = true }

        fun texte(c: ContenuRepas): String = joli.encodeToString(JsonObject.serializer(), versJson(c)) + "\n"

        fun lire(texte: String): ContenuRepas {
            try {
                val racine = Json.parseToJsonElement(texte).jsonObject
                // Un lecteur par version ; l'écriture se fait toujours dans la dernière. Absent = 1.
                return when (val version = racine["version"]?.jsonPrimitive?.intOrNull ?: 1) {
                    1 -> lireV1(racine)
                    else -> throw VersionInconnue(version)
                }
            } catch (e: VersionInconnue) {
                throw e
            } catch (e: Exception) {
                throw FormatIllisible(e)
            }
        }

        fun versJson(c: ContenuRepas): JsonObject = buildJsonObject {
            put("version", VERSION)
            putJsonObject("reglages") {
                c.reglages.graine?.let { put("graine", it) }
                c.reglages.probas?.let { table ->
                    putJsonObject("probas") { table.entries.sortedBy { it.key.code }.forEach { (s, p) -> put(s.code, p) } }
                }
                c.reglages.dernierRecalcul?.let { put("dernier_recalcul", it.toString()) }
            }
            putJsonArray("phrases") {
                c.phrases.sortedWith(compareBy({ it.jour }, { it.moment.ordinal }, { it.horodatage }, { it.id })).forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id)
                        put("jour", p.jour.toString())
                        put("moment", p.moment.code)
                        put("texte", p.texte)
                        put("origine", p.origine.code)
                        put("tardive", p.tardive)
                        put("horodatage", p.horodatage.toString())
                    })
                }
            }
            putJsonArray("tirages") {
                c.tirages.sortedWith(compareBy({ it.jour }, { it.moment.ordinal })).forEach { t ->
                    add(buildJsonObject {
                        put("jour", t.jour.toString())
                        put("moment", t.moment.code)
                        put("strate", t.strate.code)
                        put("pi", t.pi)
                        put("graine", t.graine)
                        put("reponse", t.reponse?.code)
                        put("tardive", t.tardive)
                    })
                }
            }
            putJsonArray("extractions") {
                c.extractions.sortedWith(compareBy({ it.horodatage }, { it.phraseId }, { it.versionPrompt }, { it.versionCiqual })).forEach { e ->
                    add(buildJsonObject {
                        put("phrase_id", e.phraseId)
                        put("json_brut", e.jsonBrut)
                        put("modele", e.modele)
                        put("version_prompt", e.versionPrompt)
                        put("version_ciqual", e.versionCiqual)
                        putJsonArray("erreurs") { e.erreurs.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("bascules") { e.bascules.forEach { add(JsonPrimitive(it)) } }
                        put("horodatage", e.horodatage.toString())
                    })
                }
            }
        }

        fun lireV1(o: JsonObject): ContenuRepas {
            val r = o["reglages"]?.jsonObject
            val reglages = ReglagesTirage(
                graine = r?.get("graine")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
                probas = r?.get("probas")?.takeIf { it !is JsonNull }?.jsonObject
                    ?.entries?.associate { (code, v) -> Strate.depuisCode(code) to v.jsonPrimitive.double },
                dernierRecalcul = r?.get("dernier_recalcul")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let(LocalDate::parse),
            )
            fun JsonObject.texte(cle: String) = getValue(cle).jsonPrimitive.content
            val phrases = (o["phrases"]?.jsonArray ?: emptyList()).map { it.jsonObject }.map { p ->
                PhraseRepas(
                    id = p.texte("id"),
                    jour = LocalDate.parse(p.texte("jour")),
                    moment = Moment.depuisCode(p.texte("moment")),
                    texte = p.texte("texte"),
                    origine = Origine.depuisCode(p.texte("origine")),
                    tardive = p.getValue("tardive").jsonPrimitive.boolean,
                    horodatage = Instant.parse(p.texte("horodatage")),
                )
            }
            val tirages = (o["tirages"]?.jsonArray ?: emptyList()).map { it.jsonObject }.map { t ->
                val moment = Moment.depuisCode(t.texte("moment"))
                TirageMoment(
                    jour = LocalDate.parse(t.texte("jour")),
                    moment = moment,
                    strate = Strate.depuisCode(t.texte("strate")),
                    pi = t.getValue("pi").jsonPrimitive.double,
                    graine = t.getValue("graine").jsonPrimitive.long,
                    reponse = t["reponse"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let(Reponse::depuisCode),
                    tardive = t.getValue("tardive").jsonPrimitive.boolean,
                )
            }
            val extractions = (o["extractions"]?.jsonArray ?: emptyList()).map { it.jsonObject }.map { e ->
                Extraction(
                    phraseId = e.texte("phrase_id"),
                    jsonBrut = e.texte("json_brut"),
                    modele = e.texte("modele"),
                    versionPrompt = e.getValue("version_prompt").jsonPrimitive.int,
                    versionCiqual = e.texte("version_ciqual"),
                    erreurs = e.getValue("erreurs").jsonArray.map { it.jsonPrimitive.content },
                    bascules = e.getValue("bascules").jsonArray.map { it.jsonPrimitive.content },
                    horodatage = Instant.parse(e.texte("horodatage")),
                )
            }
            return ContenuRepas(reglages, phrases, tirages, extractions)
        }
    }
}
