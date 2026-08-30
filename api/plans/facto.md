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

## Backend (`api`)

### 1. Contrat d’événement

Créer `SyncStatusEvent` sérialisable avec :

- `status` : `PENDING`, `RUNNING`, `DONE`, `FAILED` ;
- `totalEntries` ;
- `processedEntries` ;
- `synchronizedCount` ;
- `invalidQueuedCount` — conservé pour compatibilité, mais représente désormais les entrées invalides déplacées directement ;
- `failedCount` ;
- `message` ;
- `startedAtEpochMs` ;
- `finishedAtEpochMs` ;
- `batchSize` ;
- `parallelism`.

Créer `SyncEventPublisher` :

- `MutableSharedFlow<SyncStatusEvent>(replay = 1)` ;
- méthode `publish()` ;
- bean partagé par le service et le contrôleur.

### 2. Refactor `SyncPacksService`

Supprimer :

- `SyncJobEntity` et `SyncJobJpaRepository` du constructeur ;
- `SyncJobStartResponse` et `SyncJobStatusResponse` ;
- `getJobStatus()` ;
- `setJobTotal()` ;
- `updateJobProgress()` ;
- `updateJobStatus()` ;
- `finishJob()` ;
- `triggerBackgroundQueueProcessing()` ;
- `processQueueBatch()` ;
- `queueProcessing` et tous les statuts de queue/job.

Modifier le démarrage :

- `startSync()` lance le traitement en arrière-plan ;
- publie immédiatement `PENDING` ;
- retourne `Unit` ;
- libère toujours le verrou de sync dans un `finally` ;
- lève une exception dédiée si une sync est déjà active.

Modifier `runJob()` :

- publier `RUNNING` après validation du démarrage ;
- publier le total dès que les entrées sont connues ;
- publier la progression après chaque batch ;
- publier `DONE` avec le message final ;
- publier `FAILED` en cas d’erreur fatale ;
- conserver la sync des métadonnées et la normalisation post-sync.

Remplacer `enqueueInvalidEntry()` par un déplacement direct :

- calculer un chemin cible unique dans `invalid/` ;
- utiliser `Files.move()` ;
- prévoir un fallback copie + suppression si nécessaire ;
- incrémenter `invalidQueuedCount` lorsque le déplacement réussit ;
- incrémenter `failedCount` lorsque le déplacement échoue ;
- journaliser la raison de l’invalidation.

Modifier `clearPacks()` :

- supprimer les packs, métadonnées, variantes et index ;
- ne plus supprimer de table de jobs ou de queue.

### 3. Refactor `PackController`

`POST /packs/sync` :

- appeler `startSync()` ;
- retourner `202 Accepted` sans `jobId` ni corps métier ;
- retourner `409 Conflict` si une sync est déjà active ;
- retourner `500` en cas d’échec du lancement.

Ajouter `GET /packs/sync/events` :

- produire `text/event-stream` ;
- collecter `SyncEventPublisher.events` ;
- envoyer chaque `SyncStatusEvent` encodé en JSON ;
- gérer déconnexion, timeout et erreur ;
- annuler la coroutine associée à l’émetteur.

Supprimer :

- `GET /packs/sync/{jobId}` ;
- sa documentation OpenAPI ;
- toute référence à un statut de job.

### 4. Helper SSE commun

Extraire depuis `DeviceController` un helper générique chargé de :

- créer un `SseEmitter` sans timeout ;
- collecter un `Flow<T>` ;
- sérialiser les événements ;
- terminer proprement sur déconnexion ;
- annuler la coroutine sur completion, timeout ou erreur.

Adapter ensuite `DeviceController` pour utiliser ce helper sans changer `/devices/events`.

### 5. Suppressions persistence et DTO

Supprimer les fichiers :

- `SyncJobEntity.kt` ;
- `SyncJobJpaRepository.kt` ;
- `SyncJobStartResponse.kt` ;
- `SyncJobStatusResponse.kt` ;
- `InvalidPackMoveQueueEntity.kt` ;
- `InvalidPackMoveQueueJpaRepository.kt`.

La base existante pourra conserver les tables historiques avec `ddl-auto: update`. Prévoir leur suppression manuelle ou une migration :

```sql
DROP TABLE IF EXISTS sync_jobs;
DROP TABLE IF EXISTS invalid_pack_move_queue;
```

## Frontend (`library-web`)

### 6. Modèles et API

Dans `pack.model.ts` :

- supprimer les types de job ;
- ajouter `SyncStatusEvent` sans `jobId`.

Dans `packs.service.ts` :

- `sync()` effectue le POST sans attendre de réponse métier ;
- supprimer `getSyncStatus()` ;
- supprimer tout polling de `/sync/{jobId}`.

### 7. `sync.service.ts`

- ouvrir `EventSource('/packs/sync/events')` avant le POST ;
- appeler ensuite `POST /packs/sync` ;
- mettre à jour le signal de synchronisation à chaque événement ;
- fermer le flux sur `DONE` ou `FAILED` ;
- rafraîchir les packs après un état terminal ;
- afficher le snackbar de résultat ;
- utiliser `NgZone` comme le service SSE existant ;
- mutualiser le helper de création/reconnexion `EventSource` avec `SseService`.

Gestion du `replay = 1` :

- le dernier événement d’une ancienne sync peut être rejoué à la connexion ;
- ne pas fermer le flux sur un ancien `DONE`/`FAILED` avant d’avoir reçu le `PENDING` de la demande courante ;
- fermer uniquement après le cycle `PENDING/RUNNING` puis `DONE/FAILED`.

### 8. UI et specs

- conserver le spinner de `settings-dialog.component.html` ;
- adapter `settings-dialog.component.spec.ts` ;
- adapter `app.spec.ts` ;
- adapter ou créer les specs de `sync.service.ts` et `packs.service.ts` ;
- tester le helper `EventSource` partagé.

## Tests backend

Adapter `SyncPacksServiceNormalizeThumbnailTest` au nouveau constructeur.

Ajouter des tests pour :

- publication de `PENDING`, `RUNNING`, progression et `DONE` ;
- publication de `FAILED` ;
- refus d’une deuxième sync avec `409` ;
- déplacement direct d’une entrée invalide ;
- comptage d’un échec de déplacement ;
- absence de repository de job et de queue ;
- flux SSE et nettoyage après déconnexion.

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

- aucune référence applicative à `SyncJob*`, `sync_jobs` ou `getSyncJobStatus` ;
- aucune référence applicative à `InvalidPackMoveQueue*`, `invalid_pack_move_queue` ou `processQueueBatch` ;
- `POST /packs/sync` répond `202` sans jobId ;
- une deuxième sync répond `409` ;
- la progression est disponible uniquement via SSE ;
- le frontend ne fait plus de polling ;
- le flux SSE se ferme sur un état terminal ;
- aucune coroutine SSE ne fuit après déconnexion ;
- les tests backend et frontend passent.
