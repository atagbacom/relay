# Relay

Messagerie mesh Bluetooth Low Energy, chiffrée de bout en bout avec le protocole Signal, fonctionnant entièrement hors ligne — sans internet, sans réseau cellulaire, sans serveur.

## Présentation

Relay est une application Android qui permet d'échanger des messages texte chiffrés directement entre appareils à portée Bluetooth Low Energy (BLE), en relayant les messages de proche en proche jusqu'à leur destinataire via un algorithme de routage épidémique (*Spray and Wait*). Chaque message est chiffré de bout en bout avec le protocole Signal (Double Ratchet, via `libsignal`), et la confiance envers un contact évolue par paliers explicites — d'un premier contact non vérifié jusqu'à un échange de clé en face à face par QR code.

Le projet cible les situations où l'infrastructure réseau est coupée, absente ou non fiable : catastrophe naturelle, zone blanche, manifestation, festival, zone rurale. C'est une **preuve de concept Android** explorant le chiffrement de bout en bout, le routage mesh tolérant aux ruptures de connexion (*delay-tolerant networking*) et la gestion de la confiance cryptographique sans autorité centrale.

## Stack technique

| Domaine | Technologie |
| --- | --- |
| Langage | Kotlin, application Android uniquement |
| Interface utilisateur | Jetpack Compose (Material 3) |
| Cible | Android (`minSdk` 26, `compileSdk`/`targetSdk` 36) |
| Chiffrement de bout en bout | Signal Protocol via `libsignal-android` / `libsignal-client` (Double Ratchet, prekeys) |
| Transport mesh | Bluetooth Low Energy natif (`android.bluetooth` — GATT client/serveur, advertising, scan) |
| Stockage local | Room (SQLite) — identité, contacts, messages |
| Échange de clé | ZXing (génération QR) + CameraX / ML Kit Barcode Scanning (scan QR) |
| Concurrence | Coroutines Kotlin |
| Build | Gradle (Kotlin DSL), Android Gradle Plugin |

## Fonctionnalités

- **Messagerie chiffrée de bout en bout** — chaque message est chiffré avec le protocole Signal (Double Ratchet) avant de transiter sur le réseau mesh.
- **Réseau mesh BLE sans infrastructure** — les appareils s'annoncent (*advertising*), se découvrent (*scan*) et échangent des paquets en GATT ; les messages sont relayés de nœud en nœud jusqu'au destinataire.
- **Routage Spray-and-Wait** — distribution bornée de copies d'un message (7 par défaut) avec déduplication par filtre de Bloom, TTL en nombre de sauts et expiration automatique après 48 h, pour éviter l'explosion combinatoire d'un flooding naïf.
- **Trois niveaux de confiance progressifs et irréversibles** (`ORANGE` → `YELLOW` → `GREEN`) :
  - `ORANGE` — premier contact, clé non vérifiée (Trust On First Use) ;
  - `YELLOW` — clé propagée et vérifiée via un gossip de prekeys signé, relayé par le réseau ;
  - `GREEN` — clé vérifiée par échange direct en QR code, confiance maximale.
- **Échange de clé par QR code** — génération et scan de QR (caméra) pour valider un contact en face à face et passer directement au niveau de confiance maximal.
- **Messages différés** — un message destiné à un contact absent du réseau est conservé et livré (avec protection anti-rejeu par nonce) lorsque ce contact réapparaît.
- **Adaptation à la batterie** — trois modes (`FULL` / `LOW` / `CRITICAL`) qui réduisent la fréquence de scan et limitent le relais aux messages prioritaires quand la batterie est faible, pour rester utile en situation d'urgence prolongée.
- **Service Android en avant-plan** (`MeshService`) qui maintient le mesh actif (scan, advertising, relais) en arrière-plan.
- **Interface Compose dédiée** — écran de contacts (avec indicateur de niveau de confiance), écran de conversation, écran d'échange QR.

## Structure du projet

```text
relay/
├── androidApp/                     Application Android — logique métier complète
│   └── src/main/kotlin/.../app/
│       ├── mesh/                   BLE (scan, advertising, GATT), routage Spray-and-Wait, suivi des nœuds, batterie
│       ├── crypto/                 Identité Signal, sessions, gossip de prekeys, sessions différées, QR, niveaux de confiance
│       ├── model/                  Modèles de données (RelayPacket, RelayContact)
│       ├── repository/             RelayRepository — source de vérité unique entre UI et service
│       ├── storage/                Persistance Room (identité, contacts, messages)
│       └── ui/                     Écrans Compose (contacts, conversation, QR) et thème
│   └── src/test/kotlin/            Tests unitaires de la couche crypto et confiance
├── shared/                         Module du gabarit de départ, inutilisé : toute la logique vit dans androidApp
├── build.gradle.kts                Configuration Gradle racine
├── settings.gradle.kts             Déclaration des modules (:androidApp, :shared)
└── gradlew / gradlew.bat           Wrapper Gradle
```

## Build et installation

La compilation et les tests se font **dans la CI GitHub Actions** (`.github/workflows/android.yml`), à chaque pull request et à chaque fusion dans `main`, pas sur un poste de développement.

- **APK de débogage** : à télécharger dans l'artefact `relay-debug-apk` de la dernière exécution réussie (onglet *Actions*).
- **Tests** : `:androidApp:testDebugUnitTest`, rapport dans l'artefact `rapport-tests`.

Pour installer l'APK téléchargé sur un téléphone branché : `adb install relay-debug.apk`.

Le mesh BLE nécessite du matériel Bluetooth réel : pour tester les échanges entre pairs, installez l'application sur au moins deux appareils physiques (un émulateur ne dispose pas de radio BLE fonctionnelle). Au premier lancement, l'application demande les permissions Bluetooth, localisation et caméra nécessaires au scan BLE et au scan de QR code.

## Journal du projet

Les étapes, problèmes rencontrés et solutions retenues sont tenus dans [docs/JOURNAL.md](docs/JOURNAL.md).
