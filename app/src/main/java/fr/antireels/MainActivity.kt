package fr.antireels

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Écran de l'app : état du service, interrupteur UNIQUE du blocage (onglet Reels + défilement),
 * durée de désactivation, raccourcis, et diagnostic (version de développement seulement).
 */
class MainActivity : Activity() {

    private lateinit var settings: BlockSettings
    private lateinit var statusView: TextView
    private lateinit var blockSwitch: Switch
    private lateinit var infoView: TextView
    private lateinit var cancelButton: Button
    private lateinit var durationSpinner: Spinner
    private var labelView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = Runnable { refresh() }
    private var updatingUi = false
    private var warnedCycle = 0L

    private val debuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BlockSettings(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        column.addView(text("Anti-Reels", 24f, bold = true))
        statusView = text("", 15f)
        column.addView(statusView)

        // --- Interrupteur unique : onglet Reels + défilement ---
        column.addView(heading("Blocage"))
        column.addView(text("Bloque l’onglet Reels et le défilement entre les reels.", 14f))
        blockSwitch = Switch(this).apply {
            textSize = 17f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> onSwitchToggled(checked) }
        }
        column.addView(blockSwitch)
        infoView = text("", 15f).apply { visibility = View.GONE }
        column.addView(infoView)
        cancelButton = button("Annuler la désactivation") {
            settings.cancelDisable()
            refresh()
        }.apply { visibility = View.GONE }
        column.addView(cancelButton)

        // --- Durée de désactivation ---
        column.addView(heading("Durée de désactivation"))
        column.addView(
            text(
                "Une fois le blocage désactivé (après le délai de 2 minutes), il se réactive " +
                    "tout seul au bout de :",
                14f
            )
        )
        durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BlockSettings.OFF_CHOICES_MINUTES.map { "$it minutes" }
            )
            setSelection(BlockSettings.OFF_CHOICES_MINUTES.indexOf(settings.offDurationMinutes))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val minutes = BlockSettings.OFF_CHOICES_MINUTES[position]
                    if (minutes != settings.offDurationMinutes) {
                        settings.offDurationMinutes = minutes
                        refresh()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        column.addView(durationSpinner)

        // --- Service d'accessibilité ---
        column.addView(heading("Service d’accessibilité"))
        column.addView(button("Ouvrir les réglages d’accessibilité") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(button("Infos de l’application (si le service est grisé)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        column.addView(button("Ouvrir Instagram") { openInstagram() })

        if (debuggable) {
            column.addView(heading("Diagnostic (développement)"))
            labelView = text("", 15f).also { column.addView(it) }
            SITUATIONS.forEach { (name, title) ->
                column.addView(button(title) {
                    DiagState.label = name
                    refresh()
                })
            }
            column.addView(
                text("Journaux : Logcat, filtre  tag:AntiReelsDiag  (les textes ne sont pas enregistrés).", 13f)
            )
        }

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------------

    private fun onSwitchToggled(checked: Boolean) {
        if (updatingUi) return

        if (checked) {
            // Réactiver est immédiat (depuis n'importe quelle phase).
            settings.enable()
            refresh()
            return
        }

        // L'utilisateur veut désactiver : le blocage reste actif tant que le délai n'est pas écoulé.
        setSwitch(true)
        if (settings.state().phase != BlockSettings.Phase.ACTIVE) {
            Toast.makeText(this, "Une désactivation est déjà en cours.", Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = settings.offDurationMinutes
        AlertDialog.Builder(this)
            .setTitle("Désactiver le blocage ?")
            .setMessage(
                "Le blocage restera actif pendant 2 minutes, puis sera désactivé pendant " +
                    "$minutes minutes avant de se réactiver automatiquement. " +
                    "Tu pourras annuler pendant le délai de 2 minutes."
            )
            .setPositiveButton("Désactiver dans 2 min") { _, _ ->
                requestNotificationPermissionIfNeeded()
                settings.requestDisable()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Pour la notification de compte à rebours. Sans autorisation, tout le reste fonctionne. */
    private fun requestNotificationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        handler.removeCallbacks(ticker)

        val state = settings.state()
        ReenableNotifier.sync(this, state)
        setSwitch(state.phase != BlockSettings.Phase.DISABLED)

        when (state.phase) {
            BlockSettings.Phase.ACTIVE -> {
                infoView.visibility = View.GONE
                cancelButton.visibility = View.GONE
            }
            BlockSettings.Phase.PENDING_DISABLE -> {
                infoView.text = "Désactivation dans ${format(state.remainingMs)}. " +
                    "Le blocage reste actif jusque-là, puis sera désactivé " +
                    "${state.offMs / 60_000} min avant de se réactiver automatiquement."
                infoView.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
            BlockSettings.Phase.DISABLED -> {
                infoView.text = "Blocage désactivé. Réactivation automatique dans " +
                    "${format(state.remainingMs)}. (Remettre l’interrupteur sur « activé » " +
                    "le réactive tout de suite.)"
                infoView.visibility = View.VISIBLE
                cancelButton.visibility = View.GONE
                maybeWarnOneMinute(state)
            }
        }
        if (state.phase != BlockSettings.Phase.ACTIVE) handler.postDelayed(ticker, 500L)

        statusView.text = "Service : " + when {
            DiagState.connected -> "actif et connecté"
            isServiceEnabled() -> "activé mais non connecté (désactive puis réactive-le)"
            else -> "désactivé"
        }
        labelView?.text = "Étiquette actuelle : ${DiagState.label}"
    }

    /** Fenêtre d'information une seule fois par cycle, dans la dernière minute avant la réactivation. */
    private fun maybeWarnOneMinute(state: BlockSettings.State) {
        if (state.remainingMs > 60_000L || warnedCycle == state.cycleId || isFinishing) return
        warnedCycle = state.cycleId
        AlertDialog.Builder(this)
            .setTitle("Le blocage va se réactiver")
            .setMessage("Le blocage se réactive dans moins d’une minute.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setSwitch(active: Boolean) {
        updatingUi = true
        blockSwitch.isChecked = active
        blockSwitch.text = if (active) "Blocage activé" else "Blocage désactivé"
        updatingUi = false
    }

    private fun format(ms: Long): String {
        val seconds = (ms + 999) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, ReelsAccessibilityService::class.java)
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    private fun openInstagram() {
        val intent = packageManager.getLaunchIntentForPackage(DetectionConfig.INSTAGRAM_PACKAGE)
        if (intent == null) {
            Toast.makeText(this, "Instagram est introuvable sur ce téléphone", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(intent)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(content: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = content
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun heading(content: String): TextView =
        text(content, 17f, bold = true).apply { setPadding(0, dp(20), 0, dp(6)) }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1

        val SITUATIONS = listOf(
            "1-fil-accueil" to "Étiquette 1 · Fil d’accueil",
            "2-onglet-reels" to "Étiquette 2 · Onglet Reels",
            "3-reel-message" to "Étiquette 3 · Reel reçu en message",
            "4-reels-profil" to "Étiquette 4 · Reels d’un profil",
            "5-reel-explorer" to "Étiquette 5 · Reel dans Explorer",
            "6-reel-fil" to "Étiquette 6 · Reel ouvert depuis le fil",
            "7-defilement" to "Étiquette 7 · Défilement entre reels",
            "aucune" to "Aucune étiquette"
        )
    }
}
