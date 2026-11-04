# GemPicker 🎯💎

Application Android qui détecte en temps réel la gemme bleue d'un jeu mobile
et tape dessus automatiquement — **uniquement** quand elle est entièrement
dans le cercle d'action vert (marge de sécurité 20 %), pour ne jamais
déclencher la popup d'erreur du jeu.

## Architecture

| Module | Rôle |
|---|---|
| `ScreenCaptureService` | Capture d'écran MediaProjection (~10 fps) |
| `GemDetector` | Segmentation HSV bleu (gemme) + vert (cercle), test d'inclusion strict |
| `AutoTapAccessibilityService` | Simulation du tap (`dispatchGesture`) |
| `FloatingBubble` | Bulle flottante draggable ON/OFF, au-dessus du jeu |
| `MainActivity` | Permissions + écran à un bouton |

## Zones interdites au tap

- HUD haut (avatar, solde, diamant, pièces, BOOSTER LOYER) : au-dessus de 12 % de hauteur
- Barre du bas (BOUTIQUE, GAGNER, CARTE, ÉVÉNEMENTS, COMMUNAUTÉ) : en dessous de 88 %

## Sécurités anti-popup d'erreur

1. Cercle vert re-détecté à chaque frame (taille dynamique)
2. Pas de cercle détecté → aucun tap
3. Gemme entière à moins de 80 % du rayon → sinon skip
4. Cooldown 800 ms entre les taps

## Calibration

Voir le guide de calibration : captures `calib-*.jpg` à fournir pour recaler
HSV bleu/vert, `MIN_GEM_AREA`/`MAX_GEM_AREA`, `CIRCLE_MIN_AREA`, `inclusionMargin`.

## Permissions au 1er lancement

1. Afficher par-dessus les autres apps (overlay)
2. Service d'accessibilité (tap simulé)
3. Capture d'écran (consentement par session — à refaire après chaque redémarrage du téléphone)

## Compilation

Android Studio → ouvrir le projet → Build. Dépendance OpenCV via Maven.

> ⚠️ Vérifiez les CGU du jeu : l'automatisation peut être interdite (risque de suspension de compte).
