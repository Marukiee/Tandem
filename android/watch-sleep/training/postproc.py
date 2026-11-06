"""Compares ways to turn the network's probabilities into a night, on nights it never saw: postproc.py DUMP_DIR [DUMP_DIR ...]. Needs train.py --dump."""
import glob, os, sys
import numpy as np

PRIOR = np.log(np.array([.7, .25, .01, .04]))
WAKE = 0


def viterbi(logp, T, prior=PRIOR):
    n = len(logp); d = logp[0] + prior; back = np.zeros((n, 4), int)
    for i in range(1, n):
        s = d[None, :].T + T
        back[i] = s.argmax(0); d = s.max(0) + logp[i]
    path = np.zeros(n, int); path[-1] = d.argmax()
    for i in range(n - 1, 0, -1): path[i - 1] = back[i, path[i]]
    return path


def read(dirs):
    out = []
    for d in dirs:
        for f in sorted(glob.glob(d + '/*.npz')):
            z = np.load(f, allow_pickle=True)
            out.append(dict(lp=z['lp'], y=z['y'], T=z['T'], subject=str(z['subject']), source=str(z['source'])))
    return out


def decode(night, scale, bias):
    lp = night['lp'].copy()
    lp[:, WAKE] += bias
    return viterbi(lp, night['T'] * scale)


def kappa(y, p, k=4):
    c = np.zeros((k, k)); np.add.at(c, (y, p), 1); n = c.sum()
    if n == 0: return float('nan')
    po = np.trace(c) / n; pe = (c.sum(0) * c.sum(1)).sum() / n / n
    return (po - pe) / (1 - pe) if pe < 1 else 0.0


def night_stats(stages):
    """Minutes awake inside the sleep period, and how often sleep broke, the way the app reports a night."""
    asleep = stages != WAKE
    if not asleep.any(): return 0.0, 0, 0
    first = int(np.argmax(asleep)); last = len(asleep) - 1 - int(np.argmax(asleep[::-1]))
    inner = asleep[first:last + 1]
    return float((~inner).sum()) / 2, int(((~inner[1:]) & inner[:-1]).sum()), int((stages[1:] != stages[:-1]).sum())


def score(nights, preds):
    y = np.concatenate([n['y'] for n in nights]); p = np.concatenate(preds); m = y >= 0; y = y[m]; p = p[m]
    tp = ((y == WAKE) & (p == WAKE)).sum(); fn = ((y == WAKE) & (p != WAKE)).sum(); fp = ((y != WAKE) & (p == WAKE)).sum()
    waso_err = []; waso_bias = []; wakes_bias = []; none = 0; none_true = 0; switches = 0; switches_true = 0; hours = 0
    stage_err = [[] for _ in range(4)]
    for n, q in zip(nights, preds):
        v = n['y'] >= 0; t = n['y'][v]; r = q[v]
        a, wa, sa = night_stats(r); b, wb, sb = night_stats(t)
        waso_err.append(abs(a - b)); waso_bias.append(a - b); wakes_bias.append(wa - wb)
        none += a == 0; none_true += b == 0; switches += sa; switches_true += sb; hours += len(t) / 120
        for c in range(4): stage_err[c].append(abs((r == c).sum() - (t == c).sum()) / 2)
    return dict(
        kappa=kappa(y, p), median=float(np.nanmedian([kappa(n['y'][n['y'] >= 0], q[n['y'] >= 0]) for n, q in zip(nights, preds)])),
        ws=kappa((y != WAKE).astype(int), (p != WAKE).astype(int), 2), sens=tp / max(tp + fn, 1), prec=tp / max(tp + fp, 1),
        waso_err=float(np.median(waso_err)), waso_bias=float(np.mean(waso_bias)), wakes_bias=float(np.mean(wakes_bias)),
        none=none / len(nights), none_true=none_true / len(nights), switches=switches / hours, switches_true=switches_true / hours,
        stage_err=[float(np.median(e)) for e in stage_err],
    )


def line(label, s):
    return (f"{label:>16} kappa {s['kappa']:.3f} med {s['median']:.3f} ws {s['ws']:.3f} | wake sens {s['sens']:.2f} prec {s['prec']:.2f} | "
            f"WASO fout {s['waso_err']:4.0f} bias {s['waso_bias']:+5.1f} | ontwaken bias {s['wakes_bias']:+5.1f} | nul wakker {s['none']:.2f} (echt {s['none_true']:.2f}) | "
            f"wissels/u {s['switches']:.1f} (echt {s['switches_true']:.1f}) | fase-fout {' '.join(f'{e:.0f}' for e in s['stage_err'])}")


def bootstrap(nights, a, b, rounds=1000, seed=7):
    """Whole people at a time, because nights of one person are not independent."""
    rng = np.random.default_rng(seed); subs = sorted({n['subject'] for n in nights})
    by = {s: [i for i, n in enumerate(nights) if n['subject'] == s] for s in subs}
    dk = []; dw = []; dws = []
    for _ in range(rounds):
        idx = [i for s in rng.choice(subs, len(subs)) for i in by[s]]
        ns = [nights[i] for i in idx]; sa = score(ns, [a[i] for i in idx]); sb = score(ns, [b[i] for i in idx])
        dk.append(sb['kappa'] - sa['kappa']); dw.append(sb['waso_err'] - sa['waso_err']); dws.append(sb['ws'] - sa['ws'])
    ci = lambda v: tuple(np.round(np.percentile(v, [2.5, 97.5]), 3))
    return ci(dk), ci(dws), ci(dw)


if __name__ == '__main__':
    args = sys.argv[1:]
    best = None
    if args[0] == '--best':
        best = tuple(float(v) for v in args[1].split(',')); args = args[2:]
    nights = read(args)
    print(len(nights), 'nachten', len({n['subject'] for n in nights}), 'mensen')
    base = [decode(n, 1.0, 0.0) for n in nights]
    print(line('ongewogen', score(nights, base)))
    print(line('zonder viterbi', score(nights, [n['lp'].argmax(1) for n in nights])))
    if not best:
        for scale in (1.0, .8, .6, .5, .4, .3):
            for bias in (0.0, .2, .4, .6, .8, 1.0, 1.3):
                if scale == 1.0 and bias == 0.0: continue
                print(line(f'T x{scale} wake+{bias}', score(nights, [decode(n, scale, bias) for n in nights])))
    if best:
        chosen = [decode(n, *best) for n in nights]
        print('gekozen', best, 'tegen ongewogen, 95% interval: verschil kappa, wakker tegen slaap, WASO-fout', bootstrap(nights, base, chosen))
        for src in sorted({n['source'] for n in nights}):
            i = [k for k, n in enumerate(nights) if n['source'] == src]
            print(line(f'{src} ongewogen', score([nights[k] for k in i], [base[k] for k in i])))
            print(line(f'{src} gekozen', score([nights[k] for k in i], [chosen[k] for k in i])))
