package fr.antireels

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ============================================================================
 *  TOUS LES CRITÈRES DE DÉTECTION D'INSTAGRAM SONT RÉUNIS DANS CE FICHIER
 * ============================================================================
 *
 * Valeurs relevées avec l'outil de diagnostic sur Instagram 455.0.0.51.72
 * (Poco M8 Pro 5G, Android 16, interface en français).
 *
 * Ce que les journaux ont montré :
 *  - La barre du bas (id "tab_bar") contient les onglets : feed_tab (Accueil),
 *    clips_tab (Reels), direct_tab, search_tab (Explorer), profile_tab.
 *  - Quand on est dans l'onglet Reels, SEUL "clips_tab" est marqué SELECTED.
 *  - Les reels ouverts depuis un message, un profil ou Explorer n'activent jamais
 *    "clips_tab" : (message : autre écran, sans barre du bas ; profil / Explorer :
 *    c'est feed_tab ou search_tab qui reste sélectionné).
 *  - PIÈGE : Instagram garde en mémoire un lecteur de reels CACHÉ (éléments
 *    "clips_viewer_view_pager", "root_clips_layout"… marqués HIDDEN) même sur
 *    l'accueil. Ces éléments ne servent donc PAS à détecter l'onglet Reels.
 *  - PIÈGE : l'onglet « Reels » de la grille d'un profil a aussi la description
 *    "Reels" (id "profile_tab_icon_view"). Ne jamais détecter par la description seule.
 *
 * Défilement des reels (phase 4) : HYPOTHÈSE à confirmer par des tests.
 *  - Un reel ouvert en plein écran contient un "clips_viewer_view_pager" VISIBLE et grand
 *    (hauteur > la moitié de l'écran), avec un "clips_media_component" dont la description
 *    commence par « Reel de <auteur> ». Le fil d'accueil n'en contient pas.
 *  - Si le reel affiché change (description différente) alors que le lecteur reste ouvert,
 *    c'est que l'utilisateur a fait défiler vers un autre reel.
 *
 * Quand Instagram change son interface et que le blocage ne marche plus :
 * relancer le diagnostic (voir README), puis corriger les identifiants ci-dessous.
 */
object DetectionConfig {

    /** Paquet de l'application Instagram officielle. */
    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    /** Identifiant(s) de l'onglet Reels de la barre du bas (sans le préfixe "com.instagram.android:id/"). */
    val REELS_TAB_VIEW_IDS = listOf("clips_tab")

    /** Identifiant(s) de l'onglet Accueil, utilisé pour ramener l'utilisateur à l'accueil. */
    val HOME_TAB_VIEW_IDS = listOf("feed_tab")

    /** Identifiant(s) du lecteur de reels plein écran (pager vertical). */
    val REEL_VIEWER_PAGER_IDS = listOf("clips_viewer_view_pager")

    /** Identifiant(s) du reel affiché dans le lecteur ; sa description change d'un reel à l'autre. */
    val REEL_MEDIA_COMPONENT_IDS = listOf("clips_media_component")

    /** Le lecteur ne compte que s'il occupe au moins cette part de la hauteur de l'écran. */
    const val MIN_VIEWER_HEIGHT_RATIO = 0.5f

    /** Vrai si l'onglet Reels de la barre du bas est actuellement sélectionné et visible. */
    fun isReelsTabSelected(root: AccessibilityNodeInfo): Boolean =
        REELS_TAB_VIEW_IDS.any { id ->
            root.findAccessibilityNodeInfosByViewId(fullId(id))
                .any { it.isSelected && it.isVisibleToUser }
        }

    /** Retrouve l'onglet Accueil visible, ou null s'il est introuvable. */
    fun findHomeTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in HOME_TAB_VIEW_IDS) {
            val node = root.findAccessibilityNodeInfosByViewId(fullId(id))
                .firstOrNull { it.isVisibleToUser }
            if (node != null) return node
        }
        return null
    }

    /** Retrouve le lecteur de reels plein écran visible, ou null s'il n'y en a pas. */
    fun findVisibleReelViewer(root: AccessibilityNodeInfo, screenHeightPx: Int): AccessibilityNodeInfo? {
        for (id in REEL_VIEWER_PAGER_IDS) {
            for (node in root.findAccessibilityNodeInfosByViewId(fullId(id))) {
                if (!node.isVisibleToUser) continue
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.height() >= screenHeightPx * MIN_VIEWER_HEIGHT_RATIO) return node
            }
        }
        return null
    }

    /**
     * Description du reel actuellement affiché, ou null si on ne peut pas trancher
     * (aucun reel visible, ou plusieurs en même temps pendant un défilement).
     */
    fun currentReelKey(root: AccessibilityNodeInfo): String? =
        REEL_MEDIA_COMPONENT_IDS
            .flatMap { id ->
                root.findAccessibilityNodeInfosByViewId(fullId(id))
                    .filter { it.isVisibleToUser }
                    .mapNotNull { it.contentDescription?.toString() }
                    .filter { it.isNotBlank() }
            }
            .singleOrNull()

    private fun fullId(id: String) = "$INSTAGRAM_PACKAGE:id/$id"
}
