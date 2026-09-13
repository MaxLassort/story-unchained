# Plan — Recherche packs : filtre nom (include / insensible à la casse) + filtre âge

État initial : voir `plans/pack-search-refactor.md`.
Réponses utilisateur intégrées :
- [x] Filtre nom : au moins 1 token présent dans le titre (insensible à la casse).
- [x] Pack avec âge : inclus par défaut.
- [x] Le bouton qui affiche les packs officiels de la lib devient un **toggle**.
- [x] Toggle = filtre la liste courante.
- [x] UI âge = **slider**.
- [x] Confusion fetch/display official library clarifiée : il y a bien un bouton fetch, mais il sert **uniquement à rafraîchir le JSON**. Le bouton qu'on peut appeler "display official library" sert **juste à afficher les packs de ce JSON**. C'est une vue identique mais la base de données est le JSON, c'est tout.
- [ ] Points en suspans restants : voir étape 5 (placement toggle), étape 6 (comportement exact display official library), point slider (champ min et max ou slider de plage ?).

---

## Étape 0 — Baseline (résumé)

### Back

- [ ] `PackFilter` :
  ```kotlin
  data class PackFilter(
      val search: String? = null,
      val official: Boolean? = null,
      val locale: String? = null,
      val inLibrary: Boolean? = null,
  )
  ```
- [ ] `PackRepositoryAdapter.getFilteredPacksPage(...)` :
  - filtre texte actuel : `meta.title?.contains(filter.search, ignoreCase = true) == true`.
  - pas de full-text / LIKE / nativeQuery dans le projet → implémentation manuelle.
- [ ] `PackMetadata` : `ageMin / ageMax (Int?)`.
- [ ] Contrôleur : `PackController.listPacks(...)` expose `search`, `official`, `locale`, `inLibrary`.

### Front

- [ ] `PacksService` :
  - signaux : `page`, `pageSize`, `searchTerm`, `showOfficial`, `showFrFr`, `showUnavailable`.
  - `httpResource` vers `/packs` avec paramètres : `page`, `size`, `search`, `official=false` si `showOfficial` est faux, `locale=fr_FR` si `showFrFr`, `inLibrary=true` si `showUnavailable` est faux.
- [ ] `PackFiltersComponent` : chips Official / Show unavailable / French only + tri.
- [ ] `PackListComponent` : bind filtres, affiche la grid.

### Endpoints existants identifiés

- [ ] `POST /metadata/refresh` : rafraîchit le JSON officiel (use case `RefreshOfficialMetadataUseCase`).
- [ ] `GET /packs?search=...&official=...&locale=...&inLibrary=...` : liste paginée avec filtres.
- [ ] `GET /packs/all` : tous les packs (sans pagination ni filtre).

---

## Étape 1 — Back : filtre nom (au moins 1 token, insensible à la casse) ✅

- [x] Implémentation :
  - [x] Si `search` n'est pas null/blank, split sur espace (`\s+`).
  - [x] Si la liste de tokens est vide après split (ou tous tokens vides) → pas de filtre.
  - [x] Sinon : un pack est conservé si son titre contient au moins un token (ignoreCase).
  - [x] Comportement : **au moins 1 token présent**.
- [x] Remplacer dans `PackRepositoryAdapter.getFilteredPacksPage(...)` :
  - [x] `meta.title?.contains(filter.search, ignoreCase = true) == true`
  - [x] → nouvelle logique à plusieurs tokens (helper privé `searchTokens`).
- [x] Garder la compatibilité avec les autres filtres (`official`, `locale`, `inLibrary`).
- [x] Tests (`api/src/test/kotlin/com/maxlass/studio/pack/adapter/PackRepositoryAdapterFilterTest.kt`) — réécrits pour tester le vrai adapter via mockk (ancien fichier cassé : contenu dupliqué + champs `ageMin/ageMax` de l'étape 2) :
  - [x] `search="disney"` match `"Story Disney 2023"`, `"disney"`, `"DISNEY"`.
  - [x] `search="disney marvel"` match un titre contenant `"disney"` seul, `"marvel"` seul, ou les deux.
  - [x] `search=" "` ou `search=""` → pas de filtre.
  - [x] Pas de `search` → tous les packs.
  - [x] Combinaisons : search + official, search + locale, search + inLibrary.
  - [x] Cas limites : titre null, titre vide, tokens vides après split (`\t`, espaces multiples).
  - [x] Bonus : official seul, inLibrary seul, search inconnu → page vide + total 0.
- [x] `./gradlew test` : 13/13 verts, suite complète OK.

---

## Étape 2 — Back : filtre âge (plage ageMin + ageMax) ✅

- [x] Étendre `PackFilter` :
  ```kotlin
  data class PackFilter(
      val search: String? = null,
      val official: Boolean? = null,
      val locale: String? = null,
      val inLibrary: Boolean? = null,
      val ageMin: Int? = null,
      val ageMax: Int? = null,
  )
  ```
- [x] Règle de filtrage âge :
  - [x] Un pack avec `[packAgeMin, packAgeMax]` est conservé si chevauchement avec `[reqMin, reqMax]` :
    ```
    (reqMin == null || packAgeMax >= reqMin) &&
    (reqMax == null || packAgeMin <= reqMax)
    ```
  - [x] Pack sans âge (`ageMin == null` ou `ageMax == null`) → **inclus par défaut** (y compris âge partiel).
- [x] Intégration dans `PackRepositoryAdapter.getFilteredPacksPage(...)` :
  - [x] Ajouter le filtre âge dans la passe de filtrage de metadata (helper `ageMatches`).
  - [x] Garder les filtres existants.
- [x] Contrôleur :
  - [x] `@RequestParam required = false ageMin: Int?` et `ageMax: Int?` dans `PackController.listPacks`.
  - [x] Passés dans `PackFilter`.
- [x] Tests (`PackRepositoryAdapterFilterTest`, 25 tests verts) :
  - [x] `ageMin=3, ageMax=6` inclut un pack `[3,6]`.
  - [x] `ageMin=5, ageMax=10` inclut `[3,8]` (chevauchement).
  - [x] `ageMin=5, ageMax=10` exclut `[1,2]` et `[11,14]`.
  - [x] Pack sans âge → conservé (test discriminant : plage `[20,25]` → seuls les packs sans âge).
  - [x] Bornes partielles : `ageMin` seul, `ageMax` seul.
  - [x] Combinaison âge + search, âge + official, âge + locale, âge + inLibrary, âge + search + official + inLibrary.
- [x] `./gradlew test` : suite complète OK.

---

## Étape 3 — Front : filtre âge (slider) ✅

- [x] Signaux dans `PacksService` :
  - [x] `ageMin: signal<number | null>(null)`
  - [x] `ageMax: signal<number | null>(null)`
  - [x] Dans `httpResource`, ajouter `params['ageMin']` si non null.
  - [x] Idem pour `ageMax`.
- [x] UI âge : **slider** (choix utilisateur).
  - [x] Choix fait : **deux sliders numériques indépendants** `Min age` / `Max age` (plage inclusive, bornes 1–15, step 1, clamp min ≤ max).
  - [x] Pas de filtre tant qu'aucun slider n'est touché (valeur `null` → pas de param envoyé) ; bouton reset visible dès qu'un filtre âge est actif.
- [x] `PackFiltersComponent` :
  - [x] Ajouter les sliders âge (`mat-slider` + `matSliderThumb`, Material 22) + bouton clear.
  - [x] Propagation : `model<number | null>` two-way binding → `PackListComponent` → `PacksService` (même pattern que les chips).
- [x] `PackListComponent` :
  - [x] Expose `ageMin` / `ageMax` du service et les lie à `app-pack-filters`.
- [x] Bonus : suppression du caractère parasite `Ï` en fin de `pack-list.component.html`.
- [x] `npm run build` : OK (warnings préexistants hors scope).

---

## Étape 4 — Front : suppression de la notion "available" de l'affichage ✅

- [x] Suppression du signal et du paramètre `inLibrary` :
  - [x] Retirer `showUnavailable` de `PacksService`.
  - [x] ~~Retirer le paramètre `inLibrary` de `httpResource`.~~
  - [x] **Correction post-test** : le paramètre `inLibrary=true` est renvoyé **en permanence en mode normal** (la liste principale = packs possédés, avec une variante). Sans lui, les packs officiels connus du JSON mais absents de la lib polluaient la liste.
  - Note : le back garde le support de `inLibrary` (non cassé, réutilisable plus tard).
  - Sémantique finale : **officiel** ≠ **dans la lib**. Liste principale → `inLibrary=true` ; catalogue officiel JSON → bouton display official library (étape 6, sans `inLibrary`).
- [x] Suppression du chip dans l'UI :
  - [x] Retirer le chip "Show unavailable" de `PackFiltersComponent`.
  - [x] Retirer la liaison `showUnavailable` dans `PackListComponent`.
- [x] Vérifié : aucun autre écran ne dépend de `showUnavailable` / `inLibrary` (6 usages, tous dans les 3 fichiers visés).
- [x] `npm run build` : OK.

---

## Étape 5 — Toggle : affichage des packs officiels présents dans la lib (filtre la liste courante) ✅ (fonctionne déjà, validé utilisateur)

- [ ] Toggle = filtre la liste courante (réponse utilisateur).
- [ ] Implémentation :
  - [ ] Ajouter un signal/toggle dans `PacksService` ou `PackFiltersComponent` :
    - ex. `onlyOfficialInLibrary: signal<boolean>(false)`.
  - [ ] Quand le toggle est activé, filtrer la liste courante pour n'afficher que les packs qui sont :
    - officiels (`official = true`), ET
    - présents dans la lib (càd ont une variante dans la bibliothèque / `inLibrary` équivalent côté front si nécessaire).
  - [ ] Quand le toggle est désactivé, afficher la liste courante sans ce filtre.
- [ ] Intégration UI :
  - [ ] Préciser où placer le toggle : dans les filtres (`PackFiltersComponent`) ou dans un autre endroit (ex. header pack list).
  - [ ] Si nécessaire, l'ajouter aux filtres existants et binder vers `PacksService`.
- [ ] Tests front (comportement toggle) :
  - [ ] Toggle activé + liste courante → n'affiche que packs officiels présents dans la lib.
  - [ ] Toggle désactivé → liste courante normale.
  - [ ] Combinaison toggle + filtres (search, âge, official, french only).

---

## Étape 6 — Bouton display official library (affichage des packs du JSON officiel) ✅

- [x] Contexte :
  - [x] Un bouton "display official library" sert **juste à afficher les packs du JSON officiel**.
  - [x] C'est une **vue identique** à la liste courante (même grid, mêmes filtres affichés), mais la base de données affichée est le JSON officiel.
  - [x] Ce bouton est distinct du bouton **fetch** (cloud_download, `POST /metadata/refresh`) qui sert uniquement à rafraîchir le JSON.
- [x] Implémentation (back) :
  - [x] `ListOfficialPacksUseCase` : lit `MetadataRefreshPort.getOfficialMetadataMap()` (official.json), filtre (tokens titre, locale, âge — mêmes règles que `/packs` via `PackFilter.searchTokens()` / `matchesAge()` extraites en membres), mappe vers des `Pack` (`official=true`, `variants=[]`, id=uuid) et pagine en `PagedPacksResponse`.
  - [x] `MetadataController` : nouveau `GET /metadata/official?page&size&search&locale&ageMin&ageMax`.
- [x] Implémentation (front) :
  - [x] `PacksService.officialMode = signal(false)` : l'`httpResource` bascule l'URL vers `/metadata/official` ; chip `official` ignoré en mode catalogue (tout y est officiel) ; search/locale/âge appliqués.
  - [x] Bouton `travel_explore` "Display official library" dans le header pack-list (état actif visuel via tokens M3, `aria-pressed`, reset page à 0 au basculement).
- [x] Distinction claire :
  - [x] Bouton fetch → `POST /metadata/refresh` (rafraîchit le JSON).
  - [x] Bouton display official library → affiche les packs du JSON (vue identique, base JSON).
  - [x] Chip Official courant → concerne les packs officiels présents dans la lib.
- [x] Tests back : `ListOfficialPacksUseCaseTest` (7 tests : mapping, search multi-tokens, locale, âge, combinaisons, pagination). `./gradlew test` OK.
- [x] `npm run build` : OK.

---

## Étape 7 — Bouton fetch officiel dans les settings (met à jour le JSON)

- [ ] Contexte :
  - [ ] Un bouton fetch officiel est nécessaire dans les settings.
  - [ ] Ce bouton **met à jour le JSON officiel** (appel `POST /metadata/refresh`).
  - [ ] Il ne fait que rafraîchir, pas afficher.
- [ ] Back :
  - [ ] L'endpoint `POST /metadata/refresh` existe déjà.
  - [ ] Vérifier qu'il est bien accessible et documenté (Swagger).
- [ ] Front (settings) :
  - [ ] Ajouter un bouton dans l'écran settings pour rafraîchir le JSON officiel.
  - [ ] Au clic : appeler `POST /metadata/refresh`, afficher un feedback (succès/erreur).
  - [ ] Si nécessaire, afficher un message de confirmation après le refresh.
- [ ] Note :
  - [ ] Ce bouton est distinct du bouton display official library (étape 6).

---

## Étape 8 — Garder "French only" et bouton Official

- [ ] French only :
  - [ ] Conserver le filtre `showFrFr` et le chip "French only".
- [ ] Bouton Official :
  - [ ] Garder le chip "Official" existant, qui concerne les packs officiels présents dans la lib.
  - [ ] Clarifier la sémantique entre :
    - toggle onlyOfficialInLibrary (étape 5),
    - bouton display official library (étape 6),
    - bouton fetch settings (étape 7),
    - chip Official courant.
  - [ ] Si nécessaire, renommer ou repositionner pour éviter la confusion.

---

## Étape 9 — Tests et vérification

- [ ] Back :
  - [ ] Lancer les tests existants + nouveaux tests filtres (nom + âge).
  - [ ] Vérifier la compilation du back (`./gradlew test` ou équivalent).
  - [ ] Vérifier que les filtres existants (official, locale, inLibrary) ne sont pas cassés.
- [ ] Front :
  - [ ] Compiler le front (`npm run build` ou équivalent Angular).
  - [ ] Tester les signaux âge (slider), le toggle, la suppression de available.
  - [ ] Tester le bouton display official library (affiche les packs du JSON).
  - [ ] Tester le bouton fetch dans les settings (rafraîchit le JSON).
  - [ ] Vérifier que le bouton fetch officiel dans les settings fonctionne.
  - [ ] Vérifier qu'aucun écran n'est cassé.
- [ ] Intégration :
  - [ ] Scénarios combinés :
    - [ ] search + âge + official + locale + toggle onlyOfficialInLibrary.
    - [ ] French only + chip Official + bouton display official library.
    - [ ] Bouton fetch settings + affichage liste officielle.
    - [ ] Liste officielle vide ou partielle.

---

## Points en suspans

- [ ] Interface exacte du slider âge (un slider de plage ou deux sliders min/max ?).
- [ ] Placement du toggle onlyOfficialInLibrary (dans les filtres ou dans un autre endroit ?).
- [ ] Bouton display official library : dans quelle vue exacte ? Quel message de confirmation ? Quels filtres appliqués par défaut ?
- [ ] Bouton fetch settings : dans quelle vue settings exacte ? Quel message de confirmation ?
- [ ] Comportement du toggle onlyOfficialInLibrary avec les autres filtres (intersection ou remplacement ?).
- [ ] Option slider âge : recommandation deux sliders min/max ou slider de plage unique ?
