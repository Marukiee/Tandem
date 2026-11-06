"""The watch's raw log format, see RawLog.kt."""
import struct
import numpy as np

def _varint(out,v):
    while v>=0x80:
        out.append((v&0x7f)|0x80);v>>=7
    out.append(v)

def _header(out,last,typ,t):
    out.append(typ);d=int(t)-last[typ];last[typ]=int(t);_varint(out,((d<<1)^(d>>63))&((1<<64)-1))

def write_tws(path,accel,heart,marks=()):
    out=bytearray(b'TWS1');last=[0]*6
    for t,label in marks:
        _header(out,last,4,t);b=label.encode();_varint(out,len(b));out+=b
    i=j=0
    while i<len(accel) or j<len(heart):
        if j>=len(heart) or (i<len(accel) and accel[i][0]<=heart[j][0]):
            t,x,y,z=accel[i];i+=1;_header(out,last,1,t);out+=struct.pack('<3h',int(x),int(y),int(z))
        else:
            t,b,a=heart[j];j+=1;_header(out,last,2,t);out+=bytes((int(b),int(a)))
    open(path,'wb').write(out)

def read_tws(path):
    b=open(path,'rb').read();i=4;last=[0]*6;acc=[];hr=[];marks=[]
    def vi():
        nonlocal i
        r=0;s=0
        while True:
            c=b[i];i+=1;r|=(c&0x7f)<<s
            if not c&0x80:return r
            s+=7
    try:
        while i<len(b):
            t=b[i];i+=1;raw=vi();d=(raw>>1)^-(raw&1);last[t]+=d;ts=last[t]
            if t==1:
                acc.append((ts,*struct.unpack('<3h',b[i:i+6])));i+=6
            elif t==2:
                hr.append((ts,b[i],b[i+1]));i+=2
            elif t==3: vi()
            elif t==5: vi()
            elif t==4:
                n=vi();marks.append((ts,b[i:i+n].decode()));i+=n
    except IndexError: pass
    return np.array(acc,dtype=np.float64),np.array(hr,dtype=np.float64).reshape(-1,3),marks
