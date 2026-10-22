# Marque Relay

**Relay** est le nom affiché (logo, application, site, vitrine). **Àtagbà Relay** est le nom à utiliser dans tout document juridique : dépôt de marque, contrats, licences, mentions légales.

Relay est une marque fille d'Àtagbà. Elle garde l'or d'Àtagbà mais remplace l'encre noire par le bleu nuit, couleur de la nuit et des cartes, pour un outil qui sert quand le réseau tombe.

Tout se régénère avec `python3 brand/source/build_brand.py` (Pillow). Le symbole reprend exactement l'icône de l'application (`androidApp/src/main/res/drawable/ic_launcher_foreground.xml`) : si l'un change, l'autre suit.

## Le symbole

Un point or et deux ondes : un téléphone qui porte le message aux autres autour de lui. L'onde intérieure est plus marquée que l'extérieure, comme un signal qui s'affaiblit avec la distance.

## Palette

| Rôle | Hex | Usage |
|---|---|---|
| Bleu nuit profond | `#0B1A30` | fond principal, tuile de l'icône |
| Bleu nuit | `#13294B` | surfaces, texte du logo sur fond clair |
| Lignes de niveau | `#1F3A63` | motif discret de l'icône, filets |
| Or | `#F2A32F` | symbole, boutons, ce qui bouge ou ce qui compte |
| Vert « remis » | `#9EDCA3` | message livré, destinataire atteint ; jamais décoratif |
| Ivoire | `#E9EEF5` | texte sur fond sombre |
| Brume | `#A9B6C9` | texte secondaire sur fond sombre |

L'or est rare : il marque une seule chose par écran ou par visuel. Le vert n'apparaît que pour une livraison réussie.

## Typographie

- **IBM Plex Sans** (SemiBold pour les titres et le logo, Regular pour le texte).
- **IBM Plex Mono** pour les identifiants, empreintes et noms dans les schémas.

Les deux sont sous licence SIL OFL 1.1 et embarquées dans l'application, qui marche hors ligne.

## Fichiers

| Fichier | Quand l'utiliser |
|---|---|
| `symbole/relay-symbole.svg` | le symbole or, fond transparent : en-têtes, petits formats |
| `symbole/relay-symbole-blanc.svg` / `-nuit.svg` | version d'une seule couleur (impression, fond photo) |
| `icone/relay-icone.svg` | icône complète (tuile bleu nuit), vectorielle |
| `icone/relay-icone-1024.png` | icône arrondie, présentations et vitrine |
| `icone/relay-icone-store-512.png` | Play Store : carré plein, l'OS arrondit |
| `icone/relay-icone-192.png` | petits usages web |
| `logo/relay-logo-fond-sombre.png` | symbole + Relay, texte ivoire, fond transparent |
| `logo/relay-logo-fond-clair.png` | symbole + Relay, texte bleu nuit, fond transparent |
| `logo/relay-logo-nuit.png` | logo sur tuile bleu nuit |
| `logo/relay-logo-signature.png` | logo + « Messagerie sans internet » |
| `logo/relay-logo-par-atagba.png` | logo + « un projet Àtagbà » : vitrine, documents Àtagbà |
| `visuels/relay-partage-1200x630.png` | aperçu de lien (Open Graph) |
| `visuels/relay-vitrine-1600x1000.png` | carte du projet sur la vitrine Àtagbà |

## Règles

- Fond sombre de préférence : le symbole est pensé pour le bleu nuit.
- En dessous de 32 px, le symbole seul : le mot devient illisible.
- Marge autour du logo : au moins le rayon du point central de chaque côté.
- Ne pas recolorer le symbole (sauf versions blanche et bleu nuit fournies), ne pas l'étirer, ni ajouter d'ombre ou de contour.
- Le schéma du trajet (Ama → relais → Koffi) est l'image de marque des visuels : un message qui passe de téléphone en téléphone, le dernier point en vert quand il est remis.
- Dans un document juridique, écrire « Àtagbà Relay ».
