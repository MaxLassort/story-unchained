# Images de chapitre (génération)

## Pourquoi

Chaque chapitre d'une histoire doit avoir une image (nœud `StageNode.image`). Plutôt que
d'obliger l'utilisateur à fournir une image pour chaque chapitre, le back peut en produire
une automatiquement, au format Lunii (PNG 320×240).

## Choix

- **Blanc sur fond noir, 320×240** : le format d'image attendu par la Lunii ; la conversion
  FS ultérieure (BMP 4-bpp RLE 320×240) réutilise l'existant au moment de `POST /packs/{id}/convert`.
- **Hiérarchie à la finalisation** : image uploadée par l'utilisateur → sinon icône de la
  bibliothèque → sinon chiffre du chapitre généré.
- **Conversion SVG immédiate** : un SVG uploadé est converti dès l'envoi
  (`POST /stories/images/render` → PNG 320×240), le front reçoit le PNG (retour visuel
  direct) et le draft ne stocke que du PNG/JPEG. Le SVG brut ne transite jamais vers le draft.
- **Bibliothèque Lucide : 4 icônes embarquées + fetch à la volée** : un petit fallback
  offline (`resources/icons/`, licence ISC) ; **tout icône Lucide est fetché à la volée par
  son slug** (kebab-case, ex. `moon-star`) depuis `cdn.jsdelivr.net/npm/lucide-static/icons/{slug}.svg`,
  avec cache mémoire (500 entrées max). La recherche couvre le catalogue complet (~2000 icônes)
  via l'API jsDelivr (liste cachée 24 h), fallback sur la liste embarquée si l'API est injoignable.
- **Pur Kotlin, zéro dépendance** : parseur SVG minimal (`d` des `<path>` → `Path2D` Java 2D)
  — pas de Batik ni autre bibliothèque de rendu SVG.
- **Rendu simple** : l'icône est téléchargée telle quelle (Lucide, tout en traits `stroke`)
  et dessinée **en blanc sur fond noir** — pas d'inversion de couleurs, pas de gestion des
  formes/fills.

## Comment ça marche

```
GET /stories/images/icons                    → liste des icônes embarquées [{id, name}]
GET /stories/images/icons/search?q=moon      → recherche dans tout le catalogue Lucide
GET /stories/images/preview?iconId=star      → PNG 320×240 (embarquée, sinon fetchée à la volée)
GET /stories/images/preview?chapterNumber=1  → PNG 320×240 (chiffre généré)
POST /stories/images/render (multipart .svg) → PNG 320×240 (conversion immédiate)
```

**Identification d'un icône Lucide** : son **slug kebab-case** (ex. `moon-star`, `book-open`),
identique au nom de fichier dans le package `lucide-static` et à l'URL `lucide.dev/icons/{slug}`.
Le slug est aussi l'`id` renvoyé par les endpoints ci-dessus.

- `ChapterImageGenerator.generate(chapterNumber)` : chiffre blanc (#FFF) centré, fond noir
  (#000), typographie adaptative.
- `SvgIconRenderer.render(svg)` : parse les `<path d="...">` (M/L/H/V/C/S/Q/T/A/Z, absolu et
  relatif, arcs → segments, flags d'arc `0`/`1` collés aux nombres), scale depuis le
  `viewBox`, ratio préservé, centrage, **trait blanc (`stroke-width` du SVG, défaut 1)
  sur fond noir**. Couleurs, fills, formes non-path et texte ignorés. SVG sans path →
  `IllegalArgumentException` → 400.
- Rendu vérifié en test : les 4 icônes embarquées passent toutes (dimensions + pixels blancs).

## API

| Endpoint | Paramètres | Réponse | Erreurs |
|---|---|---|---|
| `GET /stories/images/icons` | — | `{icons: [{id, name}]}` | — |
| `GET /stories/images/icons/search` | `q` (≥ 2 chars) | `{icons: [{id, name}]}` (max 50) | 400 (query trop courte) |
| `GET /stories/images/preview` | `iconId` **ou** `chapterNumber` | `image/png` | 400 (aucun/les deux), 404 (icône inconnue) |
| `POST /stories/images/render` | `file` (multipart, `.svg`) | `image/png` | 400 (vide, non-SVG, SVG invalide) |

## Où

- `pack/format/utils/ChapterImageGenerator.kt` : génération du chiffre
- `pack/format/utils/SvgIconRenderer.kt` : parseur/rendu SVG → PNG
- `pack/service/ChapterIconCatalogService.kt` : catalogue embarqué + fetch à la volée + recherche
- `pack/web/ChapterImageController.kt` : endpoints ci-dessus
- `resources/icons/*.svg` : 4 icônes Lucide (ISC, fallback offline)

## Contraintes

- Rendu en **trait blanc** uniquement : `<path>` + formes de base (`<circle>`, `<ellipse>`,
  `<rect>`, `<line>`, `<polyline>`, `<polygon>`) dessinés en contour ; fills, couleurs,
  texte, dégradés et filtres ignorés. Un SVG utilisateur avec des formes remplies ne
  rendra que leurs contours.
- Le fetch à la volée dépend du CDN jsDelivr : hors-ligne, seules les icônes embarquées
  sont disponibles (le slug inconnu → 404).
- PNG 320×240 ; la conversion BMP/FS se fait à la finalisation/conversion, pas ici.
