"""機体の設計図(建築家が、台の上に機体を組み立てるためのもの)を書き出す。

    python tools/make_aircraft_blueprints.py

機体の型ごとに 1 つ、src/main/resources/blueprints/airline/airfield/aircraft/<型>.blueprint を書く。
MineColonies の「飾り」の設計図として、空路 MOD の建築パック(Airline)に入る。機体ブロックを台の真ん中に置くと、
MOD がこの設計図の建築の依頼を出す(flight/Shipyard.java)。基準の点は機体ブロック、機首は +x(東)。

型のブロックの並びの出どころ:
    small       tools/aircraft_small.json(flight/AircraftType.java の small() と同じ並び。機体ブロックを除く全部。
                small() を変えたら、このファイルも変える。合っていることは、試験の場面 yard が確かめる)
    それ以外    src/main/resources/airline_aircraft/<型>.json(MOD が読むものと同じファイル)

nbtlib が要る(make_blueprint.py と同じ)。
"""
import glob
import json
import os
import sys

import nbtlib
from nbtlib.tag import Byte, Compound, Int, IntArray, List, Short, String

sys.dont_write_bytecode = True
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_blueprint  # noqa: E402  (状態の書き方と、Minecraft の版の番号を借りる)

OUT = os.path.join(ROOT, "src", "main", "resources", "blueprints", "airline", "airfield", "aircraft")


def designs():
    out = {}
    with open(os.path.join(ROOT, "tools", "aircraft_small.json"), encoding="utf-8") as f:
        out["small"] = json.load(f)["blocks"]
    for path in sorted(glob.glob(os.path.join(ROOT, "src", "main", "resources", "airline_aircraft", "*.json"))):
        with open(path, encoding="utf-8") as f:
            out[os.path.splitext(os.path.basename(path))[0]] = json.load(f)["blocks"]
    return out


def write(name, parts):
    blocks = {(x, y, z): state for x, y, z, state in parts}
    assert (0, 0, 0) not in blocks, "the aircraft's own block is added here"
    blocks[(0, 0, 0)] = "airline:aircraft"
    x0, y0, z0 = (min(p[i] for p in blocks) for i in range(3))
    x1, y1, z1 = (max(p[i] for p in blocks) for i in range(3))
    sx, sy, sz = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
    palette, index = [Compound({"Name": String("minecraft:air")})], {}
    for state in blocks.values():
        if state not in index:
            index[state] = len(palette)
            palette.append(make_blueprint.parse(state))
    # 並びは y → z → x。short を 2 個ずつ int に詰める(上位 16 ビットが先)。
    shorts = [index[blocks[(x, y, z)]] if (x, y, z) in blocks else 0 for y in range(y0, y1 + 1) for z in range(z0, z1 + 1) for x in range(x0, x1 + 1)]
    ints = []
    for i in range(0, len(shorts), 2):
        value = (shorts[i] << 16) | (shorts[i + 1] if i + 1 < len(shorts) else 0)
        ints.append(value - (1 << 32) if value >= 1 << 31 else value)
    mods = sorted({state.split(":")[0] for state in blocks.values()} - {"minecraft"})
    blueprint = nbtlib.File({
        "version": Byte(1),
        "size_x": Short(sx), "size_y": Short(sy), "size_z": Short(sz),
        "palette": List[Compound](palette),
        "blocks": IntArray(ints),
        "tile_entities": List[Compound]([]),
        "entities": List[Compound]([]),
        "required_mods": List[String]([String(mod) for mod in ["minecolonies"] + mods]),
        "name": String(name),
        "architects": List[String]([String("asteain")]),
        "mcversion": Int(make_blueprint.DATA_VERSION),
        "optional_data": Compound({"structurize": Compound({"primary_offset": make_blueprint.pos(-x0, -y0, -z0)})}),
    })
    os.makedirs(OUT, exist_ok=True)
    out = os.path.join(OUT, name + ".blueprint")
    blueprint.save(out, gzipped=True)
    print("wrote", out, os.path.getsize(out), "bytes; size", sx, sy, sz, "; anchor", (-x0, -y0, -z0), "; blocks", len(blocks), "; palette", len(palette))


def main():
    for name, parts in designs().items():
        write(name, parts)


if __name__ == "__main__":
    main()
