# L'audio d'une node — formats et compression

> Chaque `stageNode` porte **au plus un fichier audio** (champ `audio`). Ce doc décrit ce fichier
> selon le format de pack : conteneurs acceptés, compression réellement appliquée, contraintes
> par format et conversions. Le modèle des nœuds est dans [`pack-model.md`](pack-model.md).

---

## 1. Résumé par format de pack

| | **Archive (studio)** | **RAW (binaire)** | **FS (folder / lunii)** |
|---|---|---|---|
| Fichiers acceptés | `.mp3`, `.ogg`, `.oga`, `.wav` | WAV uniquement | MP3 uniquement |
| Compression | Libre (telle que fournie) | PCM signé **16 bits**, **mono**, **32 kHz** | MPEG-1 Layer III, **CBR 128 kbps**, **mono**, **44,1 kHz** |
| Tag ID3 | Toléré | n/a | **Interdit** (v1 et v2 rejetés) |
| MIME | déduit de l'**extension** à la lecture | `audio/x-wav` | `audio/mpeg` |
| Nommage | `{sha1-hex}{ext}` dans `assets/` | adressé par secteur | `sf/000\{index:08}` + index `si` |
| Page sans audio | `audio: null` | asset `-1` | **MP3 blank obligatoire** (placeholder) |

Lecture/écriture : `ArchiveStoryPackReader/Writer` (archive), `BinaryStoryPackReader/Writer` (RAW),
`FsStoryPackReader/Writer` (FS). Conversions : `AudioConversion` + `PackAssetsCompression`.

---

## 2. Format FS — les contraintes exactes

Le format **folder** (celui de l'appareil, firmware ≥ 2) est le plus contraint. Le writer valide
chaque asset audio (`FsStoryPackWriter`) :

1. **MIME** = `audio/mp3` ou `audio/mpeg` → sinon rejet (« FS pack file requires audio assets to be MP3 »).
2. **Aucun tag ID3** v1 ni v2 (`Id3Tags.hasId3v1Tag/hasId3v2Tag`) → les tags décalent les frames
   attendues par le décodeur de l'appareil.
3. **Mono et 44 100 Hz** (`AudioSystem.getAudioFileFormat` : `channels == 1 && sampleRate == 44100f`).

### L'encodage appliqué par StoryUnchained (`AudioConversion.anyToMp3`)

```
entrée (MP3/WAV/OGG…)
   │ décodage via Java AudioSystem → PCM signé, fréquence d'origine
   │ sur-échantillonnage ×2 (réduit les artefacts de ré-échantillonnage)
   │ ré-échantillonnage → PCM float 44 100 Hz mono
   ▼ encodage jump3r (port Java de LAME)
sortie : MP3 MPEG-1 Layer III — CBR 128 kbps — mono — 44,1 kHz
```

> **Pourquoi 128 kbps CBR ?** Le décodeur Lunii **rejette les frames bas débit** (32 kbps)
> produites par le VBR par défaut de jump3r. L'encodage est donc forcé à **128 kbps CBR mono**.

### Le MP3 blank (`BlankMp3`)

Une page sans audio **doit** quand même référencer un asset en FS : le writer insère un MP3
silencieux embarqué (`BlankMp3.HEX`, MIME `audio/mpeg`). En mémoire le pack garde bien
`audio = null` ; le placeholder n'existe que dans le format folder.

---

## 3. Format RAW — le WAV « device »

Le binaire RAW (appareils firmware 1.x) stocke l'audio **non compressé**, tel que le matériel le
lit : `BinaryStoryPackWriter` exige `audio/x-wav`.

- PCM **signé 16 bits** (`BITSIZE = 16`), **mono** (`CHANNELS = 1`), **32 000 Hz**
  (`WAVE_SAMPLE_RATE = 32000`), little-endian.
- Toute entrée (MP3, OGG…) passe par `AudioConversion.anyToWave` : décodage Java AudioSystem →
  ré-échantillonnage linéaire vers 32 kHz mono.
- Taille : ≈ 64 Ko/seconde — c'est le format le plus volumineux, la conversion
  ARCHIVE → RAW compresse d'abord les images mais passe l'audio en WAV.

---

## 4. Format archive — conteneur souple

Le zip STUdio n'impose **aucune compression audio** : le format d'un asset est déduit de son
**extension** (`.mp3` → `audio/mpeg`, `.ogg`/`.oga` → `audio/ogg`, `.wav` → `audio/x-wav`).

- Packs produits par StoryUnchained (création d'histoire) : audio **MP3** (uploads et TTS).
- Conversion RAW → ARCHIVE : WAV → **OGG** visé pour économiser de l'espace — mais l'encodage
  OGG n'est **pas disponible** dans le projet (`waveToOgg` → `UnsupportedOperationException`,
  dépendance `vorbis-java` non résoluble) : le WAV d'origine est conservé en fallback.
- Le décodage OGG/MP3 en entrée fonctionne, lui, dans tous les sens (vers WAV, vers MP3).

---

## 5. Ce qui entre côté éditeur

| Source | Détail |
|---|---|
| Upload manuel | MP3, WAV, OGG… (max 50 Mo, `audio/*`) — endpoint `PUT /story-drafts/{id}/chapters/{chapterId}/audio` |
| TTS (synthèse vocale) | Texte → audio généré à la finalisation (moteur & réglages : `doc/tts-engine.md`, `doc/tts-settings.md`) |
| Audio de titre | Couverture + titres de chapitres : upload **ou** texte TTS |

Peu importe la source : à la conversion FS, tout passe par `anyToMp3` (128 kbps CBR mono 44,1 kHz).

---

## 6. Matrice de conversion

| De ↓ vers → | ARCHIVE | RAW (WAV) | FS (MP3) |
|---|---|---|---|
| **ARCHIVE** | — | décodage → PCM 16-bit mono 32 kHz (`anyToWave`) | ré-encodage CBR 128 kbps mono 44,1 kHz (`anyToMp3`) |
| **RAW (WAV)** | conservé WAV ou OGG (encodage indisponible) | — | `anyToMp3` |
| **FS (MP3)** | conservé | décodage → 32 kHz (`anyToWave`) | — |

Règles transverses :

- **Déduplication par SHA-1** : deux pages partageant le même fichier → un seul asset stocké,
  plusieurs index qui pointent dessus.
- Les conversions s'appliquent **une fois par asset unique**, pas par nœud.

---

## 7. Où dans le code

| Élément | Fichier |
|---|---|
| Conversions audio | `pack/format/utils/AudioConversion.kt` |
| Compression/décompression de pack | `pack/format/utils/PackAssetsCompression.kt` |
| Détection tag ID3 | `pack/format/utils/Id3Tags.kt` |
| MP3 blank | `pack/format/writer/BlankMp3.kt` |
| Validation FS (MP3 mono 44,1 kHz, sans ID3) | `pack/format/writer/FsStoryPackWriter.kt` |
