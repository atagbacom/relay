# Journal du projet relay

Ce journal retrace les étapes du projet, les problèmes rencontrés, leurs causes et les solutions retenues. Il sert de documentation pour qui reprend le projet, et de trace de l'expérience acquise.

Chaque entrée suit le même schéma : **constat**, **cause**, **solution**, **leçon**. Les numéros (#N) renvoient aux issues et pull requests du dépôt `atagbacom/relay`.

---

## Phase 1 — Juin 2026 : la preuve de concept

**26 juin 2026.** Construction de la base de l'application Android en une série de commits :

- identité cryptographique (paire de clés Signal) ;
- établissement de session X3DH, chiffrement Double Ratchet via `libsignal` ;
- trois niveaux de confiance : ORANGE (non vérifié), YELLOW (vérifié par le réseau), GREEN (vérifié en personne) ;
- propagation de clés pré-signées (gossip) et sessions différées avec nonce ;
- transport Bluetooth Low Energy : annonce, scan, serveur et client GATT ;
- routage : déduplication par filtre de Bloom, Spray-and-Wait, multi-sauts, mode batterie critique ;
- persistance Room, repository, service au premier plan ;
- interface Compose : contacts, conversation, échange de clé par QR code.

**Bilan rétrospectif.** L'architecture était posée, mais le code **n'avait jamais compilé** et les tests **n'avaient jamais été exécutés** : ils étaient hors de tout jeu de sources Gradle. Un commit annonçait « All 5 unit tests passing » sans que rien ne le vérifie. La phase 2 l'a révélé.

> **Leçon.** Un test qui ne tourne pas automatiquement n'existe pas. La CI doit être en place dès le premier commit, avant les fonctionnalités.

---

## Phase 2 — Octobre 2026 : mise en CI et remise en état

### 1. Aucune compilation sur le poste de travail

- **Décision.** Toute compilation et tous les tests passent par GitHub Actions (`.github/workflows/android.yml`), jamais sur le PC.
- **Mise en place.** Compilation de l'APK de débogage, tests unitaires, APK et rapport de tests en artefacts téléchargeables (#1, #4).

### 2. GitHub Actions ne se déclenchait pas

- **Constat.** Dépôt créé sur le compte personnel : aucune exécution, même après réouverture de la pull request et un push. Aucun dépôt du compte personnel n'avait jamais eu une seule exécution, alors que ceux de l'organisation tournaient normalement.
- **Cause.** Blocage au niveau du compte personnel (cause exacte non identifiée). Ce n'était ni la configuration du dépôt, ni le fichier YAML, ni un incident GitHub, tous vérifiés.
- **Solution.** Transfert du dépôt vers l'organisation `atagbacom`.
- **Second constat.** Toujours aucune exécution : GitHub ne voyait **aucun workflow**, car le fichier n'existait que sur la branche de la pull request, pas sur `main`.
- **Solution.** Fusion de la PR de CI (#4) dans `main` sans CI préalable, pour amorcer. Les PR suivantes ont eu leur CI normalement.

> **Leçon.** Sur un dépôt neuf, mettre le workflow sur `main` en premier. Et vérifier tôt qu'Actions tourne vraiment sur le compte qui héberge le dépôt.

### 3. Le build Gradle ne se configurait pas (#1, #4)

- **Constat.** Erreurs de script : `ksp(...)` inconnu, `kotlinCompilerExtensionVersion` inconnu.
- **Causes.** Room utilisait KSP sans que le plugin KSP soit appliqué ; une option de l'ancien compilateur Compose était placée dans `compileOptions`, où elle n'existe pas ; cible JVM Kotlin (11) différente de Java (17).
- **Solution.** Plugin KSP 2.3.12, Room 2.8.5 via le catalogue de versions, option obsolète retirée, cibles JVM alignées sur 17.

### 4. libsignal exige le desugaring (#7, #8)

- **Constat.** `Dependency 'org.signal:libsignal-android' requires core library desugaring`.
- **Solution.** `isCoreLibraryDesugaringEnabled = true` et `desugar_jdk_libs` 2.1.5 : les API Java récentes deviennent disponibles dès Android 8 (minSdk 26).

### 5. Le manifeste Android était invalide (#8)

- **Constat.** Erreur AAPT : `unexpected element <uses-permission> found in <application>`.
- **Causes, en le corrigeant.**
  - les permissions étaient à l'intérieur de `<application>` ;
  - `.MainActivity` et `.mesh.MeshService` pointaient vers le namespace `com.example.relay` alors que le code est dans `com.relay.app` : **plantage au lancement** ;
  - il manquait les permissions Bluetooth d'Android 8 à 11.
- **Solution.** Permissions déplacées, noms de classes complets, `BLUETOOTH` et `BLUETOOTH_ADMIN` limités à l'API 30.

### 6. Le code Kotlin ne compilait pas (#8)

- **Constat.** Une quarantaine d'erreurs, ramenées à cinq causes :
  - un ancien écran inutilisé (`MainScreen`) appelait du code disparu ;
  - le type `UiMessage` n'était défini nulle part ;
  - deux constantes manquaient dans `MeshService`, et une fonction était appelée sous un mauvais nom ;
  - un import manquait ;
  - `libsignal` 0.67 a remplacé `KeyHelper.generateIdentityKeyPair()` par `IdentityKeyPair.generate()`.
- **Défauts découverts en lisant.**
  - la liste des pairs n'était jamais remplie, donc aucun message ne partait vers personne ;
  - l'état réseau et batterie n'était jamais transmis à l'écran ;
  - les messages internes vers l'interface n'étaient pas ciblés sur l'application, et Android les aurait bloqués.
- **Résultat.** Premier APK produit par la CI.

> **Leçon.** Corriger une erreur de compilation est l'occasion de relire le code autour : trois défauts de fonctionnement sont apparus ainsi.

### 7. Les tests tournent enfin, et révèlent de vrais défauts (#2, #5)

- **Constat.** Une fois rattachés au build : 11 tests, 3 en échec.
- **Causes.**
  - un nom de test contenait « : », caractère interdit dans un nom de fonction sur Android ;
  - `PreKeyGossip` utilisait `android.util.Base64`, absent hors appareil ;
  - **défaut réel** : `createPreKeyBundle()` publiait les clés publiques sans enregistrer leurs parties privées, si bien que **le premier message reçu ne pouvait jamais être déchiffré**.
- **Solution.** Nom corrigé, `java.util.Base64`, clés privées enregistrées. 11 tests sur 11.

### 8. Sessions de chiffrement : un défaut et une faille (#9, #10)

- **Constats.**
  - `MeshService` publiait ses clés depuis un store créé pour l'occasion puis perdu, et déchiffrait avec un autre : sur deux vrais téléphones, le premier message aurait échoué ;
  - les clés reçues d'un pair ne servaient qu'à relever un niveau de confiance : **aucune session n'était ouverte**, donc rien ne pouvait partir ;
  - **faille** : la signature des clés reçues n'était **jamais vérifiée**. N'importe qui pouvait passer « vérifié par le réseau » sous l'identifiant d'un autre ;
  - un bundle diffusé à tout le mesh contenait une clé à usage unique, que le premier pair à l'utiliser consommait pour tous.
- **Solution.**
  - un seul store Signal par appareil, persistant dans Room (`PersistentSignalStore`, migration 1 → 2) ;
  - signature vérifiée, et clé correspondant à l'identifiant annoncé ;
  - une clé différente pour un contact connu est ignorée ;
  - bundle diffusé sans clé à usage unique, clé pré-signée renouvelée chaque semaine.
- **Tests ajoutés.** Redémarrage du téléphone, usurpation d'identifiant, bundle sans clé unique : 17 tests sur 17.

### 9. Transport : rien ne partait, et la méthode était dangereuse (#11, #13)

- **Constat.** Les paquets étaient sérialisés avec `ObjectOutputStream`, mais `RelayPacket` n'était pas `Serializable`. L'exception était avalée : **aucun paquet n'est jamais parti sur le mesh**.
- **Problème plus grave.** Désérialiser avec `ObjectInputStream` des octets reçus de n'importe quel appareil est une faille connue (exécution de code).
- **Solution.**
  - format binaire explicite et versionné (`PacketCodec`) : en-tête, taille totale, champs bornés, rejet strict de tout paquet mal formé ;
  - côté réception Bluetooth, un paquet est complet quand la taille annoncée est atteinte. Avant, un paquet de taille multiple de 512 octets n'était jamais considéré comme terminé.

> **Leçon.** Les données qui arrivent du réseau ne doivent jamais reconstruire d'objets arbitraires : un format explicite, des longueurs bornées, et le rejet par défaut.

### 10. Bluetooth : l'envoi en plusieurs morceaux échouait (#14)

- **Constat.** `BleGattClient` écrivait tous les morceaux d'un paquet à la suite, avec une pause de 50 ms, puis annonçait « envoyé » sans attendre.
- **Causes.**
  - Android n'accepte qu'**une écriture GATT à la fois** : les suivantes échouaient ;
  - les morceaux faisaient 512 octets, alors qu'une écriture en contient au plus **MTU négociée − 3** ;
  - API dépréciée depuis Android 13 ;
  - si une écriture visait une autre caractéristique, le serveur ne répondait pas, et le client attendait.
- **Solution.**
  - file d'attente : un morceau, attendre sa confirmation, puis le suivant ;
  - taille des morceaux calculée depuis la MTU réellement obtenue (20 octets si la négociation échoue) ;
  - délai maximal de 15 s ; nouvelle API d'écriture sur Android 13 et plus ;
  - découpage et réassemblage extraits dans une classe sans Android (`Fragmentation`, `Reassembleur`), partagée par le client et le serveur, et testée hors appareil.

> **Leçon.** Isoler la logique pure du code matériel : elle devient testable en CI, même sans téléphone.

### 11. L'APK pesait 157 Mo (#18)

- **Constat.** L'APK de débogage produit par la CI pesait 157 Mo : inacceptable pour une messagerie destinée à des zones où les données coûtent cher.
- **Causes** (inventaire des archives de libsignal 0.67) :
  - `libsignal-android` (100 Mo) embarque, pour chaque architecture, une variante de test de sa bibliothèque native d'environ 50 Mo ;
  - il tire `libsignal-client` (77 Mo), dont les bibliothèques natives pour Linux, macOS et Windows finissaient dans l'APK ;
  - les architectures x86 (émulateurs) étaient incluses ;
  - les bibliothèques natives étaient stockées non compressées dans l'APK.
- **Solution.**
  - exclusion des variantes de test et des bibliothèques pour ordinateur ;
  - `libsignal-client` déclaré seulement pour les tests JVM ;
  - un APK par architecture (arm64, armv7), plus un universel ;
  - bibliothèques natives compressées ;
  - taille des APK affichée dans le résumé de chaque exécution de CI.

> **Leçon.** Mesurer la taille livrée dès le premier build : une dépendance peut embarquer dix fois plus que ce qu'on utilise.

### 12. Design : de l'esthétique « terminal » à un outil institutionnel

- **Constat.** Interface en police à chasse fixe, vert fluo sur noir, boutons entre crochets : lecture difficile en plein soleil, peu crédible pour une ONG ou la protection civile.
- **Démarche.** Prototype interactif et animé en 5 écrans, validé avant tout code :
  - un radar des appareils proches ;
  - le trajet d'un message de relais en relais ;
  - la vérification en personne avec un sceau ;
  - l'échelle de confiance expliquée ;
  - l'alerte à maintenir deux secondes.
- **Principes.**
  - bleu nuit et or Àtagbà, thème clair par défaut et sombre selon le téléphone ;
  - aucune information portée par la couleur seule (toujours texte et icône) ;
  - des mots simples (« appareils à proximité » plutôt que « pairs »).
- **Réalisation** (une PR par étape, #21 à #31) :
  - thème bleu nuit et or, IBM Plex embarquée (l'app marche hors ligne), icônes vectorielles légères ;
  - radar animé des appareils à portée, écrans Réseau, Messages, Conversation (trajet du message jusqu'à « Remis »), Vérifier, Fiche contact, Alerte ;
  - animations coupées quand l'utilisateur les a désactivées dans Android ; appareils du radar et commandes annoncés par le lecteur d'écran.
- **Défauts trouvés pendant la refonte.**
  - le QR code expirait après 5 minutes mais n'était généré qu'au lancement ;
  - la clé d'un QR scanné n'était pas comparée à l'identifiant affiché ;
  - un même QR était enregistré à chaque image de la caméra, et la caméra n'était pas libérée ;
  - un contact n'avait que son identifiant technique : ajout du renommage.
- **Ajouts.** Empreinte lisible de la clé et quatre mots de sécurité à lire à voix haute, qui changent si quelqu'un s'interpose ; alerte réelle (message chiffré à chaque contact, remise suivie contact par contact).

> **Leçon.** Refaire un écran oblige à relire son code : la moitié des défauts de cette phase ont été trouvés en redessinant.


### 13. Premier essai sur un vrai téléphone : plantage au lancement

- **Constat.** Sur Android, l'installation directe de l'APK échouait (il a fallu passer par Xender), puis l'application s'arrêtait à chaque ouverture.
- **Causes trouvées dans le code.**
  - le service mesh initialisait son identité avec `IdentityKeyPair(ByteArray(0))` : exception à la création du service, donc plantage dès que les permissions étaient accordées ;
  - une erreur dans n'importe quelle tâche du service fermait toute l'application ;
  - Bluetooth absent ou éteint : l'adaptateur était utilisé sans vérification ;
  - l'annonce Bluetooth dépassait la limite de 31 octets : elle ne pouvait jamais partir, les téléphones ne se seraient jamais trouvés ;
  - chaque exécution de CI signait l'APK avec une clé de débogage différente : Android refuse alors d'installer par-dessus la version précédente.
- **Solution.**
  - identité chargée avant usage, erreurs du service journalisées sans fermer l'application, envoi qui attend que le service soit prêt ;
  - Bluetooth vérifié, identifiant court déplacé dans la réponse au scan ;
  - clé de débogage fixe versionnée ;
  - rapport de plantage dans l'application, affiché à la réouverture avec un bouton de partage ;
  - **test de démarrage sur émulateur dans la CI** : installation avec permissions, lancement, recherche de plantage dans le journal Android.

> **Leçon.** Compiler et tester la logique ne suffit pas : il faut lancer l'application. Un test de démarrage automatique aurait attrapé ce plantage avant qu'il n'arrive sur un téléphone.

---

## Points ouverts

| N° | Sujet | Pourquoi c'est important |
|---|---|---|
| #12 | L'identifiant court n'a que 3 octets utiles | Un attaquant peut fabriquer une clé qui affiche le même identifiant en quelques secondes ; seule la vérification par QR code protège vraiment |
| — | Test sur deux vrais téléphones | Toute la chaîne (clés, session, paquet, Bluetooth) n'a encore tourné qu'en tests unitaires |

---

## Leçons transverses

1. **La CI d'abord.** Sans elle, ce projet a accumulé quatre mois de code qui ne compilait pas.
2. **Les tests doivent tourner pour exister.** Les trois premiers échecs étaient de vrais défauts.
3. **Relire autour de chaque correction.** La plupart des défauts graves ont été trouvés en corrigeant autre chose.
4. **Une faille de sécurité se cache souvent dans une vérification absente**, pas dans un algorithme : signature non contrôlée, désérialisation sans garde-fou.
5. **Une issue et une pull request par sujet.** Chaque problème trouvé en chemin a sa propre issue plutôt que d'alourdir la correction en cours.
6. **Le design avant le code.** Un prototype qu'on peut toucher évite de coder des écrans qu'on refera.
