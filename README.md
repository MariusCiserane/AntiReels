# Anti-Reels (Android)

Application Android qui bloque, dans l'application Instagram officielle :
- **l'onglet Reels** de la barre du bas ;
- **le défilement entre reels** : un reel ouvert de n'importe où (fil, message, profil, Explorer)
  peut être regardé, mais pas le suivant.

Le fil d'accueil, Explorer, les notifications et les reels reçus en message restent utilisables.

> **État : version 1.0** (phases 1 à 4 validées sur Poco M8 Pro 5G, Android 16, Instagram 455.0.0.51.72).

## Un seul interrupteur
Il contrôle à la fois l'onglet Reels et le défilement.
- Pour désactiver : confirmation, puis **délai de 2 minutes** (annulable, le blocage reste actif).
- Une fois désactivé, le blocage se **réactive automatiquement** après 5, 10 (par défaut), 15, 20 ou 30 minutes
  (réglable dans l'app).
- Une notification avec compte à rebours indique le temps restant avant la réactivation ; une fenêtre
  d'information s'affiche aussi dans la dernière minute si l'app est ouverte.
- Réactiver à la main est immédiat.

## Sécurité
- Aucune permission `INTERNET` : l'app ne peut rien envoyer à l'extérieur. Seule autre permission :
  `POST_NOTIFICATIONS` (notification de compte à rebours).
- Service d'accessibilité limité au paquet `com.instagram.android`.
- Aucune bibliothèque tierce. `allowBackup="false"`.
- Rien n'est enregistré : seulement l'état du blocage, la durée choisie et l'heure de début du cycle.
- L'outil de diagnostic (`DiagnosticLogger`) ne fonctionne qu'en version de débogage et n'enregistre pas les textes.

## Compiler et installer
1. Ouvrir le dossier dans Android Studio, attendre la synchronisation Gradle.
2. Version finale : dans **View > Tool Windows > Build Variants**, choisir **release** pour le module `app`.
   (La version `debug` est la version de développement, avec l'outil de diagnostic.)
3. Brancher le téléphone (débogage USB activé), le choisir en haut, cliquer sur ▶ Run.
4. Dans l'app : activer le service (Paramètres > Accessibilité > Applications installées).
5. Sur Xiaomi : démarrage automatique activé, économie de batterie sur « Aucune restriction »,
   app verrouillée dans les applications récentes (sinon le service peut être arrêté en arrière-plan).

## Maintenance : quand Instagram change son interface
Tous les critères de détection sont dans `app/src/main/java/fr/antireels/DetectionConfig.kt`.
Si le blocage ne marche plus après une mise à jour d'Instagram :
1. Installer l'app en version de débogage (▶ depuis Android Studio).
2. Ouvrir Logcat, filtre `tag:AntiReelsDiag`.
3. Dans l'app, choisir l'étiquette de la situation, ouvrir Instagram, la reproduire, attendre 3 s.
4. Repérer dans le journal les éléments concernés (id, état SELECTED, description) et mettre à jour
   `DetectionConfig.kt`.

## Limites connues
- Le défilement est détecté quand le **reel affiché change** : deux reels consécutifs d'un même auteur
  (ex. reels d'un profil) ne sont pas distingués, donc pas bloqués. Les reels proposés au hasard
  (fil, Explorer, messages, reels suggérés) le sont.
- Le blocage est un garde-fou pour soi-même, pas un verrouillage : on peut toujours désactiver le service
  dans les réglages d'Android ou désinstaller l'app. Le délai de 2 minutes ne protège que l'interrupteur de l'app.
- Une mise à jour d'Instagram peut casser la détection : voir « Maintenance ».

## Licence
À choisir (phase 5).
