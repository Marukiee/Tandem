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

## Naamsvermelding
Beide datasets staan onder ODC-BY 1.0, zie `docs/WATCH.md`. DREAMT is niet gebruikt: daar zit een gebruiksovereenkomst op.
