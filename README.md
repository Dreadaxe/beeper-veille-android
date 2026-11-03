# Beeper Veille Android — v0.1.0

Petite application Android personnelle de veille Discord à partir de **Beeper Android**.

## Fonctions incluses

- accès local à Beeper via le Content Provider `com.beeper.api` ;
- affichage des salons `protocol=discord` ;
- recherche instantanée des salons par nom ;
- sélection de plusieurs salons ;
- périodes : depuis le dernier passage, aujourd'hui, hier, 7 jours, 30 jours ;
- mots-clés séparés par virgules avec option **2 messages de contexte avant/après** ;
- condensé local par jour et salon (volume, participants, extraits, occurrences) ;
- export du corpus retenu en **TXT UTF-8** avec le sélecteur de fichiers Android ;
- bouton **Résumer dans ChatGPT** : prépare une consigne de synthèse + le corpus et l'envoie à l'application ChatGPT si elle est installée, sinon au menu de partage Android ;
- mémorisation du dernier message traité par salon ;
- veille périodique 1 h / 6 h / 12 h / 24 h avec notification en cas de nouvelle activité (ou de mot-clé trouvé).

## Installation / autorisations

1. Installer Beeper Android et y connecter Discord.
2. Installer l'APK de Beeper Veille.
3. Au premier lancement, autoriser **la lecture Beeper**.
4. Pour la veille automatique, autoriser aussi les notifications si Android le demande.

L'application ne demande pas la permission d'envoyer des messages via Beeper.

## Utilisation

1. Rechercher un salon dans **Rechercher un salon Discord**.
2. Cocher un ou plusieurs salons.
3. Choisir la période.
4. Facultatif : saisir `mordheim, halfling, mirliton` par exemple.
5. Appuyer sur **Charger la veille**.
6. Lire le condensé local, exporter en TXT ou utiliser **Résumer dans ChatGPT**.

### Mode quotidien

Laisser le champ mots-clés vide. L'application retient tous les messages de la période choisie.

### Mode mot-clé

Saisir un ou plusieurs termes. L'application conserve les messages contenant ces termes et, si l'option est activée, deux messages avant et deux après afin de garder le contexte.

### Depuis le dernier passage

Chaque chargement mémorise le timestamp le plus récent lu pour chaque salon. Le prochain passage peut donc récupérer uniquement la suite.

## Remarques techniques

Beeper indique actuellement que son Content Provider Android est **expérimental**.

## Construire l'APK

Le dépôt contient un workflow GitHub Actions (`.github/workflows/build-apk.yml`).

- onglet **Actions** → **Build Android APK** → **Run workflow** ;
- récupérer l'artefact `BeeperVeille-debug-apk`.

On peut aussi ouvrir le projet dans Android Studio et lancer `assembleDebug`.
