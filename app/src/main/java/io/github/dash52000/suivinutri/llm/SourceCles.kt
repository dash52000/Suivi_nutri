package io.github.dash52000.suivinutri.llm

/**
 * Accès aux clés API par fournisseur. Implémentée par `CoffreCles` (Android Keystore) dans l'appli, par une table en
 * mémoire dans les tests. Une clé absente = ce fournisseur est sauté.
 */
fun interface SourceCles {
    fun cle(fournisseur: Fournisseur): String?
}
