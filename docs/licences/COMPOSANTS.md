# Composants tiers et licences

Relay est distribué sous la licence **GNU Affero General Public License, version 3** ([LICENSE](../../LICENSE)). Ce choix est imposé par libsignal, sous la même licence : toute version distribuée de Relay, modifiée ou non, doit être accompagnée de son code source.

## Embarqués dans l'application

| Composant | Rôle | Licence |
|---|---|---|
| libsignal (`org.signal:libsignal-android`) | Chiffrement de bout en bout, protocole Signal | AGPL-3.0 |
| ZXing (`com.google.zxing:core`) | Génération des codes QR | Apache-2.0 |
| ML Kit Barcode Scanning (`com.google.mlkit:barcode-scanning`) | Lecture des codes QR par la caméra | Conditions Google ML Kit (propriétaire), **à remplacer** |
| AndroidX CameraX | Aperçu de la caméra pour le scan | Apache-2.0 |
| Jetpack Compose, Material 3, Activity, Lifecycle, Navigation | Interface | Apache-2.0 |
| Room | Base de données locale | Apache-2.0 |
| Kotlin, kotlinx.coroutines | Langage et concurrence | Apache-2.0 |
| desugar_jdk_libs | Compatibilité Java sur Android ancien | GPL-2.0 avec exception de liaison (Classpath Exception) |
| IBM Plex Sans et Plex Mono | Polices de l'interface | SIL OFL 1.1 ([texte](OFL-IBM-Plex.txt)) |

## Utilisés seulement pour les tests et la compilation

| Composant | Licence |
|---|---|
| JUnit 4 | EPL-1.0 |
| kotlinx-coroutines-test | Apache-2.0 |
| Gradle, Android Gradle Plugin, KSP | Apache-2.0 |

## Point ouvert

ML Kit est un composant propriétaire de Google embarqué dans une application AGPL, et il dépend des services Google. Il doit être remplacé par une lecture des codes QR avec ZXing, déjà présent, pour une distribution sans ambiguïté et hors Google Play (F-Droid, téléphones sans services Google).
