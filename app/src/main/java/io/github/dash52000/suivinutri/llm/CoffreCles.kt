package io.github.dash52000.suivinutri.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Coffre des clés API (architecture section 5) : une clé AES-GCM générée dans l'Android Keystore (non exportable)
 * chiffre chaque clé API ; texte chiffré et IV sont dans le fichier de réglages [FICHIER].
 *
 * - Pas de déverrouillage biométrique (zéro friction, décision du 26/11).
 * - [FICHIER] est exclu de la sauvegarde Android (`res/xml/regles_*.xml`) ; la sauvegarde JSON et l'export n'y ont
 *   aucun accès. Après un changement de téléphone, les clés se ressaisissent une fois (accepté par Damien).
 * - Une clé illisible (Keystore réinitialisé, fichier abîmé) est effacée et traitée comme absente.
 * - Jamais de journalisation d'une clé : seuls « enregistrée » ou « effacée » vont dans le journal d'usage.
 */
class CoffreCles(context: Context) : SourceCles {

    private val prefs = context.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

    override fun cle(fournisseur: Fournisseur): String? = lire(fournisseur)

    fun presente(fournisseur: Fournisseur): Boolean = lire(fournisseur) != null

    fun aucune(): Boolean = Fournisseur.entries.none { presente(it) }

    /** Les 4 derniers caractères, pour « clé enregistrée · •••• 1234 ». */
    fun fin(fournisseur: Fournisseur): String? = lire(fournisseur)?.takeLast(4)

    /** Chiffre et enregistre. Rend false si le Keystore refuse (la clé n'est alors pas enregistrée). */
    fun enregistrer(fournisseur: Fournisseur, cleApi: String): Boolean {
        val propre = cleApi.trim()
        if (propre.isEmpty()) return false
        return try {
            val chiffreur = Cipher.getInstance(TRANSFORMATION)
            chiffreur.init(Cipher.ENCRYPT_MODE, cleAes())
            val chiffre = chiffreur.doFinal(propre.toByteArray(Charsets.UTF_8))
            val valeur = b64(chiffreur.iv) + SEPARATEUR + b64(chiffre)
            prefs.edit().putString(nom(fournisseur), valeur).commit()
        } catch (e: Exception) {
            false
        }
    }

    fun effacer(fournisseur: Fournisseur) {
        prefs.edit().remove(nom(fournisseur)).commit()
    }

    private fun lire(fournisseur: Fournisseur): String? {
        val valeur = prefs.getString(nom(fournisseur), null) ?: return null
        return try {
            val (iv, chiffre) = valeur.split(SEPARATEUR).also { require(it.size == 2) }.map { Base64.decode(it, Base64.NO_WRAP) }
            val dechiffreur = Cipher.getInstance(TRANSFORMATION)
            dechiffreur.init(Cipher.DECRYPT_MODE, cleAes(), GCMParameterSpec(128, iv))
            String(dechiffreur.doFinal(chiffre), Charsets.UTF_8).ifEmpty { null }
        } catch (e: Exception) {
            // Clé du Keystore perdue ou valeur abîmée : illisible pour toujours, on l'efface (à ressaisir).
            effacer(fournisseur)
            null
        }
    }

    private fun cleAes(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generateur.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generateur.generateKey()
    }

    private fun nom(fournisseur: Fournisseur) = fournisseur.name.lowercase()

    private fun b64(octets: ByteArray) = Base64.encodeToString(octets, Base64.NO_WRAP)

    companion object {
        /** Nom du fichier de réglages : `shared_prefs/cles_llm.xml`, exclu de la sauvegarde Android. */
        const val FICHIER = "cles_llm"
        private const val ALIAS = "suivinutri_cles_llm"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEPARATEUR = ":"
    }
}

/** Chaîne de modèles choisie dans Réglages (pas un secret : elle suit la sauvegarde Android comme le reste). */
object ChaineReglee {

    private const val FICHIER = "llm"
    private const val CLE = "chaine"

    fun lire(context: Context): List<Maillon> =
        context.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).getString(CLE, null)
            ?.let { Fournisseurs.chaineDepuisTexte(it) }
            ?.ifEmpty { null }
            ?: Fournisseurs.CHAINE_DEFAUT

    /** Enregistre la chaîne saisie ; vide ou illisible = chaîne de départ. Rend la chaîne retenue. */
    fun regler(context: Context, texte: String): List<Maillon> {
        val chaine = Fournisseurs.chaineDepuisTexte(texte)
        val prefs = context.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).edit()
        if (chaine.isEmpty() || chaine == Fournisseurs.CHAINE_DEFAUT) prefs.remove(CLE) else prefs.putString(CLE, Fournisseurs.chaineVersTexte(chaine))
        prefs.apply()
        return chaine.ifEmpty { Fournisseurs.CHAINE_DEFAUT }
    }
}
