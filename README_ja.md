# Cos Carpet

[English](README.md)

作者: tontonton0502 / リポジトリ: https://github.com/hayatonton1008-creator/Cos-Carpet

## 依存Mod
- [carpet](https://modrinth.com/mod/carpet)(必須)
- [fabric-api](https://modrinth.com/mod/fabric-api)(必須)

## ルール

### commandPlayerActionmine

`/player <名前> mine`コマンドの有効化。

- 型: `String`
- デフォルト値: `false`
- 選択肢の例: `true`、`false`、`ops`、`0`、`1`、`2`、`3`、`4`

### commandPlayerActionbuild

`/player <名前> build`コマンドの有効化。

- 型: `String`
- デフォルト値: `false`
- 選択肢の例: `true`、`false`、`ops`、`0`、`1`、`2`、`3`、`4`

### commandHat

`/hat`コマンドの有効化。essential addons からの移植。

- 型: `String`
- デフォルト値: `false`
- 選択肢の例: `true`、`false`、`ops`、`0`、`1`、`2`、`3`、`4`

### commandSit

`/sit`コマンドの有効化。PCA からの移植。

- 型: `String`
- デフォルト値: `false`
- 選択肢の例: `true`、`false`、`ops`、`0`、`1`、`2`、`3`、`4`

### commandEnderChest

`/EnderChest(/ec)`コマンドの有効化。

- 型: `String`
- デフォルト値: `false`
- 選択肢の例: `true`、`false`、`ops`、`0`、`1`、`2`、`3`、`4`

### commandCraftingTable

`/CraftingTable(/ct)`コマンドの有効化。
- 型: `String`
- デフォルト値: `false`
- 選択肢の例: `true`、`false`、`ops`、`0`、`1`、`2`、`3`、`4`

### netherPortalInEnd

> trueの場合、エンドで、オーバーワールド/ネザーと同じようにネザーポータルが機能する。

- 型: `boolean`
- デフォルト値: `false`
- 選択肢の例: `false`、`true`

### itemFrameInvisibleRename

> trueの場合、額縁に名札で「invisible」と名付けると、額縁の背景が非表示になる。

- 型: `boolean`
- デフォルト値: `false`
- 選択肢の例: `false`、`true`

### noTrialSpawnerCooldown

> trueの場合、真下に羊毛ブロックがある試練の湧き出し場は、クールダウンを短縮してすぐ起動する。

- 型: `boolean`
- デフォルト値: `false`
- 選択肢の例: `false`、`true`

### vaultUnlimitedRewards

> trueの場合、同じプレイヤーが金庫を何度でも開けられるようになる。

- 型: `boolean`
- デフォルト値: `false`
- 選択肢の例: `false`、`true`

## コマンド

### `/player <名前> mine <x1> <y1> <z1> <x2> <y2> <z2>`

`/player <名前> mine <x1> <y1> <z1> <x2> <y2> <z2>`:指定したフェイクプレイヤーが指定範囲を採掘する。

### `/player <名前> build <ファイル名> [region <リージョン名>]`

`/player <名前> build <ファイル名> [region <リージョン名>]`:指定したフェイクプレイヤーがlitematicaを建築する。

### hat

`/hat` : 頭に所持しているアイテムを装備します。不死のトーテムや空でないシュルカーボックスは装備できません。また、束縛の呪いが付いた装備すでにしている場合も同様です。

### sit

`/sit` : 座ります。
