# StoryUnchained

> ⚠️ **DISCLAIMER ANTI-PIRATERIE** — Lisez ceci avant tout.
>
> **StoryUnchained n'a PAS pour but de pirater, de distribuer ou de partager des histoires Lunii.**
> L'application ne télécharge, ne fournit et ne contient **aucun contenu protégé**. Elle sert
> uniquement à gérer **vos propres packs** : ceux que vous créez, ceux que vous avez acheté et
> possédez légalement, et les packs libres que vous êtes autorisé à utiliser.
>
> Le transfert vers/depuis la Lunii est une fonctionnalité d'**interopérabilité** pour vos
> propres fichiers, à des fins d'usage privé et de sauvegarde. L'utilisation de cette
> application pour obtenir ou partager du contenu auquel vous n'avez pas droit est **hors de son
> but** et restera de votre responsabilité.
>
> *Lunii est une marque déposée de Lunii SAS. Ce projet n'est en aucune façon affilié à Lunii SAS.*

---

## À propos

**StoryUnchained** est un **fork** de **[STUdio - Story Teller Unleashed](https://github.com/marian-m12l/studio)**,
le formidable projet open-source de gestion de story packs Lunii créé par **marian-m12l**.

STUdio a rendu possible ce que fait StoryUnchained : lire, écrire et transférer les packs Lunii dans
leurs formats (Archive, RAW, FS), comprendre le chiffrement des appareils et dialoguer avec la
Lunii. **StoryUnchained repart de ce travail** — les readers/writers de packs, le chiffrement XXTEA/AES,
les drivers USB et le catalogue de métadonnées sont des portages de STUdio — et l'enveloppe dans une
application moderne (backend Spring Boot + frontend Angular + app de bureau Electron).

> **Merci à marian-m12l et à tou·te·s les contributeur·rice·s de STUdio.**
> C'est leur travail, leur reverse engineering et leur générosité qui ont permis à cette
> application d'exister. Sans eux, rien de tout cela ne serait possible. 🙏
>
> ▶ [github.com/marian-m12l/studio](https://github.com/marian-m12l/studio) — projet sous licence **MPL-2.0**

---

## Ce que fait l'application

**StoryUnchained scanne votre bibliothèque de packs et en fait une base de données** pour trier,
filtrer et gérer vos packs beaucoup plus facilement qu'en parcourant des fichiers.

### Au premier lancement / sync, l'application :

1. **Scanne votre bibliothèque** (par défaut `~/Documents/StudioKMP/Packs`) : elle inspecte
   chaque fichier ou dossier et reconnaît les formats de packs Lunii (Archive `.zip`, RAW `.pack`,
   dossier FS).
2. **Crée une base de données** locale (H2, dans `~/.studio_kmp/db/`) qui indexe tous vos packs :
   titre, description, version, vignette, format, statut **officiel / non officiel** (via le
   catalogue Lunii officiel), etc.
3. **Trie tout cela pour vous** : recherche, filtres (officiel / non officiel / fork),
   pagination, vignettes locales, édition des métadonnées de vos packs, lien de "fork" vers un
   pack officiel.

### Les dossiers créés

- **`~/Documents/StudioKMP/Packs`** — votre bibliothèque de packs (le dossier par défaut).
- **`~/Documents/StudioKMP/invalid`** — le **dossier "erreur"** : les fichiers/dossiers qui ne
  sont pas des packs valides y sont déplacés automatiquement au sync, pour garder votre
  bibliothèque propre.
- **`~/.studio_kmp/db/`** — la base de données (H2) + le catalogue officiel (`official.json`) et
  les métadonnées non officielles (`unofficial.json`).

### Et aussi

- **Transferts Lunii** : détection de la Lunii branchée (USB), liste des packs de l'appareil,
  copier vers / depuis la Lunii, supprimer — avec mises à jour en direct (SSE).
- **Conversion de formats** entre Archive / RAW / FS selon l'appareil cible.
- **App de bureau** (Electron) : backend + frontend packagés avec un JRE embarqué — une vraie
  application installable.

---

## Structure

| Module | Rôle |
|---|---|
| `api/` | Backend **Spring Boot** (Kotlin) : sync, metadata, conversion, driver USB Lunii, SSE. Port **9090**. |
| `library-web/` | Frontend **Angular** (appel l'API sur `http://localhost:9090`). |
| `desktop/` | App **Electron** (backend + frontend packagés, JRE jlink embarqué). |

## Prérequis

- **Java 21** (JDK)
- **Node.js** (>= 22) pour le frontend
- **Driver USB (usb4java)** : natif **arm64** inclus (Apple Silicon) et **x86-64** (Intel) — les deux architectures supportées

## Run (dev)

```shell
# Backend
cd api && ./gradlew bootRun       # http://localhost:9090

# Frontend (second terminal)
cd library-web && npm install && npm start   # http://localhost:4200
```

## App desktop (Electron)

`desktop/` emballe le backend (jar Spring Boot + JRE jlink) et le frontend (build Angular) dans
une app native. Port API **9090** : si un API sain tourne déjà, l'app le réutilise, sinon elle
lance le jar embarqué (JRE dans `resources/jre`).

### Prérequis packaging (macOS Apple Silicon)

- JDK 21 **arm64** (`/usr/libexec/java_home -v 21 -a arm64`)
- libusb + cmake (Homebrew arm64) pour régénérer le natif USB : `desktop/scripts/build-native.sh`

### Build + packaging

```shell
cd desktop && npm install
npm run build        # jar + jre (jlink) + front
npm run dist:mac     # .dmg/.zip arm64 dans desktop/release/
npm run dist:win     # .nsis x64 (sur machine Windows)
```

### Dev

```shell
cd desktop && npm install
npm run dev          # Electron + ng serve (http://localhost:4200), backend 9090
```

## Build

```shell
# Backend fat jar
cd api && ./gradlew bootJar        # api/build/libs/api-0.0.1-SNAPSHOT.jar

# Frontend (production)
cd library-web && npm install && npm run build
```

## Tests

```shell
cd api && ./gradlew test
```

## Contribuer & roadmap

- **[ROADMAP.md](ROADMAP.md)** — feuille de route et fonctionnalités planifiées.
- **[CHANGELOG.md](CHANGELOG.md)** — journal des versions.
- **[CONTRIBUTING.md](.github/CONTRIBUTING.md)** — comment contribuer (fork → branche → PR → review → merge).

## Licence & remerciements

- **STUdio** ([marian-m12l/studio](https://github.com/marian-m12l/studio)) — projet original,
  licence **MPL-2.0**. Merci à toute la communauté qui l'a rendu possible.
- Ce projet reprend des éléments de STUdio (formats de packs, chiffrement, drivers, catalogue).
  Reportez-vous à la licence de chaque composant.
- Ce projet est sous **Non-Commercial Source-Available License** — voir `LICENSE`.
