package com.reibaru.arbiter.mc;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.HashSet;
import java.util.Set;

/**
 * 設定（config/structure_arbiter-common.toml）。
 * ワールド生成が重いときは、まず minSpacingChunks を上げるか excludedNamespaces で
 * 重いMODを外してみる。重い構造物は /arbiter stats の「先読みが重い構造物」で分かる。
 */
public final class ArbiterConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue EXTENT_BLOCKS;
    private static final ForgeConfigSpec.IntValue MIN_SPACING_CHUNKS;
    private static final ForgeConfigSpec.BooleanValue BIOME_PREFILTER;
    private static final ForgeConfigSpec.IntValue DEFAULT_MARGIN;
    private static final ForgeConfigSpec.IntValue DEFAULT_MAX_SHIFT;
    private static final ForgeConfigSpec.ConfigValue<String> EXCLUDED_NAMESPACES;
    private static final ForgeConfigSpec.ConfigValue<String> EXCLUDED_STRUCTURES;
    private static final ForgeConfigSpec.IntValue SLOW_RESOLVE_MS;
    private static final ForgeConfigSpec.IntValue SLOW_DECIDE_MS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("先読み（隣の構造物の位置を、生成前に求める処理）の設定").push("lookahead");
        EXTENT_BLOCKS = b.comment(
                        "候補チャンクから構造物の端までの最大距離（ブロック）。小さいほど軽いが、これより大きい構造物の衝突を見逃す。",
                        "ジグソー構造物の max_distance_from_center は最大128なので、160が目安。")
                .defineInRange("extentBlocks", 160, 32, 1024);
        MIN_SPACING_CHUNKS = b.comment(
                        "spacing（チャンク）がこの値より小さい構造物セットは調停の対象外にする。",
                        "モンスタールームのように密集して小さいものは、先読みの数が爆発するので外すのが無難。")
                .defineInRange("minSpacingChunks", 8, 1, 128);
        BIOME_PREFILTER = b.comment(
                        "バイオームが合わない候補を、重い生成の前に捨てる。",
                        "バニラはバイオーム判定を生成の後に行うので、これで先読みが大幅に減る。")
                .define("biomePrefilter", true);
        EXCLUDED_NAMESPACES = b.comment("調停の対象外にするMOD（namespace）。カンマ区切り。例: \"dungeons_arise,trek\"")
                .define("excludedNamespaces", "");
        EXCLUDED_STRUCTURES = b.comment("調停の対象外にする構造物ID。カンマ区切り。例: \"minecraft:village_plains\"")
                .define("excludedStructures", "");
        b.pop();

        b.comment("衝突の判定").push("arbitration");
        DEFAULT_MARGIN = b.comment("他の構造物との最小水平間隔（ブロック）。地形への馴染ませ分を見込む。")
                .defineInRange("defaultMarginBlocks", 8, 0, 128);
        DEFAULT_MAX_SHIFT = b.comment("衝突したとき平行移動を許す最大距離（ブロック）。0なら移動せず棄却する。")
                .defineInRange("defaultMaxShiftBlocks", 0, 0, 256);
        b.pop();

        b.comment("警告ログ").push("diagnostics");
        SLOW_RESOLVE_MS = b.comment("先読み1件の生成がこの時間（ms）を超えたら警告する（構造物ごとに最初の数回だけ）。")
                .defineInRange("slowResolveWarnMs", 250, 1, 600_000);
        SLOW_DECIDE_MS = b.comment("1回の判定がこの時間（ms）を超えたら警告する。")
                .defineInRange("slowDecideWarnMs", 1000, 1, 600_000);
        b.pop();

        SPEC = b.build();
    }

    private ArbiterConfig() {}

    public static int extentBlocks() {
        return SPEC.isLoaded() ? EXTENT_BLOCKS.get() : 160;
    }

    public static int minSpacingChunks() {
        return SPEC.isLoaded() ? MIN_SPACING_CHUNKS.get() : 8;
    }

    public static boolean biomePrefilter() {
        return !SPEC.isLoaded() || BIOME_PREFILTER.get();
    }

    public static int defaultMargin() {
        return SPEC.isLoaded() ? DEFAULT_MARGIN.get() : 8;
    }

    public static int defaultMaxShift() {
        return SPEC.isLoaded() ? DEFAULT_MAX_SHIFT.get() : 0;
    }

    public static int slowResolveMs() {
        return SPEC.isLoaded() ? SLOW_RESOLVE_MS.get() : 250;
    }

    public static int slowDecideMs() {
        return SPEC.isLoaded() ? SLOW_DECIDE_MS.get() : 1000;
    }

    /** "ns:path" が除外対象か。 */
    public static boolean isExcluded(String id) {
        if (!SPEC.isLoaded()) return false;
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "" : id.substring(0, colon);
        return parse(EXCLUDED_NAMESPACES.get(), true).contains(namespace)
                || parse(EXCLUDED_STRUCTURES.get(), false).contains(id);
    }

    // 設定文字列の解析結果を、文字列が変わらない限り使い回す
    private record Parsed(String raw, Set<String> values) {}

    private static volatile Parsed parsedNamespaces = new Parsed("", Set.of());
    private static volatile Parsed parsedStructures = new Parsed("", Set.of());

    private static Set<String> parse(String raw, boolean namespaces) {
        Parsed cached = namespaces ? parsedNamespaces : parsedStructures;
        if (cached.raw().equals(raw)) return cached.values();
        Set<String> values = new HashSet<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) values.add(t);
        }
        Parsed fresh = new Parsed(raw, Set.copyOf(values));
        if (namespaces) parsedNamespaces = fresh;
        else parsedStructures = fresh;
        return fresh.values();
    }
}
