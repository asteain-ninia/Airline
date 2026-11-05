# 使える部品と、その働き(Create Aeronautics 同梱版 1.3.2)

空を飛ぶ船を一から設計する人のための、部品の表。**逆コンパイルしたコードと jar の中のデータに書いてあること**だけを書いた。ゲームは動かしていない。実測値は、これまでに試験機を実際に飛ばして測った記録から引いた。

対象:Aeronautics 1.3.2、Simulated 1.3.2、Offroad 1.3.2(同梱 jar の中身)、Sable 2.0.5、Create 6.0.10、Minecraft 1.21.1 / NeoForge。設定はすべて既定値。

## この文書の印

| 印 | 意味 |
| --- | --- |
| 【確認】 | コードまたはデータを読んで確かめた。動かしてはいない |
| 【試算】 | コードの式に数字を入れて計算した。測ってはいない |
| 【実測】 | 試験機を実際に飛ばして測った値 |
| 【未確認】 | コードから決められなかった。推測で埋めていない |

## 根拠について

各項の【確認】は、MOD のコードとデータを読んで確かめたもの。かっこの中のクラス名・行番号は、その場所の控え(A = Aeronautics、S = Simulated、B = Sable、X = Create と Sable の Create 互換)。**コードそのものは、この作業フォルダには置いていない**(読みに行く必要はない)。

---

## 1. 設計者向けの早見表

### 1.0 先に覚える決まり(単位・向き・気圧)

- **座標**:`facing=east` は +x、`south` は +z、`up` は +y。船の中の座標(置いたブロックの座標)で考えてよい。力はすべて船の座標で掛かる。
- **力の単位**:質量 × ブロック/秒²。**重力は 11**(下向き)。質量 M の船の重さは **11 × M**。【確認:B `DimensionPhysics` 25 行】
- **気圧 P**:高さ y だけで決まる。**P(y) = exp(−0.004 × (y − 63))**(y = −38〜280。280 を超えると 320 で 0 まで急に落ちる)。推力・浮力・気嚢の抵抗・帆の力のすべてに掛かる。【確認:B `DimensionPhysics.createDefault` 65–105 行、`BezierResourceFunction.evaluateFunction` 45–79 行。式は制御点の間を 3 次で結んだもので、下の表は計算値】

| y | 63 | 80 | 100 | 120 | 140 | 160 | 180 | 200 | 240 | 280 | 300 | 320 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P | 1.000 | 0.934 | 0.862 | 0.796 | 0.734 | 0.678 | 0.626 | 0.578 | 0.493 | 0.420 | 0.306 | 0 |
| 熱気 1 m³ が支える質量(1.5 × P) | 1.50 | 1.40 | 1.29 | 1.19 | 1.10 | 1.02 | 0.94 | 0.87 | 0.74 | 0.63 | 0.46 | 0 |

- **回転数の符号**:Create の回転数には符号がある。この文書では **s**(RPM、符号つき)と書く。シャフトでまっすぐつないだ先は同じ符号、歯車がかみ合うたびに符号が反転する(Create の決まり)。
- **「気密ブロック」**:タグ `#aeronautics:airtight` =気嚢のブロック全色(`*_envelope`、`*_envelope_encased_shaft`)と**羊毛全色**(`#minecraft:wool`)。気嚢を閉じるのも、空気の抵抗を受けるのも、このタグのブロックだけ。【確認:データ `aeronautics/tags/block/airtight.json`、`envelope.json`】

### 1.1 推す部品

| 部品(ID) | ブロックの状態 | 質量 | 推力(1 基、気圧と気流の補正の前) | 気流の速さ(この速さで推力が 0 になる) | 応力の負荷 | 置き方の決まり |
| --- | --- | --- | --- | --- | --- | --- |
| 木のプロペラ `aeronautics:wooden_propeller` | `facing`(6 方向)、`reversed` | 1.0 | **1.0 × \|s\|** | 0.1 × \|s\| ブロック/秒 | 4 × \|s\| | シャフトは `facing` の**反対の面**から入れる。軸は `facing` の軸 |
| 安山岩のプロペラ `aeronautics:andesite_propeller` | 同上 | 1.0 | 1.0 × \|s\|(木と同じ) | 0.1 × \|s\| | 4 × \|s\| | 同上。違いは見た目の羽根の半径だけ(木 1.5、安山岩 1.0) |
| スマートプロペラ `aeronautics:smart_propeller` | `axis`(x か z)、`reversed`、`ceiling` | 1.0 | 1.0 × \|s\| | 0.1 × \|s\| | 4 × \|s\| | **上下に押す専用**。回る軸はいつも縦。シャフトは下の面から(`ceiling=true` なら上の面から)。船が傾いても推力を鉛直に保つ(`axis` で決まる 1 方向だけ、±45 度まで) |
| プロペラ軸受け `aeronautics:propeller_bearing` + 帆 N 枚 | `facing`(6 方向) | 軸受け 1.0、帆 1 枚 0.25 | **0.2 × N^1.5 × \|s\|** | 0.05 × √N × \|s\| | 2 × max(N, 2) × \|s\| | シャフトは `facing` の反対の面から。`facing` の前に帆をつなげて置く。帆は 2 枚以上。回転が来ると次のティックに自分で組み上がる |
| ジャイロ式プロペラ軸受け `aeronautics:gyroscopic_propeller_bearing` + 帆 | `facing`(6 方向) | 同上 | 同上 | 同上 | 同上 | 推力の向きを、`facing` から 12 度以内で**真上(重力の逆)へ自分で傾ける**。操縦では傾けられない。隣にレッドストーン信号を入れると傾きを止めて `facing` に戻す |

**力の向き(小型プロペラ・軸受けに共通)**:軸の正の向き(x 軸なら東、y 軸なら上、z 軸なら南)を ê とすると、**船に掛かる力 = −ê × s × 推力の係数**。つまり **s が正なら軸の負の向き(西・下・北)へ押す。`facing` がどちら向きでも同じ**。`reversed=true`(軸受けは「推力の向き」の設定を 1)にすると逆になる。`facing` が決めるのは、シャフトをつなぐ面だけ。

**実際の力** = 上の推力 × **P**(プロペラの位置の気圧)× **気流の係数 k**。k = 1 −(押す向きへの船の速さ ÷ 気流の速さ)を 0〜1 に切ったもの。止まっているときは 1。**船の速さが気流の速さに届くと推力は 0**(これより速くは進めない)。逆向きに流されているときは 1 のまま。

帆の枚数と軸受けの性能【試算:上の式】:

| 帆 N 枚 | 2 | 3 | 4 | 6 | 9 | 12 | 16 | 25 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 推力 ÷ \|s\| | 0.57 | 1.04 | 1.60 | 2.94 | 5.40 | 8.31 | 12.8 | 25.0 |
| 気流の速さ ÷ \|s\| | 0.071 | 0.087 | 0.100 | 0.122 | 0.150 | 0.173 | 0.200 | 0.250 |
| 応力 ÷ \|s\| | 4 | 6 | 8 | 12 | 18 | 24 | 32 | 50 |
| 推力 ÷ 応力(小型プロペラは 0.25) | 0.14 | 0.17 | 0.20 | 0.24 | 0.30 | 0.35 | 0.40 | 0.50 |

帆として数えられるブロック(タグ `#create:windmill_sails`):`create:sail_frame`、`create:white_sail` など Create の帆全色、羊毛全色、`simulated:white_symmetric_sail` など対称帆全色。**1 枚 = 1、種類や向きによる差は無い**。

### 1.2 浮かせる部品

| 部品(ID) | ブロックの状態 | 質量 | 出る力 | 置き方の決まり |
| --- | --- | --- | --- | --- |
| 熱気バーナー `aeronautics:adjustable_burner` | `powered`、`variant`(`fire` / `soulful`。見た目だけ) | 1.0 | 自分では力を出さない。気嚢へ熱気を送る。**目標の熱気の量 = 設定値(5〜500 m³、既定 500)× 隣の信号の強さ ÷ 15** | 真上へ最大 80 ブロック、**当たり判定のあるブロックに遮られずに**気密ブロック(天井)へ届くこと。気嚢の中でも下でもよい。燃料は要らない |
| 気嚢 `aeronautics:<色>_envelope`(16 色) | なし | 0.25 | 熱気 V m³ のとき**上向きに 11 × 1.5 × V × P**。支える質量は 1.5 × V × P。掛かる位置は気嚢の中の空気のマスの重心 | 天井と四方の壁で囲む。**底は開いていてよい**。判定は「水平 4 方向と真上」だけ(1.5 節) |
| 気嚢つきシャフト `aeronautics:<色>_envelope_encased_shaft` | `axis` | 0.25 | 気嚢と同じ(気密)。中をシャフトが通る | 気嚢の壁を回転が抜ける所に使う。応力の負荷は指定なし |
| レビタイト `aeronautics:levitite`、`aeronautics:pearlescent_levitite` | なし | 1.0 | **重力を打ち消す**。1 個で質量 10 まで。船の重さを超えては押さない(浮き上がらせない)。気圧に関係しない。操れない。別に、遅いときに強い抵抗を出す(1.4 節) | 置くだけ。2 種は同じ性質(色違い) |
| 蒸気の噴き出し口 `aeronautics:steam_vent` | `facing`(水平 4 方向)、`powered`、`waterlogged`、`variant` | 1.0 | 気嚢へ蒸気を送る(熱気と同じ 1.5/m³)。量 = 設定値(50〜5000、既定 5000)× ボイラーの効率 × 信号 ÷ 15 | **Create の流体タンク(ボイラー)の真上**に置く。水と熱源が要る。小型機には重い |

`levitite_blend` はブロックではなく液体(レビタイトの材料)で、船の部品ではない。

釣り合いの式:**要る熱気 V = M ÷ (1.5 × P)**。例:M = 60、y = 140 なら 54.5 m³。【実測と一致:質量 43.75・y = 130〜140 で約 40 m³、質量 59.75・y = 135〜143 で 54〜56 m³】

### 1.3 動力・変速・信号

| 部品(ID) | ブロックの状態 | 質量 | 働き(数字) | 置き方の決まり |
| --- | --- | --- | --- | --- |
| 携帯エンジン `simulated:<色>_portable_engine`(16 色。色なしの ID は無い) | `facing`(水平 4 方向)、`lit` | 1.0 | 燃料が燃えている間 **32 RPM**。応力の容量 **64 × 32 = 2048**。過熱中は 64 RPM・4096。止める手段は無い(レッドストーンを見ない) | シャフトは **`facing` の面**から出る。`facing` が東か南なら s = +32、西か北なら −32(回転の向きの設定が既定のとき) |
| アナログ変速機 `simulated:analog_transmission` | `axis`(x / y / z)、`powered` | 1.0 | 1 個の中に「シャフト」と「小さい歯車」があり、その間の比が隣の信号の強さ n(0〜15)で変わる。**歯車の速さ = シャフトの速さ × (1 − (n+1)/16)**(n = 0 だけは 1 倍、n = 15 は切れる)。応力の負荷は指定なし | シャフトは `axis` の両端。歯車は横の歯車とかみ合う。**歯車側から入れてシャフト側から出すと増速**(n = 7 で 2 倍、11 で 4 倍、13 で 8 倍) |
| 方向ギアシフト `simulated:directional_gearshift` | `facing`(6 方向)、`axis_along_first`、`left_powered`、`right_powered` | 1.0 | 回転を通す・逆にする・切る。**`facing` の側の信号だけ → そのまま(1 倍)。反対側の信号だけ → 逆(−1 倍)。両方か、どちらも無し → 止まる**。応力の負荷は指定なし | 軸は `facing` と直角。`facing` が上下なら `axis_along_first=true` で x 軸・`false` で z 軸 |
| スロットルレバー `simulated:throttle_lever` | `face`(`floor` / `wall` / `ceiling`)、`facing`(水平 4 方向)、`inverted` | 0 | 0〜15 の信号を出す。**6 方向の隣すべてへ**弱い信号、取り付けたブロックへは強い信号(そのブロックを通してさらに隣へ届く) | `inverted=false` で使う(`true` だと書いた値 n が 15 − n になって出る) |

### 1.4 空気の抵抗(速さを決めるもの)

| 出どころ | 式(船に掛かる力) | 向き | 掛かる位置 | 根拠 |
| --- | --- | --- | --- | --- |
| **気密ブロック**(気嚢・羊毛) | **0.33 × 個数 × P × 速さ**(速さに比例) | 全方向で同じ | 気密ブロックの重心。回転にも減衰が掛かる(ブロックが重心から離れているほど大きい) | 【確認】データ `balloon_drag.json`・`simple_drag.json`、B `FloatingBlockController.applyFriction` |
| **全体の抵抗**(どの船にも) | 設定値 0.09。**0.09 × 質量 × 速さ** と置くと実測と合う | — | — | 値は【確認】、使われ方は【未確認】(ネイティブ側) |
| **プロペラ自身** | 気流の補正のぶん。小型 1 基あたり **10 × P × 速さ**、軸受けは **4 × N × P × 速さ**(回転数に関係しない) | 軸の向きだけ | プロペラのブロック | 【試算】1.1 節の式を書き直したもの |
| レビタイト | 水平 **11 × (1.5 × exp(−v²/6) + 0.05) × v**、上下 **11 × (2 × exp(−v²/6) + 0.1) × v**(1 個あたり。気圧に関係しない) | 上下と水平で別 | レビタイトの重心 | 【確認】データ `levitite.json`、同じメソッド。数字は【試算】 |
| Create の帆 `create:*_sail`、`create:sail_frame` | 面の向き n(`facing` の逆)について:0.75 × P × (n·v) を n の向きに、0.0689 × P × v を全方向に、さらに**速さ × 0.475 × P の力を `facing` の向きへ**(揚力) | 向きによる | 帆のブロック | 【確認】B `BlockSubLevelLiftProvider` 74–148 行、X `SailBlockMixin` |
| 対称帆 `simulated:*_symmetric_sail` | 軸の向きの速さに 1.75 × P、全方向に 0.0689 × P。揚力なし | `axis` の向きに強い | 帆のブロック | 【確認】S `SymmetricSailBlock` 179–194 行 |
| そのほかのブロック(板材、歯車、機械) | 個別の抵抗は無い(全体の抵抗だけ) | — | — | 【確認】抵抗を持つのは上の仕組みだけ |

レビタイト 1 個の抵抗【試算】:速さ 0.5 で 8.2、1.0 で 14.5、**1.7 で 18.3(山)**、3.0 で 12.7、5.0 で 4.0、8.0 で 4.4。遅いときは強く止め、秒速 4 を超えると軽い。

**最高速の式【試算】**(水平にまっすぐ、釣り合ったとき):

> v = (推力の合計 × P) ÷ (プロペラ自身の分 + 0.33 × 気密ブロックの数 × P + 0.09 × 質量)

小型プロペラ n 基を s RPM で回すなら **v = n × s × P ÷ (10 × n × P + 0.33 × N気密 × P + 0.09 × M)**。**速さは回転数に比例する**。

| 試験機(実際に飛ばしたもの) | 式の値 | 実測 |
| --- | --- | --- |
| 試験機 A:小型プロペラ 2 基 32 RPM、気密ブロック 115、質量 43.75、y ≈ 137 | 1.01 | 0.98〜1.03 |
| 試験機 B:小型プロペラ 2 基 64 RPM、気密ブロック 135、質量 59.75 | 1.78 | 1.76〜1.84 |
| 試験機 C:小型プロペラ 2 基 128 RPM、気密ブロック 135、質量 58.75 | 3.57 | 3.5〜3.65 |

### 1.5 気嚢と認められる決まり(要点)

1. バーナーの**上面の中心から真上**へ線を伸ばす。最初に当たった「当たり判定のあるブロック」が気密ブロックなら、そのすぐ下のマスが始点。気密でないブロック(板材など)に先に当たると、気嚢は作られない。当たり判定の無いブロック(レバーなど)は素通りする。
2. 始点の段を**水平 4 方向**に広げる。気密ブロックで止まる。気密でないブロック(板材、機械、空気)は通り抜ける。
3. 広がった先の各マスの**真上**が気密でなければ、上の段も同じように広げる。そのまま世界の天井(建築の上限)まで抜けたら失敗。**いちばん上の段で失敗すると、気嚢そのものが作られない**(天井に穴、いちばん上の段の壁に穴)。
4. 下へは 1 段ずつ伸ばす。その段を水平に広げて、外へ漏れ出さなければ(漏れた先の真上が空いていなければ)気嚢に加える。**漏れる段とそれより下は加えない。上の段は生きる**。底が開いた箱なら、壁のいちばん下の段までが気嚢になる。
5. **容量 =気嚢に入ったマスの数 − その中の空気でないブロックの数**(中の機械・レバー・シャフトは 1 個につき 1 m³ 減らす)。大きさの上限はコードに無い。
6. 縦の角の柱と、天井の縁(壁の真上)は、判定に関わらない。内側が a × b × 高さ h の箱なら、**壁 2(a + b) × h + 天井 a × b 個**で閉じる(例:3 × 5 × 5 = 75 m³ が 95 個。角と縁まで張ると 135 個)。船が分かれる判定は辺でつながっていればよいので、これでも 1 隻のまま。【確認:判定の作り。角なしで実機に組んだことはない】

### 1.6 質量の早見(Sable のタグで決まる)

| 質量 | ブロック |
| --- | --- |
| **0**(当たり判定が無いもの) | スロットルレバー、バニラのレバー、たいまつ、レッドストーンの粉など |
| **0.25** | 気嚢・気嚢つきシャフト、羊毛、Create の帆・対称帆、木のハーフブロック・階段、柵・柵の門・トラップドア・ドア・はしご・板ガラス・鉄格子・鎖・ランタン・カーペット・葉、`simulated:redstone_accumulator`・`redstone_inductor`、Simulated の取っ手・名札・ロープ結合器・リンク受信機 |
| **0.5** | 板材・原木、樽・チェスト、Create のシャフト・歯車・大きい歯車・ケース入りのシャフトと歯車(安山岩・真鍮)・座席・ベルト、`simulated:steering_wheel`、`simulated:spring` |
| **1.0**(どのタグにも入らないもの) | プロペラ 3 種、プロペラ軸受け 2 種、熱気バーナー、蒸気の噴き出し口、レビタイト、携帯エンジン、アナログ変速機、方向ギアシフト、`simulated:swivel_bearing`(組み立て後 0.75)、Simulated のセンサー類・磁石・ねじりばね・ロープ巻き上げ機、Create のケース類・ギアボックスなど |
| **2.0** | 石・丸石の類(`#c:stones`、`#c:cobblestones`)、玄武岩、黒曜石、Offroad の車輪の台 |
| **4.0** | 鉱物ブロック(`#c:storage_blocks`。銅・スライム・骨・段ボールは除く)、金床、Create のはずみ車 |

2 枚重ねのハーフブロック(`type=double`)は倍。プロペラ軸受けが組み上げて回している帆の質量も、船の質量に数えられる。

### 1.7 動力の勘定

- エンジン 1 台(32 RPM)= 2048。小型プロペラは 1 基 4 × |s|。**1 台で回せる小型プロペラ**:64 RPM なら 8 基、128 RPM なら 4 基、**256 RPM(Create の上限)なら 2 基**(ちょうど 2048。容量と同じなら止まらない)。
- 軸受けは 2 × N × |s|。帆 9 枚を 64 RPM で 1152、128 RPM で 2304(エンジン 2 台か過熱が要る)。
- シャフト、歯車、方向ギアシフト、アナログ変速機、気嚢つきシャフトに負荷の指定は無い。

### 1.8 速さと旋回の見積もり【試算】

最高速(1.4 節の式。y ≈ 137、P = 0.743。まっすぐ水平に飛び、推力の高さと抵抗の高さが合っている場合):

| 推進 | 応力 | 気密ブロック 95 個・質量 55 | 気密ブロック 135 個・質量 60 |
| --- | --- | --- | --- |
| 小型 2 基・64 RPM | 512 | 2.21 | 1.78(実測 1.76〜1.84) |
| 小型 2 基・128 RPM | 1024 | 4.41 | 3.56(実測 3.5〜3.65) |
| 小型 2 基・256 RPM | 2048 | 8.83 | 7.13 |
| 小型 4 基・64 RPM | 1024 | 3.28 | 2.79 |
| 小型 4 基・128 RPM | 2048 | 6.56 | 5.58 |
| 軸受け 1 基・帆 9 枚・64 RPM | 1152 | 4.67 | 3.94 |
| 軸受け 1 基・帆 9 枚・128 RPM | 2304 | 9.34 | 7.87 |
| 軸受け 1 基・帆 16 枚・64 RPM | 2048 | 8.03 | 7.07 |
| 軸受け 2 基・帆 9 枚ずつ・64 RPM | 2304 | 6.28 | 5.58 |

(単位はブロック/秒。質量は推進の部品の増減を入れていない目安。)

その場で回る速さ(左右のプロペラを逆に回す。腕 = 重心から各プロペラまでの左右の距離):

> 角速度 ≈ (2 × s × P × 腕) ÷ (0.33 × P × Σ(d² + 1/3) + 2 × 10 × P × 腕² + 未確認の分)

Σ は気密ブロック全部について、d は気密ブロックの重心から各ブロックまでの水平の距離。**回る速さは「回転数 × 腕」に比例し、気嚢のブロックが外へ広がっているほど遅い**。試験機に当てると、A が 19.0 度/秒(実測 17)、B が 19.1(実測 14.4)、C が 38.1(実測 29)。**実測は式より 1〜2.5 割遅い**(全体の抵抗の回転ぶんなど、式に入っていない減衰がある)。回転数に比例する点は実測と合う。

気嚢の形ごとの数字(角と縁を省いた形。1.5 節の 6):

| 内側(左右 × 前後 × 高さ) | 容量 m³ | 個数 | 質量 | 直進の抵抗(× P × 速さ) | 回る抵抗(× P × 角速度) | 外寸 |
| --- | --- | --- | --- | --- | --- | --- |
| 3 × 3 × 6 | 54 | 81 | 20.25 | 26.7 | 124 | 5 × 5 × 7 |
| 3 × 4 × 5 | 60 | 82 | 20.5 | 27.1 | 154 | 5 × 6 × 6 |
| 3 × 5 × 4 | 60 | 79 | 19.75 | 26.1 | 178 | 5 × 7 × 5 |
| 4 × 4 × 4 | 64 | 80 | 20.0 | 26.4 | 180 | 6 × 6 × 5 |
| 3 × 5 × 5 | 75 | 95 | 23.75 | 31.4 | 218 | 5 × 7 × 6 |
| 5 × 5 × 3 | 75 | 85 | 21.25 | 28.1 | 260 | 7 × 7 × 4 |
| 4 × 5 × 4 | 80 | 92 | 23.0 | 30.4 | 249 | 6 × 7 × 5 |
| 5 × 5 × 4 | 100 | 105 | 26.25 | 34.6 | 335 | 7 × 7 × 5 |
| 5 × 7 × 3 | 105 | 107 | 26.75 | 35.3 | 439 | 7 × 9 × 4 |
| 参考:3 × 5 × 5 を、角と縁まで張った場合 | 75 | 135 | 33.75 | 44.6 | 365 | 5 × 7 × 6 |

同じ容量なら、**細く高い形ほど回りやすく、個数はあまり変わらない**。

---

## 2. 小型プロペラ(木・安山岩・スマート)

### 2.1 ブロックと状態

- `WoodenPropellerBlock`・`AndesitePropellerBlock` は `BasePropellerBlock`(Create の `DirectionalKineticBlock`)を継ぐ。状態は `facing`(6 方向)と `reversed`。【確認:A `content/blocks/propeller/small/BasePropellerBlock` 26–37 行】
- シャフトがつながる面は `facing.getOpposite()` だけ。回る軸は `facing` の軸。【確認:同 53–59 行】
- レンチで羽根の側を叩くと `reversed` が切り替わる(それ以外の面は向きの回転)。【確認:同 65–77 行】
- `SmartPropellerBlock` は `HorizontalAxisKineticBlock` を継ぐ。状態は `axis`(x / z)、`reversed`、`ceiling`。回る軸はいつも y。シャフトは `ceiling=false` で下の面、`true` で上の面。【確認:A `…/smart_propeller/SmartPropellerBlock` 27–55 行】

### 2.2 推力の式

- `getThrust() = 設定の推力 × getDirectionIndependentSpeed()`、`getAirflow() = 設定の気流 × 同じ値`。【確認:A `BasePropellerBlockEntity` 140–146 行】
- `getDirectionIndependentSpeed() = facing の軸の符号(正の向きなら +1、負なら −1)× rotationSpeed × 3.3333 × (reversed なら −1)`。`rotationSpeed` は毎ティック `Mth.lerp(0.15, 今, 目標)` で目標 `convertToAngular(getSpeed())` = 0.3 × s へ寄る。つまり落ち着くと **`getDirectionIndependentSpeed()` = 軸の符号 × s**(RPM)。【確認:同 86–101 行、X `create/…/KineticBlockEntity.convertToAngular` 474–476 行】
- 設定の既定値:推力 1.0、気流 0.1。木・安山岩・スマートに**別々の設定項目があるが、既定値は 3 つとも同じ**。【確認:A `config/server/AeroPhysics` 16–33 行】
- 動いていると見なす条件:|rotationSpeed| > 0.01。【確認:A `BasePropellerBlockEntity` 148–150 行】
- 立ち上がり:1 ティックに差の 15% ずつ。95% まで約 18 ティック。【試算。実測は「95% まで約 20 ティック」】

### 2.3 力の掛け方と向き

- 毎サブステップ、`力 = facing の単位ベクトル × getScaledThrust() × 刻み` を、プロペラのブロックの中心に掛ける(力のグループは `PROPULSION`)。【確認:B `api/block/propeller/BlockEntitySubLevelPropellerActor` 20–35 行】
- `getScaledThrust() = −getThrust() × getAirflowScaling() × getCurrentAirPressure()`。【確認:B `BlockEntityPropeller` 21–23 行】
- まとめると、力 = −(facing の単位ベクトル × facing の軸の符号)× s × … = **−(軸の正の向き)× s ×(reversed なら −1)× 1.0 × P × k**。`facing` の符号は 2 回掛かって消える。【確認:上の 2 つの式から】【実測と一致:軸を +x に向けたエンジン(s = +32)から 1 倍でつないだプロペラで、船は −x へ進んだ】
- 気流の係数:`k = clamp((airflow + 船のその位置の速度 · facing の向き) ÷ airflow, 0, 1)`。`|airflow| ≤ 0.001` なら 1。速度は 1 秒あたりのブロック数。【確認:B `BlockEntityPropeller.getAirflowScaling` 30–46 行】

### 2.4 前後のブロック

- 小型プロペラの推力を、前後のブロックで減らすコードは**無い**。【確認:推力の式(`getThrust`・`getScaledThrust`・`getAirflowScaling`)に周りのブロックを見る所が無い。光線を飛ばしているのは、生き物を吹き飛ばす処理(A `PropellerActorBehaviour.pushEntities` 90–186 行)と粒子だけ】
- 木のプロペラの羽根は見た目の半径 1.5(3 ブロック幅)。当たり判定は 1 ブロックぶんで、隣のブロックに羽根がめり込んで見えても力は変わらない。【確認:A `WoodenPropellerBlockEntity.getRadius` 24–27 行は粒子と吹き飛ばしにだけ使う】
- 参考:Create のケース入りファン(`create:encased_fan`)も Sable が船のプロペラとして扱う(推力 0.27 × |s|、気流 0.09 × |s|)。こちらは**前のブロックが空気でないと働かない**。【確認:X `sablecompat/…/fans_provide_force/EncasedFanBlockEntityMixin` 25–53 行】

### 2.5 スマートプロペラは何がスマートか

- 推力の向きが固定でなく、**船の傾きを打ち消すように自分で首を振る**。`getHingeAngle` が船の向きから傾きを出し、±45 度に切って、推力の向き(元は真上)をその角度だけ回す。【確認:A `SmartPropellerBlockEntity` 82–91 行、126–155 行】
- 首を振るのは 1 方向だけ。`axis=x` なら z 軸まわり(推力が x–y 面の中で動く)、`axis=z` なら x 軸まわり(y–z 面の中)。【確認:同 82–91 行。`Direction.get(POSITIVE, axis).getClockWise()` が回転の軸】
- 力の向き:`ceiling=false` では推力の向きが上なので、力 = −上 × s。**s が負(または `reversed=true`)で船を持ち上げる**。`ceiling=true` では推力の向きが下になり、s が正で持ち上げる。【確認:同 36 行、75–91 行、126–155 行】
- 気流の係数は首の向きでなく「上」で計算される(`getBlockDirection()` がいつも `UP`)。【確認:同 61–64 行】このため `ceiling=true` では、気流の補正が実際の押す向きと逆に働くはず。【試算。実機では見ていない】
- 操縦で向きを変えることはできない。入力は回転数だけ。

---

## 3. プロペラ軸受けと帆

### 3.1 ブロックと状態

- `PropellerBearingBlock` は Create の `BearingBlock` を継ぐ。状態は `facing`(6 方向)だけ。シャフトは `facing` の反対の面。【確認:A `…/propeller_bearing/PropellerBearingBlock` 25–28 行、X `create/…/bearing/BearingBlock` 19–27 行】
- 「推力の向き」はブロックの状態でなく、ブロックエンティティの設定(`getThrustDirectionOption()`。0 = `RIGHT_HANDED`、1 = `LEFT_HANDED`)。1 にすると力が逆になる。【確認:A `PropellerBearingBlockEntity` 145–150 行、470–489 行】

### 3.2 組み立て

- **回転数が変わると、次のティックに自分で組み上がる**。`onSpeedChanged` が `assembleNextTick = true` を立て、`tick` が「まだ動いていない・回転数が 0 でない」なら `assemble()` を呼ぶ。【確認:X `create/…/bearing/MechanicalBearingBlockEntity` 131–134 行、247–254 行】
- したがって、**サーバー側のコードは `setBlock` で軸受けと帆を置き、そのあと回転を届ければよい**。組み立てのメソッドを呼ぶ必要は無い。すでに回っている軸受けの前にあとから帆を置いた場合は、回転数が変わるまで組み上がらないので、`setAssembleNextTick(true)`(公開)か `assemble()`(公開)を呼ぶ。【確認:A `PropellerBearingBlockEntity` 288–337 行】
- プレイヤーの操作:素手の右クリックで、止まっていれば `setAssembleNextTick(true)`、動いていれば減速して分解。ただし回転数が 0 だと組み上がらない。【確認:A `PropellerBearingBlock.useItemOn` 42–65 行、X 同 250–252 行】
- 組み立ては、軸受けの `facing` の前のブロックから、Create の「くっついているブロック」をたどって集める。**帆が 2 枚未満だと失敗**(`not_enough_sails`)。【確認:X `create/…/bearing/BearingContraption.assemble` 35–48 行、A `mixin/propeller_bearing/BearingContraptionMixin` 39–55 行】
- 帆どうしのくっつき方:Create の帆は、**自分の `facing` の軸でない向き**の隣とくっつく(同じ面に並べた帆は糊なしでつながる)。対称帆は自分の `axis` でない向きとくっつき、Create の帆とはくっつかない。羊毛は糊が要る。【確認:X `create2/…/impl/contraption/BlockMovementChecksImpl` 307–308 行、S `index/SimBlockMovementChecks` 55–57 行】
- 一度組み上がると、回転が 0 になっても分解しない(動作の設定を「置かない」に固定してある)。【確認:A `PropellerBearingBlockEntity.addBehaviours` 105–108 行、X `MechanicalBearingBlockEntity` 256–265 行、`create2/…/IControlContraption.RotationMode` 41–44 行】
- 組み上げた帆は、船に乗った「回る装置」になり、その質量は船の質量に足される。【確認:B `api/physics/mass/MergedMassTracker.update` 41–76 行】装置になった帆は、帆としての抵抗・揚力を出さない。【確認:A `mixin/propeller_bearing/PropellerBearingContraptionEntityMixin` 12–14 行】

### 3.3 推力の式

- `getThrust() = totalSailPower^1.5 × getDirectionIndependentSpeed() × 0.2`。0.2 は設定 `propellerBearingThrust` の既定値。【確認:A `PropellerBearingBlockEntity` 133–135 行、`AeroPhysics` 10–12 行】
- `getAirflow() = √totalSailPower × getDirectionIndependentSpeed() × 0.05`(設定 `propellerBearingAirflow`)。【確認:同 141–143 行、`AeroPhysics` 13–15 行】
- `totalSailPower` = 組み上げた装置の中の、タグ `#create:windmill_sails` のブロックの数(1 個 1.0。`create:copycat_panel` は中身の素材で判定)。**帆の向きや軸からの距離は見ない**。【確認:同 361–376 行、378–418 行】
- `getDirectionIndependentSpeed()` は小型プロペラと同じ形で、落ち着くと 軸の符号 × s(RPM)。**速度の単位は RPM**。【確認:同 145–150 行、254–269 行】
- 立ち上がり:毎ティック `0.4 ÷ √totalSailPower` ずつ寄る(帆が多いほど遅い。9 枚で 13%)。【確認:同 264–268 行】
- 応力:`max(帆の数, 2) × 2.0`(1 RPM あたり)。【確認:同 88–103 行、A `index/AeroBlocks` 302 行】
- 力の掛かる位置は軸受けのブロックの中心(帆の位置ではない)。向きの決まりは小型プロペラと同じ。【確認:B `BlockEntitySubLevelPropellerActor` 28–35 行】

### 3.4 必要な空間

- コードが求めるのは「`facing` の前に帆が 2 枚以上つながっていること」だけ。帆の並べ方に決まりは無い。
- 回っている帆が船のほかのブロックに当たるかどうかは【未確認】(装置と船の当たり判定は読んでいない)。帆の回る範囲は空けておくのが無難。

---

## 4. ジャイロ式プロペラ軸受け

- `GyroscopicPropellerBearingBlockEntity` は `PropellerBearingBlockEntity` を継ぐ。推力・応力・組み立ては同じ。【確認:A `…/gyroscopic_propeller_bearing/GyroscopicPropellerBearingBlockEntity` 32 行】
- **傾けるのは推力の向き**(見た目では回る帆ごと)。目標は「重力の逆(真上)を船の座標に直した向き」で、`facing` を中心にした**半角 12 度の円すいの中**に切られる。1 ティックに 0.05 ずつ寄る。【確認:同 106–131 行(`updateTilt`、`setTilt`)、181–189 行(`sable$physicsTick`)】
- **操るもの**:隣のレッドストーン信号だけ。信号があると(`hasNeighborSignal`)目標が `facing` そのものになり、傾きをやめる。舵輪などの回転で傾ける入口は無い。【確認:同 94–96 行、116–119 行】
- 使い道:上向きに置けば、船が 12 度まで傾いても推力が鉛直を向く。横向きに置くと、推力が 12 度だけ上へ寄る。**向きを変える操縦には使えない**。

---

## 5. 熱気バーナーと気嚢

### 5.1 バーナー

- ID は `aeronautics:adjustable_burner`。状態は `powered` と `variant`(`fire` / `soulful`)。向きは無い。【確認:A `index/AeroBlocks` 224–226 行、`…/hot_air_burner/HotAirBurnerBlock` 48–49 行】
- 信号:`level.getBestNeighborSignal(pos)`(6 方向の隣のうちいちばん強い値、0〜15)。隣のブロックが変わるたびに読み直す。【確認:A `HotAirBurnerBlockEntity.updateSignal` 91–121 行、`HotAirBurnerBlock.neighborChanged` 117–125 行】
- ガスの量:`getGasOutput() = 設定値 × 信号 ÷ 15`。設定値は 5〜500(設定 `hot_air_burner_max` の既定 500)、**置いた直後は 500**。プレイヤーの画面では 5 刻み。コードからは `hotAirAmountBehaviour.setValue(n)` で 1 刻み。【確認:同 82–89 行、277–279 行、`HotAirBurnerValueBehaviour` 28–32 行・65–72 行、`config/server/AeroBlockConfigs` 11 行】
- 信号が 0 だとガスを出さず、気嚢からも外れる。【確認:同 287–289 行、A `…/balloon/Balloon.checkHeaters` 60–87 行】
- 気嚢を探すのは、置いた直後と、20 ティックに 1 回。【確認:同 60–72 行、123–134 行】
- 燃料は使わない。

### 5.2 バーナーの位置

- 光線は `Vec3.upFromBottomCenterOf(pos, 1.0)`(バーナーの上面の中心)から、真上へ 80 ブロック(設定 `hot_air_burner_max_range`)。【確認:同 269–274 行、`AeroBlockConfigs` 12–14 行】
- `ClipContext.Block.COLLIDER` で最初に当たったブロックがタグ `#aeronautics:airtight` なら、当たった面の側の隣(=すぐ下のマス)を始点にする。気密でなければ null(気嚢なし)。【確認:A `…/hot_air/BlockEntityLiftingGasProvider.getRaycastedPosition` 62–66 行】
- つまり、**バーナーは気嚢の天井の真下の縦の線の上ならどこでもよい**。間は 79 ブロックまで空けられる。ただし、間に当たり判定のある気密でないブロックを挟めない。
- バーナーのすぐ上が気密ブロックのとき(間が 0)の扱いは【未確認】(光線の始点が相手の面の上になる)。1 ブロック以上空ける。

### 5.3 気嚢と認められる条件(1.5 節の根拠)

- 入口:`BalloonBuilder.attemptBuildBalloon` → `buildBalloon` → `upwardsBiasedFloodFill`(始点の段)→ `completeBalloon`(下の段)。【確認:A `…/balloon/graph/BalloonBuilder` 58–69 行、211–224 行】
- 気嚢のマスになれる条件:空気、または**気密でない**ブロック。【確認:同 `isCandidatePosition` 29–39 行】
- 段の広げ方:水平 4 方向(`HORIZONTAL_DIRECTIONS`)。各マスで真上を見て、真上が気嚢のマスになれるなら上の段を再帰で広げる。始点が建築の高さの外なら失敗。**水平には打ち切りが無く、外へ漏れると、漏れた先の真上へ再帰して建築の上限に当たり、失敗になる**。【確認:同 `upwardsBiasedFloodFill` 156–209 行】
- 下への伸ばし方:できた段の各マスの 1 つ下を候補にし、そこから同じ広げ方をする。成功した段だけ加え、進まなくなるまでくり返す。【確認:同 `completeBalloon` 71–125 行】
- 容量:`段ごとの hotAirCount − solidCount` の合計。`solidCount` は空気でないブロック(`!state.isAir()`)。【確認:A `…/balloon/Balloon.recomputeBalloonData` 252–278 行、`BalloonBuilder.isSolid` 226–228 行】
- 中のブロックを足し引きすると容量が 1 ずつ変わる。気密ブロックを足すと作り直し、外すと、漏れる段から下を捨てるか、漏れ扱い(目標 0)になる。【確認:`Balloon.onSolidBlockAdded` 105–119 行、`onAirtightBlockRemoved` 135–185 行、`onAirtightBlockAdded` 201–205 行】
- 最大の大きさ:コードに上限は無い。バーナー 1 基の出せる量(500 m³)が実際の上限になる。

### 5.4 満ちる速さ・抜ける速さ

毎ティック(`ServerBalloon.updateGasAmounts`、A 213–250 行)【確認】:

1. 目標の合計 = つながっているバーナーの `getGasOutput()` の合計。容量を超えるぶんは、容量に収まるよう全体を縮める。
2. 差 = 目標 − 今の量。
3. 変化 = 差 ÷ 180(満ちるときも抜けるときも 180。`DefaultLiftingGas` 13–21 行)。
4. さらに `× (1 + 5 ÷ (1 + 3 × x²))`、x = 差 ÷ (容量 × 0.05)(同 28–36 行)。**目標から遠いときは 1 倍(時定数 180 ティック)、容量の 5% 以内に近づくと最大 6 倍(時定数 30 ティック)**。

【試算と実測】容量 60・目標 60 で空から:半分まで 124 ティック(実測 124)、90% まで 384(実測 約 385)、97% まで 490(実測 約 480)。

信号を 0 にすると同じ式で抜ける(58.5 m³ から 200 ティックで 18.8、400 ティックで 5.1、600 ティックで 0.06)。量も目標も 0.05 未満になると気嚢そのものが消える。【確認:`ServerBalloon.isValid` 203–205 行】

### 5.5 浮力の式

- `totalLift = Σ(ガスの量 × そのガスの強さ)`。熱気も蒸気も強さ 1.5(設定 `hotAirStrength`・`steamStrength`)。【確認:A `ServerBalloon` 241–247 行、`AeroPhysics` 34–35 行】
- 力 = `−重力 × totalLift × 気圧`。気圧は**気嚢の中心の高さ**で読む。【確認:同 `applyForces` 91–130 行】
- 掛かる位置は、気嚢のマス(中のブロックを除く)の平均の位置。満ち具合では動かない。高さによる気圧の差のぶん、わずかに補正が入る。【確認:同 99–115 行、133–171 行】
- 上下の頭打ち:気嚢が満杯で浮力が重さの約 1.5 倍のとき、秒速 6.5 で頭打ち【実測】。気密ブロックの抵抗が上下にも同じだけ掛かるため。

### 5.6 バーナーが複数、気嚢が複数

- **1 つの気嚢にバーナー複数**:あとから置いたバーナーは、始点が既存の気嚢の中なら、その気嚢に加わる。目標は**足し算**(同じガスの種類どうし)。容量で頭打ち。【確認:A `BlockEntityLiftingGasProvider.tryJoinBalloon` 72–83 行、`ServerBalloon.checkHeaters` 69–89 行】
- **1 隻に気嚢複数**:持てる。気嚢は世界ごとの一覧(`BalloonMap`)にあり、それぞれが自分の入っている船へ力を掛ける。重なった気嚢は 1 つにまとめられる。【確認:A `…/balloon/map/BalloonMap.physicsTick` 44–54 行、`mergeBalloons` 111–130 行】
- 気嚢ごとにバーナーが要る。気嚢を左右に分けて別々のレバーで焚けば、傾きを浮力で直せる。【試算。実機では見ていない】

### 5.7 気嚢つきシャフト

- `EnvelopeEncasedShaftBlock` は Create の `EncasedShaftBlock` を継ぐ。状態は `axis`。タグ `#aeronautics:envelope` に入るので、気密で、質量 0.25、抵抗も気嚢と同じ。【確認:A `…/envelope/EnvelopeEncasedShaftBlock` 36 行、データ `envelope.json`】
- 気嚢の壁を回転が抜ける所に使えば、気嚢に穴を開けずに中から外(または外から中)へ回転を通せる。

---

## 6. レビタイト

- 物理はデータで決まる。タグ `#aeronautics:levitite`(`levitite`、`pearlescent_levitite`)に `sable:floating_material = aeronautics:levitite` が付く。【確認:データ `aeronautics/physics_block_properties/levitite.json`】
- 素材の値:`prevent_self_lift: true`、`scale_friction_with_gravity: true`、`lift_strength: 10`、`transition_speed: 3`、`slow_vertical_friction: 2`、`fast_vertical_friction: 0.1`、`slow_horizontal_friction: 1.5`、`fast_horizontal_friction: 0.05`。`scale_with_pressure` は無い(=気圧に関係しない)。【確認:データ `aeronautics/floating_materials/levitite.json`】
- **浮く力**:合計 = `lift_strength × 個数`。力 = −重力 × 合計。ただし `prevent_self_lift` のため、**その力で船が重力より大きく加速しないよう縮められる**(掛かる点での上向きの加速度が、重力の大きさを超えない)。重心に置いた場合、質量 M ≤ 10 × 個数 なら重さがちょうど消え、M > 10 × 個数 なら 110 × 個数 だけ軽くなる。【確認:B `physics/floating_block/FloatingBlockController.applyLift` 148–209 行。数字は【試算】】
- 重心から外れた所に置くと、回す力も出る。重心の真上か真下、または左右対称に置く。
- **抵抗**:1.4 節の式。`transition_speed` を境に、遅い側は強い摩擦(上下 2・水平 1.5)、速い側は弱い摩擦(0.1・0.05)。重力の大きさ 11 が掛かる。【確認:同 `applyFriction` 225–286 行】
- **操れない**。信号も回転も見ない。浮く量を変えるには個数を変えるしかない。
- 質量はタグに無いので 1.0。
- 参考:エンドストーンの類も同じ仕組みで浮く(`lift_strength: 2`、質量 1 個で 2 まで)。【確認:データ `sable/floating_materials/end_stone.json`】
- 「浮遊」の成分 `Levitating`(`drag_fraction` 0.93)は**アイテムが落ちているときの挙動**で、ブロックの物理ではない。【確認:A `index/AeroItems` 42–47 行、`mixin/levitite/ItemEntityMixin` 35–49 行】

---

## 7. 蒸気の噴き出し口

- 気嚢へ蒸気(強さ 1.5、満ちる・抜けるは熱気と同じ 180)を送る、もう 1 つのガスの出どころ。**推力は出さない**。【確認:A `…/steam_vent/SteamVentBlockEntity` 58 行、305–317 行、`…/lifting_gas/SteamLiftingGas` 13–36 行】
- 量 = `設定値 × 効率 × 信号 ÷ 15`。設定値は 50〜5000(既定 5000)。効率は、**真下の Create の流体タンク**のボイラーの `getEngineEfficiency`(0〜1)。真下がタンクでなければ 0。【確認:同 59 行、81–91 行、106–117 行、251–259 行、305–307 行】
- 光線で気嚢を探す決まりはバーナーと同じ(範囲 80)。【確認:同 162–168 行】
- ボイラーから見ると、蒸気エンジンと同じ「蒸気を使う口」として数えられる。【確認:A `mixin/steam_vent/BoilerDataMixin` 45–93 行】
- 熱気と蒸気は別々に量を持ち、同じ気嚢に入れると合計が容量に収まるよう縮められる。
- 小型機の判断:タンク・水・熱源が要るので、質量 40〜70 の機体には向かない。

---

## 8. 空気の抵抗の出どころ

### 8.1 気嚢のブロックの抵抗

- データ `aeronautics/physics_block_properties/balloon_drag.json` が、タグ `#aeronautics:airtight` のブロックに `sable:floating_material = aeronautics:simple_drag`、`sable:floating_scale = 0.33` を付ける。【確認】
- 素材 `simple_drag`:`scale_with_pressure: true`、`lift_strength: 0.0`、`fast_vertical_friction: 1.0`、`fast_horizontal_friction: 1.0`。`transition_speed` が無い(0)ので、遅い側の摩擦は働かない。【確認:データ `aeronautics/floating_materials/simple_drag.json`、B `FloatingBlockMaterial` 20–33 行】
- 計算は Sable の `FloatingBlockController.applyFriction`(B 225–286 行)。同じ素材のブロックを 1 かたまりにして、`力 = −(摩擦 × floating_scale の合計 × 気圧) × かたまりの平均の速度`。上下と水平の摩擦が同じ 1.0 なので、**向きによる差は無い。速さに比例(2 乗ではない)**。【確認】
- 掛かる位置:かたまりの重心(`floating_scale` の重みつき平均)。【確認:同 249 行、278 行】
- 回転への減衰:かたまりのブロックの散らばりから出す。縦の軸まわりなら、およそ `0.33 × P × Σ(重心からの水平距離² + 1/3) × 角速度`。【確認:同 281–285 行(`matrixThingy`)。式の形は【試算】】
- 気圧はかたまりの重心の高さで読む。【確認:B `FloatingBlockData.computePressureScale` 49–53 行】
- 羊毛も同じタグなので、同じ抵抗を受ける。

### 8.2 全体の抵抗

- 次元ごとの物理の既定値に `universal_drag = 0.09` がある。物理の世界を作るとき、ネイティブ側へ 1 回渡される。【確認:B `DimensionPhysics` 28 行・93 行、`sublevel/system/SubLevelPhysicsSystem` 100–102 行、`physics/impl/rapier/RapierPhysicsPipeline.init` 112–114 行】
- ネイティブ側での使われ方は Java から読めない。【未確認】「力 = 0.09 × 質量 × 速さ」と置くと、試験機 3 機の最高速がすべて実測の幅に入る(1.4 節の表)。これを入れないと 8〜11% 速く出る。【試算】
- 回転にも同じ係数が掛かるかは【未確認】。

### 8.3 帆

- Sable が Create の帆(`SailBlock`)に `BlockSubLevelLiftProvider` を付ける。面の向きは `facing` の逆。係数は既定(平行の抵抗 0.75、全方向の抵抗 0.068882、揚力 0.475)。【確認:X `sablecompat/…/sails_providing_lift/SailBlockMixin` 15–25 行、B `api/block/BlockSubLevelLiftProvider` 74–84 行】
- 式(1 枚、毎サブステップ):面の向きを n、その位置の速度を v として、`n × (n·v) × 0.75 × P + v × 0.068882 × P + n × |v| × 0.475 × P` を作り、**その逆向きを船に掛ける**。3 つめの項は速さに比例して、いつも `facing` の向きへ押す。【確認:同 86–148 行】
- 対称帆は揚力 0、平行の抵抗 1.75。面の向きは `axis` の正の向き。【確認:S `content/blocks/symmetric_sail/SymmetricSailBlock` 179–194 行】
- どちらも気圧が掛かり、速さに比例。掛かる位置は帆のブロックの中心。
- 使い道【試算】:`facing=up` の Create の帆は、水平に速さ v で進むと 1 枚あたり 0.475 × P × v の上向きの力を出す(秒速 4・P = 0.73 で 1.4。質量 0.13 ぶん)。小型の飛行船の速さでは小さい。対称帆を縦に(軸を左右に)立てると、横滑りと首振りを抑える板になる。

### 8.4 力の掛かる位置のまとめ

| 力 | 位置 |
| --- | --- |
| 重力 | 質量の重心 |
| 浮力 | 気嚢の中の空気のマスの平均 |
| 気密ブロックの抵抗 | 気密ブロックの重心 |
| プロペラの推力 | プロペラ(軸受け)のブロックの中心 |
| レビタイト | レビタイトの重心 |

進むときの傾きは、推力の高さと抵抗の高さの差で決まる【実測:プロペラを気嚢のブロックの重心の高さへ上げたら、傾きが 22〜27 度から平均 3.3 度になった】。

---

## 9. 動力

### 9.1 携帯エンジン

- ID は色つきの 16 種(`simulated:white_portable_engine` など)。状態は `facing`(水平 4 方向)と `lit`。【確認:S `index/SimBlocks` 930 行、`content/blocks/portable_engine/PortableEngineBlock` 60–63 行】
- シャフトの面は `facing`、軸は `facing` の軸。【確認:同 76–82 行】
- 回転数:燃えている間 32。`getGeneratedSpeed() = convertToDirection(32 ×(回転の向きの設定が 0 なら +1、それ以外 −1), facing) ×(過熱なら 2)`。`convertToDirection` は、向きが正(東・南)ならそのまま、負(西・北)なら符号を反転。【確認:S `PortableEngineBlockEntity` 103–109 行・198 行、X `create/…/KineticBlockEntity.convertToDirection` 466–468 行】
- 回転の向きの設定はブロックエンティティの `movementDirection`(0 = 時計回り、1 = 反時計回り)。点火のとき、つながった先がすでに逆向きに回っていたら、自分で設定を切り替えて合わせる。【確認:同 76–86 行、185–196 行】
- **過熱の条件**:いま燃やし始めた燃料が、Create の「ブレイズバーナーを過熱させる燃料」(データマップ `SUPERHEATED_BLAZE_BURNER_FUELS`。ブレイズケーキなど)であること。その 1 個が燃えている間だけ 64 RPM。【確認:同 167–181 行、449–451 行、S `neoforge/service/NeoForgeSimItemService` 15–19 行】
- 応力の容量:1 RPM あたり 64。32 RPM で 2048、過熱で 4096。【確認:S `index/SimBlocks` 944–945 行、X `create/…/KineticNetwork` 128–168 行(容量 × 自分の回転数)】
- 燃料:かまどの燃焼時間(`getBurnTime(SMELTING)`)を 1 個ぶん読み込み、毎ティック 1 減らす。0 になると次の 1 個。入れられるのは 1 スタック。石炭 1 個 80 秒、石炭ブロック 1 個 800 秒、石炭ブロック 64 個で約 14 時間。回転数や負荷で減り方は変わらない。【確認:同 157–181 行、`NeoForgeSimItemService` 11–13 行】
- `facing` の反対側にケーキを置くと、60 ティックごとに 1 口かじって 100 ティック足す。【確認:同 242–262 行】
- レッドストーンで止める手段は無い(ブロックに信号を見る処理が無い)。比較器には燃料の残りを出す(`min(残り ÷ 200, 14) + 1`)。【確認:`PortableEngineBlock` 173–189 行】

### 9.2 アナログ変速機

- 状態は `axis` と `powered`。シャフトは `axis` の両端。【確認:S `content/blocks/analog_transmission/AnalogTransmissionBlock` 34–38 行、72–74 行】
- 中に「もう 1 つの小さい歯車」(`extraWheel`)を持つ。軸は同じ。横の歯車とかみ合う(斜めの大きい歯車とも)。【確認:S `AnalogTransmissionBlockEntity` 29–37 行、146–171 行】
- 信号:毎ティック `getBestNeighborSignal`(6 方向の最大)。変わると回転のつながりを作り直す。【確認:同 39–68 行】
- 比:`getRotationModifier() = 1 − (信号 + 1) ÷ 16`。シャフト → 歯車は × その値、歯車 → シャフトは ÷ その値。信号 0 はどちらも 1 倍、信号 15 は 0(切れる)。符号は変えない。【確認:同 71–100 行】
- 歯車 → シャフトで、結果が Create の上限(256 RPM)を超えると「速すぎ」で 0 になる。【確認:同 85–91 行】

| 信号 | 0 | 1 | 3 | 5 | 7 | 9 | 11 | 12 | 13 | 14 | 15 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| シャフト → 歯車 | 1 | 0.875 | 0.75 | 0.625 | 0.5 | 0.375 | 0.25 | 0.1875 | 0.125 | 0.0625 | 切 |
| 歯車 → シャフト | 1 | 1.14 | 1.33 | 1.6 | 2 | 2.67 | 4 | 5.33 | 8 | 16 | 切 |
| 歯車に 32 RPM を入れたときのシャフト | 32 | 36.6 | 42.7 | 51.2 | 64 | 85.3 | 128 | 170.7 | 256 | 止まる(512) | 0 |

使い道【試算】:エンジンの回転を歯車側へ入れ、シャフト側をプロペラへつなぐと、**レバー 1 本で 32〜256 RPM を選べる**(歯車の列が要らない)。信号 14 は止まるので使わない。

### 9.3 方向ギアシフト

- 状態は `facing`、`axis_along_first`、`left_powered`、`right_powered`。Create の `DirectionalAxisKineticBlock` を継ぐ。【確認:S `content/blocks/directional_gearshift/DirectionalGearshiftBlock` 31–47 行】
- 軸:`facing` の軸が x なら `axis_along_first` が真で y・偽で z。`facing` が y なら 真で x・偽で z。`facing` が z なら 真で x・偽で y。シャフトは軸の両端。【確認:X `create/…/base/DirectionalAxisKineticBlock.getRotationAxis` 100–112 行、141–143 行】
- 入力:「左」= `facing` の側、「右」= その反対側。それぞれ、隣のブロックがこちらへ出している信号(`level.getSignal(隣の位置, その向き)`)が 1 以上なら入。隣が信号を通すブロックなら、そのブロックへの強い信号も拾う。【確認:同 96–112 行、124–130 行】
- 出力の倍率(入ってきた面の反対側):両方入 → 0、左だけ → 1、右だけ → −1、どちらも無し → 0。【確認:S `DirectionalGearshiftBlockEntity.getRotationSpeedModifier` 14–36 行】
- 信号が変わると、1 ティック後に回転をつなぎ直す。【確認:同 84–94 行、114–122 行】
- 【実測】`facing=up` で上のレバーだけ → +32、下のレバーだけ → −32、どちらも無し → 0。

### 9.4 スロットルレバー

- 状態は `face`、`facing`、`inverted`。バニラのレバーと同じ取り付け方(床・壁・天井)。当たり判定は無い。【確認:S `content/blocks/throttle_lever/ThrottleLeverBlock` 40 行・146–148 行、`index/SimBlocks` 638–639 行(`Blocks.LEVER` の性質を引き継ぐ)】
- 弱い信号:`getSignal` が向きを見ずに値を返すので、**6 方向の隣すべて**へ出る。強い信号:取り付けたブロックへだけ(`getDirectSignal`)。【確認:同 102–112 行】
- 値を書く:`ThrottleLeverBlockEntity.setSignal(n)`。`inverted` が真だと 15 − n が入る。2 ティック後に隣へ知らせる。プレイヤーが動かしたときは 15 ティック後。【確認:S `ThrottleLeverBlockEntity` 50–66 行、80–89 行、104–109 行】
- 【実測】レバーは隣り合うブロックを全部動かす。バーナーの隣に別の用のレバーを置かない。

---

## 10. そのほか、操縦に使えそうなもの

行番号はすべて **S**(Simulated)のもの。この節の 11 個は補助の調査で読み、速度センサー・高度センサー・旋回ベアリングの式は原文で確かめ直した。応力の指定があるのは 4 つだけ(旋回ベアリング 負荷 4、ロープ巻き上げ機 負荷 4、ねじりばね 負荷 16・容量 8、舵輪 容量 16)。【確認:`index/SimBlocks` 132・176・283–284・620–621 行】

| 部品(ID) | ブロックの状態 | 何をするか(数字) | 操縦での使い道 |
| --- | --- | --- | --- |
| 旋回ベアリング `simulated:swivel_bearing` | `facing`(6 方向)、`assembled`、`powered` | `facing` の前のブロックのかたまりを**別の船として切り出し、回転継手でつなぐ**。中の歯車の回転数を毎ティック足して目標の角度にする(1 RPM = 0.3 度/ティック。上限 96 RPM)。信号なしで角度を保ち(剛性 1600・減衰 40)、信号ありで自由に回る。軸の回転は切り出した側へ 1 倍で通す | プロペラや対称帆を載せた「首」を振れる。歯車が回り始めると自分で組み上がる。ただし**船が 2 隻になる**(自動操縦・固定・読み込みの扱いは未確認) |
| 舵輪 `simulated:steering_wheel` | `facing`(水平 4 方向)、`on_floor`、`waterlogged` | 自分で回転を出す。目標の角度(±180 度まで。設定で 1〜360)と今の角度の差のぶんだけ **16 RPM**(4.8 度/ティック)で回して止まる。出力の軸は縦で、`on_floor=true` なら下の面 | 旋回ベアリングの歯車へつなぐと、舵輪の角度が首の角度になる。サーバー側からは `targetAngleToUpdate` と `startHolding()` で書ける |
| レッドストーン磁石 `simulated:redstone_magnet` | `facing`(6 方向)、`powered` | 信号の強さに比例した磁石になる(強さ 15 で磁気の大きさ √1000 ≈ 31.6)。**別の船か地面にある磁石**と 8 ブロック未満で引き合う・押し合う。同じ軸で向かい合うと、力 = 両方の磁気の積 ÷ 距離³(距離 1 で最大 1000) | 飛行そのものには使えない(相手の磁石が要る)。着地の位置合わせ用 |
| ばね `simulated:spring` | `facing`(6 方向)、`size`(`small` / `medium` / `large`) | 対になった 2 つの端の間に力を掛ける。伸び 1 ブロックあたり 72.5(`large` は 8 倍、`small` は 0.5 倍)、速度の減衰 4.5。自然長 1〜9 | 首や脚の緩衝。船の中だけでは操縦に使わない |
| ねじりばね `simulated:torsion_spring` | `facing`(6 方向)、`powered` | 力は出さない。入力の軸(`facing` の反対)が回ると、出力(`facing`)を**決めた角度(既定 90 度、1〜360)まで**同じ回転数で回して止める。入力が止まると 0 度へ戻る(`powered` なら保持) | 回転の入り切りを「決まった角度の首振り」に変える。旋回ベアリングの歯車の前に置く使い方が考えられる |
| 高度センサー `simulated:altitude_sensor` | `facing`(水平 4 方向)、`face`、`dial` | 高さを 0〜15 にする。`信号 = round(clamp((高さの割合 − 下) ÷ (上 − 下), 0, 1) × 15)`。高さの割合は建築の下限〜上限を 0〜1 にしたもの。**既定は下 0・上 1**(世界の高さ全体。1 段が約 25 ブロック)。範囲は画面で設定(コードからは `lowSignal`・`highSignal`)。全方向の隣へ出す | バーナーのレバー代わり(高いほど弱く焚く向きにするには反転が要る)。範囲を狭く設定すれば、プレイヤーなしで高さを保つ素になる |
| 速度センサー `simulated:velocity_sensor` | `facing`(6 方向)、`axis_along_first`、`powered`(0〜2) | **`facing` と直角の 1 本の軸**の向きの速さを測る。`強さ = (int) clamp(速さ ÷ 上限 × 15, −15, 15)` の絶対値。上限は 1〜50(既定 10)。0.05 以下は 0。進む向きで、出す面が入れ替わる | 速さの読み取り。秒速 2〜4 の機体なら上限を 5 前後に下げないと粗い |
| ジンバルセンサー `simulated:gimbal_sensor` | `axis`(x / z) | 船の傾きを 4 つの側面に出す(東西の面に z 軸まわり、南北の面に x 軸まわり)。`強さ = clamp((int)(14.5 × 角度 ÷ 上限 + 0.5), 0, 15)`、上限は既定 45 度。方位は出さない | 傾きを直す仕掛けの入力(左右の気嚢やスマートプロペラへ) |
| レッドストーンアキュムレーター `simulated:redstone_accumulator` | `facing`(水平 4 方向)、`powered`、`side_powered`、`powering`、`inverted` | 上げ下げの数え上げ。後ろの入力が入っている間は一定の間隔(既定 10 ティック、2〜72000)で出力を +1、横の入力で −1、両方で保持。出力 0〜15 を `facing` の向きへ | 「上げる」「下げる」の 2 つのボタンで、バーナーや変速機の値を 1 段ずつ動かす |
| レッドストーンインダクター `simulated:redstone_inductor` | `facing`(水平 4 方向)、`powered`、`inverted` | 後ろの入力の強さへ、一定の間隔(既定 10 ティック、0〜72000)で 1 段ずつ近づく。出力を `facing` の向きへ | 信号の急な変化をなだらかにする(推進の入り切りで機体がのめるのを抑える) |
| ロープ巻き上げ機 `simulated:rope_winch` | `facing`(6 方向)、`axis_along_first` | 回転でロープを繰り出す・巻き取る。速さ = 回転数 ÷ 512 ブロック/ティック(32 RPM で秒速 1.25)、上限 0.49 ブロック/ティック。届く長さの上限 40 | 飛行には使わない。荷の上げ下ろし、係留 |

根拠の行:旋回ベアリング `content/blocks/swivel_bearing/SwivelBearingBlock` 50–63・72–78・93–96 行、`SwivelBearingBlockEntity` 170–189・313–320・332–406・649–667・768–772 行、`config/server/blocks/SimBlockConfigs` 23–25 行、`config/server/physics/SimPhysics` 25–33 行。舵輪 `content/blocks/steering_wheel/SteeringWheelBlock` 58–63・128–130・211–217 行、`SteeringWheelBlockEntity` 51・74–75・96–130 行。磁石 `content/blocks/redstone_magnet/RedstoneMagnetBlock` 21–41 行、`RedstoneMagnetBlockEntity` 55–77・157–161 行、`MagnetPair` 86–107・296–314 行。ばね `content/blocks/spring/SpringBlock` 40–49・153–177 行、`SpringBlockEntity` 134–236 行。ねじりばね `content/blocks/torsion_spring/TorsionSpringBlock` 34–47 行、`TorsionSpringBlockEntity` 82–84・180–234・280–303 行。高度センサー `content/blocks/altitude_sensor/AltitudeSensorBlock` 41・68–71・96–103 行、`AltitudeSensorBlockEntity` 28–65 行。速度センサー `content/blocks/velocity_sensor/VelocitySensorBlock` 27・44–65 行、`content/blocks/util/AbstractDirectionalAxisBlock` 54–69 行、`VelocitySensorBlockEntity` 60–61・72–101・193–195 行。ジンバルセンサー `content/blocks/gimbal_sensor/GimbalSensorBlock` 31–53 行、`GimbalSensorBlockEntity` 81–109・206–215 行。アキュムレーター `content/blocks/redstone/redstone_accumulator/RedstoneAccumulatorBlock` 40–92 行、`RedstoneAccumulatorBlockEntity` 56–100 行。インダクター `content/blocks/redstone/redstone_inductor/RedstoneInductorBlock` 40・99–129 行、`RedstoneInductorBlockEntity` 52–106 行。ロープ巻き上げ機 `content/blocks/rope/rope_winch/RopeWinchBlockEntity` 58–143 行、X `create/…/KineticBlockEntity.convertToLinear` 470–472 行。

速度センサーの軸:`facing` が上下なら `axis_along_first=true` で x・`false` で z。`facing` が南北なら 真で x・偽で y。`facing` が東西なら 真で y・偽で z。

そのほか、調べるうちに見つけたもの:

- **Create のはずみ車 `create:flywheel`**:Sable が「反動輪」として扱う。回転数が変わると、角運動量の変化ぶんを船に掛ける(質量 4.0、軸まわりの慣性 2.25、回転数は 1/32 ずつなまされる)。縦の軸で置いて回転数を変えると、船の向きが変わる。【確認:X `sablecompat/…/flywheel/FlywheelBlockEntityMixin` 35–60 行、B `physics/ReactionWheelManager` 24–67 行、データ `sable/physics_block_properties/flywheel.json`】256 RPM で角運動量 約 241。【試算】効きの大きさは船の慣性と抵抗しだいで、実機では見ていない。
- **Create のケース入りファン `create:encased_fan`**:2.4 節。小型プロペラの 0.27 倍の推力。
- Offroad(車輪、掘削の頭)に、飛ぶための部品は無い。

---

## 11. 質量の決まり方

- 船の質量は、ブロックごとの質量の合計。`MassTracker.build` が船の範囲のブロックを回って足す。【確認:B `api/physics/mass/MassTracker.build` 62–120 行】
- ブロックの質量 = `PhysicsBlockPropertyHelper.getMass`:**当たり判定の形が空なら 0**。そうでなければ、そのブロックの状態に付いた性質 `sable:mass`(**既定 1.0**)。【確認:B `physics/config/block_properties/PhysicsBlockPropertyHelper.getMass` 15–21 行、`PhysicsBlockPropertyTypes` 20 行、`physics/chunk/VoxelNeighborhoodState` 28–46 行・69–71 行】
- 性質はデータ(`data/<名前空間>/physics_block_properties/*.json`)から来る。1 つの定義が「選ぶ相手(ブロックの ID かタグ)・優先度(既定 1000)・性質・状態ごとの上書き」を持つ。優先度の小さい順に当てていき、あとから当てたものが勝つ。【確認:B `PhysicsBlockPropertiesDefinition` 15–34 行、`PhysicsBlockPropertiesDefinitionLoader` 48–63 行・65–98 行】
- 質量を決めている定義(すべて Sable の jar):`#sable:super_light` 0.25(優先度 1001 で最後に当たる)、`#sable:light` 0.5、`#sable:normal` 1.0、`#sable:heavy` 2.0、`#sable:super_heavy` 4.0、`#sable:weightless` 0、`minecraft:bedrock` 1000、`create:flywheel` 4.0、`#sable:pistons` 1.0。Simulated が `swivel_bearing`・`docking_connector` を個別に定義。Aeronautics は質量の定義を持たず、気嚢をタグ `#sable:super_light` に入れているだけ。【確認:データ】
- タグの中身は 1.6 節の表のとおり。どのタグにも入らないブロックは 1.0。
- 重心は、ブロックの当たり判定の形の中心で数える(形が 1 ブロックいっぱいなら中心)。【確認:B `MassTracker` 26–48 行】
- 船が 2 つに分かれる判定:空気でないブロックが、面か**辺**で隣り合っていればつながっている(角だけの接触は不可)。【確認:B `sublevel/plot/heat/SubLevelHeatMapManager` 37–56 行、269–272 行】

---

## 12. 未確認のこと

| 項目 | 何が分からないか |
| --- | --- |
| 全体の抵抗 0.09 の使われ方 | ネイティブ側。直進は「0.09 × 質量 × 速さ」で実測と合うが、回転への掛かり方は分からない |
| 回る帆(軸受けの装置)と船のブロックの当たり | 読んでいない。帆の回る範囲を空けずに置いたときの挙動 |
| バーナーのすぐ上が気密ブロックのとき | 光線の始点が相手の面の上になる。気嚢ができるかどうか |
| 角と縁を省いた気嚢 | 判定のコードからは閉じるが、実機で組んだことはない |
| `ceiling=true` のスマートプロペラの気流の補正 | コードからは逆向きに働くと読めるが、実機では見ていない |
| 軸受けを組み上げた帆の質量の数え方の細部 | 船の質量に足されることは確認。重心への効き方は読んでいない |
| Create の帆の「揚力」の実際の効き | 式は確認。向きの決まり(いつも `facing` の向きへ押す)が実機でどう見えるかは見ていない |
| はずみ車で向きを変える効き | 仕組みは確認。大きさは試算だけ |
| 左右に分けた気嚢で傾きを直す | 複数の気嚢を持てることは確認。操縦として成り立つかは見ていない |
| 同じ優先度の質量の定義が 2 つ当たるブロック | 当たる順が決まらない。今回の部品には該当なし |
| その場で回る速さの式のずれ | 実測は式より 1〜2.5 割遅い。足りない減衰の出どころ(全体の抵抗の回転ぶんか、ほかか) |
| 旋回ベアリングで首を振る機体 | 船が 2 隻になる。空路 MOD の自動操縦・固定・読み込みの仕組みがそのまま効くか |
| 舵輪から旋回ベアリングまでの角度の比 | 途中の歯車の比と、決まった角度で止める仕組みの細部 |
| センサーが信号を出す面の向き | 速度センサー・ジンバルセンサーは「どの面へ出すか」の条件をコードで確認したが、実機でどちら側の面になるかは見ていない |
| 高度センサーの範囲の設定 | 下と上を逆にしたとき、最小の幅 |
| Simulated のセンサー類・磁石などの質量 | どのタグにも入らないので 1.0 と読めるが、当たり判定の形が空でないことは確かめていない |

---

## 13. 補い: 試験場で確かめた値と、空路 MOD に入れるときの扱い

上の表は、コードを読んだ結果です。ここは、試験場(本物のサーバー)で実際に測った値と、今回の設計で気をつけることです。

### 13.1 式と試験場の一致(2026-10-13 に、試験場で測った)

上の「試験機 B」と同じ作り(小型プロペラ 2 基を 64 RPM、気密ブロック 135 個)に、樽 2 個と機体ブロックを足した機体(質量 64.25)を、試験場にかけた結果:

| 測ったもの | 試験場の値 | 式の値 |
| --- | --- | --- |
| つり合うレバー(バーナーの設定値 80、高さ y = 116) | 9.89 | 要る熱気 = 64.25 ÷ (1.5 × 0.809) = 52.9 m³ → レバー = 15 × 52.9 ÷ 80 = **9.9** |
| 最高の速さ(前進) | 毎秒 1.75 | 2 × 64 × 0.809 ÷ (20 × 0.809 + 0.33 × 135 × 0.809 + 0.09 × 64.25) = **1.78** |
| 63% の速さに届くまで | 約 2 秒 | 質量 ÷ 抵抗の合計 = 64.25 ÷ 58.0 = 1.1 秒(試験場は 1 秒刻みで見ている) |
| 推進を切ってから止まるまで | 3.2 ブロック、4.7 秒 | — |
| 逆に入れて止める(毎秒 1.7 から) | 1.4 ブロック、1.4 秒 | — |
| その場の旋回(左右を逆に回す。腕 1 ブロック) | 毎秒 16.6 度 | 19(上の式は 1〜2.5 割速く出る) |
| 回すのをやめてから止まるまで | 29 度、5.5 秒 | — |
| 12 ブロックの上昇(目標を一気に上げた場合) | 7 秒で届くが、行き過ぎて、落ち着くまで 53 秒 | — |

**熱気の釣り合いと、直進の速さの式は、そのまま当てにできます。** 旋回の式は、上限の目安です。

### 13.2 熱気の遅れ(上昇と、高さの保持)

- 熱気は、目標の量へゆっくり寄ります(半分まで約 6 秒、9 割まで約 19 秒)。抜けるほうは、もっと遅い。
- このため、目標の高さを一気に上げると、行き過ぎてから戻るのに時間が掛かります。自動操縦は、**上昇のとき、目標の高さを毎秒 `climbRate` ブロック(既定 1.0)ずつ上げていきます**。`climbRate` は設計の `handling` で変えられます。試験場の `climb` が、その結果です。
- 浮く余裕(容量と設定値)が大きいほど、早く上がれます。ただし、気密ブロックが増えると、遅くなり、回りにくくなります。

### 13.3 空路 MOD に入れるときの扱い(部品ごと)

| 部品 | 扱い |
| --- | --- |
| 小型プロペラ(木・安山岩)、シャフト、歯車、ギアボックス、クラッチ、逆転ギア、方向ギアシフト、アナログ変速機、ベルト・チェーン | **そのまま使える。** いちばん取り込みやすい |
| プロペラ軸受け + 帆(羽根つきプロペラ) | **使ってよい。** プレイヤーの機体にも多い、この世界らしい見た目です。ただし、組み上がった帆は「ブロック」でなくなるので、空路 MOD の整備(機体のブロックが設計どおりかを見て、欠けていれば直す仕組み)が、回っている帆を「欠けたブロック」と見ないように、こちらで手を入れる必要があります。**同じ性能なら、小型プロペラのほうが確実です。** 使うなら、report.md に、軸受けと帆の位置を書いてください。帆の回る範囲には、ほかのブロックを置かないこと(当たるかどうかは未確認) |
| スマートプロペラ(上下専用) | 置けるが、自動操縦が高さに使うのは**バーナーのレバーだけ**です。推進のレバーで上下の力を出す作りは、高さの保持とけんかするので、勧めません |
| レビタイト | 置ける。ただし、材料が特殊で、遅いときの抵抗が強い。使うなら、理由を report.md に |
| ジャイロ式の軸受け、蒸気の噴き出し口(ボイラー) | 勧めない(前者は操縦に使えない、後者は重い) |
| 舵輪、旋回ベアリング、ロープ、物理の組み立て機 | **使えない。** 機体は、分かれない 1 隻であること |
| 連結器(`simulated:docking_connector`) | **中型機・大型機は、決まった位置に置く**(FORMAT.md の「連結器」。2026-10-15 から)。それ以外の所には置かない。質量 1.0(`data/simulated/physics_block_properties/docking_connector.json`)。状態は `facing`(6 方向)・`extended`・`powered`。隣に信号があると伸び、伸びた 2 つが向きを合わせると結ばれる(結ばれると、品物と液体を受け渡せる)。設計では縮めたまま(`extended=false,powered=false`) |
| クリエイティブ専用の部品(`create:creative_motor` など) | **使えない** |
| 携帯エンジン | 1〜4 台。**燃料は、飛んでいる間、台数ぶん減り続ける**(燃料槽は共通で、2 台なら 80 分、1 台なら 160 分ぶん)。少ないほど良い。過熱(64 RPM)は使えない |

### 13.4 スロットルレバーと「切」

- スロットルレバーは `simulated:throttle_lever[face=floor|wall|ceiling,facing=north|south|east|west]`。`face=floor` は下のブロックに、`ceiling` は上のブロックに、`wall` は `facing` の**反対側**のブロックに付きます(バニラのレバーと同じ)。付く相手のブロックが要ります。
- レバーは、**6 方向の隣すべて**に信号を出し、付いているブロックを通して、その先の隣にも届きます。**意図しない部品(とくにバーナー、ほかのギアシフトやクラッチ)に触れない・届かない位置**に置いてください。試験場の `turning` で、「切」のときに回っている部品、入れたときに一緒に動いてしまう部品が分かります。
- アナログ変速機のように「信号 15 で切れる」部品を使うなら、`inverted=true` のレバー(書いた値 n が、信号 15 − n になって出る)を使うか、設計の `drive` に `"off": {"レバーの名前": 値, ...}` を書いて、「切」のときの値を決めてください(書かなければ、切は全部 0)。**駐機中も、レバーは「切」の値のままです。**

### 13.5 Create の回転の決まり(念のため)

- 回転数の上限は 256 RPM。エンジンの容量を超えると、つながっている全部が止まります(試験場の `turning` に `overstressed` が出ます)。
- 小さい歯車どうしは、軸に直角な面の上下左右の隣でかみ合い、向きが反転します。大きい歯車と小さい歯車は、その面の斜め隣でかみ合い、大きい歯車から小さい歯車へは 2 倍になります。軸が直角の大きい歯車どうしは、斜めでかみ合って、軸の向きを変えます。
- ブロックの状態の書き方は、`refs/world-ships/` の実例が、そのまま使えます(`create:gearshift[axis=z,powered=false]`、`create:clutch[axis=x,powered=false]`、`create:gearbox[axis=y]`、`create:cogwheel[axis=x]` など)。`powered` は、置くときは `false` にしてください(信号が来れば、自分で変わります)。
- クラッチは、信号が**入ると切れ**ます。逆転ギアは、信号が入ると、反対側の回転が逆になります。

### 13.6 高さによる違い

試験場は、台が y = 100、試験の高さが y = 112〜116 です(気圧 0.82〜0.81)。コロニーは、もっと高い所にも在りえます。y = 250 では気圧 0.47 で、同じ熱気が支えられる質量は 6 割、プロペラの推力も 6 割になります(抵抗も、気密ブロックとプロペラのぶんは同じ割合で減るので、最高の速さは、あまり変わりません)。**浮く余裕だけは、高い所のぶんを見込んでください**(FORMAT.md の「つり合うレバーが 9.5 以下」は、そのための線です)。

2026-11-03 から、空路 MOD は飛んでいる間、バーナーの設定値を「試験場の気圧 ÷ いまの気圧」倍にします(最大 500、気嚢の容量まで)。熱気が薄い空気のぶんだけ増えるので、レバー 1 段の効き目は高さで変わりません。級ごとの上がれる高さは、型の `thinnestAir` を 0.9 で割った気圧になる高さで、Sable の気圧の式から出します。それより高い航路は、その級には開きません。飛んでみて上がりきれなかったとき(航路より 1 ブロック以上下で、45 秒の間 0.3 ブロックも上がらない)は、その航路をその級に使わず、別の道を通るか、戻ります。
