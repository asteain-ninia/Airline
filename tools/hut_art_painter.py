"""新しい小屋ブロック 3 つ(中型飛行場、大型飛行場、泊地の係船柱)の模型と絵を描く。Codex(GPT の Astra)の作(2026-10-15)。

    python tools/hut_art_painter.py src/main/resources

で、src/main/resources/assets/airline/ の models/block/blockhut{mediumairfield,largeairfield,anchorage}.json と
textures/block/hut_*.png を書き直す。依頼文と経緯は ColonyPatchServer/design/hut-art-2026-10-15/。
重なりの確かめ: tools/find_overlaps.py、見えない面: tools/check_hidden.py --all(どちらも 0 だった)。
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw

DIRECTIONS = ('north', 'south', 'west', 'east', 'up', 'down')


class Model:
    def __init__(self):
        self.data = {'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout',
                     'ambientocclusion': True, 'textures': {}, 'elements': []}
        for key, value in {'oak': 'oak_planks', 'spruce': 'spruce_planks',
                           'dark': 'dark_oak_planks', 'post': 'stripped_oak_log',
                           'stone': 'stone_bricks', 'cap': 'smooth_stone',
                           'wool': 'white_wool', 'iron': 'iron_block'}.items():
            self.data['textures'][key] = 'minecraft:block/' + value
        self.data['textures']['particle'] = 'minecraft:block/stone_bricks'

    def tex(self, key, name):
        self.data['textures'][key] = 'airline:block/' + name

    def box(self, name, a, b, tex, omit=(), uv=None, rotation=None, face_tex=None):
        x, y, z = a
        X, Y, Z = b
        # Minecraft の通常の UV。模型でも板目・石目の密度を揃える。
        mapping = {'north': [16-X, 16-Y, 16-x, 16-y],
                   'south': [x, 16-Y, X, 16-y],
                   'west': [z, 16-Y, Z, 16-y],
                   'east': [16-Z, 16-Y, 16-z, 16-y],
                   'up': [x, z, X, Z], 'down': [x, 16-Z, X, 16-z]}
        e = {'name': name, 'from': list(a), 'to': list(b), 'faces': {}}
        for f in DIRECTIONS:
            if f not in omit:
                e['faces'][f] = {'texture': '#' + (face_tex or {}).get(f, tex),
                                 'uv': list(uv if uv is not None else mapping[f])}
        if rotation:
            e['rotation'] = rotation
        self.data['elements'].append(e)
        return e

    def finish(self):
        # 完全に別の箱に覆われた面を省く。部分的に露出した面は残す。
        es = self.data['elements']
        defs = {'west': (0, 0, -1), 'east': (0, 1, 1), 'down': (1, 0, -1),
                'up': (1, 1, 1), 'north': (2, 0, -1), 'south': (2, 1, 1)}
        for e in es:
            if 'rotation' in e:
                continue
            for f in list(e['faces']):
                axis, end, sign = defs[f]
                p = e['to' if end else 'from'][axis]
                for other in es:
                    if other is e or 'rotation' in other:
                        continue
                    a, b = other['from'], other['to']
                    inside = a[axis] <= p < b[axis] if sign > 0 else a[axis] < p <= b[axis]
                    if inside and all(a[k] <= e['from'][k] and b[k] >= e['to'][k]
                                      for k in range(3) if k != axis):
                        del e['faces'][f]
                        break
        assert len(es) <= 48
        for e in es:
            assert all(0 <= a < b <= 16 for a, b in zip(e['from'], e['to']))
            assert e['faces'], e['name']
        return self.data


def paint(folder):
    # 生成模様は座標だけで決定。白い羊毛の一家に、細い帆布の継ぎ目と革帯。
    for size in ('medium', 'large'):
        im = Image.new('RGBA', (16, 16))
        for y in range(16):
            for x in range(16):
                d = ((x * 7 + y * 11 + x * y) % 7) - 3
                base = (226, 223, 207) if size == 'medium' else (232, 229, 214)
                col = tuple(c + d for c in base)
                if y in (3, 11):
                    col = (204+d, 201+d, 187+d)
                if x in (3, 12):
                    col = (121+d, 100+d, 65+d)
                if x in (4, 11):
                    col = (170+d, 147+d, 101+d)
                if size == 'large' and y == 8 and x not in (3, 4, 11, 12):
                    col = (211+d, 207+d, 190+d)
                im.putpixel((x, y), (*col, 255))
        im.save(folder / ('hut_' + size + '_canvas.png'))
    # 黒鉄: 青すぎず、鈍い縁の摩耗と小さな鍛造跡だけ。
    iron = Image.new('RGBA', (16, 16))
    rope = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            d = ((x*13 + y*7 + x*y*3) % 9) - 4
            v = 62 + d + (14 if x in (0, 15) or y in (0, 15) else 0)
            iron.putpixel((x, y), (v-5, v, v+1, 255))
            braid = (x + 2*y) % 6
            c = [(105, 77, 44), (145, 110, 63), (188, 153, 94),
                 (204, 171, 110), (170, 135, 77), (132, 98, 54)][braid]
            rope.putpixel((x, y), (*c, 255))
    iron.save(folder / 'hut_anchorage_iron.png')
    rope.save(folder / 'hut_anchorage_rope.png')
    # 小さい灯り用。全面をひとつのランタン正面として使う。
    lamp = Image.new('RGBA', (16, 16), (65, 59, 47, 255))
    d = ImageDraw.Draw(lamp)
    d.rectangle((2, 3, 13, 13), fill=(173, 105, 37, 255))
    d.rectangle((4, 4, 11, 11), fill=(238, 175, 65, 255))
    d.rectangle((6, 5, 9, 10), fill=(255, 221, 124, 255))
    d.rectangle((1, 1, 14, 2), fill=(101, 91, 66, 255))
    d.line((2, 14, 13, 14), fill=(115, 91, 51, 255))
    lamp.save(folder / 'hut_medium_lantern.png')


def fittings(m):
    m.tex('metal', 'hut_anchorage_iron')
    m.tex('rope', 'hut_anchorage_rope')
    m.tex('lamp', 'hut_medium_lantern')


def lantern(m, name, x, y, z):
    m.box(name+'_foot', (x-.65, y, z-.65), (x+.65, y+.3, z+.65), 'metal')
    m.box(name+'_light', (x-.5, y+.3, z-.5), (x+.5, y+1.5, z+.5), 'lamp', uv=(0,0,16,16))
    m.box(name+'_roof', (x-.7, y+1.5, z-.7), (x+.7, y+1.8, z+.7), 'metal')


def ship(m, large=False):
    # 船首は東、鑑賞正面は北。大型も岸壁に隠れず船腹が見える。
    z = 4.65 if large else 7.5
    low = 5.4 if large else 4.6
    L, R = (.3, 15.7) if large else (1.5, 15)
    by, ty, width = (9.5, 14.5, 6.8) if large else (9.6, 13.9, 5.8)
    m.tex('canvas', 'hut_large_canvas' if large else 'hut_medium_canvas')
    m.box('gondola_keel', (3.2,low,z-1), (13.5,low+.6,z+1), 'spruce')
    m.box('gondola_deck', (2.5,low+.6,z-1.5), (14,low+1.4,z+1.5), 'oak')
    m.box('gondola_bow', (14,low+.8,z-.9), (14.8,low+1.35,z+.9), 'oak')
    for side in (-1,1):
        zz = z + side*1.35
        m.box('gunwale_'+str(side), (2.7,low+1.4,zz-.15), (13.8,low+1.85,zz+.15), 'dark')
        for x in (4.5,11.6):
            m.box('suspension_%s_%s'%(side,x), (x,low+1.85,zz-.16), (x+.32,by+.25,zz+.16), 'rope')
    # 五段の輪郭で角を落とす。接合部は載せるだけで共面の重なりを作らない。
    core = m.box('envelope', (L+1.2,by,z-width/2), (R-1.2,ty,z+width/2), 'canvas',
                 face_tex={'east':'canvas','west':'canvas'})
    for f in ('north','south'):
        core['faces'][f]['uv'] = [0,0,16,16]
    for name,a,b in [
        ('envelope_crown',(L+2.1,ty,z-width/2+.8),(R-2.1,15.75 if large else 15.1,z+width/2-.8)),
        ('envelope_belly',(L+2.1,by-.75,z-width/2+.8),(R-2.1,by,z+width/2-.8)),
        ('envelope_tail',(L,by+.9,z-width/2+1),(L+1.2,ty-.9,z+width/2-1)),
        ('envelope_nose',(R-1.2,by+.9,z-width/2+1),(R,ty-.9,z+width/2-1))]:
        e = m.box(name,a,b,'canvas')
        if name.endswith(('tail','nose')):
            for face in e['faces'].values():
                face['uv'] = [5,0,11,16]
        if name.endswith(('crown','belly')):
            for f in ('up','down'):
                e['faces'][f]['uv'] = [0,0,16,16]
            for f in ('north','south'):
                e['faces'][f]['uv'] = [0,0,16,3]
    # 帯の X 座標を上段・下段でも揃え、段差で帯が途切れないようにする。
    for e in m.data['elements']:
        if e['name'] in ('envelope','envelope_crown','envelope_belly'):
            u = 16*(e['from'][0]-L-1.2)/(R-L-2.4)
            U = 16*(e['to'][0]-L-1.2)/(R-L-2.4)
            for f in ('north','south','up','down'):
                e['faces'][f]['uv'] = [round(max(0,u),6),0,round(min(16,U),6),16]
            for f in ('west','east'):
                e['faces'][f]['uv'] = [5,0,11,16]
    m.box('tail_rudder', (L+.15,by-2,z-.23), (L+1.8,by+1.05,z+.23), 'spruce', omit=('up',))
    m.box('tail_plane', (L+.4,by-.8,z-2), (L+2.1,by-.45,z+2), 'oak')
    # 左右二基ずつ。一本の斜め羽根と鉄のハブで小サイズでも読みやすく。
    for x in (5.3,11):
        m.box('prop_axle_'+str(x), (x-.23,low+2.45,z-3.8), (x+.23,low+2.9,z+3.8), 'metal')
        for side in (-1,1):
            zz = z+side*3.8
            m.box('prop_blade_%s_%s'%(x,side), (x-.25,low+.9,zz-.12), (x+.25,low+4.45,zz+.12), 'spruce',
                  rotation={'axis':'z','angle':45 if side==1 else -45,'origin':[x,low+2.675,zz]})
            m.box('prop_hub_%s_%s'%(x,side), (x-.4,low+2.275,zz-.27), (x+.4,low+3.075,zz+.27), 'iron')


def medium():
    m = Model(); fittings(m)
    # 四周の石縁と低い木床。前作の広い台座を、石造へ一段格上げ。
    m.box('foundation', (.5,0,.5), (15.5,2,15.5), 'stone', omit=('up',))
    m.box('deck', (1.5,2,1.5), (14.5,2.8,14.5), 'oak')
    m.box('rim_north', (.5,2,.5), (15.5,3,1.5), 'stone')
    m.box('rim_south', (.5,2,14.5), (15.5,3,15.5), 'stone')
    m.box('rim_west', (.5,2,1.5), (1.5,3,14.5), 'stone')
    m.box('rim_east', (14.5,2,1.5), (15.5,3,14.5), 'stone')
    ship(m)
    for x in (2.2,13.8):
        lantern(m,'deck_lantern_'+str(x),x,2.8,2.5)
    m.box('mooring_post', (14.55,3,7.05), (15.3,6.1,7.95), 'post')
    m.box('mooring_line', (14,5.85,7.32), (14.95,6.15,7.68), 'rope', omit=('down',))
    m.box('mast_foot', (1.2,2.8,12.2), (3,3.5,14), 'spruce', omit=('down',))
    m.box('windsock_mast', (1.85,3.5,12.85), (2.35,15.5,13.35), 'post')
    m.tex('sock','airfield_hut_windsock')
    for i,(a,b,uv) in enumerate([
        ((2.35,13.25,12.45),(4.2,15.25,13.8),(0,0,6,16)),
        ((4.2,13.5,12.65),(5.8,15,13.6),(6,0,12,16)),
        ((5.8,13.75,12.85),(7.2,14.75,13.4),(12,0,16,16))]):
        m.box('windsock_'+str(i),a,b,'sock',uv=uv)
    return m.finish()


def large():
    m = Model(); fittings(m)
    # 足元は後方だけ。手前に床を敷かず「空中の横付け」を明示。
    m.box('quay_wall', (.5,0,11), (15.5,5.6,15.5), 'stone')
    m.box('quay_coping', (.25,5.6,10.5), (15.75,6.4,15.75), 'cap')
    m.box('quay_walk', (3.2,6.4,11.3), (15.1,6.7,14.9), 'oak')
    for x in (3.5,12.5):
        m.box('buttress_'+str(x), (x-.65,0,10.1), (x+.65,4.8,11), 'stone')
        m.box('timber_fender_'+str(x), (x-.45,2.5,9.65), (x+.45,5.7,10.1), 'dark')
    ship(m,True)
    # 船体から岸壁へ水平に伸びる連結器。二本の腕で塔と役割を分ける。
    m.box('horizontal_coupler', (8.2,6.4,6.15), (9,7.05,11.35), 'metal', omit=('north',))
    m.box('coupler_collar', (7.95,6.2,8.5), (9.25,7.3,9.1), 'iron')
    m.box('tower_plinth', (.8,6.4,11.35), (3.3,7.3,14.5), 'stone')
    m.box('mooring_tower', (1.35,7.3,12), (2.75,13,13.9), 'dark')
    m.box('tower_cap', (.85,13,11.5), (3.25,13.65,14.4), 'cap')
    m.box('tower_mast', (1.8,13.65,12.4), (2.3,15.6,12.9), 'iron')
    m.box('tower_arm', (1.65,11.1,8.8), (2.45,11.7,12), 'spruce')
    lantern(m,'quay_lantern',14.3,6.7,13.4)
    m.box('rear_rail', (4,8.2,15), (13.2,8.6,15.4), 'spruce')
    for x in (4,12.8):
        m.box('rail_post_'+str(x), (x,6.4,15), (x+.4,8.2,15.4), 'post', omit=('up',))
    return m.finish()


def anchorage():
    m = Model(); fittings(m)
    m.data['textures']['particle'] = 'airline:block/hut_anchorage_iron'
    # 石の敷板と黒鉄は木桟橋にも石桟橋にも馴染む。主役は低く太い係船柱。
    m.box('paving', (1,0,1), (15,1.25,15), 'stone')
    m.box('mounting_plate', (3.5,1.25,3.5), (12.5,2,12.5), 'metal')
    m.box('bollard_foot', (4.5,2,4.5), (11.5,3,11.5), 'metal')
    m.box('bollard_stem', (5.3,3,5.3), (10.7,8.4,10.7), 'metal')
    m.box('overhanging_head', (3.8,8.4,4.5), (12.2,10.2,11.5), 'metal')
    m.box('head_crown', (4.6,10.2,5.2), (11.4,10.7,10.8), 'metal')
    for x in (4,11.2):
        for z in (4,11.2):
            m.box('anchor_bolt_%s_%s'%(x,z), (x,2,z), (x+.8,2.35,z+.8), 'iron', uv=(4,4,8,8))
    # 巻き綱は四辺を突き合わせ、同じ平面の重複を作らない。
    for j,y in enumerate((4.5,5.4)):
        for name,a,b in [
            ('front',(4.85,y,4.75),(11.15,y+.75,5.3)),
            ('back',(4.85,y,10.7),(11.15,y+.75,11.25)),
            ('left',(4.75,y,5.3),(5.3,y+.75,10.7)),
            ('right',(10.7,y,5.3),(11.25,y+.75,10.7))]:
            m.box('rope_wrap_%d_%s'%(j,name),a,b,'rope',
                  omit=('south',) if name == 'front' else ('north',) if name == 'back' else ())
    m.box('rope_fall', (10.8,1.25,4.05), (11.55,5.2,4.75), 'rope')
    # 手前に余った綱の輪。空いた中心と石の余白が太い柱を引き立てる。
    for name,a,b in [
        ('coil_front',(3.4,1.25,1.65),(9.5,1.9,2.3)),
        ('coil_back',(3.4,1.25,3.45),(9.5,1.9,4.1)),
        ('coil_left',(2.75,1.25,2.3),(3.4,1.9,3.45)),
        ('coil_right',(9.5,1.25,2.3),(10.15,1.9,3.45)),
        ('coil_to_fall',(9.5,1.25,3.45),(11.55,1.9,4.05)),
        ('rope_end',(10.15,1.25,2.8),(13.4,1.9,3.45))]:
        m.box(name,a,b,'rope', omit=('east',) if name == 'coil_back' else ('north',) if name == 'coil_to_fall' else ())
    return m.finish()


def build(out_dir: str, vanilla_dir: str) -> None:
    """Write three Java 1.21 block models and five deterministic RGBA textures."""
    out = Path(out_dir)
    models = out / 'assets/airline/models/block'
    textures = out / 'assets/airline/textures/block'
    models.mkdir(parents=True, exist_ok=True)
    textures.mkdir(parents=True, exist_ok=True)
    paint(textures)
    for name, model in [('blockhutmediumairfield', medium()),
                        ('blockhutlargeairfield', large()),
                        ('blockhutanchorage', anchorage())]:
        (models / (name+'.json')).write_text(json.dumps(model,indent=2)+'\n',encoding='utf-8')


if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument('out_dir', nargs='?', default='final/out')
    p.add_argument('vanilla_dir', nargs='?', default='refs/vanilla')
    a = p.parse_args()
    build(a.out_dir, a.vanilla_dir)
