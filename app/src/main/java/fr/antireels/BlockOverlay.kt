package fr.antireels

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Écran de blocage plein écran (titre et message variables, ex. « Reels bloqués » ou
 * « Défilement bloqué ») avec un bouton OK.
 *
 * Il s'affiche dans une fenêtre de type TYPE_ACCESSIBILITY_OVERLAY : ce type est réservé
 * aux services d'accessibilité et ne demande PAS l'autorisation « afficher par-dessus
 * les autres applications ».
 */
class BlockOverlay(
    private val context: Context,
    private val onOk: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show(title: String, subtitle: String) {
        if (view != null) return
        val content = buildView(title, subtitle)
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            windowManager.addView(content, params)
            view = content
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'afficher l'écran de blocage : ${e.javaClass.simpleName}")
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (e: Exception) {
            // Déjà retirée : rien à faire.
        }
    }

    private fun buildView(titleText: String, subtitleText: String): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val title = TextView(context).apply {
            text = titleText
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(context).apply {
            text = subtitleText
            textSize = 16f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        val okButton = Button(context).apply {
            text = "OK"
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(28).toFloat()
            }
            setPadding(dp(56), dp(14), dp(56), dp(14))
            setOnClickListener { onOk() }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(title)
            addView(subtitle)
            addView(
                okButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(40) }
            )
        }

        return FrameLayout(context).apply {
            setBackgroundColor(0xFF121212.toInt())
            isClickable = true
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }

    private companion object {
        const val TAG = "AntiReels"
    }
}
