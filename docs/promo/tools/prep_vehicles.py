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

def photo(src,box,name):
    Image.open(f'{P}/{src}').convert('RGB').crop(box).save(f'{O}/{name}.png')

V=dict(
  light_truck=('vehicles.png',(50,452,306,715)),
  box_truck=('vehicles_road.png',(0,1452,400,1716)),
  cargo_van=('vehicles_road.png',(0,1120,400,1380)),
  scooter=('vehicles_road.png',(0,452,400,715)),
  cargo_bike=('vehicles_road.png',(0,88,400,384)),
  atv=('vehicles_road.png',(0,786,400,1050)),
  ox_cart=('vehicles_farm.png',(84,92,262,384)),
  tiller=('vehicles_farm.png',(68,428,266,719)),
  tractor=('vehicles_farm.png',(34,762,397,1048)),
  hand_cart=('cart_before_after.png',(0,458,400,778)),
  jige=('vehicles_misc.png',(0,812,400,1142)),
  boat=('vehicles_misc.png',(0,450,320,778)),
)
import sys
for n,(s,b) in V.items(): print(n,cut(s,b,n,drop_grey=n not in ('box_truck',)))
# truck bed loading stages (empty → full)
for i in range(5): photo('vehicles.png',(i*320,1418,i*320+320,1716),f'load{i}')
photo('docks.png',(0,70,1000,810),'dock_land'); photo('docks.png',(1000,70,2000,810),'dock_port')
photo('terminal.png',(0,110,1400,996),'terminal')
photo('road.png',(0,90,897,695),'road_flat'); photo('road.png',(1799,90,2700,695),'road_bridge')
photo('road.png',(0,790,897,1393),'road_tunnel'); photo('road.png',(899,790,1799,1393),'road_forest')
photo('road.png',(0,1460,897,2090),'road_junction'); photo('road.png',(899,1460,1799,2090),'road_overpass')
