# Configuration TTS (Text-to-Speech) — BYOK

Documentation du stockage de la configuration TTS utilisée pour la création d'histoires
(étape 1 du plan `plans/story-creation-plan.md`).
Fichiers concernés : `api/src/main/kotlin/com/maxlass/studio/settings/` et
`library-web/src/app/features/settings/settings-dialog/`.

## 1. Vue d'ensemble

La création d'histoires génère des **titres audio** de nœuds via TTS. Le moteur choisi est
**BYOK** (« Bring Your Own Key ») : l'utilisateur fournit sa propre clé API, stockée localement
dans le fichier de settings de l'application. Sans clé configurée, un **fallback gratuit**
(Google Translate TTS) est utilisé.

| Champ (`Settings`) | Type | Valeurs | Défaut |
|---|---|---|---|
| `ttsProvider` | `String?` | `"OPENAI"`, `"ELEVENLABS"`, ou `null` (= `"FREE"`) | `null` |
| `ttsApiKey` | `String?` | clé API de l'utilisateur | `null` |
| `ttsVoice` | `String?` | voix du provider (ex. `alloy` pour OpenAI) | `null` |

> `null` et chaîne vide sont équivalents côté front : le provider par défaut est le **fallback
> gratuit** (`FREE`), qui ne nécessite aucune clé.

## 2. Où est stockée la configuration

Le fichier `settings.json` est écrit par `SettingsRepositoryImpl`
(`api/src/main/kotlin/com/maxlass/studio/settings/data/SettingsRepositoryImpl.kt`) dans le
répertoire `studio.storage-dir` de `StudioProperties` (défaut `~/.luniiUnchained/settings.json`).

```json
{
  "libraryPath": "/Users/me/Documents/luniiUnchained/Packs",
  "unofficialDbPath": null,
  "targetDeviceType": null,
  "ttsProvider": "OPENAI",
  "ttsApiKey": "sk-...",
  "ttsVoice": "alloy"
}
```

**Implications sécurité** :
- la clé est stockée **en clair** dans un fichier local (application de bureau monoposte) ;
- elle n'est **jamais** envoyée ailleurs que sur l'API du provider TTS (OpenAI/ElevenLabs) ;
- un futur chiffrement (clé de session Electron, Keychain) est possible sans changer le contrat
  de l'API `PUT /settings` (les champs restent les mêmes).

## 3. API HTTP

- `GET /settings` → retourne l'objet complet (y compris la clé API, en clair — app locale).
- `PUT /settings` → enregistre l'objet complet (mêmes champs).

Le contrat est inchangé par rapport à l'existant : seuls les 3 nouveaux champs sont ajoutés.
Le front mappe `ttsProvider = "FREE"` → `null` à l'enregistrement.

## 4. Guide utilisateur

### Obtenir une clé API

| Provider | Où | Coût |
|---|---|---|
| OpenAI | https://platform.openai.com → API keys (modèle `gpt-4o-mini-tts` ou `tts-1`) | Payant (à l'usage) |
| ElevenLabs | https://elevenlabs.io → Profile → API keys | Gratuit limité / payant |
| Fallback gratuit | aucun — Google Translate TTS (inclus) | Gratuit, sans clé |

### Configurer dans l'app

1. Ouvrir les **Settings** (icône engrenage dans l'en-tête) ;
2. Section **Text-to-speech** ;
3. Choisir le provider (`Free (Google Translate)` par défaut, `OpenAI`, `ElevenLabs`) ;
4. Si un provider payant est choisi : coller la **clé API** et, optionnellement, une **voix**
   (laisser vide pour la voix par défaut du provider) ;
5. **Save**.

> Le choix `Free (Google Translate)` masque les champs clé/voix. La clé n'est envoyée au serveur
> que lors d'un enregistrement explicite des settings.

## 5. Tests

- `api/src/test/kotlin/com/maxlass/studio/settings/data/SettingsRepositoryImplTest.kt` :
  défauts (provider `null`), round-trip des champs TTS, tolérance aux clés inconnues
  (`ignoreUnknownKeys`) pour la compatibilité ascendante des fichiers existants.
- `library-web/src/app/features/settings/settings-dialog/settings-dialog.component.spec.ts` :
  rendu de la section (champs clé/voix masqués en mode `FREE`), mapping `FREE → null` au save,
  round-trip clé/voix.

## 6. Consommateurs

- Étape 2 (moteur TTS) : `TtsEngine` lira `settings.ttsProvider` / `ttsApiKey` / `ttsVoice` pour
  sélectionner l'adaptateur (OpenAI, ElevenLabs, fallback gratuit).
