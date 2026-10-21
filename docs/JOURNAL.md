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


### 14. Deux téléphones qui ne se voient jamais

- **Constat (essai à deux téléphones).** Le design et le scan des codes fonctionnent. Mais les téléphones ne se voient jamais sur le radar, même côte à côte, Bluetooth éteint puis rallumé, application relancée. Les messages ne passent pas.
- **Causes trouvées dans le code** (sans journal du téléphone, donc à confirmer) :
  - si le Bluetooth était éteint au démarrage du service, celui-ci se mettait en attente sans jamais surveiller son rallumage. Fermer l'application ne l'arrêtait pas : il ne redémarrait donc jamais ;
  - au premier lancement, l'écran et le service créaient chacun une identité et la seconde écrasait la première : le QR code et l'annonce Bluetooth pouvaient porter deux identités différentes ;
  - le radar n'était mis à jour que lorsqu'un nouvel appareil apparaissait : il restait vide à la réouverture de l'application.
- **Solution.**
  - le service relance annonce, scan et serveur dès que le Bluetooth se rallume ;
  - identité créée une seule fois, sous verrou, sans jamais écraser l'existante ;
  - état du réseau partagé directement entre le service et l'écran, rafraîchi toutes les 5 s ;
  - **écran de diagnostic** : identité, Bluetooth, autorisations, état de l'annonce, du scan et du serveur (avec la raison en cas d'échec), annonces reçues, appareils vus, journal partageable, bouton « Relancer le réseau ».

> **Leçon.** Sur le terrain, sans câble ni adb, une application doit pouvoir expliquer elle-même pourquoi elle ne marche pas.


### 15. Autorisations expliquées et notifications

- **Constats.**
  - toutes les autorisations étaient demandées d'un coup au lancement, sans explication ; refuser **la caméra** empêchait **le réseau** de démarrer ;
  - aucune notification à l'arrivée d'un message : il fallait ouvrir l'application pour le voir.
- **Solution.**
  - écran d'accueil qui explique chaque autorisation ; le réseau démarre dès que le Bluetooth est autorisé ; si l'utilisateur a refusé définitivement, l'écran l'envoie dans les réglages du téléphone ;
  - caméra demandée au moment de scanner ;
  - notifications des messages et des alertes, sans contenu sur l'écran verrouillé ; les toucher ouvre la conversation.


### 16. Deux téléphones se parlent, mais l'acheminement est capricieux

- **Constat (essai à deux téléphones, avec le diagnostic).** Les messages passent ! Mais :
  - un message envoyé pendant l'absence de l'autre reste « en attente » même après son retour ;
  - un message déjà arrivé est parfois affiché « en attente » ;
  - de deux messages envoyés vite, le premier se perd parfois ;
  - le diagnostic montre **8 549 annonces Relay reçues, mais l'autre appareil reconnu une seule fois**.
- **Causes trouvées.**
  - l'identifiant n'est que dans la réponse au scan, qui n'accompagne qu'une partie des annonces : l'appareil était reconnu une fois puis considéré comme parti ;
  - Android change régulièrement l'adresse Bluetooth : l'application gardait la première, périmée ;
  - **l'accusé de réception transmettait l'identifiant de l'appareil au lieu de celui du message** : aucun message n'était jamais marqué « remis » ;
  - un message reçu deux fois était ignoré sans renvoyer d'accusé ;
  - aucune file d'attente : un message partait une fois, vers les appareils présents à cet instant ;
  - un paquet « gardé en attente » pour un autre n'était en fait gardé nulle part : le relais à plusieurs sauts ne pouvait pas marcher ;
  - chaque envoi ouvrait sa propre connexion Bluetooth, et deux connexions simultanées se gênaient.
- **Solution.**
  - adresse associée à l'identifiant dès la première réponse au scan, adresse mise à jour à chaque annonce ;
  - accusé corrigé, et renvoyé si un message arrive en double ;
  - file d'attente enregistrée en base : renvoi toutes les 30 s et dès qu'un appareil apparaît, jusqu'à l'accusé (48 h au plus) ;
  - paquets pour d'autres réellement gardés et livrés quand leur destinataire passe ;
  - envois Bluetooth un par un ;
  - tests du routeur (accusé, doublon, garde et livraison différée, changement d'adresse).

> **Leçon.** Un essai réel, même à deux téléphones, révèle en une heure ce que des semaines de lecture de code ne montrent pas. Et un écran de diagnostic transforme « ça ne marche pas » en un chiffre qui désigne la cause.


### 17. Vérification en personne avec un seul scan

- **Constat.** Le scan d'un QR ne va que dans un sens : A connaît B, mais B ne sait même pas qu'il a été scanné. Il fallait que chacun scanne l'autre.
- **Solution.**
  - le QR contient un code à usage unique (valable 5 minutes, une seule fois) ;
  - après le scan, le téléphone de A envoie à B, par le mesh, sa clé signée avec ce code : B sait que l'envoi vient de quelqu'un qui vient de scanner *son* code ;
  - B vérifie la signature, que la clé correspond à l'identifiant, et le code ; puis les deux comparent les quatre mots à voix haute ; si B confirme, A devient « vérifié en personne » chez B aussi ;
  - un paquet refusé ne consomme pas le code ; tests de l'aller-retour, du code à usage unique, de l'usurpation et du contenu modifié.

> **Leçon.** La sécurité ne doit pas se payer en effort : un seul scan, mais les mots gardent la garantie qu'on parle à la bonne personne.

### 18. Réglages et nom de profil

- **Constat.** Aucun écran de réglages : impossible de choisir son nom, de retrouver ses contacts hors des conversations ou de savoir quelles licences l'application embarque. La roue dentée ouvrait le diagnostic brut.
- **Solution.**
  - écran Réglages : nom, identifiant, empreinte, contacts (renommage via la fiche), état du réseau avec le diagnostic détaillé, accès aux notifications du téléphone, à propos (version, code ouvert, licences) ;
  - le nom choisi part dans le QR code et dans le paquet de vérification : la personne qui scanne voit tout de suite un nom, pas un identifiant ;
  - un nom donné à la main par l'utilisateur reste prioritaire sur celui annoncé par le contact.
- **Licences.** libsignal est sous AGPLv3 : Relay doit rester distribué avec son code source sous une licence compatible.

> **Leçon.** Le nom affiché vient d'abord de celui qui regarde : on propose le nom annoncé, on ne l'impose pas.

### 19. Régression : plus aucun message après la création du contact

- **Constat.** Après les versions « un seul scan » et « réglages », plus aucun message ne passait entre deux téléphones, et la vérification n'arrivait pas non plus chez l'autre (d'où l'impression de devoir encore scanner deux fois).
- **Causes trouvées** (sans téléphone sous la main, par lecture du code ; aucune n'apparaissait dans le diagnostic) :
  - les clés de chiffrement n'étaient envoyées qu'une fois, à la toute première détection : si cet unique envoi Bluetooth échouait, aucune session ne s'ouvrait jamais ;
  - sans session, le message était perdu sans trace : ni enregistré, ni signalé ;
  - un envoi abandonné après délai laissait sa connexion Bluetooth ouverte, ce qui pouvait bloquer les suivants vers le même téléphone ;
  - un paquet interrompu restait dans le tampon de réception et faisait jeter le paquet suivant ;
  - un message déjà déchiffré avant un redémarrage n'était pas réacquitté : il restait « en attente » chez l'expéditeur.
- **Solution.**
  - clés renvoyées à chaque voisin toutes les minutes, et en retour dès qu'une session s'ouvre ;
  - message toujours enregistré, mis en attente sans session, chiffré dès que les clés arrivent ;
  - session renouvelée automatiquement si les messages restent sans accusé, avec les mêmes identifiants (pas de doublon) ;
  - connexion fermée après délai, tampon de réception remis à zéro par un nouvel en-tête ou après 10 s ;
  - toutes les erreurs de chiffrement, de déchiffrement et de clés écrites dans le journal du diagnostic.

> **Leçon.** Une erreur qui ne va que dans les logs n'existe pas sur le terrain. Tout échec doit être visible là où l'utilisateur peut le lire.

### 20. Essai à trois téléphones : le relais ne livrait pas un destinataire absent

- **Constat** (essai réel à trois) : l'alerte passe, mais un message pour un destinataire absent au départ n'arrive jamais. Dès qu'un Bluetooth ou une application est coupé puis relancé, les messages arrivent en retard, dans le désordre, ou pas du tout. Le radar reste figé sur la première position.
- **Causes.**
  - le relais retirait sa copie dès le premier essai de livraison : un essai raté, et le message était perdu ; les renvois de l'expéditeur étaient ensuite ignorés comme doublons ;
  - un appareil vu dans les 10 dernières minutes était considéré « à portée » : le relais tentait une livraison directe vers une adresse périmée, et c'était le seul essai ;
  - les copies gardées n'existaient qu'en mémoire : couper l'application les effaçait ;
  - un accusé de réception ne faisait pas jeter les autres copies du message ;
  - chaque envoi vers un téléphone parti bloquait la file Bluetooth jusqu'à 20 s, et les messages suivants prenaient du retard ; la file pouvait aussi déborder et jeter des envois ;
  - le message reçu était daté de son arrivée, pas de son écriture : des messages relayés ou renvoyés s'affichaient dans le désordre ;
  - Android ralentit puis coupe un scan qui dure, et certains téléphones ne remontent plus les annonces répétées : la force du signal n'était plus mise à jour ;
  - les clés d'un appareil ne voyageaient pas : sans les avoir croisé, on ne pouvait pas lui écrire.
- **Solution.**
  - le relais garde chaque copie en base jusqu'à la confirmation de livraison, l'accusé ou l'expiration (48 h), et la représente toutes les 20 s tant que le destinataire est à portée ;
  - « à portée » veut dire vu il y a moins de 30 s ; un appareil revenu après 30 s d'absence est traité comme une nouvelle rencontre ;
  - un accusé vu passer fait jeter la copie et bloque tout relais ultérieur du message ;
  - file Bluetooth sans doublon, sans limite, et une adresse qui échoue est écartée 15 s ;
  - message daté de son écriture ; scan relancé toutes les 5 minutes ;
  - les relais retiennent les dernières clés de chaque appareil et les remettent à ceux qu'ils croisent ;
  - 4 nouveaux tests du routeur.

> **Leçon.** Dans un réseau qui se coupe, rien ne doit être lâché sur un seul essai : on garde jusqu'à la preuve de livraison.

---

## Points ouverts

| N° | Sujet | Pourquoi c'est important |
|---|---|---|
| #12 | L'identifiant court n'a que 3 octets utiles | Un attaquant peut fabriquer une clé qui affiche le même identifiant en quelques secondes ; seule la vérification par QR code protège vraiment |
| — | Test à trois téléphones ou plus | Le relais à plusieurs sauts (bouche à oreille) n'a pas encore été essayé sur le terrain |

---

## Leçons transverses

1. **La CI d'abord.** Sans elle, ce projet a accumulé quatre mois de code qui ne compilait pas.
2. **Les tests doivent tourner pour exister.** Les trois premiers échecs étaient de vrais défauts.
3. **Relire autour de chaque correction.** La plupart des défauts graves ont été trouvés en corrigeant autre chose.
4. **Une faille de sécurité se cache souvent dans une vérification absente**, pas dans un algorithme : signature non contrôlée, désérialisation sans garde-fou.
5. **Une issue et une pull request par sujet.** Chaque problème trouvé en chemin a sa propre issue plutôt que d'alourdir la correction en cours.
6. **Le design avant le code.** Un prototype qu'on peut toucher évite de coder des écrans qu'on refera.
