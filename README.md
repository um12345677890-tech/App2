# Suivi Bourse 📈

Application Android (Kotlin + Jetpack Compose) qui suit en quasi-temps réel les cours de
**WPEA** (iShares MSCI World Swap PEA UCITS ETF) et **PAEEM** (Amundi PEA MSCI Emerging
Markets UCITS ETF) sur Euronext Paris.

## Fonctionnalités

- 🔄 **Rafraîchissement automatique toutes les 15 secondes** (+ tirer pour rafraîchir)
- 💶 Cours actuel, variation du jour (absolue et %), clôture de la veille, plus haut / plus bas
- 📊 Mini-graphique de la séance avec repère de la clôture de la veille
- 🟢 Indicateur d'état du marché (ouvert / fermé / pré-ouverture / après-clôture)
- 🌍 **Recherche mondiale** : tapez un nom (« LVMH », « Apple », « MSCI World »…) ou un
  symbole et ajoutez des valeurs de **toutes les bourses** (Euronext, NYSE, NASDAQ,
  Londres, Francfort, Tokyo…) ; la liste est sauvegardée localement
- 🏛️ Place de cotation et devise (€, $, £, ¥, pence…) affichées sur chaque carte
- 💼 **Portefeuille avec PRU** : saisissez pour chaque valeur le nombre de parts détenues
  et votre PRU (prix de revient unitaire) via le bouton ✏️ ; l'app affiche par ligne et
  en synthèse le **total investi (sans plus-value)**, la **valeur actuelle (avec
  plus-value)** et la **plus-value latente** en devise et en %
- 🧭 **Onglet « Composition »** : répartition sectorielle (barres de pourcentage) et
  localisation géographique de chaque position, via l'API Yahoo `quoteSummary`
  (jeton « crumb » géré automatiquement)
- 🍩 **Allocation géographique** façon rapport de gestion : graphique en anneau et
  légende par pays pour chaque position et pour l'ensemble du portefeuille (pondérée
  par la valeur des positions). Pour les actions, le pays vient de Yahoo ; pour les
  ETF, l'allocation réelle du fonds est lue sur sa fiche **justETF** (ISIN résolu
  automatiquement, endpoints non officiels), avec en secours la répartition de
  l'indice suivi embarquée (ex. MSCI World au 30/06/2026 : États-Unis 72,26 %,
  Japon 5,69 %…). La source réellement utilisée est affichée sous chaque graphique
- 🌙 Thème sombre orienté finance

## Source des données

Les données proviennent de l'API publique **Yahoo Finance** :

- cotations : `…/v8/finance/chart/SYMBOLE`
- recherche mondiale : `…/v1/finance/search?q=…`

Deux hôtes (`query1` et `query2.finance.yahoo.com`) sont utilisés en repli l'un de
l'autre. Ce choix est délibéré : les API concurrentes (Alpha Vantage, Twelve Data,
Marketstack, Finnhub…) exigent une clé d'API et ont des quotas gratuits très faibles
(ex. 25 requêtes/jour chez Alpha Vantage), incompatibles avec un rafraîchissement
toutes les 15 s, ou ne couvrent pas toutes les places mondiales. Yahoo Finance est la
seule source gratuite, sans clé, couvrant l'ensemble des bourses. Les cours peuvent
être légèrement différés selon la place (généralement ~15 min pour Euronext).

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
