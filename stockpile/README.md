# Objem hromady (Stockpile Volume)

Samostatná aplikace pro Android: obejdete hromadu (štěrk, písek, zemina, uhlí, štěpka…)
s telefonem, ten ji naskenuje ve 3D přes **ARCore** a **sám spočítá objem**, bez serveru
a bez internetu. Výsledek pak můžete jedním klepnutím **zapsat do blockchainu XRP Ledger**.
Transakci potvrdíte v peněžence **Xaman** a aplikace zápis ověří přímo v ledgeru.

```
 Telefon (ARCore hloubka + pohyb) ──▶ výpočet objemu v telefonu ──▶ record.json (+ SHA-256)
                                                                       │
                Xaman (podpis) ◀── žádost o podpis (AccountSet + Memo) ┘
                      │
                      ▼
               XRP Ledger ──▶ aplikace ověří transakci na veřejných uzlech
```

Serverová fotogrammetrie (OpenDroneMap) zůstává v projektu jako **volitelný doplněk na později**
pro velké hromady (složka `server/`). Aplikace bez ní funguje plně.

```
android/   aplikace (Kotlin, Jetpack Compose, ARCore)
server/    volitelný zpracovací server (Python + OpenDroneMap), na později
.github/   GitHub Actions, které sestaví APK
```

---

## 1. Telefon

- Android 8.0 nebo novější s podporou **ARCore**: seznam je na
  [developers.google.com/ar/devices](https://developers.google.com/ar/devices).
- Nejlepší výsledky dává telefon s **Depth API** (většina modelů střední a vyšší třídy od roku 2020).
  Aplikace sama zvolí nejlepší dostupný režim:
  1. *Raw Depth*: nejpřesnější;
  2. *Depth API*: vyhlazená hloubka;
  3. *jen body obrazu*: nouzový režim s nízkou přesností (aplikace upozorní).
- Hloubka z telefonu sahá spolehlivě asi do **5–6 m**. Aplikace je proto určená pro hromady
  přibližně **do 15 m průměru**. Větší hromady patří na serverovou fotogrammetrii nebo dron.

## 2. Sestavení a instalace APK

**Přes GitHub (bez Android Studia):**
1. Založte repozitář na GitHubu a nahrajte do něj celou tuto složku.
2. Záložka **Actions** → *Build Android APK* se spustí sama (nebo *Run workflow*).
3. Stáhněte artefakt `stockpile-volume-apk`, rozbalte a `.apk` nainstalujte do telefonu
   (povolte „instalaci z neznámých zdrojů“).

**Přes Android Studio:** otevřete složku `android/`, počkejte na Gradle sync a spusťte
**Run** s telefonem připojeným přes USB.

## 3. Nastavení Xamanu (jednorázově, asi 5 minut)

1. Na [apps.xumm.dev](https://apps.xumm.dev) se přihlaste Xamanem a vytvořte aplikaci.
2. Do povolených adres pro návrat (*Redirect URIs* / *Return URL*) přidejte:
   - `https://spikerb.github.io/3Dpille/xaman/auth.html`
   - `https://spikerb.github.io/3Dpille/xaman/signed.html`

   Jsou to dvě malé stránky ze složky `xaman/` v repozitáři (GitHub Pages), které výsledek
   z Xamanu hned předají aplikaci. Xaman přijímá jen adresy začínající `https://`.
3. Zkopírujte **API Key** a vložte ho v aplikaci: **Nastavení → Xaman API Key**.
   **API Secret do aplikace nepatří a nepotřebujete ho.** Přihlášení probíhá přes OAuth2 PKCE,
   kterému stačí veřejný klíč.
4. Zvolte síť:
   - **Testnet** na zkoušky (zdarma; testovací XRP získáte z faucetu na xrpl.org);
   - **Mainnet** pro ostré záznamy.
5. Účet v Xamanu musí být aktivovaný (mít zůstatek alespoň na rezervu XRPL).
   Jeden zápis stojí poplatek kolem 0,00001 XRP.

## 4. Měření

1. **Nové měření** → název, případně objemová hmotnost (t/m³), aby aplikace spočítala i tuny.
2. **Spustit 3D sken** a pomalu obcházejte hromadu:
   - 2–4 m od paty hromady, telefon držte klidně;
   - mířte na hromadu i na **zem mezi sebou a hromadou**, protože z ní se počítá základní rovina;
   - obejděte **celou hromadu** a vraťte se na místo startu. Aplikace ukáže „Okruh uzavřen“;
   - body na obrazovce se barví podle výšky (modrá = zem, červená = vrchol);
   - u vyšších hromad zvedněte telefon nad hlavu nebo udělejte druhé kolo, aby byl vidět i vrchol.
3. **Dokončit měření** → aplikace spočítá objem, půdorys, max. výšku a výškovou mapu
   a upozorní na problémy (nerovný terén, málo naskenovaných míst, hromada až u hranice trasy).

### Jak se objem počítá

Stejnou metodou jako server:
- **Hranice** je obrys trasy, kterou jste prošli.
- **Povrch** se skládá z hloubkových bodů, průměrovaných ve voxelech 5 cm. Odfiltrují se
  ojedinělé a jednou viděné body.
- **Základní rovina** je robustně proložená terénem podél trasy (auta, lidé a rozsypaný
  materiál se vyřadí).
- **Objem** je součet výšek povrchu nad rovinou uvnitř hranice. Nenaskenovaná místa
  (např. vrchol) se dopočtou z okolí a aplikace ukáže, kolik procent plochy bylo skutečně naskenováno.

Test na syntetických hromadách (kužel na svahu, šum, odlehlé body, nenaskenovaný vrchol)
vychází s chybou pod 0,4 %. Viz `android/tools/VolumeTest.kt`.

### Přesnost

Měřítko dává ARCore z kamery a pohybových senzorů. V praxi počítejte s **±3–10 % objemu**
podle telefonu, světla a povrchu materiálu. Před ostrým použitím si aplikaci ověřte na
hromadě se známým objemem (např. dovezená fůra). Lesklé, tmavé nebo velmi jednolité materiály
a přímé slunce přesnost zhoršují.

## 5. Zápis do blockchainu

V detailu měření → **Zapsat do blockchainu**:

1. Poprvé se otevře přihlášení Xamanu (platí 24 h).
2. Aplikace vytvoří žádost o podpis a otevře Xaman. Uvidíte text
   „Zápis měření … m³ do XRP Ledger“ a transakci potvrdíte.
3. Xaman vás vrátí do aplikace. Ta počká na validovaný ledger a zkontroluje, že transakce
   uspěla (`tesSUCCESS`) a obsahuje hash měření. Stav se změní na **✓ Zapsáno a potvrzeno**.

**Co se zapisuje (veřejně):** transakce `AccountSet` (na účtu nic nemění) s poznámkou (Memo)
typu `stockpile-volume/1` ve formátu JSON:

```json
{"sha256":"…","id":"…","name":"Štěrk 16/32","measured_at":"2026-10-15T08:00:00Z",
 "volume_m3":523.132,"tonnes":837.01,"footprint_m2":301.7,"max_height_m":4.32,"method":"arcore-raw-depth"}
```

**GPS poloha se do blockchainu nezapisuje.** Je jen v soukromém souboru `record.json`, který je
zajištěný hashem. Uvědomte si, že **název hromady je veřejný**.

### Ověření kýmkoli

Tlačítko **Sdílet** pošle `record.json`, výškovou mapu (PNG) a výškovou mřížku (`heightmap.f32`).
Příjemce si zápis ověří bez aplikace:

```bash
sha256sum record.json        # musí se shodovat s "sha256" v memu transakce
```

Transakci najde v průzkumníku (odkaz je ve sdíleném textu, např. `livenet.xrpl.org/transactions/…`).
Memo je zobrazené v hex/UTF-8. V aplikaci totéž udělá tlačítko **Ověřit**.

`heightmap.f32` je mřížka `rows × cols` hodnot float32 (little-endian, po řádcích) s výškou
nad základní rovinou v metrech (NaN = mimo hranici). Rozměry a velikost buňky jsou
v `record.json → heightmap`, kde je i SHA-256 tohoto souboru.

## 6. Volitelný server (na později)

Pro velké hromady lze v **Nastavení → Zpracovací server** zadat server z `server/` (Docker +
OpenDroneMap). V detailu hromady se pak objeví fotografický režim a odeslání fotek.
Postup instalace serveru je v `server/` (`docker compose up -d --build`, nastavit `API_KEY`
v `.env`), API popisuje `server/stockpile_api/main.py`.

## 7. Poznámky

- APK z GitHub Actions je podepsané ladicím klíčem, aby šlo rovnou instalovat. Před distribucí
  nebo Google Play nastavte vlastní podpisový klíč.
- Data měření jsou jen v telefonu. Do blockchainu jde pouze memo popsané výše.
- Test výpočtu na počítači:
  ```bash
  cd android
  kotlinc tools/VolumeTest.kt app/src/main/java/com/stockpile/volume/measure/PointCloud.kt \
          app/src/main/java/com/stockpile/volume/measure/VolumeCalculator.kt -include-runtime -d vt.jar
  java -jar vt.jar
  ```
