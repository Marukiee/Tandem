"""bidsleep-dataset (PhysioNet): 47 people, 253 nights at home, Dreem 2 scored by an expert, Apple Watch. WORK/data/bidsleep.full.zip."""
import numpy as np, glob, os, sys, io, zipfile, datetime
from zoneinfo import ZoneInfo
import scipy.io as sio
HERE=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,HERE)
from features import *
WORK=os.environ.get('SLEEP_WORK',os.path.join(HERE,'work'))
root=os.path.join(WORK,'data','bid')
os.makedirs(WORK+'/p',exist_ok=True)
MAP={0:0,1:1,2:1,3:2,4:3}
zf=None
zpath=os.path.join(WORK,'data','bidsleep.full.zip')
if os.path.exists(zpath):
    zf=zipfile.ZipFile(zpath); names={n.split('bidsleep',1)[-1]:n for n in zf.namelist()}
    by={('/'.join(n.split('/')[-3:])):n for n in zf.namelist()}
def whole_lines(f):
    # A few motion files stop in the middle of a line.
    for line in f:
        if line.endswith('\n') and line.count(',')==3:
            yield line
done=0
# load_bid.py i n: only every n-th night, so several processes can share the work.
shard,shards=(int(sys.argv[1]),int(sys.argv[2])) if len(sys.argv)>2 else (0,1)
for lab in sorted(glob.glob(root+'/Bidslab*/*/labels.mat'))[shard::shards]:
    d=os.path.dirname(lab); sub=d.split('/')[-2]; night=d.split('/')[-1]
    m=sio.loadmat(lab)
    e=np.asarray(m['expert_label']).ravel().astype(int)
    rec=str(np.asarray(m['recStart']).ravel()[0])
    t0=datetime.datetime.strptime(rec,'%Y-%m-%d %H:%M:%S').replace(tzinfo=ZoneInfo('America/New_York')).timestamp()
    n=len(e); y=np.array([MAP.get(v,-1) for v in e])
    rows=[]
    for line in open(d+'/hr.csv'):   # a few files have a torn line
        q=line.strip().split(',')
        if len(q)==2:
            try: rows.append((float(q[0]),float(q[1])))
            except ValueError: pass
    H=np.array(rows,dtype=float).reshape(-1,2)
    g=heart_grid(H[:,0]-t0,H[:,1],n)
    key=f'{sub}/{night}/motion.csv'
    if zf is not None and key in by:
        M=np.loadtxt(whole_lines(io.TextIOWrapper(zf.open(by[key]))),delimiter=',',skiprows=1)
        act,peak,turn,ang,ok=motion_primitives(M[:,0]-t0,-M[:,1:],n)
    else:
        act=np.full(n,np.nan);peak=act.copy();turn=np.zeros(n);ang=act.copy();ok=np.zeros(n)
    np.savez_compressed(f'{WORK}/p/bid_{sub}_{night}.npz',act=act,peak=peak,turn=turn,ang=ang,ok=ok,g=g,y=y,subject=sub,source='bid')
    X=night_features(act,peak,turn,ang,ok,g,n)
    done+=1
    if done%20==0 or done<4: print(done,sub,night,'n',n,'hr ok',round(float(X[:,6].mean()),2),'motion ok',round(float(X[:,4].mean()),2),'dist',np.bincount(y[y>=0],minlength=4),flush=True)
print('nights',done)
