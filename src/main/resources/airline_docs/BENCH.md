# 試験場の使い方と、結果の読み方

試験場は、本物の専用サーバー(Minecraft 1.21.1、Create、Create Aeronautics、Sable、MineColonies、空路 MOD)です。送った設計どおりの機体を 1 機つくり、**機体のスロットルレバーだけ**で動かして(空路 MOD の自動操縦と同じ手段)、測った値を JSON で返します。機体を外から押したり、中身を書き換えたりは、していません。

```
python tools/bench.py run <設計.json> --mode static      約 1 分
python tools/bench.py run <設計.json> --mode raw         約 1 分
python tools/bench.py run <設計.json> --mode all         約 2 分(どれも早回し。2026-10-15 から)
python tools/bench.py wait <札>                          まだ終わっていない試験を待つ
python tools/bench.py turning <札> [操作 ...]            回った部品の一覧(1 部品 1 行)。操作を書けば、その操作だけ
python tools/bench.py list                               これまでの札
```

- `run` は、設計を窓口に預けて、100 秒まで結果を待ちます。終わっていなければ「NOT DONE YET」と札(ticket)を出すので、`wait <札>` を、結果が出るまで繰り返してください(1 回の `wait` も 100 秒まで)。
- 試験は 1 つずつです。ほかの試験(こちらの別の作業)と順番待ちになることがあり、そのぶん遅くなります。
- 回数の上限は、依頼文(`brief.md`)に書いた数です。
- 結果の全部は `queue/<札>.report.json` に残ります。画面に出るのは、長い所(回った部品の一覧 `turning` と、上昇・速さの 2 つの曲線)を除いたものです。回った部品は `turning <札>` で見てください。送った設計の写しは `queue/<札>.design.json`。

## 何をするか

| モード | すること |
| --- | --- |
| `static` | ① 台(y = 100)の上に機体を組む。② 質量・重心・気嚢を読む。③ **台に固定したまま**エンジンを回し、操作を 1 つずつ 4 秒入れて、回る部品 1 つずつの回転数と、プロペラの推力を読む(機体は動かない) |
| `raw` | static に続けて、④ 固定を外して 12 ブロック上がり、高さを保つ。⑤ `ahead` を速さが落ち着くまで。⑥ 切って、止まるまで。⑦ `astern`。⑧ 最高の速さから `astern` で止める。⑨ `right`、切、`left`、切。⑩ `ahead_slow`、`right_slow`(設計にあれば)。そして、測った値から、自動操縦の数字(`handling`)を決める |
| `all` | raw に続けて、⑪ 進みながら `ahead_right`・`ahead_left`。⑫ **空路 MOD の自動操縦そのもの**で、80 ブロックの直進と停止、直角に曲がって 40 ブロック、90 度の旋回、3 ブロック先への位置合わせ |

## 結果の読み方

`"ok": false` のときは、`"error"` にわけがあります(設計が読めない、置けないブロックがある、気嚢が認められない、上がれない、など)。そこまでに測れた値は、入っています。`"notes"` は、試験場が気づいたこと(「この操作では、どのプロペラも押していない」「この操作は、逆に回る」など)。**まず `notes` を読んでください。**

単位は、長さがブロック、速さがブロック毎秒、角度が度、時間が秒です。

| 項目 | 中身 |
| --- | --- |
| `weight` | `mass` 質量。`centreTowardNose`・`centreUp`・`centreToRight` 重心(機体ブロックの中心から、機首のほうへ・上へ・右舷のほうへ)。前後と左右は ±0.3 以内に |
| `balloons` | バーナーごとに、気嚢が認められたか(`seesBalloon`)、容量(`room`、m³)、設定値(`dial`) |
| `liftEstimate` | この高さで要る熱気のおよその量と、レバー全開で入る量(容量と設定値の小さいほう) |
| `turning` | **操作ごとの、回る部品の一覧**(画面には出ない。`python tools/bench.py turning <札> ahead` のように見る)。`at` 位置(x y z)、`block`、`rpm`(符号つき)、`overstressed`(エンジンの容量を超えた)、`thrust`(プロペラだけ)。**`thrust` が正なら、そのプロペラは機体を `facing` の反対向きへ押し、負なら `facing` の向きへ押します。** 機械のつなぎ間違い(回っていない、逆に回る、切っても回る)は、ここで分かります |
| `turningSummary` | 操作ごとの、回っている部品の数と、推力の大きさの合計 |
| `climb` | `secondsUntilItFliesOff` 12 ブロック上の高さの近く(下 0.5〜上 1.75 ブロック、上下の速さ 毎秒 0.6 未満)に入るまでの秒数。**空路 MOD は、ここで航路を飛び始めます**(行き過ぎが抜けるのは、飛びながら)。`secondsUntilThere` 誤差 0.5・上下の速さ 0.2 以内に落ち着くまで(以前は、ここまで待ってから飛び始めていました。**2026-10-13 15:45 ごろに、自動操縦の決まりを上のとおりに変えました**)。`overshoot` 行き過ぎ(**1.75 を超えると、航路の管の天井に近すぎるので、落ちてくるまで待たされます**)。`targetRaisedPerSecond` 目標を上げた速さ(設計の `handling.climbRate`)。`secondsHeightLever` 1 秒ごとの [秒, 上がった高さ, バーナーのレバー] |
| `hover` | `hoverLever` 高さを保っているときのレバーの平均(**9.5 以下に**)。`meanHeightError`・`heightSwing` 高さの誤差と揺れ。`meanNoseUp`・`noseUpFrom`・`noseUpTo` その 10 秒の間の、機首の上がり(度。**機首が下がっていれば負**。重心が、浮く力の中心より前にあると、機首が下がる: 重心の前後のずれ 0.10 で 4.5 度、0.13 で 5.7 度だった。**2 度以内を目指す**)。`hotAir`・`room` そのときの熱気と容量。`benchAir` そこの気圧。`thinnestAir` バーナーを全開にし、設定値も上げきったとき(500 か容量の小さいほう)に支えられる一番薄い気圧(`benchAir` × `hotAir` ÷ その熱気)。この 2 つは `handling` にも書かれ、型のファイルに要ります |
| `ahead` | `topSpeed` 最高の速さ。`secondsTo63Percent`・`secondsTo90Percent` 立ち上がり。`largestNoseUpOrDown` 前後の傾きの最大、`noseUpAtTheEnd` 巡航中の傾き(正が機首上げ)。`largestHeightError` 高さの乱れ。`turnedMeanwhile` 勝手に回った角度(左右のつり合い)。`secondsSpeedNoseUp` 1 秒ごとの [秒, 速さ, 機首上げ] |
| `coast` | 推進を切ってから止まるまでの距離と時間 |
| `astern` | 後進の最高の速さ |
| `brake` | 最高の速さから `astern` を入れて、前への動きが止まるまでの距離と時間、そのときの前後の傾き |
| `right` / `left` | `degreesPerSecond` その場の旋回の速さ(右が正)。`movedMeanwhile` その間に動いてしまった距離(小さいほど「その場」)。`largestTilt` 傾き。`turnsOnAfterDrivesOff`・`secondsUntilItStops` 切ってから回り続ける角度と時間 |
| `aheadSlow` / `rightSlow` | 弱い操作の、最高の速さ・回る速さ(設計に `_slow` があるときだけ) |
| `aheadRight` / `aheadLeft` | 進みながらの旋回の速さ(右が正)と、そのときの前進の速さ |
| `gentlePush` | **巡航の速さで、`ahead_right` を 6 ティックだけ押して、`ahead` に戻したとき**(2026-10-13 から。速い機体の自動操縦は、これで向きを保ちます)。`turnedInTheEnd` 最後に回った角度(右が正)、`turnedDuringThePush` 押している間に回った角度、`asDegreesPerSecond` 押した長さあたりに直した回る速さ(= 最後に回った角度 ÷ 6 × 20。**大きいほど、短い押しで向きが直る**)、`secondsTheTurningCarriesOn` 押し終わったあと、回り続ける長さ(秒。**短いほど、収まりが早い**)、`speedAhead` そのときの前進の速さ |
| `handling` | 測った値から決めた、自動操縦の数字。空路 MOD に入れるとき、このまま使います |
| `autopilot.leg` | 80 ブロックの直進。`seconds` 掛かった時間、`stoppedThisFarFromThePoint` 止まった位置の誤差(**1.5 以内**)、`wentPastThePointBy` 行き過ぎ、`largestDistanceFromTheLine` 線からの横ずれ、`largestTilt` 傾き、`furtherTries` 寄せ直した回数、`cameToRest` 止まれたか(1/0) |
| `autopilot.corner` | 止まった所から、**右へ直角に曲がって 40 ブロック**(航路の途中の点と同じ)。`seconds` 全部の時間、`secondsUntilUnderWay` 向きを変えて進み始めるまで、`largestDistanceFromTheLine` 新しい線からの横ずれ(**2.0 以内**。航路の管の余裕)、`stoppedThisFarFromThePoint`(1.5 以内)、傾き、止まれたか |
| `autopilot.turn` | 右へ 90 度。掛かった時間、向きの誤差、その間に動いた距離、収まったか |
| `autopilot.place` | 向きを保ったまま、3 ブロック先の点の真上へ。掛かった時間、誤差(**0.2 以内で合格**)、合わせられたか(`placed`) |
| `leverWrites` | レバーを書いた回数 |

## 比べる相手(こかぜ・小型機。採用した 013 を、同じ試験場で測った値。全部は `refs/kokaze/013_design_all.report.json`)

| 項目 | こかぜ(013) |
| --- | --- |
| `weight.mass` | 63.5 |
| `hover.hoverLever` / `heightSwing` | 6.5 / 0.37 |
| `ahead.topSpeed` / `largestNoseUpOrDown` | 7.46 / 17.9 |
| `brake.largestNoseUpOrDown` | 16.6 |
| `right.degreesPerSecond` | 58.7 |
| `aheadSlow.topSpeed` / `rightSlow.degreesPerSecond` | 1.24 / 18.4 |
| `aheadRight.degreesPerSecond` / `gentlePush.asDegreesPerSecond` | 12.4 / 12.3 |
| `autopilot.leg.seconds`(80 ブロック) / `stoppedThisFarFromThePoint` | 21.1 / 0.88 |
| `autopilot.corner.seconds` | 21.9 |
| `autopilot.turn.seconds`(90 度) | 8.15 |
| `autopilot.place.seconds` | 7.05 |
| 荷室の樽 | 2 |

## 試験場が見ていないこと

- 市民が実際に歩いて乗り降りできるか(`tools/check.py` が、形だけ確かめます。本当の乗り降りは、納めていただいたあとに、こちらで確かめます)。
- 降下と着陸、台の上での向き合わせ(自動操縦の旋回と位置合わせの組み合わせなので、`autopilot.turn`・`autopilot.place` が目安になります)。
- 高い所(y = 200 以上)での浮く余裕(`hover.hoverLever` が 9.5 以下なら、足りる見込みです)。上がれる高さは、`hover.thinnestAir`(全開で支えられる一番薄い気圧。その 1 割増しの気圧の高さが上限)から計算で出します。実際に上限まで上がってはいません。
- 建築士が 1 ブロックずつ建てられるか。
