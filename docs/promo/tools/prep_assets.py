"""Cut promo sprites out of docs/previews and copy item textures. Run from repo root."""
from PIL import Image
from collections import deque
import os, shutil
P='docs/previews'; O='docs/promo/assets'; T='economy/content/economy/textures'; T2='content/stsv/textures'
BG=(27,29,34)

def key(im, tol=26):
    im=im.convert('RGBA'); w,h=im.size; px=im.load()
    seen=bytearray(w*h); q=deque()
    for x in range(w): q.append((x,0)); q.append((x,h-1))
    for y in range(h): q.append((0,y)); q.append((w-1,y))
    while q:
        x,y=q.popleft()
        if x<0 or y<0 or x>=w or y>=h or seen[y*w+x]: continue
        seen[y*w+x]=1
        r,g,b,a=px[x,y]
        if abs(r-BG[0])+abs(g-BG[1])+abs(b-BG[2])>tol: continue
        px[x,y]=(0,0,0,0)
        q.extend(((x+1,y),(x-1,y),(x,y+1),(x,y-1)))
    bb=im.getbbox(); return im.crop(bb) if bb else im

def fade(im):
    # keep the preview's own charcoal floor, feather the rectangle edges to transparent
    im=im.convert('RGBA'); w,h=im.size; a=Image.new('L',(w,h),0); px=a.load()
    for y in range(h):
        for x in range(w):
            d=min(x,y,w-1-x,h-1-y); px[x,y]=max(0,min(255,int(255*d/40)))
    im.putalpha(a); return im

def save(im,name): im.save(f'{O}/{name}.png')

m=Image.open(f'{P}/machines_front.png')
names=['m_gem','m_mill','m_fish','m_forge','m_cook','m_still']
rh=3290/7
for i,n in enumerate(names):
    top=int(i*rh+96); bot=int((i+1)*rh)-4
    for lv in range(4):
        save(fade(m.crop((4+460*lv,top,456+460*lv,bot))),n+str(lv))
room=Image.open(f'{P}/furniture_japanese_room.png')
save(key(room.crop((80,200,1500,1000))),'room')
c1=Image.open(f'{P}/cosmetics_worn_1.png')
for n,(cx,cy) in {'c_straw':(0,0),'c_wizard':(980,0),'c_crown':(0,305),'c_frog':(980,305),'c_gat':(490,305)}.items():
    save(key(c1.crop((cx+10,cy+28,cx+245,cy+305))),n)
# crop growth stages (17 rows, 5 stages max)
cr=Image.open(f'{P}/crops.png').convert('RGBA'); rows=17; rh2=3060/rows
sky=(150,192,232)
for r in range(rows):
    for s,x0 in enumerate([185,275,365,455,545]):
        tile=cr.crop((x0,int(r*rh2+20),x0+95,int(r*rh2+rh2-8)))
        px=tile.load(); w,h=tile.size
        for y in range(h):
            for x in range(w):
                p=px[x,y]
                if abs(p[0]-sky[0])+abs(p[1]-sky[1])+abs(p[2]-sky[2])<30: px[x,y]=(0,0,0,0)
        bb=tile.getbbox()
        if bb: tile.save(f'{O}/crop_{r}_{s}.png')
for f in ['kimchi','napa_cabbage','chili_powder','chili_pepper','rough_ruby','ruby','ruby_ring','jeweled_crown','vintage_wine',
          'makgeolli','rice','wild_ginseng','golden_ear','red_coral','ambergris','meteoric_iron','meteor_sword','steel_ingot',
          'mackerel','salted_mackerel','grapes','wine','ginseng_liquor','tomato','corn','radish','eggplant','muskmelon',
          'flour','gold_ring','pearl_necklace','tuna_sushi','bluefin_tuna','marlin','clownfish','octopus','king_crab',
          'golden_crucian','sunfish','whale_shark','oak_barrel','cut_diamond','sapphire','bibimbap','samgyetang','pearl',
          'giant_squid','dragon_carp','garlic','silk_cocoon','gold_ring','onion','strawberry','soybean','green_onion','lettuce','cucumber','zucchini','godeungeo_gui','sickle','homi','iron_pan','copper_pot','soju','strawberry_cake','golden_injeolmi','paddy']:
    shutil.copy(f'{T}/{f}.png',f'{O}/i_{f}.png')
for f in ['kotatsu_icon','torii_icon','maneki_neko_icon','daruma_icon','wagasa_icon','chochin_icon','crown_voucher','cat_ears_voucher','angel_wings_voucher','heart_trail_voucher','cheque']:
    shutil.copy(f'{T2}/{f}.png',f'{O}/i_{f}.png')
print(len(os.listdir(O)))
