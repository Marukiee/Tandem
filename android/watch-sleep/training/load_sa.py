"""sleep-accel (PhysioNet): 31 people, one night each in a sleep lab with polysomnography, Apple Watch. Unpacked in WORK/data/sa."""
import numpy as np, glob, os, sys
HERE=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,HERE)
from features import *
WORK=os.environ.get('SLEEP_WORK',os.path.join(HERE,'work'))
root = glob.glob(os.path.join(WORK,'data','sa','*'))[0]
os.makedirs(WORK+'/p',exist_ok=True)
ids = sorted(os.path.basename(f).split('_')[0] for f in glob.glob(root+'/labels/*'))
MAP = {0:0,1:1,2:1,3:2,5:3}   # wake, light, deep, rem
for s in ids:
    L = np.loadtxt(f'{root}/labels/{s}_labeled_sleep.txt')
    M = np.fromfile(f'{root}/motion/{s}_acceleration.txt', sep=' ').reshape(-1,4)
    H = np.loadtxt(f'{root}/heart_rate/{s}_heartrate.txt', delimiter=',')
    n = int(L[-1,0]/30)+1
    y = np.full(n, -1)
    for t,l in L:
        if int(l) in MAP: y[int(t/30)] = MAP[int(l)]
    # Apple reports gravity with the opposite sign of Android
    act,peak,turn,ang,ok = motion_primitives(M[:,0], -M[:,1:], n)
    g = heart_grid(H[:,0], H[:,1], n)
    np.savez_compressed(f'{WORK}/p/sa_{s}.npz', act=act,peak=peak,turn=turn,ang=ang,ok=ok,g=g,y=y,subject=f'sa{s}',source='sa')
    X = night_features(act,peak,turn,ang,ok,g,n)
    dt = np.diff(M[(M[:,0]>0)&(M[:,0]<600),0])
    print(s, 'n',n, 'accel Hz', round(1/np.median(dt),1), 'hr pts', int(((H[:,0]>=0)&(H[:,0]<n*30)).sum()),'hr ok',round(float(X[:,6].mean()),2), 'dist', np.bincount(y[y>=0],minlength=4), flush=True)
