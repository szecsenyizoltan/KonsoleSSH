# F-Droid és IzzyOnDroid submission

A `hu.billman.konsolessh.yml` az előkészített F-Droid metadata. Két beküldést érdemes párhuzamosan elindítani: IzzyOnDroid (1-3 nap, könnyebb) és hivatalos F-Droid (1-4 hét, nagyobb közönség).

## 1. IzzyOnDroid (Codeberg, nem GitLab)

Codeberg-en kell fiókot nyitni, ha még nincs (GitHub-osom mail-lel pár perc).

1. Menj: https://codeberg.org/IzzyOnDroid/rfp
2. `Issues → New issue`
3. **Title**: `RFP: KonsoleSSH — Multi-tab SSH terminal`
4. **Body** (copy-paste):

```
**Package name:** hu.billman.konsolessh
**Source code:** https://github.com/szecsenyizoltan/KonsoleSSH
**License:** GPL-3.0-or-later
**Category:** Connectivity, Development

**Description:**
Multi-tab SSH terminal for Android with a Canvas-based emulator,
jump-host support, SFTP file upload, and encrypted storage for
saved connection profiles. Inspired by KDE Konsole.

Key points for IzzyOnDroid inclusion:
- Signed release APKs are published on every tag as GitHub Release
  assets (naming: `KonsoleSSH-X.Y.Z-release.apk`).
- SHA-256 signer fingerprint (stable across all releases):
  `5B:1B:21:DA:18:64:2C:4E:50:44:61:FE:31:7F:4F:2C:B7:A5:AB:54:74:2C:E4:41:B2:5D:12:D4:C7:BC:03:4D`
- No Google Play Services, no proprietary dependencies.
- targetSdk 35, minSdk 26.

Latest release: v1.0.7 — https://github.com/szecsenyizoltan/KonsoleSSH/releases/tag/v1.0.7
```

5. **Submit issue.**

Utána a maintainer (Izzy) felveszi a repójába, és az alkalmazás néhány napon belül megjelenik az `IzzyOnDroid` repo alatt minden F-Droid kliensben.

---

## 2. Hivatalos F-Droid (GitLab MR)

### Előkészület

1. GitLab fiók — ha a GitHub-osom mail-lel regisztráltál, már van.
2. Nyiss egy terminál-tabot, és állítsd be a GitLab SSH kulcsot (ha még nem):
   ```
   cat ~/.ssh/id_ed25519.pub
   ```
   Menj https://gitlab.com/-/user_settings/ssh_keys → Add new key → paste.

### Fork + MR

1. **Fork** a hivatalos fdroiddata repo-t: https://gitlab.com/fdroid/fdroiddata → `Fork` gomb jobb felül → célozd a saját namespace-edet.

2. **Clone** lokálisan (vigyázz, ~1 GB):
   ```bash
   git clone git@gitlab.com:<saját-felhasználód>/fdroiddata.git
   cd fdroiddata
   git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
   git fetch upstream
   git checkout -b konsolessh-new upstream/master
   ```

3. **Másold a metadata-t**:
   ```bash
   cp ~/AndroidStudioProjects/KonsoleSSH/fdroid/hu.billman.konsolessh.yml metadata/
   ```

4. (Opcionális) Telepítsd az `fdroidserver`-t és lintelj:
   ```bash
   sudo apt install fdroidserver
   fdroid lint hu.billman.konsolessh
   fdroid rewritemeta hu.billman.konsolessh
   fdroid build --try-net hu.billman.konsolessh:8   # teljes próba-build
   ```
   Az utolsó parancs percekig tart, kipróbálja, hogy a build-szerverükön menne-e.

5. **Commit + push**:
   ```bash
   git add metadata/hu.billman.konsolessh.yml
   git commit -m "New app: KonsoleSSH"
   git push origin konsolessh-new
   ```

6. **Nyiss MR-t** a saját fork → fdroid/fdroiddata `master` branch között:
   - GitLab felkínálja a linket push után
   - **Title**: `New app: KonsoleSSH`
   - **Description**:
     ```markdown
     New app submission: **KonsoleSSH** (`hu.billman.konsolessh`)
     
     Multi-tab SSH terminal for Android with jump-host support, Canvas-based
     terminal emulator, SFTP file upload, and encrypted saved connections.
     
     - Source: https://github.com/szecsenyizoltan/KonsoleSSH
     - License: GPL-3.0-or-later
     - Latest tag: v1.0.7 (versionCode 8)
     - No proprietary dependencies, no Google Play Services.
     - targetSdk 35, minSdk 26.
     
     Closes: #XXXX  (only if you opened an RFP issue first)
     ```
   - Submit.

### Review folyamat

- Automatikus CI lefut a build-próbával.
- Ha zöld, egy maintainer (pl. @Izzy, @linsui, @Bubu) megnézi a YAML-t, és kérhet változtatásokat commentben.
- Merge után a következő F-Droid build-ciklusban (naponta) bekerül.

### Gyakori kérdések a review-ban

- **Reprodukálható build?** — Most nem, de nem kötelező az inclusion-höz.
- **AllowedAPKSigningKeys?** — Akkor kellene, ha a GitHub-os APK-t szeretnéd a hivatalos F-Droid-on is megjeleníteni (signature-match). Most hagyjuk el, F-Droid saját kulcsával fog aláírni — aki F-Droid-ról telepíti, annak saját verziója lesz, ami nem keveredik a Play-essel.

---

## Signature-különbség — fontos

- **Play Store APK / sideload GitHub-ról**: Te-aláírva (SHA-256 `5b1b21da…`)
- **F-Droid-ról telepített APK**: F-Droid-aláírva (más signature)
- **IzzyOnDroid-ról telepített APK**: Te-aláírva (IzzyOnDroid a GitHub APK-kat triggereli, nem épít újra)

Egy user csak **egyik** forrásból telepítheti egyszerre. Ha F-Droid-ról települt, a Play-update vagy GitHub APK-update signature-mismatch miatt nem lehetséges (újra kellene telepíteni). Ezért az IzzyOnDroid a kompatibilisebb útvonal a Play-es usereknek.
