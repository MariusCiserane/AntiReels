package fr.antireels

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * OUTIL DE DIAGNOSTIC (développement uniquement) : écrit dans Logcat (tag "AntiReelsDiag")
 * la structure de l'écran d'Instagram et les clics. Sert à corriger DetectionConfig quand
 * Instagram change son interface.
 *
 * Garde-fous : ne fait RIEN si l'app n'est pas compilée en mode débogage ; n'écrit jamais
 * les textes (seulement leur longueur) ; ne stocke rien ; ne peut rien envoyer (pas d'Internet).
 */
class DiagnosticLogger(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val enabled = (service.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var dumpPending = false
    private var lastSignature = 0
    private var dumpCount = 0
    private var lastScrollLogAt = 0L

    fun onServiceConnected() {
        if (enabled) emit("=== SERVICE CONNECTÉ (diagnostic actif) ===")
    }

    fun onEvent(event: AccessibilityEvent) {
        if (!enabled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> logNodeChain("CLIC", event.source)
            AccessibilityEvent.TYPE_VIEW_SELECTED -> logNodeChain("SÉLECTION", event.source)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                emit("--- FENÊTRE (étiquette=${DiagState.label}) classe=${event.className}")
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> logScroll(event)
        }
        scheduleDump()
    }

    /** Défilements : au plus un toutes les 300 ms pour ne pas noyer le journal. */
    private fun logScroll(event: AccessibilityEvent) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScrollLogAt < 300L) return
        lastScrollLogAt = now
        val id = event.source?.viewIdResourceName
            ?.removePrefix("${DetectionConfig.INSTAGRAM_PACKAGE}:id/") ?: "-"
        emit(
            "--- DÉFILEMENT (étiquette=${DiagState.label}) classe=${event.className} id=$id " +
                "de=${event.fromIndex} à=${event.toIndex} total=${event.itemCount} " +
                "scrollY=${event.scrollY}/${event.maxScrollY}"
        )
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        dumpPending = false
    }

    // ---------------------------------------------------------------------

    private fun scheduleDump() {
        if (dumpPending) return
        dumpPending = true
        handler.postDelayed({
            dumpPending = false
            dumpTree()
        }, DUMP_DELAY_MS)
    }

    private fun dumpTree() {
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != DetectionConfig.INSTAGRAM_PACKAGE) return

        val w = Walk()
        visit(root, 0, w)

        val signature = w.signature.toString().hashCode()
        if (signature == lastSignature) return
        lastSignature = signature
        dumpCount++

        val dm = service.resources.displayMetrics
        val header = "=== DUMP #$dumpCount | étiquette=${DiagState.label} | " +
            "fenêtre=${root.className} | écran=${dm.widthPixels}x${dm.heightPixels} | " +
            "éléments=${w.visited} notés=${w.logged} ==="
        emit(header + "\n" + w.out.toString().trimEnd())
    }

    private class Walk {
        val out = StringBuilder()
        val signature = StringBuilder()
        var visited = 0
        var logged = 0
    }

    private fun visit(node: AccessibilityNodeInfo, depth: Int, w: Walk) {
        if (w.visited >= MAX_NODES || depth > MAX_DEPTH) return
        w.visited++

        if (shouldLog(node)) {
            w.logged++
            w.out.append('d').append(depth).append(' ').append(describe(node)).append('\n')
            w.signature
                .append(node.className).append('|')
                .append(node.viewIdResourceName).append('|')
                .append(node.isSelected).append(node.isChecked).append(node.isVisibleToUser)
                .append(';')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            visit(child, depth + 1, w)
        }
    }

    private fun shouldLog(n: AccessibilityNodeInfo): Boolean =
        n.viewIdResourceName != null ||
            !n.contentDescription.isNullOrEmpty() ||
            n.isSelected || n.isChecked || n.isClickable ||
            n.stateDescription != null

    private fun describe(n: AccessibilityNodeInfo): String {
        val bounds = Rect()
        n.getBoundsInScreen(bounds)
        val id = n.viewIdResourceName?.removePrefix("${DetectionConfig.INSTAGRAM_PACKAGE}:id/") ?: "-"
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        val desc = n.contentDescription?.toString()?.replace('\n', ' ')?.let {
            if (it.length > DESC_MAX) it.take(DESC_MAX) + "…(${it.length} car.)" else it
        }
        val state = n.stateDescription?.toString()?.replace('\n', ' ')?.take(30)
        val textLength = n.text?.length ?: 0

        return buildString {
            append(cls).append(" id=").append(id)
            if (desc != null) append(" desc=\"").append(desc).append('"')
            if (state != null) append(" etat=\"").append(state).append('"')
            if (textLength > 0) append(" texte=").append(textLength).append("car.")
            if (n.isSelected) append(" SELECTED")
            if (n.isChecked) append(" CHECKED")
            if (n.isClickable) append(" clickable")
            if (!n.isVisibleToUser) append(" HIDDEN")
            append(" [").append(bounds.left).append(',').append(bounds.top).append(',')
                .append(bounds.right).append(',').append(bounds.bottom).append(']')
        }
    }

    private fun logNodeChain(kind: String, source: AccessibilityNodeInfo?) {
        if (source == null) {
            emit("--- $kind sans source (étiquette=${DiagState.label})")
            return
        }
        val sb = StringBuilder("--- $kind (étiquette=${DiagState.label}) : élément touché puis ses parents\n")
        var current: AccessibilityNodeInfo? = source
        var level = 0
        while (current != null && level < 6) {
            sb.append("  ^").append(level).append(' ').append(describe(current)).append('\n')
            current = current.parent
            level++
        }
        emit(sb.toString().trimEnd())
    }

    private fun emit(text: String) {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + CHUNK, text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline > start) end = newline
            }
            Log.i(TAG, text.substring(start, end))
            start = if (end < text.length && text[end] == '\n') end + 1 else end
        }
    }

    private companion object {
        const val TAG = "AntiReelsDiag"
        const val DUMP_DELAY_MS = 800L
        const val MAX_NODES = 700
        const val MAX_DEPTH = 40
        const val DESC_MAX = 50
        const val CHUNK = 3500
    }
}
