# Plan — Factoriser la synchronisation et supprimer les queues manuelles

## Objectif

Remplacer le système de jobs persistants et le polling de synchronisation par un traitement asynchrone observable via SSE.

La parallélisation interne du scan (`async`, `Semaphore`, batch de 50) est conservée. En revanche, aucune file DB ne doit plus piloter ou reprendre la synchronisation.

## Décisions

1. **Pas de job persistant** : suppression de `sync_jobs`, `SyncJobEntity` et `SyncJobJpaRepository`.
2. **Pas de file des entrées invalides** : suppression de `invalid_pack_move_queue`, `InvalidPackMoveQueueEntity` et `InvalidPackMoveQueueJpaRepository`.
3. **Déplacement immédiat** : une entrée invalide est déplacée directement vers `invalid/` pendant le traitement ; un échec de déplacement est compté comme erreur.
4. **Une seule sync à la fois** : une nouvelle demande pendant une sync active retourne `409 Conflict`.
5. **SSE factorisé** : mutualiser le cycle de vie `SseEmitter` du flux device et du flux sync.
6. **UI inchangée pour cette itération** : conserver le spinner du dialog Settings ; la barre determinate sera traitée séparément.
7. **État SSE global** : `MutableSharedFlow<SyncStatusEvent>(replay = 1)`.

## Méthode

Ce plan est implémenté 단계적으로 (par étapes) et chaque étape est validée par des tests avant de passer à la suivante.

Règle générale : écrire ou adapter le test qui exprime le comportement attendu, faire passer le test, puis nettoyer. Aucune implémentation sans test qui le justifie.

- Backend : tests Kotest + MockK / premier test dédié par comportement, refactorisation sans régression sur le test existant de normalisation.
- Frontend : tests Vitest, dans l’ordre : helpers SSE, modèles, services, puis specs de composants.

## Étape 0 — État actuel de la codebase (référence)

Le code actuel utilise encore :

- un job persistants : `SyncJobEntity`, `SyncJobJpaRepository`, `sync_jobs` ;
- une file invalide : `InvalidPackMoveQueueEntity`, `InvalidPackMoveQueueJpaRepository`, `invalid_pack_move_queue` ;
- des réponses de job : `SyncJobStartResponse`, `SyncJobStatusResponse` ;
- un endpoint de statut : `GET /packs/sync/{jobId}` ;
- un polling frontend dans `SyncService.pollUntilDone(jobId)` via `PacksService.getSyncStatus(jobId)`.

Ces éléments sont nécessaires pour lire le plan et vérifier les critères d’acceptation à la fin.

## Étape 1 — Contrat d’événement (backend)

### Ticket

Créer le contrat SSE de sync sans toucher encore au service de sync.

### Ce qu’on fait

- Créer `SyncStatusEvent`, sérialisable, avec :
  - `status` : `PENDING`, `RUNNING`, `DONE`, `FAILED`
  - `totalEntries`
  - `processedEntries`
  - `synchronizedCount`
  - `invalidQueuedCount` — conservé pour compatibilité, mais il représente désormais les entrées invalides déplacées directement
  - `failedCount`
  - `message`
  - `startedAtEpochMs`
  - `finishedAtEpochMs`
  - `batchSize`
  - `parallelism`
- Créer `SyncEventPublisher` :
  - `MutableSharedFlow<SyncStatusEvent>(replay = 1)`
  - méthode `publish()`
  - bean partagé par le service et le contrôleur

### Règle TDD

Écrire d’abord un test de sérialisation / structure de `SyncStatusEvent` et un test unitaire de `SyncEventPublisher` (publish + réémission du dernier événement avec `replay = 1`).

### Sortie validée

- événement cohérent avec le contrat prévu
- publisher testable sans controller, sans HTTP, sans job

## Étape 2 — Refactor `SyncPacksService` : suppression du job et de la queue

### Ticket

Supprimer les dépendances job/queue du service et remplacer la gestion de progression par la publication d’événements.

### Ce qu’on supprime

- `SyncJobEntity` et `SyncJobJpaRepository` du constructeur
- `SyncJobStartResponse` et `SyncJobStatusResponse`
- `getJobStatus()`
- `setJobTotal()`
- `updateJobProgress()`
- `updateJobStatus()`
- `finishJob()`
- `triggerBackgroundQueueProcessing()`
- `processQueueBatch()`
- `queueProcessing` et les statuts de queue/job

### Ce qu’on change

- `startSync()` :
  - lance le traitement en arrière-plan
  - publie immédiatement `PENDING`
  - retourne `Unit`
  - libère toujours le verrou de sync dans un `finally`
  - lève une exception dédiée si une sync est déjà active
- `runJob()` :
  - publie `RUNNING` après validation du démarrage
  - publie le total dès que les entrées sont connues
  - publie la progression après chaque batch
  - publie `DONE` avec le message final
  - publie `FAILED` en cas d’erreur fatale
  - conserve la sync des métadonnées et la normalisation post-sync
- `enqueueInvalidEntry()` → déplacement direct :
  - chemin cible unique dans `invalid/`
  - `Files.move()`
  - fallback copie + suppression si nécessaire
  - incrémente `invalidQueuedCount` quand le déplacement réussit
  - incrémente `failedCount` quand il échoue
  - journalise la raison de l’invalidation
- `clearPacks()` :
  - supprime packs, métadonnées, variantes et index
  - ne supprime plus de table de jobs ou de queue

### Règle TDD

- Adapter d’abord le test existant `SyncPacksServiceNormalizeThumbnailTest` pour le nouveau constructeur.
- Ajouter un test du cycle d’événements (`PENDING`, `RUNNING`, progression, `DONE`).
- Ajouter un test de `FAILED`.
- Ajouter un test du refus d’une deuxième sync (`409` côté API, exception dédiée côté service).
- Ajouter un test du déplacement direct d’une entrée invalide.
- Ajouter un test du comptage d’un échec de déplacement.
- Vérifier qu’aucun test ne dépend encore de `SyncJob*` ou `InvalidPackMoveQueue*`.

### Sortie validée

- le service ne dépend plus de la persistence de job/queue
- la progression est publiée, pas interrogée par polling
- une sync active bloque les demandes concurrentes

## Étape 3 — Refactor `PackController`

### Ticket

Remplacer les contrats job par le flux SSE et supprimer l’endpoint de statut.

### Ce qu’on change

- `POST /packs/sync` :
  - appelle `startSync()`
  - retourne `202 Accepted` sans `jobId` ni corps métier
  - retourne `409 Conflict` si une sync est déjà active
  - retourne `500` en cas d’échec du lancement
- `GET /packs/sync/events` :
  - `text/event-stream`
  - collecte `SyncEventPublisher.events`
  - envoie chaque `SyncStatusEvent` encodé en JSON
  - gère déconnexion, timeout et erreur
  - annule la coroutine associée à l’émetteur

### Ce qu’on supprime

- `GET /packs/sync/{jobId}`
- sa documentation OpenAPI
- toute référence à un statut de job

### Règle TDD

- Écrire le test de `POST /packs/sync` → `202`.
- Écrire le test de `POST /packs/sync` pendant une sync active → `409`.
- Écrire le test de `GET /packs/sync/events` avec collecte d’événements et nettoyage après déconnexion.
- Supprimer l’endpoint de job et vérifier que plus aucun test/controller ne l’utilise.

### Sortie validée

- API sync sans jobId
- progression disponible uniquement via SSE

## Étape 4 — Helper SSE commun

### Ticket

Factoriser le cycle de vie `SseEmitter` réutilisé par le flux device et le flux sync.

### Ce qu’on extrait

Un helper générique issu de `DeviceController`, capable de :

- créer un `SseEmitter` sans timeout
- collecter un `Flow<T>`
- sérialiser les événements
- terminer proprement sur déconnexion
- annuler la coroutine sur completion, timeout ou erreur

### Règle TDD

- Écrire un test du helper sur un flow factice (envoi, déconnexion, annulation de coroutine).
- Remplacer l’usage dans `DeviceController` sans changer le comportement de `/devices/events`.
- Utiliser le helper pour `GET /packs/sync/events`.

### Sortie validée

- un seul point de vérité pour les flux SSE
- aucune coroutine SSE qui fuit après déconnexion

## Étape 5 — Suppression des fichiers obsolètes (backend)

### Ticket

Supprimer persistence et DTO de job/queue une fois qu’ils ne sont plus référencés.

### Ce qu’on supprime

- `SyncJobEntity.kt`
- `SyncJobJpaRepository.kt`
- `SyncJobStartResponse.kt`
- `SyncJobStatusResponse.kt`
- `InvalidPackMoveQueueEntity.kt`
- `InvalidPackMoveQueueJpaRepository.kt`

### Règle TDD

- Faire passer les tests de l’étape 2 et 3 avant suppression.
- Supprimer les fichiers, puis vérifier la compilation et le test.

### Note DB

La base existante peut garder les tables historiques tant que `ddl-auto: update` est actif. Prévoir leur suppression manuelle ou une migration :

```sql
DROP TABLE IF EXISTS sync_jobs;
DROP TABLE IF EXISTS invalid_pack_move_queue;
```

### Sortie validée

- aucune référence applicative à `SyncJob*`, `sync_jobs` ou `getSyncJobStatus`
- aucune référence applicative à `InvalidPackMoveQueue*`, `invalid_pack_move_queue` ou `processQueueBatch`

## Étape 6 — Frontend : modèles et API

### Ticket

Supprimer les types de job côté frontend et ajouter le contrat SSE de sync.

### Ce qu’on change

Dans `pack.model.ts` :

- supprimer les types de job (`SyncJobStartResponse`, `SyncJobStatusResponse`)
- ajouter `SyncStatusEvent` sans `jobId`

Dans `packs.service.ts` :

- `sync()` fait le `POST` sans attendre de réponse métier
- supprimer `getSyncStatus()`
- supprimer tout polling de `/sync/{jobId}`

### Règle TDD

- Ajouter les tests de modèle / contrat d’événement
- Adapter les tests de `PacksService.sync()` pour le nouveau contrat

### Sortie validée

- frontend ne dépend plus de `SyncJobStartResponse` / `SyncJobStatusResponse`

## Étape 7 — Frontend : `sync.service.ts` sans polling

### Ticket

Remplacer le polling par un flux SSE.

### Ce qu’on change

- ouvrir `EventSource('/packs/sync/events')` avant le `POST`
- appeler ensuite `POST /packs/sync`
- mettre à jour le signal de synchronisation à chaque événement
- fermer le flux sur `DONE` ou `FAILED`
- rafraîchir les packs après un état terminal
- afficher le snackbar de résultat
- utiliser `NgZone` comme le service SSE existant
- mutualiser le helper de création/reconnexion `EventSource` avec `SseService`

### Gestion du `replay = 1`

- le dernier événement d’une ancienne sync peut être rejoué à la connexion
- ne pas fermer le flux sur un ancien `DONE`/`FAILED` avant d’avoir reçu le `PENDING` de la demande courante
- fermer uniquement après le cycle `PENDING/RUNNING` puis `DONE/FAILED`

### Règle TDD

- Écrire un test du helper EventSource partagé.
- Écrire un test de `SyncService` :
  - cycle complet `PENDING → RUNNING → DONE`
  - cycle `PENDING → RUNNING → FAILED`
  - comportement sur replay d’un vieux `DONE`/`FAILED` avant `PENDING`
  - refresh des packs et snackbar sur état terminal
- Adapter les specs qui dépendent de `sync()` ou du polling.

### Sortie validée

- le frontend ne fait plus de polling
- le flux SSE se ferme sur un état terminal

## Étape 8 — UI et specs

### Ticket

Garder le spinner existant et adapter les specs impactées.

### Ce qu’on change

- conserver le spinner de `settings-dialog.component.html`
- adapter `settings-dialog.component.spec.ts`
- adapter `app.spec.ts`
- adapter ou créer les specs de `sync.service.ts` et `packs.service.ts`
- tester le helper `EventSource` partagé

### Règle TDD

- Laisser les specs existantes guide le changement.
- Ajouter des tests pour le nouveau flux SSE frontend.

### Sortie validée

- UI inchangée pour cette itération
- les specs passent

## Tests backend

Adapter `SyncPacksServiceNormalizeThumbnailTest` au nouveau constructeur.

Ajouter des tests pour :

- publication de `PENDING`, `RUNNING`, progression et `DONE`
- publication de `FAILED`
- refus d’une deuxième sync avec `409`
- déplacement direct d’une entrée invalide
- comptage d’un échec de déplacement
- absence de repository de job et de queue
- flux SSE et nettoyage après déconnexion

## Vérification

Backend :

```bash
./gradlew test
./gradlew compileKotlin
```

Frontend :

```bash
# depuis library-web
npm test
```

## Critères d’acceptation

- aucune référence applicative à `SyncJob*`, `sync_jobs` ou `getSyncJobStatus`
- aucune référence applicative à `InvalidPackMoveQueue*`, `invalid_pack_move_queue` ou `processQueueBatch`
- `POST /packs/sync` répond `202` sans jobId
- une deuxième sync répond `409`
- la progression est disponible uniquement via SSE
- le frontend ne fait plus de polling
- le flux SSE se ferme sur un état terminal
- aucune coroutine SSE ne fuit après déconnexion
- les tests backend et frontend passent