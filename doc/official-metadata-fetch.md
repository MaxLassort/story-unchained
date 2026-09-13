# Fetch des métadonnées officielles (catalogue Lunii)

Comment le backend récupère, corrige, stocke puis sert le catalogue officiel des packs.
L'affichage frontend utilise **directement** l'URL du champ `thumbnail` — aucun proxy backend.

## Vue d'ensemble

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

Deux phases indépendantes : le **refresh** (écriture du JSON, manuel) et la **lecture**
(servie depuis le JSON, avec cache mémoire invalidé sur `lastModified`).

## 1. Refresh — `POST /metadata/refresh`

Chaîne : `MetadataController.refreshOfficial` → `RefreshOfficialMetadataUseCase.invoke()`
→ `MetadataRefreshAdapter.refreshOfficialMetadata()`
(`api/src/main/kotlin/com/maxlass/studio/pack/adapter/MetadataRefreshAdapter.kt`).

### Étape 1 — token invité

`GET https://server-auth-prod.lunii.com/guest/create` → extrait
`response.token.server`. Sans token : `IllegalStateException`.

### Étape 2 — base packs

`GET https://server-data-prod.lunii.com/v2/packs` avec `X-AUTH-TOKEN: <token>`,
nœud `response` conservé (~600 packs, ~3 Mo). Chaque pack contient `uuid`,
`localized_infos` (par locale : `title`, `description`, `image.image_url`),
`age_min`, `age_max`, `duration`, `story_count`, `reference`, `slug`.

Constantes : `MetadataDb` (`infrastructure/metadata/MetadataDb.kt`).
Timeouts HTTP : 10 s connect / 10 s read.

### Étape 3 — correction des covers mortes (`rewriteDeadImageUrls`)

Certains objets GCS n'existent plus (HTTP **403**) alors que le pack est toujours
vendu — typiquement du contenu sous licence (constaté : 4 Astérix, 2 Harry Potter,
1 Colo de la Belle Étoile). Lunii miroire les mêmes fichiers sur son CDN Shopify
**public**, sous le **nom de fichier identique** :

```
GCS     : https://storage.googleapis.com/lunii-data-prod/public/images/packs/<basename>
Shopify : https://cdn.shopify.com/s/files/1/0644/5426/2826/files/<basename>
              (constante SHOPIFY_IMAGES_ROOT ; ?v= optionnel, ?width= supporté)
```

Pour chaque `localized_infos.*.image.image_url` (toutes locales, en `parallelStream`) :
1. URL déjà absolue (`http…`) → conservée (idempotence entre refresh) ;
2. `HEAD` sur l'URL GCS → 2xx : conservée ;
3. sinon `HEAD` sur l'URL Shopify déduite du basename → 2xx : `image_url`
   **réécrite en absolu** dans le JSON ;
4. sinon : originale conservée + log `warn` (aucun cas constaté : 584 GCS OK, 7
   rattrapés Shopify, 0 morts des deux côtés sur 591 `image_url`).

Logs : `info` par substitution, `warn` si irrécupérable.

### Étape 4 — écriture

`official.json` écrit tel quel (pretty-print) dans `studioProperties.officialJsonPath`
(défaut `~/.studio/db/official.json`).

## 2. Lecture — `GET /metadata/official?page&size&locale&search&ageMin&ageMax`

Chaîne : `MetadataController.listOfficial` → `ListOfficialPacksUseCase.invoke()`
(`pack/service/ListOfficialPacksUseCase.kt`) → `getOfficialMetadataMap()`.

- Le JSON est relu depuis le disque (ou cache mémoire si `lastModified` inchangé),
  enveloppe `response` tolérée.
- Locale servie : `fr_FR` si présente, sinon première locale disponible ; le filtre
  `locale=fr_FR` compare ce champ.
- `image_url` **relative** → préfixée `THUMBNAILS_STORAGE_ROOT` (GCS) ;
  `image_url` **absolue** → prise telle quelle (packs corrigés au refresh, cf. §1).
  Même règle dans `MetadataStore.toOfficialMetadata` (utilisé par le sync BDD).
- Filtres : recherche (au moins un mot du titre, insensible à la casse),
  chevauchement de plage d'âge ; pagination `page`/`size` (size 1–200).
- Réponse : `Pack` avec `metadata.official = true`, `version = 1`, `variants = []`.

Le frontend (`PacksService.officialMode`) appelle cet endpoint et `pack-card` /
`pack-detail` affichent `metadata.thumbnail` **en direct dans `<img>`** —
d'où l'exigence que le JSON contienne des URLs valides (travail fait au refresh).

## 3. Piste GraphQL Shopify — écartée (contexte)

Le site lunii.fr expose un Storefront API (`lunii-prod.myshopify.com/api/…/graphql.json`,
query `getProductById`) dont `featuredImage.url` pointe le même fichier. Non retenu :
la query exige un **ID produit Shopify** absent d'`official.json`, nécessiterait le
token storefront, et coûterait ~259 unités/requête en unitaire. La jointure par
**nom de fichier** suffit (`reference` = SKU, `slug` = handle, basename identique —
vérifié). Fallback envisageable si un basename ne matchait plus un jour :
`productByHandle` avec le `slug` (+ token storefront).

## 4. Fichiers de référence

| Fichier | Rôle |
|---|---|
| `api/…/pack/web/MetadataController.kt` | `POST /metadata/refresh`, `GET /metadata/official` |
| `api/…/pack/service/RefreshOfficialMetadataUseCase.kt` | use-case refresh |
| `api/…/pack/service/ListOfficialPacksUseCase.kt` | catalogue paginé/filtré |
| `api/…/pack/adapter/MetadataRefreshAdapter.kt` | fetch Lunii, rewrite covers, lecture JSON |
| `api/…/infrastructure/metadata/MetadataDb.kt` | constantes URLs (`THUMBNAILS_STORAGE_ROOT`, `SHOPIFY_IMAGES_ROOT`, …) |
| `api/…/infrastructure/metadata/MetadataStore.kt` | lecture `toOfficialMetadata` (sync BDD) |
| `api/…/pack/domain/dto/OfficialMetadataDto.kt` | DTO intermédiaire |
| `library-web/src/app/core/services/packs.service.ts` | `officialMode` → `/metadata/official` |

## 5. Vérifier / re-tester

- Relancer un refresh : `POST http://localhost:9090/metadata/refresh`, surveiller les
  logs `Cover unreachable on GCS, using Shopify CDN for pack …`.
- Contrôler le JSON : `localized_infos.fr_FR.image.image_url` du pack
  `9096b340-ec42-4d8b-a708-48a6bcba8668` (Astérix – Le Combat des chefs) doit être
  l'URL Shopify absolue ; `GET /metadata/official?locale=fr_FR` doit la retourner
  dans `metadata.thumbnail`.
- Rejouer le scan complet : sonder en HEAD chaque `image_url` (GCS puis Shopify
  déduit du basename) et compter `GCS OK / Shopify OK / morts` — référence : 584 / 7 / 0.
