# Cos Carpet

[日本語](README_ja.md)

## Dependencies
- [carpet](https://modrinth.com/mod/carpet) (required)
- [fabric-api](https://modrinth.com/mod/fabric-api) (required)

## Rules

### commandPlayerActionmine

> If true, the `/player <name> mine <x1> <y1> <z1> <x2> <y2> <z2>` subcommand is enabled. If false, nobody can use it.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### commandPlayerActionbuild

> If true, the `/player <name> build <file> [region <regionName>]` subcommand is enabled. If false, nobody can use it. It builds a schematic currently loaded in Litematica, by file name (singleplayer / same game folder only).

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### commandHat

> If true, the `/hat` command is enabled. If false, nobody can use it.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### commandSit

> If true, the `/sit` command is enabled. If false, nobody can use it.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### commandEnderchest

> If true, `/enderchest` (or `/ec`) opens your own ender chest without needing a real one.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### commandCraftingTable

> If true, `/craftingtable` (or `/ct`) opens a crafting table without needing a real one.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### netherPortalInEnd

> If true, lighting flint & steel on an obsidian frame in the End creates a working Nether portal, just like in the Overworld/Nether.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### itemFrameInvisibleRename

> If true, naming an item frame "invisible" with a name tag hides its background (the item inside stays visible).

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### noTrialSpawnerCooldown

> If true, trial spawners with a wool block directly below them skip their cooldown period (it becomes 1 tick) and can be reactivated immediately.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

### vaultUnlimitedRewards

> If true, vaults can be opened by the same player any number of times, ignoring the normal once-per-player limit.

- Type: `boolean`
- Default value: `false`
- Suggested options: `false`, `true`

## Commands

### `/player <name> mine <x1> <y1> <z1> <x2> <y2> <z2>`

> Adds a new subcommand to Carpet's existing `/player` command. The specified fake player mines out every block in the cuboid region defined by the two corner coordinates, working top layer to bottom layer; within each layer, blocks are swept in a serpentine (boustrophedon) pattern starting from whichever side is closest to the bot, rather than by raw distance, so the bot never backtracks partway through a layer. Drops are redirected straight into the bot's inventory before they'd ever appear in the world (same approach as EssentialAddons' `essentialCarefulDrop`), rather than dropping on the ground and being picked back up; anything that doesn't fit is dropped normally. Placing a block — for bridging, climbing, or sealing water/lava left behind by mining — is limited to the same whitelist as `/player build` (see below). Movement, bridging, climbing, and getting unstuck (breaking a blocking ceiling vs. rerouting around a blocking wall/fence) work exactly the same way as `/player build`, described in detail there. While mining or breaking any block, if the bot's held tool drops to 20 uses of durability left, it switches to another suitable tool in its inventory before the current one can break.

> `x`/`y`/`z` of `/player <name> mine` tab-complete to the coordinates of the block the command sender is currently looking at, not their own position: at `x` you get both the whole `x y z` triple in one go and just `x`, at `y` both `y z` and just `y`, and at `z` just `z` (the second corner works the same way).

### `/player <name> build <file> [region <regionName>]`

> Adds a new subcommand to Carpet's existing `/player` command. The specified fake player autonomously synchronizes the world to the schematic you currently have loaded in Litematica, walking to each position and working like a real survival player would. You only give it the file name — the origin, rotation and mirror come from the Litematica placement, so there are no coordinates to type.
>
> - `<file>`: file name of a schematic currently loaded in Litematica, without its folder or the `.litematic` extension (tab-completed from what Litematica has loaded).
> - `[region <regionName>]`: build only this one sub-region of the placement (tab-completed from the placement's sub-regions; ones switched off in Litematica are marked). If omitted, all sub-regions that are switched **on** in Litematica are built; a sub-region you name explicitly is built even if it is switched off. Sub-regions you moved in Litematica are built at their moved position.
>
> This reads the placement data Litematica saved in `config/litematica/` for the current world and dimension, so it needs the same machine/game folder as Litematica: it works in singleplayer and LAN but not on a remote dedicated server. It uses what Litematica last saved; if you just changed a placement and it isn't reflected, re-enter the world. Per-sub-region rotation/mirror is not supported (the whole placement's settings are used). The start message shows which file, origin and rotation were used.
>
> This is a full sync, not just placement: within each region's bounding box, missing blocks are placed, extra blocks that shouldn't be there are broken, and blocks of the wrong type are broken and replaced with the correct one. Blocks that already match are left untouched. Breaking uses survival-accurate timing based on block hardness and the bot's best available tool, same as `/player mine`, and (like mine) drops go straight into the bot's inventory instead of dropping on the ground. Missing materials stop the build immediately and are reported in chat, rather than being silently skipped. If the schematic has multiple sub-regions and no `region` is given, they're processed one at a time in file order — one region is fully finished before the next one starts. Within a region, missing/wrong blocks are placed following the schematic's own adjacency (26-direction) — expanding outward and upward from whatever's already built, rather than by raw distance — so upper floors and roofs are only queued once something below or beside them exists to build from. This greatly cuts down on bridging to disconnected high points and on breaking through a roof or wall the bot already finished, especially in buildings with very uneven heights. Extra blocks to remove are simply cleared bottom-to-top, nearest first, ahead of placement. While moving, if the bot gets stuck in place (unmoving x,z) because of a ceiling above while climbing, it clears the block using the same mining mechanic; if it's stuck for any other reason (an obstacle like a fence in front — fences have taller hitboxes than they look and can't just be jumped over), it reroutes around that spot instead of breaking it. Scaffolding used to bridge gaps or climb is limited to a small whitelist of blocks (currently Pale Moss Block and Moss Block; other mods can extend the list). Scaffolding is kept until the build is finished and then removed all at once, top to bottom, at survival speed — the bot doesn't go back down to break each one right after placing it. Only if more than 16 pile up does it remove the ones it can already reach from where it stands. A scaffolding block the bot is standing on is left in place if breaking it would mean a fall of more than 3 blocks, and this is reported in the completion message. The bot never breaks a block that is (or was placed as) part of the schematic: if the block overhead belongs to the build, it reroutes instead. When it can't get past an obstacle — an unbreakable or very slow block overhead, or a spot it keeps getting stuck at after several reroutes — it gives up on that spot and moves on (build retries it once in its final check) instead of aiming at the obstacle indefinitely. For tall or steep builds the bot can now climb the way a player does: if no spot it can walk to reaches the next block, it jumps and stacks scaffolding under its feet until it does, works from there, and breaks its own scaffolding block by block to climb back down (it only does this when walking is not an option, and spots that are still waiting for a schematic block are never used as footing). Right after placing a block it also hops up onto it when that makes the next block reachable — e.g. on staircases and terraced roofs — which needs no scaffolding at all. Blocks that have no item form and so can't be placed from an inventory (piston heads, water, fire, portals, …) are skipped with a note at the start instead of stopping the build, and extended pistons are placed retracted. Names with spaces or non-ASCII characters (files and regions) are tab-completed in quotes so they can be entered as-is. Just before announcing completion, the bot re-checks every position it touched against the schematic one more time and fixes anything that's still off (up to 3 such passes), so the completion message reflects the actual final state rather than just "ran out of tasks". While breaking any block, if the bot's held tool drops to 20 uses of durability left, it switches to another suitable tool in its inventory before the current one can break.

> Requires the [`commandPlayerActionbuild`](#commandplayeractionbuild) rule to be enabled.

### `/hat`

> Wear the item in your hand as a hat.

### `/sit`

> Sit down on the block you're standing on.
