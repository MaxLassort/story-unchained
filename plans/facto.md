Plan d'exécution
Backend (api)
1. Nouveau SyncEventPublisher : MutableSharedFlow<SyncStatusEvent>(replay=1) + publish().
2. DTO : supprimer SyncJobStartResponse ; transformer SyncJobStatusResponse → SyncStatusEvent (sans jobId).
3. SyncPacksService.kt :
- supprimer SyncJobEntity/SyncJobJpaRepository + leur usage (clearPacks(), ctor)
- startSync() → lance runJob() sans écriture DB, retourne 202
- remplacer setJobTotal/updateJobProgress/updateJobStatus/finishJob (écritures DB) par des publisher.publish(...)
4. PackController.kt : POST /sync → 202 ; nouveau GET /sync/events (SseEmitter identique au DeviceController) ; suppression de GET /sync/{jobId}.
5. Supprimer SyncJobEntity.kt + SyncJobJpaRepository.kt. (ddl-auto: update gère le schéma ; la table sync_jobs résiduelle peut être droppée à la main.)
   Frontend (library-web)
6. pack.model.ts : remplacer les types job par SyncStatusEvent (sans jobId).
7. packs.service.ts : sync() → POST sans body attendu ; supprimer getSyncStatus.
8. sync.service.ts : réécriture — ouvre EventSource('/sync/events') + POST, met à jour un signal progress, ferme le flux au statut terminal, puis refresh + snackbar. NgZone et auto-reconnect comme SseService. (Bonus : extraire un helper createEventSource() partagé pour dédupliquer avec SseService.)
9. settings-dialog.component.html : spinner → barre de progression determinate (processedEntries/totalEntries) — le vrai gain visible.
10. Adapter les specs impactées (settings-dialog.component.spec.ts, app.spec.ts).
    Vérification : ./gradlew test (api) + tests vitest Angular.
    Deux questions avant de lancer :
1. Barre de progression dans le dialog settings : tu la veux (recommandé) ou on garde le spinner ?
2. Helper partagé pour l'EventSource (SseService + SyncService) ou duplication assumée ?
