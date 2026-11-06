"""Trains the networks that ship on all nights and writes sleep_model.bin plus a golden night for the Kotlin test."""
import argparse, os, struct, sys
import numpy as np, torch
HERE=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,HERE)
from features import *
from train import load, train_net, transitions, Net, net_logp, viterbi
from tws import write_tws, read_tws

PRIOR=np.log(np.array([.7,.25,.01,.04]))

def write_model(path,nets,T,din,h,layers=2):
    with open(path,'wb') as f:
        f.write(struct.pack('<5i',0x314D5354,len(nets),din,h,layers))
        f.write(T.astype('<f4').tobytes());f.write(PRIOR.astype('<f4').tobytes())
        for net in nets:
            sd=net.state_dict()
            def put(k): f.write(sd[k].numpy().astype('<f4').tobytes())
            put('inp.weight');put('inp.bias')
            for l in range(layers):
                for d in ('','_reverse'):
                    for k in ('weight_ih','weight_hh','bias_ih','bias_hh'): put(f'gru.{k}_l{l}{d}')
            put('out.weight');put('out.bias')

def golden_night(path):
    """A made up night with everything in it: awake, still, moving, gaps, a wild heart value. No real person's data goes in the repo."""
    rng=np.random.default_rng(7)
    t0=1_780_000_000_000; hours=3; accel=[];heart=[]
    ms=0;end=hours*3600*1000
    while ms<end:
        h=ms/3600000
        awake=h<0.3 or h>2.7 or 1.9<h<2.0
        ang=np.radians(10+(40*np.sin(ms/7000) if awake else 15*(int(h*3)%2)))
        noise=0.2 if awake else 0.004
        v=np.array([np.cos(ang),0,np.sin(ang)])+rng.normal(0,noise,3)
        if not 2.5<h<2.55: accel.append((t0+ms,*(np.round(v*2048).astype(int))))
        if ms%2000==0 and not 2.2<h<2.3:
            bpm=(75 if awake else 54+8*np.sin(h*4))+rng.normal(0,1.5)
            heart.append((t0+ms,int(round(bpm)) if ms!=4_000_000 else 180,3))
        ms+=500
    write_tws(path,accel,heart,marks=[(t0,'SLEEP')])

def features_of(path):
    accel,heart,marks=read_tws(path)
    start=accel[0,0]
    n=int((accel[-1,0]-start)//30000)+1
    act,peak,turn,ang,ok=motion_primitives((accel[:,0]-start)/1000.0,accel[:,1:]/2048.0,n)
    g=heart_grid((heart[:,0]-start)/1000.0,heart[:,1],n)
    return night_features(act,peak,turn,ang,ok,g,n)

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--wpow',type=float,default=.25);a.add_argument('--fdir',default='fc');a.add_argument('--nets',type=int,default=3);a.add_argument('--epochs',type=int,default=40);a.add_argument('--h',type=int,default=48);a.add_argument('--out',default='out');o=a.parse_args()
    os.makedirs(o.out,exist_ok=True)
    nights=load('*',o.fdir);T=transitions(nights)
    nets=[train_net(nights,epochs=o.epochs,h=o.h,seed=100+i,wpow=o.wpow) for i in range(o.nets)]
    din=nights[0]['X'].shape[1]
    write_model(f'{o.out}/sleep_model.bin',nets,T,din,o.h)
    golden_night(f'{o.out}/golden_night.tws')
    X=features_of(f'{o.out}/golden_night.tws')
    n=dict(X=X)
    lp=np.mean([torch.log_softmax(net(torch.from_numpy(X[None])),-1)[0].detach().numpy() for net in nets],axis=0)
    path=viterbi(lp,T,PRIOR)
    with open(f'{o.out}/golden_night.bin','wb') as f:
        f.write(struct.pack('<2i',X.shape[0],X.shape[1]));f.write(X.astype('<f4').tobytes());f.write(lp.astype('<f4').tobytes());f.write(path.astype('<i4').tobytes())
    print('nights',len(nights),'golden epochs',X.shape[0],'stages',np.bincount(path,minlength=4),'model kB',os.path.getsize(f'{o.out}/sleep_model.bin')//1024)
