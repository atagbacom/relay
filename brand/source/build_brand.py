"""Génère toute la marque Relay à partir de ce script.

    python3 brand/source/build_brand.py

Dépendance : Pillow. Polices IBM Plex (SIL OFL 1.1) et monogramme Àtagbà dans
brand/source/. Le symbole reprend exactement l'icône de l'application
(androidApp/src/main/res/drawable/ic_launcher_foreground.xml) : un point or et
deux ondes, sur le bleu nuit de Relay.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SOURCE = Path(__file__).resolve().parent
SORTIE = SOURCE.parent

# Palette (identique à RelayPalette dans l'application)
NUIT = (11, 26, 48)          # #0B1A30 bleu nuit profond : fond
NUIT_2 = (19, 41, 75)        # #13294B bleu nuit
TOPO = (31, 58, 99)          # #1F3A63 lignes de niveau
OR = (242, 163, 47)          # #F2A32F or
REMIS = (158, 220, 163)      # #9EDCA3 vert « remis »
IVOIRE = (233, 238, 245)     # #E9EEF5 texte sur fond sombre
BRUME = (169, 182, 201)      # #A9B6C9 texte secondaire

K = 4  # suréchantillonnage : dessin à 4x puis réduction, bords lisses


def police(nom, taille):
    return ImageFont.truetype(str(SOURCE / f"{nom}.ttf"), taille * K)


def melange(c, fond, alpha):
    return tuple(round(fond[i] + (c[i] - fond[i]) * alpha) for i in range(3))


def symbole(d, cx, cy, unite, couleur=OR, fond=None):
    """Le point et les deux ondes. unite = rayon de l'onde extérieure / 27.5 (repère de l'icône)."""
    def anneau(r, ep, alpha):
        col = couleur + (round(255 * alpha),) if fond is None else melange(couleur, fond, alpha)
        e = ep * unite
        d.ellipse([cx - r * unite, cy - r * unite, cx + r * unite, cy + r * unite], outline=col, width=max(1, round(e)))
    anneau(27.5, 2.4, 0.45)
    anneau(17.5, 3.2, 0.78)
    r = 7.5 * unite
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=couleur if fond is None else couleur)


def reduire(im, taille):
    return im.resize(taille, Image.LANCZOS)


def tuile(taille, arrondi=True):
    """Icône : symbole sur bleu nuit avec deux lignes de niveau, coins arrondis ou carré plein."""
    t = taille * K
    im = Image.new("RGBA", (t, t), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rayon = round(t * 0.2237) if arrondi else 0
    d.rounded_rectangle([0, 0, t - 1, t - 1], radius=rayon, fill=NUIT)
    # lignes de niveau (repère 108 de l'icône Android)
    s = t / 108
    for y0, y1 in ((30, 26), (82, 78)):
        pts = [(x * s, (y0 + (y1 - y0) * x / 108 + 4 * __import__("math").sin(x / 108 * 6.28)) * s) for x in range(-4, 113, 2)]
        d.line(pts, fill=TOPO, width=max(1, round(s)))
    symbole(d, t / 2, t / 2, s, fond=NUIT)
    if arrondi:
        masque = Image.new("L", (t, t), 0)
        ImageDraw.Draw(masque).rounded_rectangle([0, 0, t - 1, t - 1], radius=rayon, fill=255)
        im.putalpha(masque)
    return reduire(im, (taille, taille))


def logo_horizontal(texte_couleur, fond=None, hauteur=240, sous_titre=None):
    """Symbole à gauche, « Relay » à droite ; sous-titre facultatif."""
    h = hauteur * K
    f = police("plex_sans_semibold", round(hauteur * 0.52))
    fs = police("plex_sans_regular", round(hauteur * 0.15)) if sous_titre else None
    mesure = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    larg_texte = mesure.textbbox((0, 0), "Relay", font=f)[2]
    larg_sous = mesure.textbbox((0, 0), sous_titre, font=fs)[2] if sous_titre else 0
    marge = round(h * 0.12)
    cote = h - 2 * marge
    l = marge + cote + round(h * 0.16) + max(larg_texte, larg_sous) + marge
    im = Image.new("RGBA", (l, h), (fond + (255,)) if fond else (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    symbole(d, marge + cote / 2, h / 2, cote / 2 / 28.7, fond=fond)
    x = marge + cote + round(h * 0.16)
    if sous_titre:
        d.text((x, h * 0.47), "Relay", font=f, fill=texte_couleur, anchor="ls")
        d.text((x, h * 0.72), sous_titre, font=fs, fill=BRUME if fond else texte_couleur, anchor="ls")
    else:
        d.text((x, h / 2), "Relay", font=f, fill=texte_couleur, anchor="lm")
    return reduire(im, (l // K, h // K))


def logo_par_atagba(hauteur=240):
    """Logo Relay + « par ÀTAGBA » avec le monogramme : signature de la marque mère."""
    base = logo_horizontal(IVOIRE, fond=NUIT, hauteur=hauteur)
    w, h = base.size
    bande = round(h * 0.42)
    im = Image.new("RGBA", (w, h + bande), NUIT + (255,))
    im.paste(base, (0, 0))
    d = ImageDraw.Draw(im)
    mono = Image.open(SOURCE / "atagba-monogramme.png").convert("RGBA")
    m = round(bande * 0.62)
    mono = mono.resize((m, m), Image.LANCZOS)
    f = ImageFont.truetype(str(SOURCE / "plex_sans_regular.ttf"), round(bande * 0.34))
    x0 = round(h * 0.12)
    d.text((x0, h + bande / 2), "un projet", font=f, fill=BRUME, anchor="lm")
    xt = x0 + d.textbbox((0, 0), "un projet ", font=f)[2]
    im.alpha_composite(mono, (xt, round(h + (bande - m) / 2)))
    d.text((xt + m + round(bande * 0.12), h + bande / 2), "Àtagbà", font=f, fill=IVOIRE, anchor="lm")
    return im


def visuel(largeur, hauteur, titre, sous_titre, adresse=None):
    """Visuel large (vitrine, partage) : texte à gauche, trajet d'un message à droite."""
    W, H = largeur * K, hauteur * K
    im = Image.new("RGB", (W, H), NUIT)
    d = ImageDraw.Draw(im)
    u = H / 630
    cx, cy = W * 0.75, H * 0.5
    for i in range(1, 5):
        r = 70 * i * u
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=melange(BRUME, NUIT, 0.22 - i * 0.035), width=max(1, round(2 * u)))
    pts = [(cx - 140 * u, cy + 85 * u), (cx - 40 * u, cy - 45 * u), (cx + 80 * u, cy + 65 * u), (cx + 190 * u, cy - 105 * u)]
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        for t in range(0, 18, 2):
            a, b = t / 18, (t + 1) / 18
            d.line([(x1 + (x2 - x1) * a, y1 + (y2 - y1) * a), (x1 + (x2 - x1) * b, y1 + (y2 - y1) * b)], fill=OR, width=round(3 * u))
    for i, (x, y) in enumerate(pts):
        r = (13 if i in (0, 3) else 9) * u
        d.ellipse([x - r, y - r, x + r, y + r], fill=REMIS if i == 3 else OR)
    fm = ImageFont.truetype(str(SOURCE / "plex_mono_regular.ttf"), round(24 * u))
    d.text((pts[0][0], pts[0][1] + 28 * u), "Ama", font=fm, fill=IVOIRE, anchor="mt")
    d.text((pts[3][0], pts[3][1] - 28 * u), "Koffi", font=fm, fill=IVOIRE, anchor="mb")
    # identité et texte
    x = 80 * u
    symbole(d, x + 26 * u, 118 * u, 26 * u / 28.7, fond=NUIT)
    d.text((x + 66 * u, 118 * u), "Relay", font=ImageFont.truetype(str(SOURCE / "plex_sans_semibold.ttf"), round(42 * u)), fill=OR, anchor="lm")
    ft = ImageFont.truetype(str(SOURCE / "plex_sans_semibold.ttf"), round(66 * u))
    y = 200 * u
    for ligne in titre:
        d.text((x, y), ligne, font=ft, fill=IVOIRE)
        y += 80 * u
    fs = ImageFont.truetype(str(SOURCE / "plex_sans_regular.ttf"), round(26 * u))
    d.text((x, y + 26 * u), sous_titre, font=fs, fill=BRUME)
    if adresse:
        d.text((x, H - 80 * u), adresse, font=ImageFont.truetype(str(SOURCE / "plex_mono_regular.ttf"), round(22 * u)), fill=BRUME, anchor="ls")
    return reduire(im, (largeur, hauteur))


SVG_SYMBOLE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="24.5 24.5 59 59" role="img" aria-label="Relay">
  <circle cx="54" cy="54" r="27.5" fill="none" stroke="{c}" stroke-width="2.4" stroke-opacity="0.45"/>
  <circle cx="54" cy="54" r="17.5" fill="none" stroke="{c}" stroke-width="3.2" stroke-opacity="0.78"/>
  <circle cx="54" cy="54" r="7.5" fill="{c}"/>
</svg>
"""

SVG_ICONE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" role="img" aria-label="Relay">
  <rect width="108" height="108" rx="24" fill="#0B1A30"/>
  <path d="M-4,30 C18,22 34,40 56,32 S92,18 112,26" fill="none" stroke="#1F3A63"/>
  <path d="M-4,82 C20,74 38,92 60,84 S94,72 112,78" fill="none" stroke="#1F3A63"/>
  <circle cx="54" cy="54" r="27.5" fill="none" stroke="#F2A32F" stroke-width="2.4" stroke-opacity="0.45"/>
  <circle cx="54" cy="54" r="17.5" fill="none" stroke="#F2A32F" stroke-width="3.2" stroke-opacity="0.78"/>
  <circle cx="54" cy="54" r="7.5" fill="#F2A32F"/>
</svg>
"""


def main():
    for dossier in ("symbole", "icone", "logo", "visuels"):
        (SORTIE / dossier).mkdir(exist_ok=True)
    (SORTIE / "symbole/relay-symbole.svg").write_text(SVG_SYMBOLE.format(c="#F2A32F"))
    (SORTIE / "symbole/relay-symbole-blanc.svg").write_text(SVG_SYMBOLE.format(c="#FFFFFF"))
    (SORTIE / "symbole/relay-symbole-nuit.svg").write_text(SVG_SYMBOLE.format(c="#0B1A30"))
    (SORTIE / "icone/relay-icone.svg").write_text(SVG_ICONE)

    tuile(1024).save(SORTIE / "icone/relay-icone-1024.png", optimize=True)
    tuile(512, arrondi=False).convert("RGB").save(SORTIE / "icone/relay-icone-store-512.png", optimize=True)
    tuile(192).save(SORTIE / "icone/relay-icone-192.png", optimize=True)

    logo_horizontal(IVOIRE).save(SORTIE / "logo/relay-logo-fond-sombre.png", optimize=True)
    logo_horizontal(NUIT_2).save(SORTIE / "logo/relay-logo-fond-clair.png", optimize=True)
    logo_horizontal(IVOIRE, fond=NUIT).save(SORTIE / "logo/relay-logo-nuit.png", optimize=True)
    logo_horizontal(IVOIRE, fond=NUIT, sous_titre="Messagerie sans internet").save(SORTIE / "logo/relay-logo-signature.png", optimize=True)
    logo_par_atagba().save(SORTIE / "logo/relay-logo-par-atagba.png", optimize=True)

    titre = ["Écrire quand", "le réseau ne", "passe plus"]
    sous = "Messagerie sans internet · Bluetooth · chiffrée"
    visuel(1200, 630, titre, sous, "atagbacom.github.io/relay").save(SORTIE / "visuels/relay-partage-1200x630.png", optimize=True)
    visuel(1600, 1000, titre, sous).save(SORTIE / "visuels/relay-vitrine-1600x1000.png", optimize=True)
    print("Marque Relay générée dans", SORTIE)


if __name__ == "__main__":
    main()
