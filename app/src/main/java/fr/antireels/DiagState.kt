package fr.antireels

/**
 * État partagé en mémoire entre l'écran de l'app et le service (même processus).
 * Rien n'est écrit sur le disque.
 */
object DiagState {
    /** Étiquette de la situation en cours (écrite dans chaque « dump » du journal). */
    @Volatile
    var label: String = "aucune"

    /** Vrai quand le service d'accessibilité est réellement connecté. */
    @Volatile
    var connected: Boolean = false
}
