# Suivi Bourse 📈

Application Android (Kotlin + Jetpack Compose) qui suit en quasi-temps réel les cours de
**WPEA** (iShares MSCI World Swap PEA UCITS ETF) et **PAEEM** (Amundi PEA MSCI Emerging
Markets UCITS ETF) sur Euronext Paris.

## Fonctionnalités

- 🔄 **Rafraîchissement automatique toutes les 15 secondes** (+ tirer pour rafraîchir)
- 💶 Cours actuel, variation du jour (absolue et %), clôture de la veille, plus haut / plus bas
- 📊 Mini-graphique de la séance avec repère de la clôture de la veille
- 🟢 Indicateur d'état du marché (ouvert / fermé / pré-ouverture / après-clôture)
- ➕ Ajout d'autres valeurs par leur symbole Yahoo Finance (ex. `CW8.PA`, `ESE.PA`, `AAPL`),
  liste sauvegardée localement
- 🌙 Thème sombre orienté finance

## Source des données

Les cotations proviennent de l'API publique de graphique **Yahoo Finance**
(`query1.finance.yahoo.com/v8/finance/chart/…`), sans clé d'API. Les cours des ETF
Euronext peuvent être légèrement différés selon Yahoo (généralement ~15 min pour Euronext).

## Compiler

### Avec Android Studio
Ouvrir le dossier du projet dans Android Studio (Ladybug ou plus récent) et lancer ▶️.

### En ligne de commande
```bash
./gradlew assembleDebug
# APK généré dans app/build/outputs/apk/debug/app-debug.apk
```

### Via GitHub Actions
Chaque push déclenche le workflow **Build APK** ; l'APK de debug est téléchargeable dans
les artefacts du run (onglet *Actions* du dépôt).

## Prérequis techniques

- `minSdk` 26 (Android 8.0) · `targetSdk` 35
- JDK 17, Gradle 8.10 (wrapper inclus), AGP 8.7, Kotlin 2.0, Compose BOM 2024.12
