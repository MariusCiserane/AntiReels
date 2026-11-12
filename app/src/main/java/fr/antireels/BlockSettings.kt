package fr.antireels

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/**
 * État du blocage (onglet Reels + défilement des reels), stocké localement
 * (SharedPreferences privées, pas de sauvegarde cloud).
 *
 * Cycle de désactivation, entièrement calculé à partir de l'HEURE DE DÉBUT enregistrée
 * (donc il survit à la fermeture de l'app, sans minuteur en mémoire) :
 *
 *   ACTIVE ──(l'utilisateur confirme)──▶ PENDING_DISABLE ──(2 min)──▶ DISABLED ──(N min)──▶ ACTIVE
 *
 *  - ACTIVE          : le blocage s'applique.
 *  - PENDING_DISABLE : délai de 2 minutes ; le blocage s'applique encore ; annulable.
 *  - DISABLED        : l'app se comporte normalement pendant N minutes (5, 10, 15, 20 ou 30),
 *                      puis le blocage se réactive tout seul.
 *
 * Réactiver à la main est immédiat. Pour résister à un changement de l'heure du téléphone,
 * on utilise l'horloge « depuis le démarrage » (elapsedRealtime) ; après un redémarrage du
 * téléphone, on se rabat sur l'heure réelle (un horloge reculée ne peut que rallonger le blocage).
 */
class BlockSettings(context: Context) {

    enum class Phase { ACTIVE, PENDING_DISABLE, DISABLED }

    /**
     * @property remainingMs temps restant avant la prochaine transition de phase
     * @property cycleId identifiant du cycle en cours (0 si ACTIVE)
     * @property offMs durée de désactivation prévue pour ce cycle
     */
    data class State(val phase: Phase, val remainingMs: Long, val cycleId: Long, val offMs: Long)

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // Nettoyage des clés de la version précédente.
        if (prefs.contains("enabled") || prefs.contains("disable_end_wall")) {
            prefs.edit()
                .remove("enabled").remove("disable_end_wall")
                .remove("disable_end_elapsed").remove("disable_boot_count")
                .apply()
        }
    }

    /** Phase actuelle ; applique au passage les transitions arrivées à échéance. */
    fun state(): State {
        val start = prefs.getLong(KEY_START_WALL, 0L)
        if (start == 0L) return State(Phase.ACTIVE, 0L, 0L, 0L)

        val delay = prefs.getLong(KEY_DELAY_MS, DISABLE_DELAY_MS)
        val off = prefs.getLong(KEY_OFF_MS, DEFAULT_OFF_MINUTES * 60_000L)
        val since = sinceStart()
        return when {
            since < delay -> State(Phase.PENDING_DISABLE, delay - since, start, off)
            since < delay + off -> State(Phase.DISABLED, delay + off - since, start, off)
            else -> {
                clearCycle()
                State(Phase.ACTIVE, 0L, 0L, 0L)
            }
        }
    }

    /** Vrai si le blocage doit s'appliquer maintenant (ACTIVE ou PENDING_DISABLE). */
    fun isBlockingActive(): Boolean = state().phase != Phase.DISABLED

    /** Durée de désactivation choisie (minutes), parmi OFF_CHOICES_MINUTES. */
    var offDurationMinutes: Int
        get() {
            val value = prefs.getInt(KEY_OFF_MINUTES, DEFAULT_OFF_MINUTES)
            return if (value in OFF_CHOICES_MINUTES) value else DEFAULT_OFF_MINUTES
        }
        set(value) {
            if (value in OFF_CHOICES_MINUTES) prefs.edit().putInt(KEY_OFF_MINUTES, value).apply()
        }

    /** Lance le délai de 2 minutes (seulement si le blocage est actif et sans désactivation en cours). */
    fun requestDisable() {
        if (state().phase != Phase.ACTIVE) return
        prefs.edit()
            .putLong(KEY_START_WALL, System.currentTimeMillis())
            .putLong(KEY_START_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_BOOT_COUNT, bootCount())
            .putLong(KEY_DELAY_MS, DISABLE_DELAY_MS)
            .putLong(KEY_OFF_MS, offDurationMinutes * 60_000L)
            .apply()
    }

    /** Annule une désactivation en attente (pendant le délai de 2 minutes). */
    fun cancelDisable() {
        if (state().phase == Phase.PENDING_DISABLE) clearCycle()
    }

    /** Réactive le blocage tout de suite (depuis n'importe quelle phase). */
    fun enable() {
        clearCycle()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ---------------------------------------------------------------------

    private fun sinceStart(): Long {
        val sameBoot = prefs.getInt(KEY_BOOT_COUNT, -2) == bootCount()
        val raw = if (sameBoot) {
            SystemClock.elapsedRealtime() - prefs.getLong(KEY_START_ELAPSED, 0L)
        } else {
            System.currentTimeMillis() - prefs.getLong(KEY_START_WALL, 0L)
        }
        return raw.coerceAtLeast(0L)
    }

    private fun clearCycle() {
        prefs.edit()
            .remove(KEY_START_WALL).remove(KEY_START_ELAPSED).remove(KEY_BOOT_COUNT)
            .remove(KEY_DELAY_MS).remove(KEY_OFF_MS)
            .apply()
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        /** Délai imposé avant qu'une désactivation prenne effet : 2 minutes. */
        const val DISABLE_DELAY_MS = 2 * 60 * 1000L

        /** Durées de désactivation proposées (minutes). */
        val OFF_CHOICES_MINUTES = listOf(5, 10, 15, 20, 30)
        const val DEFAULT_OFF_MINUTES = 10

        private const val PREFS = "anti_reels_block"
        private const val KEY_START_WALL = "cycle_start_wall"
        private const val KEY_START_ELAPSED = "cycle_start_elapsed"
        private const val KEY_BOOT_COUNT = "cycle_boot_count"
        private const val KEY_DELAY_MS = "cycle_delay_ms"
        private const val KEY_OFF_MS = "cycle_off_ms"
        private const val KEY_OFF_MINUTES = "off_duration_minutes"
    }
}
