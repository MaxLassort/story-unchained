# Configuration TTS (Text-to-Speech) — BYOK

> Stockage et configuration de la clé API TTS utilisée pour la création d'histoires.

---

## Métadonnées

- **Statut** : Actif
- **Dernière mise à jour** : 2026-08-21
- **Liens** : [Moteur TTS](tts-engine.md) · [Plan étape 1](../plans/story-creation-plan.md)
- **Fichiers concernés** : `api/src/main/kotlin/com/maxlass/studio/settings/` · `library-web/src/app/features/settings/settings-dialog/`

---

## 1. Vue d'ensemble

La création d'histoires génère des **titres audio** de nœuds via TTS. Le moteur choisi est **BYOK** (« Bring Your Own Key ») : l'utilisateur fournit sa propre clé API, stockée localement dans le fichier de settings de l'application. Sans clé configurée, un **fallback gratuit** (Google Translate TTS) est utilisé.

### Champs de configuration

| Champ (`Settings`) | Type | Valeurs | Défaut |
|---|---|---|---|
| `ttsProvider` | `String?` | `"OPENAI"`, `"ELEVENLABS"`, ou `null` (= `"FREE"`) | `null` |
| `ttsOpenAiApiKey` | `String?` | clé API OpenAI (BYOK) | `null` |
| `ttsElevenLabsApiKey` | `String?` | clé API ElevenLabs (BYOK) | `null` |
| `ttsVoice` | `String?` | voix du provider (ex. `alloy` pour OpenAI, voice id ElevenLabs) | `null` |
| `ttsLang` | `String?` | langue ISO 639-1 (ex. `fr`), utilisée par le fallback gratuit | `null` (= `fr`) |

> `null` et chaîne vide sont équivalents côté front : le provider par défaut est le **fallback gratuit** (`FREE`), qui ne nécessite aucune clé.

## 2. Emplacement du stockage

Le fichier `settings.json` est écrit par `SettingsRepositoryImpl` (`api/src/main/kotlin/com/maxlass/studio/settings/data/SettingsRepositoryImpl.kt`) dans le répertoire `studio.storage-dir` de `StudioProperties` (défaut `~/.luniiUnchained/settings.json`).

```json
{
  "libraryPath": "/Users/me/luniiUnchained/Packs",
  "unofficialDbPath": null,
  "targetDeviceType": null,
  "ttsProvider": "OPENAI",
  "ttsOpenAiApiKey": "sk-...",
  "ttsElevenLabsApiKey": "sk_...",
  "ttsVoice": "alloy",
  "ttsLang": "fr"
}
```

### Implications sécurité

- La clé est stockée **en clair** dans un fichier local (application de bureau monoposte).
- Elle n'est **jamais** envoyée ailleurs que sur l'API du provider TTS (OpenAI/ElevenLabs).
- Un futur chiffrement (clé de session Electron, Keychain) est possible sans changer le contrat de l'API `PUT /settings`.

## 3. API HTTP

| Endpoint | Méthode | Corps | Réponse | Description |
|---|---|---|---|---|
| `/settings` | GET | — | Objet complet (clé en clair) | Lecture des settings |
| `/settings` | PUT | Objet complet | Objet complet | Écriture des settings |

Le contrat est inchangé par rapport à l'existant : seuls les 3 nouveaux champs sont ajoutés. Le front mappe `ttsProvider = "FREE"` → `null` à l'enregistrement.

## 4. Guide utilisateur

### Obtenir une clé API

| Provider | Où | Coût |
|---|---|---|
| OpenAI | https://platform.openai.com → API keys (modèle `gpt-4o-mini-tts` ou `tts-1`) | Payant (à l'usage) |
| ElevenLabs | https://elevenlabs.io → Profile → API keys | Gratuit limité / payant |
| Fallback gratuit | aucun — Google Translate TTS (inclus) | Gratuit, sans clé |

### Configurer dans l'app

1. Ouvrir les **Settings** (icône engrenage dans l'en-tête).
2. Section **Text-to-speech**.
3. Choisir le provider (`Free (Google Translate)` par défaut, `OpenAI`, `ElevenLabs`).
4. Si un provider payant est choisi : coller la **clé API** et, optionnellement, une **voix** (laisser vide pour la voix par défaut du provider).
5. **Save**.

> Le choix `Free (Google Translate)` masque les champs clé/voix. La clé n'est envoyée au serveur que lors d'un enregistrement explicite des settings.

## 5. Tests

| Fichier | Couverture |
|---|---|
| `api/src/test/kotlin/com/maxlass/studio/settings/data/SettingsRepositoryImplTest.kt` | Défauts (provider `null`), round-trip des champs TTS, tolérance aux clés inconnues (`ignoreUnknownKeys`) |
| `library-web/src/app/features/settings/settings-dialog/settings-dialog.component.spec.ts` | Rendu de la section (champs clé/voix masqués en mode `FREE`), mapping `FREE → null` au save, round-trip clé/voix |

## 6. Consommateurs

- **Étape 2 (moteur TTS)** : `TtsEngine` lit `settings.ttsProvider` / `ttsOpenAiApiKey` / `ttsElevenLabsApiKey` / `ttsVoice` / `ttsLang` pour sélectionner l'adaptateur (OpenAI, ElevenLabs, fallback gratuit).
