"""Epoch features for sleep staging. Mirrored by SleepFeatures.kt: change one, change the other."""
import numpy as np
EPOCH = 30.0
SUB = 5.0          # seconds per sub window
GRID = 5.0         # seconds per heart rate grid step
CENTER = True      # per night: see the comment in night_features
FLOOR_G = 0.002    # below this a wrist is not distinguishable from sensor noise, and sensors differ there
NAMES = ['act','peak','turn','angle','motion_ok',
         'hr','hr_ok','hr_step','hr_local2','hr_local10','hr_slow','hr_jitter',
         'act2','act10','still10','hours',
         'hr_b1','hr_b2','hr_b3']

def motion_primitives(t, xyz, n):
    """t in seconds since the night started, xyz in g with Android's sign. Returns per epoch act, peak, turn, angle, ok."""
    per = int(EPOCH/SUB)
    subs = n*per
    idx = np.floor(t/SUB).astype(np.int64)
    keep = (idx >= 0) & (idx < subs)
    idx = idx[keep]; v = xyz[keep].astype(np.float64)
    count = np.bincount(idx, minlength=subs).astype(np.float64)
    means = np.zeros((subs,3)); var = np.zeros(subs)
    for a in range(3):
        s = np.bincount(idx, v[:,a], minlength=subs)
        q = np.bincount(idx, v[:,a]*v[:,a], minlength=subs)
        with np.errstate(invalid='ignore', divide='ignore'):
            m = s/count; means[:,a] = m; var += q/count - m*m
    ok_sub = count >= 2
    std = np.sqrt(np.clip(var, 0, None))
    angle = np.degrees(np.arctan2(means[:,2], np.hypot(means[:,0], means[:,1])))
    d = np.zeros(subs); d[1:] = np.abs(angle[1:]-angle[:-1])
    d[~ok_sub] = 0; d[1:][~ok_sub[:-1]] = 0
    act = np.full(n, np.nan); peak = np.full(n, np.nan); turn = np.zeros(n); ang = np.full(n, np.nan)
    ok = np.zeros(n)
    for i in range(n):
        o = ok_sub[i*per:(i+1)*per]
        if o.sum() == 0: continue
        s = std[i*per:(i+1)*per][o]
        act[i] = np.log10(max(s.mean(), FLOOR_G)); peak[i] = np.log10(max(s.max(), FLOOR_G))
        turn[i] = np.log10(1 + d[i*per:(i+1)*per].sum()); ang[i] = angle[i*per:(i+1)*per][o].mean()/90
        ok[i] = o.mean()
    return act, peak, turn, ang, ok

def heart_grid(t, bpm, n):
    """Mean per 5 s step, whatever rate the sensor reports at. NaN where nothing came."""
    steps = int(n*EPOCH/GRID)
    keep = (t >= 0) & (t < n*EPOCH) & (bpm >= 30) & (bpm <= 220)
    t = t[keep]; bpm = bpm[keep].astype(np.float64)
    order = np.argsort(t); t = t[order]; bpm = bpm[order]
    if len(bpm) >= 3:  # a single wild value is a sensor error, not a heart
        med = np.median(np.stack([np.r_[bpm[0],bpm[:-1]], bpm, np.r_[bpm[1:],bpm[-1]]]), axis=0)
        bpm = np.where(np.abs(bpm-med) > 15, med, bpm)
    idx = np.floor(t/GRID).astype(np.int64)
    c = np.bincount(idx, minlength=steps).astype(np.float64)
    s = np.bincount(idx, bpm, minlength=steps)
    with np.errstate(invalid='ignore', divide='ignore'):
        g = s/c
    return g

def rolling(a, half, fn):
    n = len(a); r = np.full(n, np.nan)
    for i in range(n):
        w = a[max(0,i-half):i+half+1]
        w = w[~np.isnan(w)]
        if len(w): r[i] = fn(w)
    return r

def causal_fill(a):
    idx = np.where(~np.isnan(a))[0]
    if len(idx) == 0: return np.zeros_like(a)
    return np.interp(np.arange(len(a)), idx, a[idx])

def night_features(act, peak, turn, ang, mok, grid, n):
    per = int(EPOCH/GRID)
    g = grid[:n*per]
    ok = np.array([np.mean(~np.isnan(g[i*per:(i+1)*per])) for i in range(n)])
    mean = np.array([np.nanmean(g[i*per:(i+1)*per]) if ok[i] > 0 else np.nan for i in range(n)])
    have = mean[~np.isnan(mean)]
    if len(have) >= 20:
        med = np.median(have); q = np.percentile(have,[25,75]); scale = max(q[1]-q[0], 3.0)
    else:
        med = 60.0; scale = 10.0
    z = (mean-med)/scale
    zf = causal_fill(z)
    step = np.zeros(n); step[1:] = np.abs(zf[1:]-zf[:-1])
    local2 = rolling(z, 2, np.std); local10 = rolling(z, 10, np.std)
    slow = zf - rolling(zf, 30, np.mean)
    # how nervous the 5 s values are around the epoch: the nearest a sparse sensor gets to variability between beats
    d = np.abs(np.diff(g))/scale
    jitter = np.array([np.nanmean(d[max(0,(i-1)*per):min(len(d),(i+2)*per)]) if np.any(~np.isnan(d[max(0,(i-1)*per):min(len(d),(i+2)*per)])) else np.nan for i in range(n)])
    a = np.where(np.isnan(act), np.log10(FLOOR_G), act)
    act2 = rolling(a, 2, np.mean); act10 = rolling(a, 10, np.mean)
    moved = (a > np.log10(0.02)).astype(float)
    still = np.zeros(n); run = 0
    for i in range(n):
        run = 0 if moved[i] else run+1
        still[i] = min(run, 40)/40
    still2 = np.zeros(n); run = 0
    for i in range(n-1,-1,-1):
        run = 0 if moved[i] else run+1
        still2[i] = min(run, 40)/40
    still10 = np.minimum(still, still2)
    hours = np.arange(n)*EPOCH/3600/10
    # heart rate energy in three time scales around the epoch: REM and deep sleep differ here more than in level
    gf = causal_fill(g)/scale
    ma = lambda a, w: np.convolve(np.r_[np.full(w//2, a[0]), a, np.full(w-1-w//2, a[-1])], np.ones(w)/w, 'valid')
    m3 = ma(gf, 3); m12 = ma(gf, 12); m48 = ma(gf, 48)
    b = [gf-m3, m3-m12, m12-m48]
    bands = []
    for x in b:
        c = np.convolve(np.r_[np.full(30,0.0), x*x, np.full(29,0.0)], np.ones(60)/60, 'valid')   # +-2.5 min
        e = np.array([c[min(i*per+per//2, len(c)-1)] for i in range(n)])
        bands.append(np.log10(e + 1e-4)/4 + 1)
    if CENTER:
        # What matters is high or low for this night: it also takes away most of the difference between sensors.
        def center(a):
            have = a[~np.isnan(a)]
            return a - (np.median(have) if len(have) else 0.0)
        step = center(step); local2 = center(local2); local10 = center(local10); jitter = center(jitter)
        bands = [center(x) for x in bands]
    X = np.stack([np.nan_to_num(act, nan=np.log10(FLOOR_G))/3+1, np.nan_to_num(peak, nan=np.log10(FLOOR_G))/3+1, turn/2, np.nan_to_num(ang), mok,
                  np.nan_to_num(z), ok, np.clip(step,-5,5), np.nan_to_num(local2), np.nan_to_num(local10), np.clip(slow,-5,5), np.nan_to_num(jitter),
                  act2/3+1, act10/3+1, still10, hours, bands[0], bands[1], bands[2]], axis=1).astype(np.float32)
    return X
