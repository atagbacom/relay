# Relay

Messagerie Android qui fonctionne sans internet, sans carte SIM et sans serveur. Les messages passent de téléphone en téléphone par Bluetooth Low Energy jusqu'à leur destinataire, chiffrés de bout en bout avec le protocole Signal.

Nom juridique : **Àtagbà Relay**. Projet porté par Àtagbà, Cotonou.

- Présentation : [atagbacom.github.io/relay](https://atagbacom.github.io/relay/)
- Versions publiées : onglet [Releases](https://github.com/atagbacom/relay/releases) (tags `vX.Y.Z`)
- Journal du projet (étapes, problèmes, solutions) : [docs/JOURNAL.md](docs/JOURNAL.md)

## Usage prévu

Relay est conçu pour **rester joignable quand le réseau manque** : zones sans couverture, coupures, catastrophes naturelles, rassemblements qui saturent le réseau mobile, équipes de terrain. Il ne sert pas à contourner une restriction d'accès imposée par la loi, et ne doit pas être présenté ainsi.

Relay est une **preuve de concept en essai sur le terrain**. Il n'a pas fait l'objet d'un audit de sécurité indépendant : ne le confiez pas à des personnes dont la sécurité dépendrait de lui.

## Fonctionnement

1. **Chiffrement sur l'appareil.** Chaque message est chiffré avec le protocole Signal (Double Ratchet, `libsignal`) pour la seule clé du destinataire.
2. **Remise aux voisins.** Les téléphones à portée Bluetooth (une dizaine de mètres) reçoivent une copie ; 7 copies au plus circulent (*Spray and Wait*).
3. **Garde et relais.** Un relais garde la copie, en base, jusqu'à la preuve de livraison, l'accusé de réception ou l'expiration (48 h), et la représente dès que le destinataire passe à portée.
4. **Accusé de réception.** Il revient vers l'expéditeur et fait jeter les autres copies en chemin. Sans accusé, l'expéditeur renvoie automatiquement.

Les relais transportent un contenu illisible. Ils voient seulement l'identifiant de l'expéditeur, celui du destinataire et l'heure.

## Fonctionnalités

### Messagerie et réseau

- Messages chiffrés de bout en bout, livrés même si aucun chemin n'existe au moment de l'envoi.
- Découverte des téléphones Relay alentour, affichée sur un radar (distance indicative d'après la force du signal).
- Indication par contact : « à portée directe » ou « hors de portée », avec ce qui arrive au message.
- Clés de chiffrement diffusées chaque minute et transmises par les relais : on peut écrire à quelqu'un qu'on n'a jamais croisé directement.
- Téléphone revenu (Bluetooth coupé puis rallumé, nouvelle adresse) reconnu dès son premier paquet.

### Confiance

- Trois niveaux visibles sur chaque contact, qui ne font que monter : orange (premier contact), jaune (clé confirmée par le réseau), vert (vérifié en personne).
- Vérification en personne en un seul scan de code QR (code à usage unique, 5 minutes), puis comparaison de quatre mots à voix haute.
- Nom choisi par chacun, transmis signé ; un nom donné à la main reste prioritaire.

### Urgence

- Mode urgence en un geste : bouton « Urgence » dans la notification de Relay ou à l'appui long sur l'icône, puis maintenir 2 secondes. L'alerte part, chiffrée, à tous les contacts.
- Trois alertes fournies (Danger, Besoin médical, Je suis en sécurité) et alertes personnalisées.
- Alertes, accusés et vérifications passent avant les autres envois.

### Fiabilité sur un vrai téléphone

- Relais en arrière-plan, repris au redémarrage du téléphone ; demande d'exemption d'économie de batterie.
- Bluetooth éteint signalé par une notification et un bandeau, avec un bouton pour l'allumer.
- Trois modes de batterie (automatique, économie, économie maximale) qui espacent réellement le scan et l'annonce Bluetooth.
- Écran de diagnostic : état du Bluetooth, appareils vus, journal des envois, erreurs de chiffrement.

### Mises à jour hors Play Store

- « Partager Relay » envoie l'application installée par Xender, Quick Share ou Bluetooth, sans internet.
- Un téléphone qui croise une version plus récente est prévenu par le maillage.
- Avec internet, vérification une fois par jour sur GitHub Releases ; téléchargement à la demande, empreinte SHA-256 vérifiée. Android refuse toute version qui ne porte pas la même signature.

## Stack technique

| Domaine | Technologie |
| --- | --- |
| Langage | Kotlin, Android uniquement (`minSdk` 26, `targetSdk` 36) |
| Interface | Jetpack Compose, Material 3, polices IBM Plex |
| Chiffrement | Protocole Signal via `libsignal-android` |
| Transport | Bluetooth Low Energy natif : annonce, scan, GATT client et serveur, fragmentation selon la MTU |
| Format des paquets | Binaire, borné et versionné (`PacketCodec`), jamais de désérialisation Java |
| Stockage | Room (SQLite) : identité, contacts, messages, état Signal, envois en attente, copies relayées |
| Codes QR | ZXing (génération et lecture), CameraX |
| Concurrence | Coroutines Kotlin |

Composants tiers et licences : [docs/licences/COMPOSANTS.md](docs/licences/COMPOSANTS.md).

## Structure du projet

```text
relay/
├── androidApp/src/main/kotlin/.../app/
│   ├── mesh/          Bluetooth (annonce, scan, GATT), routeur Spray-and-Wait, suivi des pairs,
│   │                  service en arrière-plan, notifications, batterie, état partagé
│   ├── crypto/        Identité, sessions Signal, diffusion des clés, vérification, QR, empreinte
│   ├── model/         Paquets et contacts, format binaire
│   ├── repository/    Accès unique aux données pour l'interface et le service
│   ├── storage/       Base Room et migrations
│   └── ui/            Écrans Compose et thème
├── androidApp/src/test/kotlin/   Tests unitaires (crypto, format, fragmentation, routeur, mises à jour)
├── site/              Landing page, publiée sur GitHub Pages
├── docs/              Journal du projet et licences
└── .github/           CI : compilation, tests, démarrage sur émulateur, publication
```

## Compilation, tests et versions

Tout se compile **dans la CI GitHub Actions**, jamais sur un poste de développement.

- **Chaque pull request** : APK de débogage, tests unitaires, démarrage sur émulateur de l'APK de débogage et de la version réduite par R8. L'APK de test est publié en pré-version avec un lien direct : `releases/download/essai-pr-<numéro>/relay-essai.apk` (lien aussi dans le résumé de l'exécution).
- **Version publique** : pousser un tag `vX.Y.Z` sur `main`. La CI teste, démarre la version réduite sur émulateur, la signe avec la clé de production (secrets GitHub) et la publie en GitHub Release avec les empreintes SHA-256.
- **Landing page** : publiée sur GitHub Pages à chaque fusion qui modifie `site/`.

Les échanges entre téléphones demandent du vrai matériel Bluetooth : un émulateur n'a pas de radio BLE. Essayez sur au moins deux téléphones, trois pour le relais.

## Licence

**Àtagbà Relay** est un logiciel libre distribué sous la **GNU Affero General Public License, version 3** : voir [LICENSE](LICENSE). Toute version distribuée, modifiée ou non, doit être accompagnée de son code source. Cette licence découle de celle de libsignal.

Copyright © 2026 les auteurs d'Àtagbà Relay (voir l'historique du dépôt).
