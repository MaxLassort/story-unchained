# Fetch des métadonnées officielles (catalogue Lunii)

> Récupération, correction, stockage et service du catalogue officiel des packs Lunii.

---

## Métadonnées

- **Statut** : Actif
- **Dernière mise à jour** : 2026-08-21
- **Liens** : [README format](format/README.md) · [Modèle de nœuds](format/pack-model.md)

---

## 1. Vue d'ensemble

```
Lunii API                          Backend (api/)                          Frontend (library-web)
──────────                         ──────────────                          ─────────────────────

server-auth-prod.lunii.com
  /guest/create ──token──▶┐
                           │  POST /metadata/refresh
server-data-prod.lunii    │  ┌─────────────────────────┐
  .com/v2/packs ──JSON──▶ ├──▶ MetadataRefreshAdapter  ──▶ ~/.studio/db/official.json
                           │  │  + rewriteDeadImageUrls  │
                           │  └─────────────────────────┘
                                      │
                                      ▼
                               GET /metadata/official ──▶ pack-card : <img [src]="metadata.thumbnail">
                               (ListOfficialPacksUseCase)      (URL GCS ou Shopify, directe)
```

Deux phases indépendantes : le **refresh** (écriture du JSON, manuel) et la **lecture** (servie depuis le JSON, avec cache mémoire invalidé sur `lastModified`).

## 2. Refresh — `POST /metadata/refresh`

Chaîne : `MetadataController.refreshOfficial` → `RefreshOfficialMetadataUseCase.invoke()` → `MetadataRefreshAdapter.refreshOfficialMetadata()`.

### Étape 1 — Token invité

`GET https://server-auth-prod.lunii.com/guest/create` → extrait `response.token.server`. Sans token : `IllegalStateException`.

### Étape 2 — Base packs

`GET https://server-data-prod.lunii.com/v2/packs` avec `X-AUTH-TOKEN: <token>`, nœud `response` conservé (~600 packs, ~3 Mo). Chaque pack contient `uuid`, `localized_infos`, `age_min`, `age_max`, `duration`, `story_count`, `reference`, `slug`.

Constantes : `MetadataDb` (`infrastructure/metadata/MetadataDb.kt`). Timeouts HTTP : 10 s connect / 10 s read.

### Étape 3 — Correction des covers mortes (`rewriteDeadImageUrls`)

Certains objets GCS n'existent plus (HTTP **403**) alors que le pack est toujours vendu. Lunii miroire les fichiers sur son CDN Shopify **public** :

```
GCS     : https://storage.googleapis.com/lunii-data-prod/public/images/packs/<basename>
Shopify : https://cdn.shopify.com/s/files/1/0644/5426/2826/files/<basename>
```

Pour chaque `image_url` (toutes locales, en `parallelStream`) :
1. URL déjà absolue → conservée (idempotence).
2. `HEAD` sur l'URL GCS → 2xx : conservée.
3. sinon `HEAD` sur l'URL Shopify → 2xx : `image_url` **réécrit en absolu**.
4. sinon : originale conservée + log `warn`.

### Étape 4 — Écriture

`official.json` écrit tel quel (pretty-print) dans `studioProperties.officialJsonPath` (défaut `~/.studio/db/official.json`).

## 3. Lecture — `GET /metadata/official`

Chaîne : `MetadataController.listOfficial` → `ListOfficialPacksUseCase.invoke()` → `getOfficialMetadataMap()`.

- Le JSON est relu depuis le disque (ou cache mémoire si `lastModified` inchangé).
- Locale servie : `fr_FR` si présente, sinon première locale disponible.
- `image_url` **relative** → préfixée `THUMBNAILS_STORAGE_ROOT` ; **absolue** → prise telle quelle.
- Filtres : recherche (au moins un mot du titre), chevauchement de plage d'âge, pagination.
- Réponse : `Pack` avec `metadata.official = true`, `version = 1`, `variants = []`.

Le frontend affiche `metadata.thumbnail` **en direct dans `<img>`** — d'où l'exigence que le JSON contienne des URLs valides.

## 4. Piste GraphQL Shopify — écartée

Le site lunii.fr expose un Storefront API dont `featuredImage.url` pointe le même fichier. Non retenu : la query exige un ID produit Shopify absent d'`official.json`. La jointure par **nom de fichier** suffit.

## 5. Références code

| Rôle | Fichier |
|---|---|
| Endpoints | `pack/web/MetadataController.kt` |
| Use-case refresh | `pack/service/RefreshOfficialMetadataUseCase.kt` |
| Catalogue paginé | `pack/service/ListOfficialPacksUseCase.kt` |
| Fetch + rewrite | `pack/adapter/MetadataRefreshAdapter.kt` |
| Constantes URLs | `infrastructure/metadata/MetadataDb.kt` |
| Lecture sync BDD | `infrastructure/metadata/MetadataStore.kt` |
| DTO intermédiaire | `pack/domain/dto/OfficialMetadataDto.kt` |
| Frontend | `library-web/src/app/core/services/packs.service.ts` (`officialMode`) |

## 6. Vérification

- Relancer un refresh : `POST http://localhost:9090/metadata/refresh`, surveiller les logs.
- Contrôler le JSON : `localized_infos.fr_FR.image.image_url` du pack Astérix – Le Combat des chefs doit être l'URL Shopify absolue.
- Rejouer le scan complet : sonder en HEAD chaque `image_url` — référence : 584 GCS OK / 7 Shopify OK / 0 morts.
