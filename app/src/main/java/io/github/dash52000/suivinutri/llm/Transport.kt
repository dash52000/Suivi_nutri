package io.github.dash52000.suivinutri.llm

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/** Requête HTTP prête à partir. Les en-têtes peuvent contenir une clé : jamais journalisés, jamais affichés. */
class RequeteHttp(val url: String, val entetes: Map<String, String>, val corps: String) {
    override fun toString(): String = "RequeteHttp($url)" // pas d'en-têtes : ils portent la clé
}

data class ReponseHttp(val code: Int, val corps: String)

/** Délai de connexion ou de réponse dépassé : un nouvel essai, comme un 503. */
class DelaiDepasse(message: String) : IOException(message)

/** Pas de réseau (DNS, connexion refusée) : inutile de continuer la chaîne, la phrase reste en attente. */
class HorsLigne(message: String) : IOException(message)

/**
 * Envoie une requête et rend la réponse, quel que soit son code HTTP. Lève [DelaiDepasse], [HorsLigne] ou une autre
 * [IOException]. Bloquant : appelé hors du fil principal. Les tests branchent des réponses figées.
 */
fun interface Transport {
    fun envoyer(requete: RequeteHttp, delaiConnexionMs: Long, delaiReponseMs: Long): ReponseHttp
}

/** Transport réel, en Java pur (aucune dépendance). */
object HttpTransport : Transport {

    override fun envoyer(requete: RequeteHttp, delaiConnexionMs: Long, delaiReponseMs: Long): ReponseHttp {
        val connexion = URI(requete.url).toURL().openConnection() as HttpURLConnection
        try {
            connexion.requestMethod = "POST"
            connexion.connectTimeout = delaiConnexionMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            connexion.readTimeout = delaiReponseMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            connexion.doOutput = true
            connexion.useCaches = false
            connexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connexion.setRequestProperty("Accept", "application/json")
            requete.entetes.forEach { (nom, valeur) -> connexion.setRequestProperty(nom, valeur) }
            connexion.outputStream.use { it.write(requete.corps.toByteArray(Charsets.UTF_8)) }
            val code = connexion.responseCode
            val flux = if (code >= 400) connexion.errorStream else connexion.inputStream
            val corps = flux?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return ReponseHttp(code, corps)
        } catch (e: SocketTimeoutException) {
            throw DelaiDepasse(e.message ?: "délai dépassé")
        } catch (e: UnknownHostException) {
            throw HorsLigne("hôte inconnu")
        } catch (e: ConnectException) {
            throw HorsLigne("connexion refusée")
        } catch (e: NoRouteToHostException) {
            throw HorsLigne("pas de route")
        } finally {
            connexion.disconnect()
        }
    }
}
