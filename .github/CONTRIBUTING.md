# Contribuer à StoryUnchained

Merci de vouloir contribuer ! 🚀

## Processus de contribution

```
Fork
  ↓
Créer une branche
  ↓
Développer
  ↓
Tests
  ↓
Commit
  ↓
Pull Request
  ↓
Review
  ↓
Merge par le maintainer
```

## 1. Fork & branches

1. **Fork** le dépôt sur GitHub.
2. Crée une branche **depuis `main`** avec un nom explicite :

```
main
├── feature/import-packs
├── feature/audio-preview
├── fix/thumbnail-race
└── refactor/device-driver
```

- `feature/*` — nouvelle fonctionnalité
- `fix/*` — correction de bug
- `refactor/*` — refactorisation / nettoyage
- `../plans/*` — documentation

## 2. Développer

- **Backend** : Kotlin + Spring Boot (`api/`).
- **Frontend** : Angular (`library-web/`).
- **Desktop** : Electron (`desktop/`).
- Suis les conventions existantes du code (structure par domaine, coroutines, data classes, loggers en `companion object`).

## 3. Tests

Avant de proposer un changement, assure-toi que tout passe :

```shell
cd api && ./gradlew test
```

Ajoute des tests pour toute nouvelle logique (Kotest + MockK côté backend). Les changements sans tests pour une logique nouvelle seront refusés.

## 4. Commit

Utilise **Conventional Commits** :

```
feat: add pack import
fix: resolve thumbnail race condition
refactor: extract device driver
docs: update roadmap
test: cover pack sync
```

## 5. Pull Request

- Ouvre une PR vers `main` (le template de PR est automatiquement pré-rempli).
- Une PR doit être **petite et ciblée** : une fonctionnalité/bug par PR.
- Référence l'**Issue** liée (`Closes #42`).
- Décris les changements, les tests faits et le manuel si nécessaire.

## 5bis. Taille des PR — limite de lignes

Pour garder les reviews efficaces et réduire les conflits, **les PR doivent rester petites** :

- **Limite recommandée : ~300 lignes de code modifiées par PR** (ajouts + suppressions cumulés,
  hors fichiers de test/ressources générés).
- Au-delà de **500 lignes**, la PR sera **refusée et devra être découpée** en plusieurs PR
  plus petites, chacune fusionnable indépendamment.
- Règles pratiques :
  - Une fonctionnalité = **une** PR (pas d'enchaînement de features dans la même PR).
  - Les **refactorisations** doivent être séparées des **fonctionnalités**.
  - Si tu sens que ta PR grossit, **découpe-la** (ex. : backend puis frontend, ou par étape).
  - Les gros fichiers générés (lockfiles, build, dist) ne doivent pas être dans la PR.

Une PR courte et claire est bien plus vite revue et mergée. 👍

## 6. Review & merge

- Toute PR passe par une **review**.
- **Seul le maintainer** peut merger dans `main` — les contributeurs proposent, le maintainer valide.
- `main` ne reçoit que des changements validés.

## 7. Outils IA / agents

Si tu utilises des outils IA/agents en dev (assistant de code, **graphify**, analyse de code, etc.) :

- **Ne commite jamais les fichiers générés par ces outils** : `graphify-out/` (graphes `graph.html`,
  `graph.json`, `GRAPH_REPORT.md`, caches, manifest), et tout autre artefact produit par un agent.
  Ces répertoires sont ignorés par le `.gitignore` — garde-le comme tel.
- Les sorties d'agents sont des **artefacts locaux**, pas des sources : chacun les régénère chez lui
  (`/graphify` ou l'outil concerné) plutôt que de les versionner.
- Seuls les fichiers de code et de config **écrits à la main** entrent dans une PR.

## Droits & licence

- Le projet est sous **Non-Commercial Source-Available License** — voir `LICENSE`.
- En contribuant, tu acceptes que ton code soit distribué sous cette licence.
- Merci de ne pas intégrer de contenu protégé (histoires/audio Lunii sans droits).
