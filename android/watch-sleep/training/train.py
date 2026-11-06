"""Trains and evaluates with whole people held out (--k folds), or on one dataset and tests on the other (--holdout sa)."""
import numpy as np, glob, os, sys, time, json, argparse
import torch, torch.nn as nn
HERE=os.path.dirname(os.path.abspath(__file__))
WORK=os.environ.get('SLEEP_WORK',os.path.join(HERE,'work'))
sys.path.insert(0,HERE)
from features import NAMES
torch.set_num_threads(4)

def load(pattern='*',fdir='fc'):
    out=[]
    for f in sorted(glob.glob(f'{WORK}/{fdir}/{pattern}.npz')):
        d=np.load(f,allow_pickle=True)
        out.append(dict(X=d['X'],y=d['y'],subject=str(d['subject']),source=str(d['source']),name=os.path.basename(f)[:-4]))
    return out

def kappa(y,p,k=4):
    c=np.zeros((k,k))
    np.add.at(c,(y,p),1)
    n=c.sum()
    if n==0: return float('nan')
    po=np.trace(c)/n; pe=(c.sum(0)*c.sum(1)).sum()/n/n
    return (po-pe)/(1-pe) if pe<1 else 0.0

def report(nights,preds,label=''):
    Y=np.concatenate([n['y'] for n in nights]);P=np.concatenate(preds);m=Y>=0
    Y=Y[m];P=P[m]
    per=[kappa(n['y'][n['y']>=0],p[n['y']>=0]) for n,p in zip(nights,preds)]
    names=['wake','light','deep','rem'];s=[]
    for c in range(4):
        tp=((Y==c)&(P==c)).sum();fn=((Y==c)&(P!=c)).sum();fp=((Y!=c)&(P==c)).sum()
        s.append(f"{names[c]} sens {tp/max(tp+fn,1):.2f} prec {tp/max(tp+fp,1):.2f} f1 {2*tp/max(2*tp+fp+fn,1):.2f}")
    err={c:[] for c in range(4)}
    for n,p in zip(nights,preds):
        v=n['y']>=0
        for c in range(4): err[c].append(abs(((n['y'][v]==c).sum()-(p[v]==c).sum())/2))
    ws=kappa((Y!=0).astype(int),(P!=0).astype(int),2)
    print(f"{label}: kappa pooled {kappa(Y,P):.3f} median night {np.nanmedian(per):.3f} acc {np.mean(Y==P):.3f} wake/sleep kappa {ws:.3f} | "+" | ".join(s)+" | median abs err min: "+" ".join(f"{names[c]} {np.median(err[c]):.0f}" for c in range(4)),flush=True)
    return kappa(Y,P)

def transitions(nights):
    T=np.ones((4,4))
    for n in nights:
        y=n['y']
        for a,b in zip(y[:-1],y[1:]):
            if a>=0 and b>=0: T[a,b]+=1
    return np.log(T/T.sum(1,keepdims=True))

def viterbi(logp,T,prior):
    n=len(logp);d=logp[0]+prior;b=np.zeros((n,4),int)
    for i in range(1,n):
        s=d[None,:].T+T
        b[i]=s.argmax(0);d=s.max(0)+logp[i]
    o=np.zeros(n,int);o[-1]=d.argmax()
    for i in range(n-1,0,-1): o[i-1]=b[i,o[i]]
    return o

# How a night is read, measured with postproc.py, see README. SleepModel.kt holds the same two numbers.
TRANSITION_WEIGHT=.4;WAKE_BOOST=.6
def read_night(logp,T,prior):
    l=logp.copy();l[:,0]+=WAKE_BOOST
    return viterbi(l,T*TRANSITION_WEIGHT,prior)

class Net(nn.Module):
    def __init__(s,din,h=48,layers=2,drop=0.25):
        super().__init__()
        s.inp=nn.Linear(din,h);s.gru=nn.GRU(h,h,layers,batch_first=True,bidirectional=True,dropout=drop if layers>1 else 0);s.drop=nn.Dropout(drop);s.out=nn.Linear(2*h,4)
    def forward(s,x):
        z,_=s.gru(torch.relu(s.inp(x)));return s.out(s.drop(z))

HR=[NAMES.index(n) for n in ['hr','hr_ok','hr_step','hr_local2','hr_local10','hr_slow','hr_jitter','hr_b1','hr_b2','hr_b3']]
MOTION=[NAMES.index(n) for n in ['act','peak','turn','angle','motion_ok','act2','act10','still10']]
HOURS=NAMES.index('hours')
def augment(X,y,rng):
    X=X.copy();n=len(X)
    # a recording that starts by itself starts when sleep already began
    if rng.random()<0.5 and n>400:
        c=rng.integers(0,120);X=X[c:];y=y[c:];n=len(X)
        if rng.random()<0.5: X[:,HOURS]-=X[0,HOURS]
    if rng.random()<0.05: X[:,HR]=0
    elif rng.random()<0.4:
        for _ in range(rng.integers(1,4)):
            a=rng.integers(0,n);b=min(n,a+rng.integers(5,120));X[a:b][:,HR]=0
    X=X+rng.normal(0,0.02,X.shape)
    X[:,:4]+=rng.normal(0,0.03)   # sensors differ a little in level
    return X.astype(np.float32),y

def train_net(tr,epochs=40,h=48,seed=0,lr=2e-3,wpow=0.25):
    torch.manual_seed(seed);rng=np.random.default_rng(seed)
    net=Net(tr[0]['X'].shape[1],h=h)
    Y=np.concatenate([n['y'] for n in tr]);f=np.bincount(Y[Y>=0],minlength=4).astype(float)
    w=torch.tensor((f.sum()/f/4)**wpow,dtype=torch.float32)
    opt=torch.optim.AdamW(net.parameters(),lr=lr,weight_decay=1e-3)
    B=8;steps=max(1,len(tr)//B)
    sched=torch.optim.lr_scheduler.OneCycleLR(opt,max_lr=lr,total_steps=epochs*steps)
    loss=nn.CrossEntropyLoss(weight=w,ignore_index=-1)
    for e in range(epochs):
        order=rng.permutation(len(tr));net.train()
        for s in range(steps):
            batch=[augment(tr[i]['X'],tr[i]['y'],rng) for i in order[s*B:(s+1)*B]]
            L=max(len(b[0]) for b in batch)
            x=np.zeros((len(batch),L,batch[0][0].shape[1]),np.float32);t=np.full((len(batch),L),-1,np.int64)
            for j,(a,b) in enumerate(batch): x[j,:len(a)]=a;t[j,:len(a)]=b
            o=net(torch.from_numpy(x));l=loss(o.reshape(-1,4),torch.from_numpy(t).reshape(-1))
            opt.zero_grad();l.backward();nn.utils.clip_grad_norm_(net.parameters(),1.0);opt.step();sched.step()
    net.eval();return net

def net_logp(net,n):
    with torch.no_grad():
        return torch.log_softmax(net(torch.from_numpy(n['X'][None])),-1)[0].numpy()

def folds(nights,k=5,seed=1):
    subs=sorted({n['subject'] for n in nights});rng=np.random.default_rng(seed);rng.shuffle(subs)
    return [set(subs[i::k]) for i in range(k)]

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--pattern',default='*');a.add_argument('--epochs',type=int,default=40);a.add_argument('--h',type=int,default=48);a.add_argument('--k',type=int,default=5);a.add_argument('--gbm',action='store_true');a.add_argument('--drop',default='');a.add_argument('--fdir',default='fc');a.add_argument('--holdout',default='');a.add_argument('--nomotion',action='store_true');a.add_argument('--wpow',type=float,default=0.25);a.add_argument('--dump',default='')
    o=a.parse_args()
    if o.dump: os.makedirs(o.dump,exist_ok=True)
    def dump(n,lp,T):
        # What the network said about a night it never saw: postproc.py compares ways to read that without training again.
        if o.dump: np.savez_compressed(f"{o.dump}/{n['name']}.npz",lp=lp,y=n['y'],T=T,subject=n['subject'],source=n['source'])
    nights=load(o.pattern,o.fdir);print(len(nights),'nights',len({n['subject'] for n in nights}),'subjects',flush=True)
    if o.drop:
        keep=[i for i,x in enumerate(NAMES) if x not in o.drop.split(',')]
        for n in nights: n['X']=n['X'][:,keep]
    if o.nomotion:
        for n in nights:
            n['X']=n['X'].copy();n['X'][:,MOTION]=0
    if o.holdout:
        tr=[n for n in nights if n['source']!=o.holdout];te=[n for n in nights if n['source']==o.holdout]
        T=transitions(tr);prior=np.log(np.array([.7,.25,.01,.04]));net=train_net(tr,epochs=o.epochs,h=o.h,seed=0,wpow=o.wpow)
        lp=[net_logp(net,n) for n in te]
        for n,x in zip(te,lp): dump(n,x,T)
        report(te,[x.argmax(1) for x in lp],f'train others, test {o.holdout}: gru');report(te,[read_night(x,T,prior) for x in lp],f'train others, test {o.holdout}: gru+viterbi');sys.exit()
    base=np.bincount(np.concatenate([n['y'] for n in nights])[np.concatenate([n['y'] for n in nights])>=0],minlength=4);print('class share',np.round(base/base.sum(),3))
    pn=[None]*len(nights);pv=[None]*len(nights);t0=time.time()
    for fi,test in enumerate(folds(nights,o.k)):
        tr=[n for n in nights if n['subject'] not in test];T=transitions(tr);prior=np.log(np.array([.7,.25,.01,.04]))
        net=train_net(tr,epochs=o.epochs,h=o.h,seed=fi,wpow=o.wpow)
        for i,n in enumerate(nights):
            if n['subject'] in test:
                lp=net_logp(net,n);pn[i]=lp.argmax(1);pv[i]=read_night(lp,T,prior);dump(n,lp,T)
        print('fold',fi,'done',round(time.time()-t0),'s',flush=True)
    report(nights,pn,'gru')
    report(nights,pv,'gru+viterbi')
    for src in sorted({n['source'] for n in nights}):
        idx=[i for i,n in enumerate(nights) if n['source']==src]
        if len(idx)<len(nights): report([nights[i] for i in idx],[pv[i] for i in idx],f'gru+viterbi on {src} only')
