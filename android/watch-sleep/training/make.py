"""Turns the primitives of the loaders into the features the network trains on: make.py [folder]."""
import numpy as np, glob, os, sys, warnings
warnings.filterwarnings('ignore')
HERE=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,HERE)
from features import *
WORK=os.environ.get('SLEEP_WORK',os.path.join(HERE,'work'))
out=sys.argv[1] if len(sys.argv)>1 else 'fc'
os.makedirs(WORK+'/'+out,exist_ok=True)
n=0
for f in sorted(glob.glob(WORK+'/p/*.npz')):
    d=np.load(f,allow_pickle=True); y=d['y']; N=len(y)
    X=night_features(d['act'],d['peak'],d['turn'],d['ang'],d['ok'],d['g'],N)
    np.savez_compressed(f"{WORK}/{out}/{os.path.basename(f)}",X=X,y=y,subject=str(d['subject']),source=str(d['source'])); n+=1
print('nights',n,'features',X.shape[1])
