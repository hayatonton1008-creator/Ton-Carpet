# Ton Carpet

A Carpet add-on for Minecraft 1.21.11 (Fabric).

Bots can mine and build on their own. Plus a few handy commands and rules.

- - -

<p align="left">
    <a href="https://github.com/hayatonton1008-creator/Ton-Carpet/blob/main/LICENSE">
        <img src="https://img.shields.io/github/license/hayatonton1008-creator/Ton-Carpet.svg" alt="License">
    </a>
    <a href="https://github.com/hayatonton1008-creator/Ton-Carpet/issues">
        <img src="https://img.shields.io/github/issues/hayatonton1008-creator/Ton-Carpet.svg" alt="Issues">
    </a>
</p>

## Features

- `/player <name> mine`: a bot mines an area.
- `/player <name> build`: a bot builds a Litematica schematic.
- `/hat`, `/sit`, `/enderChest`, `/craftingtable`.
- Small rules for the End portal, item frames, trial spawners and vaults.

## Requirements

- Fabric Loader, Fabric API, Carpet
- Litematica (only for `build`)

## All rules

<details>
<summary>English</summary>

## Rules

### commandHat

Enable `/hat` command. Ported from essential addons.

- Type: `String`
- Default value: `ops`
- Allowed options: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- Categories: `TonCarpet`

### commandSit

Enable `/sit` command. Ported from PCA.

- Type: `String`
- Default value: `ops`
- Allowed options: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- Categories: `TonCarpet`

### commandEnderChest

Enable `/enderChest` and `/ec` commands. Opens your ender chest.

- Type: `String`
- Default value: `ops`
- Allowed options: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- Categories: `TonCarpet`

### commandCraftingTable

Enable `/craftingtable` and `/ct` commands. Opens a crafting table.

- Type: `String`
- Default value: `ops`
- Allowed options: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- Categories: `TonCarpet`

### commandPlayerActionmine

Enable `/player <name> mine`.

> <font size="3" color="#1f6feb">NOTE</font><br>
> Only use fake players.

- Type: `String`
- Default value: `ops`
- Allowed options: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- Categories: `TonCarpet`

### commandPlayerActionbuild

Enable `/player <name> build`.

> <font size="3" color="#1f6feb">NOTE</font><br>
> Only use fake players.

- Type: `String`
- Default value: `ops`
- Allowed options: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- Categories: `TonCarpet`

### netherPortalInEnd

Flint and steel on an obsidian frame makes a Nether portal in the End.

- Type: `boolean`
- Default value: `false`
- Categories: `TonCarpet`

### itemFrameInvisibleRename

An item frame named `invisible` hides its background.

- Type: `boolean`
- Default value: `false`
- Categories: `TonCarpet`

### noTrialSpawnerCooldown

A trial spawner with wool right below it has no cooldown.

- Type: `boolean`
- Default value: `false`
- Categories: `TonCarpet`

### vaultUnlimitedRewards

The same player can open a vault any number of times.

- Type: `boolean`
- Default value: `false`
- Categories: `TonCarpet`

## Command

### hat

`/hat` : Put the item in your hand on your head. Totem of undying and non-empty shulker boxes cannot be equipped. Not usable with Curse of Binding on your head.

### sit

`/sit` : Sit down. Sneak to stand up.

### enderChest

`/enderChest` or `/ec` : Open your ender chest.

### craftingtable

`/craftingtable` or `/ct` : Open a crafting table.

### mine

`/player <name> mine <x1> <y1> <z1> <x2> <y2> <z2>` : The bot mines the area.

- Walks to the blocks. Breaks them at survival speed.
- Picks the best tool. Eats when hungry.
- Fills water and lava. Builds bridges and pillars, then removes them.
- Max 200,000 blocks.

### build

`/player <name> build <file> [region <regionName>]` : The bot builds a schematic loaded in Litematica.

- `<file>` is the schematic file name (no folder, no extension).
- Without `region`, all enabled sub regions are built in order.
- Singleplayer only.

### Bot features

- Swaps a tool that is about to break, like Tweakeroo's `tweakSwapAlmostBrokenTools`.
- Mined drops go straight into the bot's inventory.

### For other mods

All in `com.toncarpet.mine`.

- `ScaffoldBlocks.allow(Block)` : add a scaffold block.
- `BotFeatures.setToolSwapEnabled(boolean)` : tool swap on/off.
- `BotFeatures.setDurabilityThreshold(int)` : swap threshold (5-10000).
- `BotFeatures.setCarefulBreakEnabled(boolean)` : instant pickup on/off.

</details>

<details>
<summary>日本語</summary>

## ルール

### commandHat

`/hat`コマンドの有効化。essential addons からの移植。

- 型式: `String`
- 既定値: `ops`
- 使用可能な値: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- 分類: `TonCarpet`

### commandSit

`/sit`コマンドの有効化。PCA からの移植。

- 型式: `String`
- 既定値: `ops`
- 使用可能な値: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- 分類: `TonCarpet`

### commandEnderChest

`/enderChest`と`/ec`コマンドの有効化。エンダーチェストを開く。

- 型式: `String`
- 既定値: `ops`
- 使用可能な値: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- 分類: `TonCarpet`

### commandCraftingTable

`/craftingtable`と`/ct`コマンドの有効化。作業台を開く。

- 型式: `String`
- 既定値: `ops`
- 使用可能な値: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- 分類: `TonCarpet`

### commandPlayerActionmine

`/player <name> mine`の有効化。

> <font size="3" color="#1f6feb">NOTE</font><br>
> fakePlayer でのみ利用可能です。

- 型式: `String`
- 既定値: `ops`
- 使用可能な値: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- 分類: `TonCarpet`

### commandPlayerActionbuild

`/player <name> build`の有効化。

> <font size="3" color="#1f6feb">NOTE</font><br>
> fakePlayer でのみ利用可能です。

- 型式: `String`
- 既定値: `ops`
- 使用可能な値: `true`, `false`, `ops`, `0`, `1`, `2`, `3`, `4`
- 分類: `TonCarpet`

### netherPortalInEnd

エンドで、黒曜石の枠にフリント&スチールで着火するとネザーポータルができる。

- 型式: `boolean`
- 既定値: `false`
- 分類: `TonCarpet`

### itemFrameInvisibleRename

`invisible`と名付けた額縁は、背景が非表示になる。

- 型式: `boolean`
- 既定値: `false`
- 分類: `TonCarpet`

### noTrialSpawnerCooldown

真下に羊毛がある試練の湧き出し場は、クールダウンが無くなる。

- 型式: `boolean`
- 既定値: `false`
- 分類: `TonCarpet`

### vaultUnlimitedRewards

同じプレイヤーが金庫を何度でも開けられる。

- 型式: `boolean`
- 既定値: `false`
- 分類: `TonCarpet`

## コマンド

### hat

`/hat` : 手に持ったアイテムを頭に装備します。不死のトーテムと、空でないシュルカーボックスは装備できません。頭に束縛の呪いの装備がある場合も使えません。

### sit

`/sit` : 座ります。スニークで立ちます。

### enderChest

`/enderChest` または `/ec` : エンダーチェストを開きます。

### craftingtable

`/craftingtable` または `/ct` : 作業台を開きます。

### mine

`/player <name> mine <x1> <y1> <z1> <x2> <y2> <z2>` : botが範囲を採掘します。

- ブロックまで歩き、サバイバルと同じ速さで壊します。
- 最適な道具を選び、空腹なら食事をします。
- 水と溶岩を埋め、橋と柱を作り、あとで撤去します。
- 最大20万ブロックです。

### build

`/player <name> build <file> [region <regionName>]` : botがLitematicaで読み込み中のスキーマティックを建築します。

- `<file>`はスキーマティックのファイル名です(フォルダと拡張子は不要)。
- `region`を省略すると、オンの子リージョンを順に建築します。
- シングルプレイ専用です。

### botの機能

- 壊れそうな道具を持ち替えます。Tweakeroo の `tweakSwapAlmostBrokenTools` と同じです。
- 採掘したドロップは、すぐbotの持ち物に入ります。

### 他のMOD向け

すべて `com.toncarpet.mine` にあります。

- `ScaffoldBlocks.allow(Block)` : 仮ブロックを追加。
- `BotFeatures.setToolSwapEnabled(boolean)` : 道具の持ち替えのON/OFF。
- `BotFeatures.setDurabilityThreshold(int)` : 持ち替えのしきい値(5〜10000)。
- `BotFeatures.setCarefulBreakEnabled(boolean)` : すぐ回収のON/OFF。

</details>
