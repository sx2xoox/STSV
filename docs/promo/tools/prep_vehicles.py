"""Cut vehicle / building sprites for the vehicles promo. Run from repo root."""
from PIL import Image
import numpy as np
from collections import deque
P='docs/previews'; O='docs/promo/vehicles/assets'
BG=np.array([27,29,34])

def components(mask):
    h,w=mask.shape; lab=np.zeros((h,w),np.int32); n=0; sizes=[0]
    for y0 in range(h):
        for x0 in np.nonzero(mask[y0] & (lab[y0]==0))[0]:
            if lab[y0,x0]: continue
            n+=1; q=deque([(y0,x0)]); lab[y0,x0]=n; c=0
            while q:
                y,x=q.popleft(); c+=1
                for yy,xx in ((y+1,x),(y-1,x),(y,x+1),(y,x-1)):
                    if 0<=yy<h and 0<=xx<w and mask[yy,xx] and not lab[yy,xx]:
                        lab[yy,xx]=n; q.append((yy,xx))
            sizes.append(c)
    return lab,sizes

def cut(src,box,name,keep=1,tol=18,drop_grey=False):
    im=Image.open(f'{P}/{src}').convert('RGB').crop(box)
    a=np.asarray(im).astype(int)
    fg=np.abs(a-BG).sum(2)>tol
    if drop_grey:  # the flat grey scale-reference mannequins
        r,g,b=a[...,0],a[...,1],a[...,2]
        grey=(np.abs(r-g)<8)&(np.abs(g-b)<10)&(r>60)&(r<125)
        fg&=~grey
    lab,sizes=components(fg)
    order=np.argsort(sizes)[::-1][:keep]
    m=np.isin(lab,[i for i in order if sizes[i]>0])
    rgba=np.dstack([a,np.where(m,255,0)]).astype(np.uint8)
    out=Image.fromarray(rgba,'RGBA'); bb=out.getbbox(); out=out.crop(bb)
    out.save(f'{O}/{name}.png'); return out.size

# 주황(운전자)·초록(동승자) 크기 비교용 인형 색: 면마다 세 가지 밝기
DOLL=np.array([(201,137,53),(177,120,46),(222,152,60),(94,127,94),(108,145,108),(120,160,120),(80,108,80),(155,105,40)])

def remove_dolls(name, peel=True, keep_frac=0.06):
    im=Image.open(f'{O}/{name}.png').convert('RGBA'); a=np.asarray(im).astype(int)
    rgb,al=a[...,:3],a[...,3]
    d=np.min(np.abs(rgb[:,:,None,:]-DOLL[None,None]).sum(3),2)
    m=(d<=12)&(al>0)
    # 경계의 섞인 픽셀까지 한 겹 넓힘
    for _ in range(2):
        g=np.zeros_like(m); g[1:]|=m[:-1]; g[:-1]|=m[1:]; g[:,1:]|=m[:,:-1]; g[:,:-1]|=m[:,1:]
        m|=g&(al>0)&(d<=45)
    al=al.copy(); al[m]=0
    # 인형의 어두운 외곽선: 인형 자리 근처에서 바깥 빈칸과 맞닿은 어두운 픽셀을 벗겨 냄
    near=m.copy()
    for _ in range(7):
        g=near.copy(); g[1:]|=near[:-1]; g[:-1]|=near[1:]; g[:,1:]|=near[:,:-1]; g[:,:-1]|=near[:,1:]; near=g
    dark=rgb.max(2)<80
    for _ in range(12 if peel else 0):
        tr=al==0
        edge=np.zeros_like(tr); edge[1:]|=tr[:-1]; edge[:-1]|=tr[1:]; edge[:,1:]|=tr[:,:-1]; edge[:,:-1]|=tr[:,1:]
        kill=edge&~tr&dark&near
        if not kill.any(): break
        al[kill]=0
    # 바깥과 이어진 빈칸은 투명 그대로, 탈것 안에 갇힌 구멍만 주변 색으로 메움
    h,w=al.shape; outside=np.zeros((h,w),bool); q=deque()
    for x in range(w):
        for y in (0,h-1):
            if al[y,x]==0: outside[y,x]=True; q.append((y,x))
    for y in range(h):
        for x in (0,w-1):
            if al[y,x]==0 and not outside[y,x]: outside[y,x]=True; q.append((y,x))
    while q:
        y,x=q.popleft()
        for yy,xx in ((y+1,x),(y-1,x),(y,x+1),(y,x-1)):
            if 0<=yy<h and 0<=xx<w and al[yy,xx]==0 and not outside[yy,xx]: outside[yy,xx]=True; q.append((yy,xx))
    hole=m&~outside
    rgb=rgb.copy(); filled=~hole&(al>0)
    while hole.any():
        acc=np.zeros((h,w,3)); cnt=np.zeros((h,w))
        for dy,dx in ((1,0),(-1,0),(0,1),(0,-1)):
            sh=np.roll(np.roll(filled,dy,0),dx,1); sc=np.roll(np.roll(rgb,dy,0),dx,1)
            acc+=sc*sh[...,None]; cnt+=sh
        new=hole&(cnt>0)
        if not new.any(): break
        rgb[new]=(acc[new]/cnt[new][:,None]).astype(int); filled|=new; hole&=~new; al[new]=255
    lab,sizes=components(al>0)
    big=max(sizes)
    al[np.isin(lab,[i for i,c in enumerate(sizes) if i and c<big*keep_frac])]=0   # 떨어져 남은 작은 조각 정리
    out=Image.fromarray(np.dstack([rgb,al]).astype(np.uint8),'RGBA'); bb=out.getbbox()
    out.crop(bb).save(f'{O}/{name}.png')

def photo(src,box,name):
    Image.open(f'{P}/{src}').convert('RGB').crop(box).save(f'{O}/{name}.png')

V=dict(
  light_truck=('vehicles.png',(50,452,306,715)),
  box_truck=('vehicles_road.png',(0,1452,400,1716)),
  cargo_van=('vehicles_road.png',(0,1120,400,1380)),
  scooter=('vehicles_road.png',(0,452,400,715)),
  cargo_bike=('vehicles_road.png',(0,88,400,384)),
  atv=('vehicles_road.png',(0,786,400,1050)),
  ox_cart=('vehicles_farm.png',(430,92,700,384)),
  tiller=('vehicles_farm.png',(68,428,266,719)),
  tractor=('vehicles_farm.png',(34,762,397,1048)),
  hand_cart=('cart_before_after.png',(0,458,400,778)),
  jige=('vehicles_misc.png',(0,812,400,1142)),
  boat=('vehicles_misc.png',(0,450,320,778)),
)
import sys
for n,(s,b) in V.items(): print(n,cut(s,b,n,drop_grey=n not in ('box_truck',)))
for n in V:
    if n not in ('hand_cart','jige','boat'): remove_dolls(n, peel=n not in ('light_truck','tractor','ox_cart'), keep_frac=0.25 if n=='ox_cart' else 0.06)
# truck bed loading stages (empty → full)
for i in range(5): photo('vehicles.png',(i*320,1418,i*320+320,1716),f'load{i}')
photo('docks.png',(0,70,1000,810),'dock_land')
photo('terminal.png',(0,110,1400,996),'terminal')
