# Images de chapitre (génération)

> Génération automatique d'images de chapitre : chiffres, icônes Lucide, conversion SVG.

---

## Métadonnées

- **Statut** : Actif
- **Dernière mise à jour** : 2026-08-21
- **Liens** : [Images (format)](format/images.md) · [Plan étape 4](../plans/story-creation-plan.md)

---

## 1. Contexte

Chaque chapitre d'une histoire doit avoir une image (nœud `StageNode.image`). Plutôt que d'obliger l'utilisateur à fournir une image pour chaque chapitre, le back peut en produire une automatiquement, au format Lunii (PNG 320×240).

### Choix de conception

- **Blanc sur fond noir, 320×240** : le format d'image attendu par la Lunii.
- **Hiérarchie à la finalisation** : image uploadée → icône Lucide → chiffre généré.
- **Conversion SVG immédiate** : un SVG uploadé est converti dès l'envoi (`POST /stories/images/render` → PNG 320×240), le front reçoit le PNG et le draft ne stocke que du PNG/JPEG.
- **Bibliothèque Lucide** : 4 icônes embarquées + fetch à la volée par slug depuis `cdn.jsdelivr.net/npm/lucide-static/icons/{slug}.svg`, avec cache mémoire (500 entrées max).
- **Pur Kotlin, zéro dépendance** : parseur SVG minimal (`d` des `<path>` → `Path2D` Java 2D).

## 2. API

| Endpoint | Méthode | Paramètres | Réponse | Description |
|---|---|---|---|---|
| `/stories/images/icons` | GET | — | `{icons: [{id, name}]}` | Liste des icônes embarquées |
| `/stories/images/icons/search` | GET | `q` (≥ 2 chars) | `{icons: [{id, name}]}` | Recherche dans tout le catalogue Lucide (max 50) |
| `/stories/images/preview` | GET | `iconId` **ou** `chapterNumber` | `image/png` | Préview 320×240 |
| `/stories/images/render` | POST | `file` (multipart, `.svg`) | `image/png` | Conversion SVG → PNG immédiate |

### Codes d'erreur

| Code | Signification |
|---|---|
| `400` | Query trop courte, aucun/les deux paramètres, SVG invalide |
| `404` | Icône inconnue |

## 3. Implémentation

### `ChapterImageGenerator`

Génère un chiffre blanc (#FFF) centré sur fond noir (#000), typographie adaptative, en PNG 320×240.

### `SvgIconRenderer`

Parse les `<path d="...">` (M/L/H/V/C/S/Q/T/A/Z, absolu et relatif), scale depuis le `viewBox`, ratio préservé, centrage, **trait blanc sur fond noir**. Couleurs, fills, formes non-path et texte ignorés.

### `ChapterIconCatalogService`

Catalogue embarqué + fetch à la volée depuis jsDelivr + recherche dans le catalogue complet (~2000 icônes) via l'API jsDelivr (liste cachée 24 h).

## 4. Références code

| Rôle | Fichier |
|---|---|
| Génération du chiffre | `pack/format/utils/ChapterImageGenerator.kt` |
| Parseur/rendu SVG | `pack/format/utils/SvgIconRenderer.kt` |
| Catalogue icônes | `pack/service/ChapterIconCatalogService.kt` |
| Endpoints | `pack/web/ChapterImageController.kt` |
| Icônes embarquées | `resources/icons/*.svg` (4 fichiers, licence ISC) |

## 5. Contraintes

- Rendu en **trait blanc** uniquement : `<path>` + formes de base dessinés en contour.
- Le fetch à la volée dépend du CDN jsDelivr : hors-ligne, seules les icônes embarquées sont disponibles.
- PNG 320×240 ; la conversion BMP/FS se fait à la finalisation/conversion, pas ici.
