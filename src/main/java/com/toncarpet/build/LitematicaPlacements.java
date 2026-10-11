package com.toncarpet.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Litematica(クライアントMOD)が保存している「読み込み中のスキーマティックの配置」を読み取る。
 *
 * Litematicaは、ワールド(サーバー)・ディメンションごとに配置の情報を
 *   <ゲームフォルダ>/config/litematica/litematica_<ワールド名>_dim_<ディメンション>.json
 * というJSONで保存している。シングルプレイ(統合サーバー)では、サーバーとクライアントが同じPC・同じ
 * ゲームフォルダを使うので、このMODからもそのファイルを読める(専用サーバーでは、サーバーから
 * クライアントPCのファイルは見えないので使えない)。
 *
 * 読み取るのは、Litematicaが最後に保存した内容。Litematica側で配置を変えた直後は、まだ保存されて
 * いないことがある(その場合は、いったんワールドを出入りすると確実に反映される)。
 */
public final class LitematicaPlacements {

    private LitematicaPlacements() {
    }

    /** 配置の中の子リージョン1つ分。pos は配置の原点からの相対座標。 */
    public record SubRegion(String name, BlockPos pos, boolean enabled, boolean ownTransform) {
    }

    /** Litematicaの配置1つ分。schematic は.litematicファイルの絶対パス(無い場合はnull)。 */
    public record Placement(String name, Path schematic, BlockPos origin, Rotation rotation, Mirror mirror,
                            boolean enabled, List<SubRegion> subRegions) {
    }

    /** 読み込み結果。selectedIndex は、Litematicaで選択中の配置の番号。 */
    public record Loaded(List<Placement> placements, int selectedIndex, Path jsonFile) {
    }

    /** 配置のファイル名(フォルダの場所と拡張子 .litematic を除いたもの)。ファイルに保存されていない配置は null。 */
    public static String fileName(Placement placement) {
        if (placement.schematic() == null || placement.schematic().getFileName() == null) {
            return null;
        }
        String name = placement.schematic().getFileName().toString();
        String ext = ".litematic";
        return name.toLowerCase(Locale.ROOT).endsWith(ext) ? name.substring(0, name.length() - ext.length()) : name;
    }

    // 直前に読んだ結果のキャッシュ。タブ補完は1文字入力するたびに呼ばれるので、保存ファイルが
    // 変わっていない(更新時刻とサイズが同じ)間は、JSONを読み直さずに前回の結果を返す。
    private static Path cachedFile;
    private static long cachedModified = Long.MIN_VALUE;
    private static long cachedSize = -1;
    private static Loaded cachedLoaded;

    public static Loaded load(MinecraftServer server, ResourceKey<Level> dimension) throws IOException {
        Path dir = server.getServerDirectory().resolve("config").resolve("litematica");
        if (!Files.isDirectory(dir)) {
            throw new IOException("Litematicaの保存フォルダ(config/litematica)が見つかりません。Litematicaを入れていますか?");
        }
        Path file = findJsonFile(server, dir, dimension);
        if (file == null) {
            throw new IOException("このワールド・ディメンション用のLitematicaの保存ファイルが見つかりません");
        }

        long modified = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        if (cachedLoaded != null && file.equals(cachedFile) && modified == cachedModified && size == cachedSize) {
            return cachedLoaded;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject placementsObj = root.getAsJsonObject("placements");
            List<Placement> result = new ArrayList<>();
            int selected = 0;
            if (placementsObj != null) {
                selected = intOr(placementsObj.get("selected"), 0);
                JsonArray array = placementsObj.getAsJsonArray("placements");
                if (array != null) {
                    for (JsonElement element : array) {
                        result.add(parsePlacement(element.getAsJsonObject()));
                    }
                }
            }
            if (selected < 0 || selected >= result.size()) {
                selected = 0;
            }
            Loaded loaded = new Loaded(List.copyOf(result), selected, file);
            cachedFile = file;
            cachedModified = modified;
            cachedSize = size;
            cachedLoaded = loaded;
            return loaded;
        } catch (RuntimeException e) {
            // JSONの形が想定と違う(Litematicaのバージョン違い等)
            throw new IOException("Litematicaの保存ファイルを解釈できませんでした(" + file.getFileName() + "): " + e.getMessage());
        }
    }

    private static Placement parsePlacement(JsonObject obj) {
        String name = stringOr(obj.get("name"), "");
        String schematicPath = stringOr(obj.get("schematic"), null);
        Path schematic = null;
        if (schematicPath != null && !schematicPath.isBlank()) {
            try {
                schematic = Path.of(schematicPath);
            } catch (RuntimeException ignored) {
                // 不正なパス文字列: schematic は null のまま(呼び出し側が「ファイルが無い」として扱う)
            }
        }
        BlockPos origin = blockPos(obj.get("origin"));
        Rotation rotation = enumOr(Rotation.class, obj.get("rotation"), Rotation.NONE);
        Mirror mirror = enumOr(Mirror.class, obj.get("mirror"), Mirror.NONE);
        boolean enabled = boolOr(obj.get("enabled"), true);

        List<SubRegion> subRegions = new ArrayList<>();
        JsonElement subs = obj.get("placements");
        if (subs != null && subs.isJsonArray()) {
            for (JsonElement e : subs.getAsJsonArray()) {
                JsonObject sub = e.getAsJsonObject();
                String subName = stringOr(sub.get("name"), "");
                JsonObject inner = sub.has("placement") ? sub.getAsJsonObject("placement") : sub;
                BlockPos pos = blockPos(inner.get("pos"));
                boolean subEnabled = boolOr(inner.get("enabled"), true);
                boolean own = enumOr(Rotation.class, inner.get("rotation"), Rotation.NONE) != Rotation.NONE
                        || enumOr(Mirror.class, inner.get("mirror"), Mirror.NONE) != Mirror.NONE;
                subRegions.add(new SubRegion(subName, pos, subEnabled, own));
            }
        }
        return new Placement(name, schematic, origin, rotation, mirror, enabled, subRegions);
    }

    /** 保存ファイルを探す。ワールド名とディメンションが一致するものを優先し、無ければ同じディメンションで最後に更新されたもの。 */
    private static Path findJsonFile(MinecraftServer server, Path dir, ResourceKey<Level> dimension) throws IOException {
        // ResourceKey#toString は "ResourceKey[minecraft:dimension / minecraft:overworld]" の形
        String keyText = dimension.toString();
        int slash = keyText.lastIndexOf("/ ");
        String dimId = slash >= 0 ? keyText.substring(slash + 2, keyText.length() - 1) : "minecraft:overworld";
        String dimPart = "dim_" + dimId.replace(':', '_').replace('/', '_');

        List<String> worldNames = new ArrayList<>();
        try {
            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (worldDir.getFileName() != null) {
                worldNames.add(worldDir.getFileName().toString());
            }
        } catch (RuntimeException ignored) {
            // 取得できなければ、フォールバックの探し方に任せる
        }
        try {
            worldNames.add(server.getWorldData().getLevelName());
        } catch (RuntimeException ignored) {
            // 同上
        }
        for (String world : worldNames) {
            Path candidate = dir.resolve("litematica_" + world + "_" + dimPart + ".json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        try (Stream<Path> files = Files.list(dir)) {
            Optional<Path> newest = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("litematica_") && n.endsWith("_" + dimPart + ".json");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
            return newest.orElse(null);
        }
    }

    // ---- JSON の読み取り補助 ----

    private static BlockPos blockPos(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) {
            return BlockPos.ZERO;
        }
        JsonArray a = e.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static String stringOr(JsonElement e, String fallback) {
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : fallback;
    }

    private static int intOr(JsonElement e, int fallback) {
        return (e != null && e.isJsonPrimitive()) ? e.getAsInt() : fallback;
    }

    private static boolean boolOr(JsonElement e, boolean fallback) {
        return (e != null && e.isJsonPrimitive()) ? e.getAsBoolean() : fallback;
    }

    private static <T extends Enum<T>> T enumOr(Class<T> type, JsonElement e, T fallback) {
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, e.getAsString());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
