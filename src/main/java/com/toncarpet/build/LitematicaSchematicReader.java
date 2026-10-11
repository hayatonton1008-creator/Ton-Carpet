package com.toncarpet.build;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Litematica形式(.litematic)のスキーマティックファイルを読み込むリーダー。
 *
 * Litematica本体(Mod)には依存しない ―― NBTの構造さえ分かれば読めるため、
 * サーバー上にLitematicaが入っていなくても .litematic ファイルをそのまま解釈できる。
 *
 * ファイル構造(このリーダーが実際に読む部分):
 *   Regions: Compound
 *     <リージョン名>: Compound
 *       Position: Compound { x, y, z : Int }   ... このリージョンの原点(Sizeが負の場合、原点は「遠い側の角」になる)
 *       Size:     Compound { x, y, z : Int }   ... 各軸の大きさ。符号付き(負の場合は原点から負方向へ広がる)
 *       BlockStatePalette: List<Compound{ Name: String, Properties: Compound(省略可) }>
 *       BlockStates: LongArray                 ... パレット番号を「隙間なく」詰め込んだビット配列
 *
 * BlockStatesのビットパッキングは、バニラ1.16以降のBitStorage(1個のlongをまたがない詰め方)とは異なり、
 * 1つの値が2つのlongにまたがることを許す「隙間なし連続ビットパック」方式(Litematica独自)。
 * 1エントリのビット数は max(2, ceil(log2(パレット数))) で、パレット数が1でも最低2ビット確保する。
 */
public final class LitematicaSchematicReader {

    private LitematicaSchematicReader() {
    }

    /**
     * タブ補完用に、ファイルの中身(パレットやブロックデータ)は読まず、子リージョン(サブリージョン)の
     * 名前一覧だけを軽量に読み取る。ファイル内での並び順のまま返す。
     */
    public static synchronized List<String> listRegionNames(Path file) throws IOException {
        // タブ補完は1文字入力するたびに呼ばれ、ファイルは大きい(圧縮されたNBTを丸ごと展開する)ことがあるので、
        // ファイルが変わっていない(更新時刻とサイズが同じ)間は、前回の結果を返す
        long modified = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        if (cachedNamesList != null && file.equals(cachedNamesFile) && modified == cachedNamesModified && size == cachedNamesSize) {
            return cachedNamesList;
        }
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag regionsTag = root.getCompoundOrEmpty("Regions");
        List<String> names = List.copyOf(regionsTag.keySet());
        cachedNamesFile = file;
        cachedNamesModified = modified;
        cachedNamesSize = size;
        cachedNamesList = names;
        return names;
    }

    private static Path cachedNamesFile;
    private static long cachedNamesModified = Long.MIN_VALUE;
    private static long cachedNamesSize = -1;
    private static List<String> cachedNamesList;

    /** 1個のブロックの、配置後(回転・原点オフセット適用済み)のワールド座標とBlockState */
    public record PlacementBlock(BlockPos worldPos, BlockState state) {
    }

    /** 1つの子リージョン(サブリージョン)分の結果: 配置すべきブロック一覧と、そのリージョンが占めるワールド座標上のバウンディングボックス。 */
    public record RegionResult(String name, List<PlacementBlock> blocks, BlockPos boundsMin, BlockPos boundsMax) {
    }

    /**
     * .litematic ファイルを読み込み、originを基準にrotation(Y軸回転)を適用した上で、
     * 子リージョン(サブリージョン)ごとに分けたまま結果を返す(空気ブロックは含まない)。
     * ファイル内でのリージョンの並び順のまま返すため、呼び出し側はリージョンごとに順番へ処理できる
     * (例: リージョンAを完全に作り終えてからリージョンBに取り掛かる、といった建築順序)。
     * 各リージョンのバウンディングボックスは、そのリージョン範囲内の「余計なブロック(除去対象)」を
     * 呼び出し側で判定するために使う。
     */
    public static List<RegionResult> readForPlacement(Path file, HolderLookup.Provider registries,
                                                        BlockPos origin, Rotation rotation) throws IOException {
        return readForPlacement(file, registries, origin, rotation, Mirror.NONE, Map.of());
    }

    /**
     * 上のメソッドに加えて、ミラー(Litematicaの配置と同じく、回転の前に原点を基準として適用する)と、
     * 子リージョンごとの位置の上書き(Litematicaで子リージョンを動かしていた場合に、その位置を使う。
     * 値は配置の原点からの相対座標で、ファイル内のPositionの代わりに使う)を指定できる。
     */
    public static List<RegionResult> readForPlacement(Path file, HolderLookup.Provider registries,
                                                        BlockPos origin, Rotation rotation,
                                                        Mirror mirror, Map<String, BlockPos> positionOverrides) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag regionsTag = root.getCompoundOrEmpty("Regions");
        if (regionsTag.isEmpty()) {
            throw new IOException("Regionsが見つかりませんでした(壊れたファイル、またはLitematica形式ではない可能性があります)");
        }

        // parseForBlockが要求するのは HolderLookup<Block> であって HolderLookup.Provider そのものではないため、
        // ここでブロックレジストリのlookupを1回だけ解決し、以降はそれを使い回す
        HolderLookup<Block> blockLookup = registries.lookupOrThrow(Registries.BLOCK);

        List<RegionResult> results = new ArrayList<>();
        for (String regionName : regionsTag.keySet()) {
            CompoundTag regionTag = regionsTag.getCompoundOrEmpty(regionName);
            RegionResult region = readRegion(regionName, regionTag, blockLookup, origin, rotation, mirror,
                    positionOverrides.get(regionName));
            if (region != null) {
                results.add(region);
            }
        }

        if (results.isEmpty()) {
            throw new IOException("有効なリージョンが見つかりませんでした(すべて空、またはサイズ0)");
        }
        return results;
    }

    private static RegionResult readRegion(String regionName, CompoundTag regionTag, HolderLookup<Block> blockLookup,
                                            BlockPos origin, Rotation rotation, Mirror mirror, BlockPos positionOverride) {
        CompoundTag posTag = regionTag.getCompoundOrEmpty("Position");
        CompoundTag sizeTag = regionTag.getCompoundOrEmpty("Size");
        int px = posTag.getIntOr("x", 0);
        int py = posTag.getIntOr("y", 0);
        int pz = posTag.getIntOr("z", 0);
        if (positionOverride != null) {
            px = positionOverride.getX();
            py = positionOverride.getY();
            pz = positionOverride.getZ();
        }
        int sx = sizeTag.getIntOr("x", 0);
        int sy = sizeTag.getIntOr("y", 0);
        int sz = sizeTag.getIntOr("z", 0);

        int sizeX = Math.abs(sx);
        int sizeY = Math.abs(sy);
        int sizeZ = Math.abs(sz);
        if (sizeX == 0 || sizeY == 0 || sizeZ == 0) {
            return null; // 空のリージョン
        }

        // Sizeが負の軸は、Positionが「遠い側の角」を指しているため、
        // そちら側へ(size-1)戻した位置が実際の最小コーナー(ローカル座標(0,0,0)に対応する点)になる
        BlockPos minCorner = new BlockPos(
                sx < 0 ? px + sx + 1 : px,
                sy < 0 ? py + sy + 1 : py,
                sz < 0 ? pz + sz + 1 : pz
        );

        List<BlockState> palette = readPalette(regionTag.getListOrEmpty("BlockStatePalette"), blockLookup);
        if (palette.isEmpty()) {
            return null;
        }
        long[] packed = regionTag.getLongArray("BlockStates").orElse(new long[0]);
        int bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(0, palette.size() - 1)));

        // パレットの各エントリに対する「ミラー・回転を適用した後の状態」と「空気かどうか」を、最初に1回だけ計算しておく
        // (セルごとに state.mirror / rotate を呼び直さない。大きなスキーマティックでは数十万回の差になる)
        BlockState[] transformedPalette = new BlockState[palette.size()];
        boolean[] airPalette = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            BlockState state = palette.get(i);
            airPalette[i] = state.isAir();
            if (!airPalette[i]) {
                transformedPalette[i] = (mirror == Mirror.NONE ? state : state.mirror(mirror)).rotate(rotation);
            }
        }

        // ミラーと(Y軸周りの)回転は、座標軸に沿った変換なので、リージョンが占める範囲は「8つの角を変換した点」の
        // 最小・最大で正確に求まる。セルごとに範囲を更新する必要は無い(空気セルも含めた範囲=呼び出し側が
        // 「範囲内の余計なブロック」を判定するために使う)。
        int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cz = 0; cz <= 1; cz++) {
                    int rx = minCorner.getX() + (cx == 0 ? 0 : sizeX - 1);
                    int ry = minCorner.getY() + (cy == 0 ? 0 : sizeY - 1);
                    int rz = minCorner.getZ() + (cz == 0 ? 0 : sizeZ - 1);
                    int[] t = transformXZ(rx, rz, mirror, rotation);
                    int wx = origin.getX() + t[0];
                    int wy = origin.getY() + ry;
                    int wz = origin.getZ() + t[1];
                    bounds[0] = Math.min(bounds[0], wx);
                    bounds[1] = Math.min(bounds[1], wy);
                    bounds[2] = Math.min(bounds[2], wz);
                    bounds[3] = Math.max(bounds[3], wx);
                    bounds[4] = Math.max(bounds[4], wy);
                    bounds[5] = Math.max(bounds[5], wz);
                }
            }
        }

        // 変換は1対1の対応なので、同じワールド座標に2つのセルが重なることは無い(重複の解決は不要)。
        // 空気セルは配置対象に含めないので、BlockPosも作らない。
        List<PlacementBlock> blocks = new ArrayList<>();
        int[] xz = new int[2];
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    long flatIndex = ((long) y * sizeZ + z) * sizeX + x;
                    int paletteIndex = readPackedEntry(packed, flatIndex, bitsPerEntry);
                    if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                        continue; // 壊れたデータ: 読み取れなかった箇所は無視する
                    }
                    if (airPalette[paletteIndex]) {
                        continue; // 空気は配置対象に含めない
                    }
                    transformXZInto(minCorner.getX() + x, minCorner.getZ() + z, mirror, rotation, xz);
                    BlockPos worldPos = new BlockPos(origin.getX() + xz[0], origin.getY() + minCorner.getY() + y,
                            origin.getZ() + xz[1]);
                    blocks.add(new PlacementBlock(worldPos, transformedPalette[paletteIndex]));
                }
            }
        }
        BlockPos boundsMin = new BlockPos(bounds[0], bounds[1], bounds[2]);
        BlockPos boundsMax = new BlockPos(bounds[3], bounds[4], bounds[5]);
        return new RegionResult(regionName, blocks, boundsMin, boundsMax);
    }

    /** BlockStatePalette(Name + Properties)を、実際のBlockStateのリストへ変換する。 */
    private static List<BlockState> readPalette(ListTag paletteTag, HolderLookup<Block> blockLookup) {
        List<BlockState> result = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag entry = paletteTag.getCompoundOrEmpty(i);
            String name = entry.getStringOr("Name", "minecraft:air");
            CompoundTag properties = entry.getCompoundOrEmpty("Properties");

            StringBuilder stateString = new StringBuilder(name);
            if (!properties.isEmpty()) {
                stateString.append('[');
                boolean first = true;
                for (String key : properties.keySet()) {
                    if (!first) {
                        stateString.append(',');
                    }
                    first = false;
                    stateString.append(key).append('=').append(properties.getStringOr(key, ""));
                }
                stateString.append(']');
            }

            try {
                BlockStateParser.BlockResult parsed = BlockStateParser.parseForBlock(blockLookup, stateString.toString(), false);
                result.add(parsed.blockState());
            } catch (CommandSyntaxException e) {
                // このサーバーに存在しないブロック(バージョン差異やMod由来)は空気で代用してスキップする
                result.add(Blocks.AIR.defaultBlockState());
            }
        }
        return result;
    }

    /**
     * 「隙間なし連続ビットパック」方式で詰められたlong配列から、index番目のエントリ(bitsPerEntryビット)を取り出す。
     * バニラ1.16以降のBitStorageと違い、1つの値が2つのlongの境界をまたぐことを許容する点に注意。
     */
    private static int readPackedEntry(long[] backing, long index, int bitsPerEntry) {
        if (backing.length == 0 || bitsPerEntry <= 0) {
            return 0;
        }
        long bitIndex = index * bitsPerEntry;
        int startLong = (int) (bitIndex >> 6);
        int startOffset = (int) (bitIndex & 0x3F);
        int endLong = (int) ((bitIndex + bitsPerEntry - 1) >> 6);
        if (startLong < 0 || endLong >= backing.length) {
            return -1; // データが足りない(壊れたファイル): 呼び出し側でスキップさせる
        }

        long value;
        if (startLong == endLong) {
            value = backing[startLong] >>> startOffset;
        } else {
            int bitsInFirstLong = 64 - startOffset;
            value = (backing[startLong] >>> startOffset) | (backing[endLong] << bitsInFirstLong);
        }
        long mask = (1L << bitsPerEntry) - 1L;
        return (int) (value & mask);
    }

    /**
     * 原点(0,0,0)を中心に、水平方向(Y軸周り)へrotation分だけ回転させた座標を返す。
     * バニラのストラクチャーブロックと同じ回転規則(StructureTemplate.transformと同じ向き)に合わせている。
     */
    /** 原点を基準にミラーした座標を返す(バニラ・Litematicaと同じく、LEFT_RIGHT は Z、FRONT_BACK は X を反転)。 */
    private static BlockPos mirrorRelative(BlockPos p, Mirror mirror) {
        return switch (mirror) {
            case LEFT_RIGHT -> new BlockPos(p.getX(), p.getY(), -p.getZ());
            case FRONT_BACK -> new BlockPos(-p.getX(), p.getY(), p.getZ());
            default -> p;
        };
    }

    /** ミラー → 回転の順に、(x, z) を変換した結果を返す(mirrorRelative・rotateAroundOrigin と同じ規則)。 */
    private static int[] transformXZ(int x, int z, Mirror mirror, Rotation rotation) {
        int[] out = new int[2];
        transformXZInto(x, z, mirror, rotation, out);
        return out;
    }

    /** transformXZ の、配列を使い回す版(セルごとに配列を作らないため)。 */
    private static void transformXZInto(int x, int z, Mirror mirror, Rotation rotation, int[] out) {
        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> { }
        }
        switch (rotation) {
            case CLOCKWISE_90 -> {
                out[0] = -z;
                out[1] = x;
            }
            case CLOCKWISE_180 -> {
                out[0] = -x;
                out[1] = -z;
            }
            case COUNTERCLOCKWISE_90 -> {
                out[0] = z;
                out[1] = -x;
            }
            default -> {
                out[0] = x;
                out[1] = z;
            }
        }
    }

    private static BlockPos rotateAroundOrigin(BlockPos p, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(-p.getZ(), p.getY(), p.getX());
            case CLOCKWISE_180 -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(p.getZ(), p.getY(), -p.getX());
            default -> p;
        };
    }
}
