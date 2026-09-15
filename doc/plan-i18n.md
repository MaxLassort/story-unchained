# Plan d'internationalisation (i18n) — StoryUnchained

## Contexte

- **Angular 22.1.0** (dernière version) — supporte `@angular/localize` pour la localisation runtime
- **22 templates HTML** avec du texte hardcodé en anglais
- **~30 messages snackbar** dans les services TypeScript
- **3 dialogs inline** (delete, convert, copy) avec du texte en anglais
- **1 dialog template** (settings) avec beaucoup de texte
- La doc reste en français

## Étape 1 — Installation et configuration

- Installer `@angular/localize` via `ng add @angular/localize`
- Configurer `angular.json` pour le builder i18n avec les locales `fr` et `en`
- Ajouter les imports nécessaires dans `polyfills` ou `main.ts`

## Étape 2 — Fichiers de traduction

Créer les fichiers XLF de localisation :

- `src/locale/messages.fr.xlf` — traductions FR (source)
- `src/locale/messages.en.xlf` — traductions EN (cible)

Chaque fichier contient toutes les chaînes extraites des templates avec les attributs `i18n`.

## Étape 3 — Service de langue (`LanguageService`)

**Fichier** : `src/app/core/services/language.service.ts`

- Signal `currentLang` (par défaut `'fr'`)
- Persistance dans `localStorage`
- Méthode `setLang(lang)` qui met à jour le signal et `localStorage`
- Fourni en racine via `providedIn: 'root'`
- Expose un signal computed `isEnglish` pour les besoins conditionnels

## Étape 4 — Pipe `TranslatePipe`

**Fichier** : `src/app/core/pipes/translate.pipe.ts`

- Pipe pur qui résout la clé de traduction depuis le service
- Usage dans les templates : `{{ 'CLÉ_TRADUCTION' | translate }}`
- Importé dans chaque composant qui en a besoin

## Étape 5 — Sélecteur de langue dans le header

**Fichier** : `src/app/shared/components/app-header/app-header.component.html`

- Ajouter un bouton toggle FR/EN à côté des outils de navigation
- Utilise le `LanguageService` pour basculer la langue
- Icône `translate` Material

## Étape 6 — Traduction des templates HTML (22 fichiers)

### 6.1 App racine

| Fichier | Texte | Clé EN |
|---|---|---|
| `app.html` | "Connecting to StoryUnchained server..." | `APP_CONNECTING` |

### 6.2 Header

| Fichier | Texte | Clé EN |
|---|---|---|
| `app-header.component.html` | "PACK MANAGER" | `HEADER_SUBTITLE` |
| | "Library" | `NAV_LIBRARY` |
| | "Device History" | `NAV_DEVICE_HISTORY` |
| | "Search packs..." | `SEARCH_PLACEHOLDER` |
| | "Settings" | `SETTINGS_TOOLTIP` |

### 6.3 Packs — Liste et filtres

| Fichier | Texte | Clé EN |
|---|---|---|
| `pack-list.component.html` | "No packs found" | `PACKS_EMPTY` |
| | "Display official library" | `PACKS_OFFICIAL_TOOLTIP` |
| | "Refresh metadata" | `PACKS_REFRESH_TOOLTIP` |
| | "Toggle device panel" | `PACKS_DEVICE_TOOLTIP` |
| `pack-filters.component.html` | "Official" | `FILTER_OFFICIAL` |
| | "French only" | `FILTER_FRENCH_ONLY` |
| | "Min" / "Max" | `FILTER_MIN` / `FILTER_MAX` |
| | "Sort by: Title A-Z" / "Title Z-A" | `FILTER_SORT_ASC` / `FILTER_SORT_DESC` |
| | "Clear age filter" | `FILTER_CLEAR_AGE` |

### 6.4 Packs — Carte

| Fichier | Texte | Clé EN |
|---|---|---|
| `pack-card.component.html` | "Official" | `CARD_OFFICIAL` |
| | "Details" | `CARD_DETAILS` |
| | "Acheter sur lunii.com" | `CARD_BUY` |
| | "Edit" | `ACTION_EDIT` |
| | "Convert" | `ACTION_CONVERT` |
| | "Copy to device" | `ACTION_COPY_TO_DEVICE` |
| | "Delete" | `ACTION_DELETE` |
| | "Choose formats..." | `ACTION_CHOOSE_FORMATS` |
| | "Converting..." | `STATUS_CONVERTING` |
| | "Deleting..." | `STATUS_DELETING` |
| | "Copying to device..." | `STATUS_COPYING` |

### 6.5 Packs — Détail

| Fichier | Texte | Clé EN |
|---|---|---|
| `pack-detail.component.html` | "Back" | `ACTION_BACK` |
| | "Copy UUID" | `ACTION_COPY_UUID` |
| | "Click to change thumbnail" | `DETAIL_THUMBNAIL_TOOLTIP` |
| | "Title" / "Description" / "Language" | `FIELD_TITLE` / `FIELD_DESCRIPTION` / `FIELD_LANGUAGE` |
| | "Age min" / "Age max" | `FIELD_AGE_MIN` / `FIELD_AGE_MAX` |
| | "Duration (min)" | `FIELD_DURATION` |
| | "Stories" | `FIELD_STORIES` |
| | "Variants" | `DETAIL_VARIANTS` |
| | "Save" | `ACTION_SAVE` |
| | "Official pack warning" | `DETAIL_OFFICIAL_WARNING` |
| | "Loading..." | `STATUS_LOADING` |

### 6.6 Packs — Pagination

| Fichier | Texte | Clé EN |
|---|---|---|
| `pagination-bar.component.html` | (info range rendu dynamiquement) | — |

### 6.7 Devices

| Fichier | Texte | Clé EN |
|---|---|---|
| `device-panel.component.html` | "Device" | `DEVICE_TITLE` |
| | "Firmware" / "Serial" / "Driver" / "UUID" | `DEVICE_FIRMWARE` / `DEVICE_SERIAL` / `DEVICE_DRIVER` / `DEVICE_UUID` |
| | "Packs on device" | `DEVICE_PACKS_ON` |
| | "Copy to library" | `DEVICE_COPY_TO_LIBRARY` |
| | "Delete from device" | `DEVICE_DELETE_FROM` |
| | "Copying to library..." | `STATUS_COPYING_LIBRARY` |
| | "Deleting from device..." | `STATUS_DELETING_DEVICE` |
| `devices-page.component.html` | "Devices" | `DEVICES_TITLE` |
| | "No devices seen yet" | `DEVICES_EMPTY` |
| | "Plug in a Lunii..." | `DEVICES_EMPTY_HINT` |
| | "Last seen" | `DEVICES_LAST_SEEN` |
| | "Currently plugged" | `DEVICES_PLUGGED` |
| | "No packs recorded" | `DEVICES_NO_PACKS` |

### 6.8 Story Creation

| Fichier | Texte | Clé EN |
|---|---|---|
| `story-creation-page.component.html` | "Create a story" | `STORY_TITLE` |
| | "Compose your own story..." | `STORY_SUBTITLE` |
| | "General Info" / "Audio Upload" / "Chapter Details" / "Finalize" | `STEP_GENERAL` / `STEP_AUDIO` / `STEP_CHAPTERS` / `STEP_FINALIZE` |
| | "Cancel" / "Back" / "Next: Bulk Upload" / "Next: Finalize" | `ACTION_CANCEL` / `ACTION_BACK` / `STEP_NEXT_BULK` / `STEP_NEXT_FINALIZE` |
| | "Confirm & Add to Story" | `STEP_CONFIRM_ADD` |
| | "Ready to create your story pack" | `FINALIZE_TITLE` |
| | "The app will assemble..." | `FINALIZE_TEXT` |
| | "Create the pack" | `ACTION_CREATE_PACK` |
| `story-details-step.component.html` | "Story Details" | `DETAILS_TITLE` |
| | "Provide the foundational metadata..." | `DETAILS_SUBTITLE` |
| | "Enter a captivating title..." | `DETAILS_TITLE_PLACEHOLDER` |
| | "What is this story about?" | `DETAILS_DESC_PLACEHOLDER` |
| | "Pack title audio *" / "Chapter selection audio *" | `DETAILS_TITLE_AUDIO` / `DETAILS_MENU_AUDIO` |
| | "Pack thumbnail *" / "Cover image *" | `DETAILS_THUMBNAIL` / `DETAILS_COVER` |
| `bulk-audio-step.component.html` | "Bulk Audio Import" | `BULK_TITLE` |
| | "Upload multiple audio files..." | `BULK_SUBTITLE` |
| | "Drag & drop audio files here" | `BULK_DROP_ZONE` |
| | "browse — MP3, WAV, OGG..." | `BULK_BROWSE_HINT` |
| | "Staging area" | `BULK_STAGING` |
| | "Chapter N — Name" | `BULK_CHAPTER_NAME` |
| | "Remove chapter" | `BULK_REMOVE_CHAPTER` |
| | "No files yet..." | `BULK_EMPTY` |
| `chapters-step.component.html` | "Chapter Configuration" | `CHAPTERS_TITLE` |
| | "Add chapters to your story..." | `CHAPTERS_SUBTITLE` |
| | "Title audio" / "Narration audio" / "Chapter image" | `CHAPTERS_TITLE_AUDIO` / `CHAPTERS_NARRATION` / `CHAPTERS_IMAGE` |
| | "Add New Chapter" | `CHAPTERS_ADD` |
| | "Delete chapter" | `CHAPTERS_DELETE` |
| | "Loading existing chapters..." | `STATUS_LOADING_CHAPTERS` |

### 6.9 Settings

| Fichier | Texte | Clé EN |
|---|---|---|
| `settings-dialog.component.html` | "Settings" | `SETTINGS_TITLE` |
| | "Library path" | `SETTINGS_LIBRARY_PATH` |
| | "Absolute path to the folder..." | `SETTINGS_LIBRARY_HINT` |
| | "How to get the absolute path on macOS:" | `SETTINGS_MACOS_HELP` |
| | "Target device type" | `SETTINGS_TARGET_DEVICE` |
| | "Preferred format when no device..." | `SETTINGS_TARGET_HINT` |
| | "Studio unofficial DB path" | `SETTINGS_STUDIO_DB` |
| | "Leave empty to use the default..." | `SETTINGS_DB_HINT` |
| | "Where is the Studio database?" | `SETTINGS_DB_WHERE_TITLE` |
| | "Studio stores its non-official..." | `SETTINGS_DB_WHERE_TEXT` |
| | "Text-to-speech" | `SETTINGS_TTS_TITLE` |
| | "TTS provider" / "Language" / "Voice" | `SETTINGS_TTS_PROVIDER` / `SETTINGS_TTS_LANG` / `SETTINGS_TTS_VOICE` |
| | "Provider used to generate..." | `SETTINGS_TTS_PROVIDER_HINT` |
| | "Language of the generated audio..." | `SETTINGS_TTS_LANG_HINT` |
| | "Voice used for the generated audio" | `SETTINGS_TTS_VOICE_HINT` |
| | "Default voices (live list unavailable...)" | `SETTINGS_TTS_FALLBACK` |
| | "Changing the library path will clear..." | `SETTINGS_LIBRARY_WARNING` |
| | "Sync library" | `ACTION_SYNC_LIBRARY` |
| | "Save to apply the new DB path" | `SETTINGS_DB_SAVE_HINT` |
| | "Save" / "Cancel" | `ACTION_SAVE` / `ACTION_CANCEL` |
| | "Your own OpenAI key..." | `SETTINGS_OPENAI_HINT` |
| | "Your own ElevenLabs key..." | `SETTINGS_ELEVENLABS_HINT` |

## Étape 7 — Traduction des dialogs inline (3 fichiers TS)

### 7.1 `pack-delete-dialog.component.ts`

| Texte | Clé EN |
|---|---|
| "Delete Pack" | `DIALOG_DELETE_TITLE` |
| "Are you sure you want to delete..." | `DIALOG_DELETE_CONFIRM` |
| "This action cannot be undone..." | `DIALOG_DELETE_WARNING` |
| "Cancel" / "Delete" | `ACTION_CANCEL` / `ACTION_DELETE` |

### 7.2 `pack-convert-dialog.component.ts`

| Texte | Clé EN |
|---|---|
| "Convert Pack" | `DIALOG_CONVERT_TITLE` |
| "Source format" / "Target format" | `DIALOG_CONVERT_SOURCE` / `DIALOG_CONVERT_TARGET` |
| "Cancel" / "Convert" | `ACTION_CANCEL` / `ACTION_CONVERT` |

### 7.3 `copy-pack-dialog.component.ts`

| Texte | Clé EN |
|---|---|
| "Copyright Notice" | `DIALOG_COPY_TITLE` |
| "This pack... is an official..." | `DIALOG_COPY_TEXT` |
| "Copying official packs may violate..." | `DIALOG_COPY_WARNING` |
| "Cancel" / "Copy Anyway" | `ACTION_CANCEL` / `ACTION_COPY_ANYWAY` |

## Étape 8 — Traduction des messages snackbar (TypeScript)

### 8.1 `pack-card.component.ts`

| Message | Clé EN |
|---|---|
| "Pack converted" | `SNACK_PACK_CONVERTED` |
| "Conversion failed" | `SNACK_CONVERSION_FAILED` |
| "Pack deleted" | `SNACK_PACK_DELETED` |
| "Failed to delete pack" | `SNACK_DELETE_FAILED` |
| "Conversion started" | `SNACK_CONVERSION_STARTED` |
| "Copied to device" | `SNACK_COPIED_TO_DEVICE` |
| "Copy failed" | `SNACK_COPY_FAILED` |
| "Device not connected" | `SNACK_DEVICE_NOT_CONNECTED` |

### 8.2 `pack-detail.component.ts`

| Message | Clé EN |
|---|---|
| "Failed to load pack" | `SNACK_LOAD_FAILED` |
| "Thumbnail updated" | `SNACK_THUMBNAIL_UPDATED` |
| "Failed to upload thumbnail" | `SNACK_THUMBNAIL_FAILED` |
| "Metadata updated" | `SNACK_METADATA_UPDATED` |
| "Failed to update" | `SNACK_UPDATE_FAILED` |

### 8.3 `pack-list.component.ts`

| Message | Clé EN |
|---|---|
| "Metadata refreshed" | `SNACK_METADATA_REFRESHED` |

### 8.4 `device-panel.component.ts`

| Message | Clé EN |
|---|---|
| "Removed from device" | `SNACK_REMOVED_FROM_DEVICE` |
| "Failed to remove from device" | `SNACK_REMOVE_FAILED` |
| "Copied to library" | `SNACK_COPIED_TO_LIBRARY` |
| "Failed to copy to library" | `SNACK_COPY_LIBRARY_FAILED` |

### 8.5 `app-header.component.ts`

| Message | Clé EN |
|---|---|
| "Settings saved" | `SNACK_SETTINGS_SAVED` |
| "Failed to save settings" | `SNACK_SETTINGS_FAILED` |

### 8.6 `sync.service.ts`

| Message | Clé EN |
|---|---|
| "Sync complete: ..." | `SNACK_SYNC_COMPLETE` |
| "Failed to start synchronization" | `SNACK_SYNC_FAILED` |
| "Synchronization failed" | `SNACK_SYNC_ERROR` |

### 8.7 `http-error.interceptor.ts`

| Message | Clé EN |
|---|---|
| "Erreur réseau" | `SNACK_NETWORK_ERROR` |

## Étape 9 — Vérification

- Exécuter `ng build` pour valider l'absence d'erreurs
- Vérifier que le build produit les deux bundles (fr + en)
- Tester le switch de langue dans le navigateur

---

## Architecture résultante

```
src/
├── locale/
│   ├── messages.fr.xlf          ← traductions FR (source)
│   └── messages.en.xlf          ← traductions EN (cible)
├── app/
│   ├── core/
│   │   ├── services/
│   │   │   └── language.service.ts   ← nouveau
│   │   └── pipes/
│   │       └── translate.pipe.ts     ← nouveau
│   └── shared/
│       └── components/
│           └── app-header/
│               └── ...               ← toggle langue ajouté
```

## Principe

Le texte FR reste le texte par défaut dans les templates (pour la lisibilité du code), et le pipe `translate` résout la traduction EN dynamiquement. Le switch de langue est instantané sans rechargement de page.

La langue par défaut est le **français**. L'utilisateur peut basculer en anglais via le bouton dans le header.
