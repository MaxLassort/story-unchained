# Moteur TTS

## Pourquoi

La création d'histoires génère des titres audio pour chaque chapitre. On veut du TTS sans
obliger l'utilisateur à configurer quoi que ce soit, tout en lui laissant la possibilité
d'utiliser une vraie voix de qualité s'il a une clé API.

## Choix

- **BYOK (Bring Your Own Key)** : l'utilisateur colle sa propre clé OpenAI ou ElevenLabs dans
  les settings. Pas de clé dans `application.yml`, rien de secret embarqué dans l'app.
- **Deux providers payants + un fallback gratuit** : OpenAI (voix simples, pas cher),
  ElevenLabs (voix plus naturelles), et Google Translate TTS en secours si aucune clé n'est
  configurée — ou si l'appel au provider payant échoue.
- **Une clé par provider** (`ttsOpenAiApiKey` / `ttsElevenLabsApiKey`) : on peut avoir un
  provider configuré et l'autre pas, sans se marcher dessus.
- **Voix et langue en liste (pas en texte libre)** : le front charge le catalogue de voix du
  provider (`GET /tts/voices`) et propose 12 langues. Pour ElevenLabs, la liste est récupérée
  en live avec la clé ; si elle échoue (clé invalide, pas de permission), on retombe sur une
  liste statique des voix publiques, marquée `fallback: true`.
- **Sortie MP3 uniforme** : tout passe par `AudioConversion.anyToMp3` (MP3 mono 44.1 kHz),
  quel que soit le provider.
- **Spring AI 2.0.0** : on ne réinvente pas les clients HTTP des providers, on construit le
  modèle au runtime avec la clé utilisateur.

## Comment ça marche

```
GET /tts/preview?text=…&voice=…&lang=…
        │
        ▼
   TtsEngine ── choisit l'adaptateur selon les settings
        │
        ├─ OpenAiTtsAdapter        (clé OpenAI)
        ├─ ElevenLabsTtsAdapter    (clé ElevenLabs)
        └─ GoogleTranslateTtsAdapter (gratuit, sans clé)
        │
        ▼
   AudioConversion.anyToMp3  →  audio/mpeg
```

- `TtsEngine` : lit `ttsProvider` + la clé correspondante → adaptateur BYOK, sinon fallback
  gratuit. En cas d'erreur du provider payant, bascule automatique sur le fallback (warning
  loggé).
- La voix demandée dans la requête prime sur la voix des settings ; pareil pour la langue
  (défaut `fr`, utilisée par le fallback Google — OpenAI/ElevenLabs sont multilingues).
- `GET /tts/voices?provider=…` fournit le catalogue au front.
- Tous les adaptateurs implémentent le même port `TextToSpeechPort.synthesize(text, voice, lang)`,
  donc ajouter un provider = une classe + un bean.

## Où

- Port : `pack/port/external/TextToSpeechPort.kt`
- Adaptateurs : `pack/adapter/{OpenAi,ElevenLabs,GoogleTranslate}TtsAdapter.kt` + `TtsAdapterConfig.kt`
- Orchestration : `pack/service/TtsEngine.kt`, `TtsVoiceCatalogService.kt`
- API : `pack/web/TtsController.kt`
- Settings : `ttsProvider`, `ttsOpenAiApiKey`, `ttsElevenLabsApiKey`, `ttsVoice`, `ttsLang`