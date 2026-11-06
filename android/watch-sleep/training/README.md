# Slaapmodel trainen

Deze scripts maken `src/main/resources/sleep_model.bin` en de testbestanden `src/test/resources/golden_night.*`. Ze draaien niet in de
app: het model draait op het horloge in `SleepModel.kt`, zonder afhankelijkheid.

`features.py` en `SleepFeatures.kt` zijn elkaars spiegel: verander je er een, verander dan ook de ander. `SleepModelTest` bewijst per
getal dat kenmerken en netwerk in Kotlin gelijk zijn aan die in Python, op een verzonnen nacht uit `export.py`. Echte data van mensen
komt niet in de repo.

## Benodigd
Python 3.12 met `numpy`, `scipy` en `torch`, in een eigen venv. Alles wat gedownload of berekend wordt, komt in `work/` (staat niet in
git), of in de map die `SLEEP_WORK` noemt:

| Map | Inhoud |
| --- | --- |
| `work/data/sa/` | [sleep-accel](https://physionet.org/content/sleep-accel/1.0.0/) uitgepakt |
| `work/data/bid/` | `hr.csv` en `labels.mat` per nacht uit de zip hieronder, uitgepakt |
| `work/data/bidsleep.full.zip` | [bidsleep-dataset](https://physionet.org/content/bidsleep-dataset/1.0.1/), 6,4 GB, om `motion.csv` uit te lezen |
| `work/p/` | per nacht de ruwe bouwstenen (`load_sa.py`, `load_bid.py`) |
| `work/fc/` | per nacht de kenmerken (`make.py`) |

## Stappen
Uitvoeren vanuit deze map, met `-I` omdat de scripts gedownloade data lezen:

```bash
python -I load_sa.py
```

```bash
python -I load_bid.py
```

```bash
python -I make.py
```

Meten, met hele mensen apart gehouden, en daarna getraind op de ene dataset en getest op de andere:

```bash
python -I train.py --k 3
```

```bash
python -I train.py --holdout sa
```

Wat beweging toevoegt: dezelfde twee runs zonder.

```bash
python -I train.py --k 3 --nomotion
```

```bash
python -I train.py --holdout sa --nomotion
```

Manieren vergelijken om een nacht te lezen, zonder opnieuw te trainen: `--dump` bewaart wat het netwerk zei over nachten die het niet zag, en
`postproc.py` rekent daarop een raster door. Met `--best` daarna de keuze tegen de ongewogen lezing, per dataset en met een interval per persoon.

```bash
python -I train.py --k 3 --dump work/dump_k3
```

```bash
python -I train.py --holdout sa --dump work/dump_sa
```

```bash
python -I postproc.py work/dump_k3
```

```bash
python -I postproc.py --best 0.4,0.6 work/dump_k3
```

Het model maken dat meegaat, op alle nachten:

```bash
python -I export.py --nets 3 --out out
```

Daarna `out/sleep_model.bin` naar `src/main/resources/` en `out/golden_night.*` naar `src/test/resources/`, en `./gradlew :watch-sleep:test`.

## Keuzes die uit metingen kwamen
- **Per nacht centreren** (de mediaan eraf per kenmerk): kappa van 0,514 naar 0,533, en het haalt het meeste verschil tussen sensoren weg.
- **Klassegewichten `wpow` 0,25** in plaats van 0,5: bij 0,5 overschat het diepe slaap en REM op nachten uit het lab.
- **BiGRU tegen LightGBM** op dezelfde kenmerken: 0,50 tegen 0,42.
- **Viterbi**: kost een fractie kappa, maar geeft een nacht zonder onmogelijke sprongen.
- **Beweging gaat mee**, zelfde verdeling met en zonder (`--nomotion`): kappa van 0,546 naar 0,554, wakker tegen slaap van 0,578 naar 0,623.
  Getraind thuis en getest in het lab: kappa van 0,514 naar 0,543, wakker tegen slaap van 0,443 naar 0,513. De mediane fout per fase wordt
  wel 2 tot 6 minuten groter, op wakker in de eerste meting na.

- **Overgangen voor 0,4 en wakker 0,6 erbij** bij het lezen van de nacht (`TRANSITION_WEIGHT` en `WAKE_BOOST`, in `train.py` en `SleepModel.kt`): nachten
  zonder een minuut wakker van 64% naar 32% (echt 2%), mediane fout in minuten wakker van 10 naar 8, kappa niet slechter. Cijfers in `docs/WATCH.md`.

## Eigenaardigheden van de data
Sommige `motion.csv`-bestanden in `bidsleep-dataset` stoppen midden in een regel; `load_bid.py` slaat die regel over. Acht van de 284
nachten hebben voor minder dan de helft van de nacht beweging, `Bidslab42/3` maar 4 minuten. Ze tellen mee.

## Naamsvermelding
Beide datasets staan onder ODC-BY 1.0, zie `docs/WATCH.md`. DREAMT is niet gebruikt: daar zit een gebruiksovereenkomst op.
