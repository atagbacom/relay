# Landing page de Relay

Page statique, sans compilation. Publiée sur GitHub Pages par `.github/workflows/site.yml` à chaque fusion dans `main` qui modifie ce dossier : https://atagbacom.github.io/relay/

## Passer au domaine relay.atagba.com

Une fois atagba.com acheté :

1. DNS : enregistrement `CNAME relay → atagbacom.github.io`.
2. Dépôt : *Settings › Pages › Custom domain* = `relay.atagba.com`, puis cocher *Enforce HTTPS*.
3. Remplacer `https://atagbacom.github.io/relay/` par `https://relay.atagba.com/` dans `index.html`, `sitemap.xml` et `robots.txt`, et regénérer `og-relay.png` avec la nouvelle adresse.
4. Google Search Console : déclarer le domaine et soumettre `sitemap.xml`.
