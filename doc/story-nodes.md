# Format des nœuds STUdio — chaque type, chaque champ, à quoi ça sert

> Référence pratique des **nœuds** et de leurs **champs** dans le format STUdio (zip, `story.json`).
> Contrairement à `pack-format-archive.md` (structure complète du zip), ce doc explique **le
> rôle de chaque nœud et de chaque option** — ce qui se passe réellement sur la Lunii.

## Le modèle mental

Une histoire est un **graphe** de deux sortes de nœuds :

- **`stageNode`** — une **page** : une image affichée, un audio joué, et des boutons.
- **`actionNode`** — un **point de choix** : une liste d'options vers d'autres pages.

La navigation se fait par **transitions** (`okTransition`, `homeTransition`) et par
**`controlSettings`** (quelles touches sont actives). Il n'y a **jamais** de nœud sans
transition OK valide : chaque page doit savoir où aller quand on appuie sur OK.

```
cover (page d'accueil)                chapitre 1 (page histoire)
   image + audio                          image + audio
   ok ──► action ──► chapitre 1            ok ──► action ──► chapitre 2
   home ──► (retour)                       home ──► (menu)
```

---

## 1. `stageNode` — la page

```json
{
  "uuid": "a1b2c3d4-…",
  "name": "Chapitre 1",
  "type": "story",
  "groupId": "grp-1",
  "position": { "x": 0, "y": 0 },
  "squareOne": true,
  "image": "e8f2a1c9….png",
  "audio": "3b7d9e10….mp3",
  "okTransition": { "actionNode": "…", "optionIndex": 0 },
  "homeTransition": null,
  "controlSettings": { "wheel": false, "ok": true, "home": true, "pause": true, "autoplay": false }
}
```

### Champs

| Champ | Type | À quoi ça sert |
|---|---|---|
| `uuid` | string | Identifiant unique de la page. Référencé par les `actionNode.options`. |
| `name` | string? | **Nom éditeur uniquement** — jamais affiché sur la Lunii. Sert aux outils (studio, logs). |
| `type` | string? | **Type éditeur** (voir §4) — ignoré par la Lunii, utilisé par l'éditeur/les stats. |
| `groupId` | string? | Regroupement éditeur (organisation visuelle uniquement). |
| `position` | {x,y}? | Position dans le canvas de l'éditeur (rien sur l'appareil). |
| `squareOne` | bool | **Marqueur de page d'accueil** : `true` uniquement sur le 1er nœud. C'est la page affichée quand le pack démarre. Écrit automatiquement sur l'index 0. |
| `image` | string? | **Image affichée** sur la page (fichier `assets/`, nommé SHA-1). `null` = pas d'image (l'écran reste sur l'image précédente). |
| `audio` | string? | **Audio joué** en entrant dans la page. `null` = pas d'audio. |
| `okTransition` | object? | **Comportement de la touche OK** : vers quel choix on va (voir §3). ⚠️ Doit être valide sur chaque page. |
| `homeTransition` | object? | **Comportement de la touche HOME** (retour/sortie). `null` = HOME inactif. |
| `controlSettings` | object | **Quelles touches fonctionnent** sur cette page (voir §2). Requis. |

### Ce qui se passe sur la Lunii en entrant dans une page

1. Si `image` non nul → l'image est affichée.
2. Si `audio` non nul → l'audio est **joué en boucle** jusqu'à interaction.
3. Les touches actives (`controlSettings`) permettent d'interagir :
   - **OK** → exécute `okTransition` (avance),
   - **HOME** → exécute `homeTransition` (retour),
   - **wheel** → défile les options (sur une page de menu),
   - **pause** → met l'audio en pause,
   - **autoplay** → avance automatiquement sans toucher.

---

## 2. `controlSettings` — quelles touches marchent

| Champ | Type | À quoi ça sert |
|---|---|---|
| `wheel` | bool | **Molette** active : permet de déplacer le curseur entre les options d'un menu. Sur une page d'histoire simple : `false`. |
| `ok` | bool | **Touche OK** active : l'appui sur OK exécute `okTransition`. `false` + `autoplay` = la page avance toute seule. |
| `home` | bool | **Touche HOME** active : l'appui sur HOME exécute `homeTransition` (retour au menu/parent). |
| `pause` | bool | **Touche pause** active : met l'audio en pause/reprise. |
| `autoplay` | bool | **Avance automatique** : quand l'audio se termine, la page avance **sans** pression de touche. Utilisé sur les chapitres qui jouent tout seuls. |

### Deux configurations types observées dans les packs réels

**Page de menu / sélection** (on choisit, on confirme) :
```json
{ "wheel": true, "ok": true, "home": true, "pause": false, "autoplay": false }
```

**Page d'histoire / chapitre** (l'audio joue, OK avance, HOME sort) :
```json
{ "wheel": false, "ok": false, "home": true, "pause": true, "autoplay": true }
```

> `controlSettings` est **obligatoire** : le lecteur lève une erreur s'il manque.

---

## 3. Transitions — à quoi sert chaque bouton

```json
{ "actionNode": "uuid-du-point-de-choix", "optionIndex": 0 }
```

| Champ | À quoi ça sert |
|---|---|
| `actionNode` | Le point de choix cible (son `id`). |
| `optionIndex` | Quelle option du point de choix est sélectionnée (index dans `actionNode.options`). |

### Le rôle de chaque transition

| Transition | Rôle | Analogie |
|---|---|---|
| `okTransition` | **Avancer** : page suivante, valider un choix, continuer le récit. | « Entrée » / « Suivant » |
| `homeTransition` | **Reculer / sortir** : retourner au menu, au parent, à la page d'accueil. | « Retour » / « Quitter » |

- **OK = avant, HOME = arrière** : les deux ne sont jamais redondants.
- Sur une histoire linéaire, chaque chapitre a `okTransition` vers la suite ; le **dernier
  chapitre boucle vers la cover** (OK relance depuis le début).
- ⚠️ **Chaque page doit avoir un `okTransition` valide.** Une page avec OK indéfini
  (`ok=-1` en format FS) provoque un **« error card »** sur la Lunii quand l'histoire
  l'atteint.

---

## 4. `actionNode` — le point de choix

```json
{
  "id": "uuid-du-point-de-choix",
  "name": "Menu chapitres",
  "type": "menu.optionsaction",
  "options": [
    "a1b2c3d4-…",   ← uuid d'un stageNode (option 0)
    "f2g3h4i5-…"    ← uuid d'un stageNode (option 1)
  ]
}
```

| Champ | À quoi ça sert |
|---|---|
| `id` | Identifiant **local au zip** du point de choix. Référencé par les transitions. Pas besoin d'être un UUID global. |
| `name` | Nom éditeur uniquement (jamais affiché). |
| `type` | Type éditeur (ex. `menu.optionsaction`) — ignoré par la Lunii. |
| `options` | Les pages proposées (uuid de `stageNode`). **L'ordre = ordre molette** : la molette défile dans cet ordre, `optionIndex` d'une transition choisit l'une d'elles. |

Le point de choix **n'a ni image ni audio** : il est invisible, c'est une pure structure de
navigation. Quand une page y arrive (via une transition), le choix est déjà **déterminé** par
l'`optionIndex` de la transition qui y mène.

---

## 5. Types de nœuds (`type`) — signification

| `type` | Code | C'est quoi |
|---|---|---|
| `stage` | 1 | Page générique (menu, intro, transition…). |
| `action` | 2 | Point de choix générique. |
| `cover` | 17 | **Page de couverture** du pack (le squareOne). |
| `menu.questionaction` | 33 | Point de choix d'une question de menu. |
| `menu.questionstage` | 34 | Page de question de menu (poser une question + options). |
| `menu.optionsaction` | 35 | Point de choix à options (le menu chapitres). |
| `menu.optionstage` | 36 | Page d'option de menu. |
| `story` | 49 | **Page d'histoire** (un chapitre). |
| `story.storyaction` | 50 | Point de choix dans une histoire (ramification). |

> Ces types sont des **métadonnées éditeur** : la Lunii ne les utilise pas pour la lecture,
> mais les outils (studio, statistiques, exports) s'en servent. On peut donc les laisser vides
> ou cohérents sans impact sur la lecture.

---

## 6. Fichiers au niveau pack (`story.json`)

| Champ | À quoi ça sert |
|---|---|
| `format` | Version du format, `"v1"`. |
| `title` / `description` | Métadonnées du pack (affichées dans la bibliothèque, pas sur la Lunii). |
| `version` | Version interne du format. |
| `nightModeAvailable` | **Mode nuit** disponible (feature Lunii) : crée le marqueur `nm` à la conversion FS. |
| `stageNodes` | Toutes les **pages** (la première, index 0, reçoit `squareOne`). |
| `actionNodes` | Tous les **points de choix** (référencés par les transitions). |

---

## 7. Guide pratique — construire une histoire

**Pack linéaire simple (cover + N chapitres)** — ce que génère `CreateStoryUseCase` :

```
cover (type=cover, squareOne)
   ok ──► action#1 ── option 0 ──► chapitre 1 (type=story)
   chapitre 1 ok ──► action#2 ── option 0 ──► chapitre 2
   …
   dernier chapitre ok ──► action#fin ── option 0 ──► cover  (boucle)
```

- `cover` : image du squareOne, audio = titre du pack, `okTransition` vers le 1er choix.
- `chapitre k` : image du chapitre, audio = titre du chapitre + narration, `okTransition`
  vers la suite ; `controlSettings` type « histoire » (`autoplay`).
- Le dernier chapitre : `okTransition` vers un choix de fin dont l'unique option est la cover
  → à la fin, OK relance le pack.

**Pack à menu (type Hayat/Disney)** :

```
cover ──► menu (page avec molette)
   menu ok ──► action ── option 0 ──► chapitre 1
               option 1 ──► chapitre 2
   chapitre 1 ok ──► action ──► retour menu  ;  home ──► menu
```

- La page de menu : `wheel=true, ok=true` ; les chapitres : `home` vers le menu.

---

## Où dans le code

- Modèle mémoire : `pack/format/model/` — `StageNode`, `ActionNode`, `Transition`,
  `ControlSettings`, `EnrichedNodeMetadata`, `EnrichedNodeType` (`StoryPack.kt`,
  `Transition.kt`, `Asset.kt`, `Enriched.kt`).
- Writer : `pack/format/writer/ArchiveStoryPackWriter.kt` (zip), `FsStoryPackWriter.kt`
  (FS), `BinaryStoryPackWriter.kt` (RAW).
- Reader : `pack/format/reader/` (mêmes trois formats).
- Finalisation d'histoires : `pack/service/CreateStoryUseCase.kt` (voir
  `doc/story-creation-flow.md`).