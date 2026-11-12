package fr.antireels

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Blocage d'Instagram, piloté par UN SEUL interrupteur (voir BlockSettings) :
 *
 *  1. ONGLET REELS (phase 2) : si l'onglet Reels de la barre du bas est sélectionné, on affiche
 *     « Reels bloqués » et on ramène Instagram à l'accueil.
 *  2. DÉFILEMENT (phase 4) : un reel ouvert de n'importe où (fil, message, profil, Explorer)
 *     peut être regardé, mais si l'utilisateur passe à un autre reel, on affiche
 *     « Défilement bloqué » et on ferme le reel (retour).
 *
 * Quand le blocage est désactivé (après le délai de 2 minutes), tout fonctionne normalement
 * pendant la durée choisie, puis le blocage se réactive tout seul.
 *
 * Rien n'est lu, enregistré ni envoyé : on ne consulte que des identifiants d'éléments,
 * l'état « sélectionné » d'un onglet et le nom du reel affiché (comparé en mémoire, jamais stocké).
 */
class ReelsAccessibilityService : AccessibilityService() {

    private enum class BlockKind(val title: String, val subtitle: String) {
        REELS_TAB("Reels bloqués", "L’onglet Reels est bloqué par Anti-Reels."),
        SCROLL("Défilement bloqué", "Tu peux regarder ce reel, mais pas passer au suivant.")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var diagnostics: DiagnosticLogger
    private lateinit var overlay: BlockOverlay
    private lateinit var settings: BlockSettings

    private var evalScheduled = false
    private var pollMisses = 0

    // Suivi du reel affiché (en mémoire uniquement)
    private var reelBaseline: String? = null
    private var reelCandidate: String? = null
    private var reelCandidateHits = 0

    private val evalRunnable = Runnable {
        evalScheduled = false
        evaluate()
    }

    /** Suit les changements d'état : notification de réactivation, minuteur de transition. */
    private val watchRunnable = Runnable { syncState() }

    // Gardée dans un champ : les préférences ne retiennent l'écouteur que faiblement.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { syncState() }
    }

    /** Tant que l'écran de blocage est affiché : le retire si on quitte Instagram. */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!overlay.isShowing) return
            if (isInstagramForeground()) pollMisses = 0 else pollMisses++
            if (pollMisses >= 2) {
                overlay.hide()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = BlockSettings(this)
        diagnostics = DiagnosticLogger(this)
        overlay = BlockOverlay(this, onOk = ::onOkPressed)
        settings.registerListener(settingsListener)
        DiagState.connected = true
        diagnostics.onServiceConnected()
        syncState(cleanup = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::overlay.isInitialized) return
        if (event.packageName?.toString() != DetectionConfig.INSTAGRAM_PACKAGE) return

        diagnostics.onEvent(event)

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> scheduleEvaluate(URGENT_DELAY_MS, urgent = true)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                resetReelTracking()
                scheduleEvaluate(URGENT_DELAY_MS, urgent = true)
            }
            // Défilement : on attend qu'il se calme (rebond du délai) avant de regarder.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scheduleEvaluate(SCROLL_DELAY_MS, urgent = true)
            // Changements de contenu (très fréquents) : on espace les vérifications.
            else -> scheduleEvaluate(CONTENT_DELAY_MS, urgent = false)
        }
    }

    override fun onInterrupt() {
        // Rien à faire.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DiagState.connected = false
        handler.removeCallbacksAndMessages(null)
        evalScheduled = false
        if (::settings.isInitialized) settings.unregisterListener(settingsListener)
        if (::overlay.isInitialized) overlay.hide()
        if (::diagnostics.isInitialized) diagnostics.release()
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------------
    // Vérification
    // ---------------------------------------------------------------------

    private fun scheduleEvaluate(delayMs: Long, urgent: Boolean) {
        if (evalScheduled) {
            if (!urgent) return
            handler.removeCallbacks(evalRunnable)
        }
        evalScheduled = true
        handler.postDelayed(evalRunnable, delayMs)
    }

    private fun evaluate() {
        if (overlay.isShowing) return

        val state = settings.state()
        ReenableNotifier.sync(this, state)
        if (state.phase == BlockSettings.Phase.DISABLED) {
            resetReelTracking()
            return
        }

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DetectionConfig.INSTAGRAM_PACKAGE) return

        if (DetectionConfig.isReelsTabSelected(root)) {
            resetReelTracking()
            block(BlockKind.REELS_TAB)
        } else if (hasScrolledToAnotherReel(root)) {
            resetReelTracking()
            block(BlockKind.SCROLL)
        }
    }

    /**
     * Vrai si, dans un lecteur de reels resté ouvert, le reel affiché n'est plus celui du début.
     * Le nouveau reel doit être observé deux fois de suite (anti-fausse alerte).
     */
    private fun hasScrolledToAnotherReel(root: AccessibilityNodeInfo): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        if (DetectionConfig.findVisibleReelViewer(root, screenHeight) == null) {
            resetReelTracking()
            return false
        }
        val key = DetectionConfig.currentReelKey(root) ?: return false

        val baseline = reelBaseline
        if (baseline == null) {
            reelBaseline = key
            return false
        }
        if (key == baseline) {
            reelCandidate = null
            reelCandidateHits = 0
            return false
        }
        if (key == reelCandidate) reelCandidateHits++ else {
            reelCandidate = key
            reelCandidateHits = 1
        }
        if (reelCandidateHits >= 2) return true

        scheduleEvaluate(CONFIRM_DELAY_MS, urgent = true)   // on revérifie très vite
        return false
    }

    private fun resetReelTracking() {
        reelBaseline = null
        reelCandidate = null
        reelCandidateHits = 0
    }

    // ---------------------------------------------------------------------
    // Blocage
    // ---------------------------------------------------------------------

    private fun block(kind: BlockKind) {
        overlay.show(kind.title, kind.subtitle)
        if (!overlay.isShowing) return
        when (kind) {
            // Derrière l'écran de blocage, on ramène Instagram à l'accueil (sinon le son continue).
            BlockKind.REELS_TAB -> redirectToHome()
            // On ferme le reel (retour), sinon le reel suivant resterait affiché et audible.
            BlockKind.SCROLL -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
        pollMisses = 0
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun onOkPressed() {
        overlay.hide()
        handler.removeCallbacks(pollRunnable)
        val root = rootInActiveWindow
        if (root != null &&
            root.packageName?.toString() == DetectionConfig.INSTAGRAM_PACKAGE &&
            DetectionConfig.isReelsTabSelected(root)
        ) {
            redirectToHome()
        }
        scheduleEvaluate(RECHECK_DELAY_MS, urgent = true)
    }

    /** Appuie sur l'onglet Accueil ; à défaut, simule le bouton « Retour ». */
    private fun redirectToHome() {
        val home = rootInActiveWindow?.let { DetectionConfig.findHomeTab(it) }
        val clicked = home != null && clickNodeOrParent(home)
        if (!clicked) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var level = 0
        while (current != null && level < 4) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
            level++
        }
        return false
    }

    private fun isInstagramForeground(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString()
        return pkg == DetectionConfig.INSTAGRAM_PACKAGE || pkg == packageName
    }

    // ---------------------------------------------------------------------
    // Suivi du cycle de désactivation (notification de réactivation)
    // ---------------------------------------------------------------------

    private fun syncState(cleanup: Boolean = false) {
        val state = settings.state()
        ReenableNotifier.sync(this, state, cleanup)
        handler.removeCallbacks(watchRunnable)
        if (state.phase != BlockSettings.Phase.ACTIVE) {
            handler.postDelayed(watchRunnable, state.remainingMs + 300L)
        }
    }

    private companion object {
        const val URGENT_DELAY_MS = 60L
        const val SCROLL_DELAY_MS = 250L
        const val CONFIRM_DELAY_MS = 300L
        const val CONTENT_DELAY_MS = 400L
        const val RECHECK_DELAY_MS = 700L
        const val POLL_MS = 500L
    }
}
