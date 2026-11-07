package com.reibaru.arbiter.mc;

import com.reibaru.arbiter.ArbiterMod;
import com.reibaru.arbiter.Box3;
import com.reibaru.arbiter.IntentSource;
import com.reibaru.arbiter.StructureIntent;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * バニラの StructureSet から、指定した窓の近くにある構造物のintentを「チャンクに書かずに」先読みする。
 *
 * コストを下げるために、重い Structure#generate の前に次の順で候補を捨てる:
 *   1. セット単位: RandomSpread 以外 / spacing が小さい密集セット / 設定で除外したMOD（最初に1回だけ判定）
 *   2. 位置: 窓から extentBlocks 以上離れた候補チャンク
 *   3. 順位（wanted）: 順位が下の構造物・同じ構造物の別インスタンス（ソルバが指定）
 *   4. バイオーム: どの高さで見ても許可バイオームに入らない候補（バニラは生成の「後」に判定する）
 *   5. 同じ (構造物, チャンク) は1回しか生成しない。複数スレッドが同時に要求しても、生成は1スレッドだけ
 *
 * 生成した結果は純粋関数（シードと座標だけで決まる）なので、キャッシュしてよい。
 *
 * 割り切り:
 *  - 1つの StructureSet に複数エントリがあるときは全エントリを候補にする（実際に選ばれるのは1つ）
 *  - ConcentricRings（要塞）は対象外
 *  - extentBlocks より大きい構造物との衝突は見逃す
 *  - バイオードの先行判定は、チャンク中央の数点での近似。外れると候補を落とし、衝突を見逃すことがある
 */
public final class VanillaIntentSource implements IntentSource {

    /** バイオーム先行判定で見る高さ（地下・地表・山）。 */
    private static final int[] PROBE_Y = {-48, 0, 64, 128, 192};
    private static final int CACHE_LIMIT = 200_000;

    private record Entry(ResourceLocation id, String idString, Structure structure, int priority) {}

    private record SetInfo(RandomSpreadStructurePlacement placement, int spacing, List<Entry> entries) {}

    private record Key(ResourceLocation id, long chunk) {}

    private final AtomicReference<GenEnv> env;
    private final ConcurrentHashMap<Key, FutureTask<Optional<StructureIntent>>> resolved = new ConcurrentHashMap<>();
    private volatile List<SetInfo> sets;

    public VanillaIntentSource(AtomicReference<GenEnv> env) {
        this.env = env;
    }

    @Override
    public int maxNeighborReach() {
        return IntentFactory.maxNeighborReach();
    }

    @Override
    public List<StructureIntent> intentsNear(long seed, int minX, int minZ, int maxX, int maxZ,
                                             BiPredicate<String, Integer> wanted) {
        GenEnv e = env.get();
        if (e == null) return List.of(); // 想定外（Mixinが先に準備する）。先読みなしで進める

        int extent = ArbiterConfig.extentBlocks();
        // 候補チャンクの原点が [minX - extent - 15, maxX + extent] に入るものだけが、窓と交わりうる
        int cx0 = Math.floorDiv(minX - extent - 15, 16);
        int cx1 = Math.floorDiv(maxX + extent, 16);
        int cz0 = Math.floorDiv(minZ - extent - 15, 16);
        int cz1 = Math.floorDiv(maxZ + extent, 16);

        List<StructureIntent> out = new ArrayList<>();
        for (SetInfo info : sets(e)) {
            int s = info.spacing();
            for (int cellX = Math.floorDiv(cx0, s); cellX <= Math.floorDiv(cx1, s); cellX++) {
                for (int cellZ = Math.floorDiv(cz0, s); cellZ <= Math.floorDiv(cz1, s); cellZ++) {
                    ChunkPos pos = info.placement().getPotentialStructureChunk(seed, cellX * s, cellZ * s);
                    if (pos.x < cx0 || pos.x > cx1 || pos.z < cz0 || pos.z > cz1) continue;
                    if (!info.placement().isStructureChunk(e.structureState(), pos.x, pos.z)) continue;

                    for (Entry entry : info.entries()) {
                        if (!wanted.test(entry.idString(), entry.priority())) continue;
                        resolve(e, seed, entry, pos).ifPresent(intent -> {
                            Box3 b = intent.box();
                            if (b.minX() <= maxX && b.maxX() >= minX && b.minZ() <= maxZ && b.maxZ() >= minZ) {
                                out.add(intent);
                            }
                        });
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- internal

    private List<SetInfo> sets(GenEnv e) {
        List<SetInfo> s = sets;
        if (s != null) return s;
        synchronized (this) {
            if (sets == null) sets = buildSets(e);
            return sets;
        }
    }

    private static List<SetInfo> buildSets(GenEnv e) {
        int minSpacing = ArbiterConfig.minSpacingChunks();
        Registry<StructureSet> setRegistry = e.registries().registryOrThrow(Registries.STRUCTURE_SET);
        Registry<Structure> structures = e.registries().registryOrThrow(Registries.STRUCTURE);

        List<SetInfo> out = new ArrayList<>();
        int total = 0, kept = 0, keptEntries = 0, skippedKind = 0, skippedDense = 0, skippedExcluded = 0;
        for (StructureSet set : setRegistry) {
            total++;
            if (!(set.placement() instanceof RandomSpreadStructurePlacement spread)) {
                skippedKind++;
                continue;
            }
            if (spread.spacing() < minSpacing) {
                skippedDense++;
                continue;
            }
            List<Entry> entries = new ArrayList<>();
            for (StructureSet.StructureSelectionEntry selection : set.structures()) {
                Structure structure = selection.structure().value();
                ResourceLocation id = structures.getKey(structure);
                if (id == null) continue;
                String idString = id.toString();
                if (ArbiterConfig.isExcluded(idString)) {
                    skippedExcluded++;
                    continue;
                }
                entries.add(new Entry(id, idString, structure, IntentFactory.priorityOf(idString)));
            }
            if (entries.isEmpty()) continue;
            out.add(new SetInfo(spread, spread.spacing(), List.copyOf(entries)));
            kept++;
            keptEntries += entries.size();
        }
        ArbiterMod.LOGGER.info("[Arbiter] 先読みの対象: セット {}/{} 件・構造物 {} 件"
                        + "（除外: 密集 spacing<{} が {} / RandomSpread以外 {} / 設定で除外 {}構造物）",
                kept, total, keptEntries, minSpacing, skippedDense, skippedKind, skippedExcluded);
        return List.copyOf(out);
    }

    /** 同じ (構造物, チャンク) は1回だけ生成する。他のスレッドは完了を待って結果を使い回す。 */
    private Optional<StructureIntent> resolve(GenEnv e, long seed, Entry entry, ChunkPos pos) {
        Key key = new Key(entry.id(), pos.toLong());
        FutureTask<Optional<StructureIntent>> task = resolved.get(key);
        if (task == null) {
            FutureTask<Optional<StructureIntent>> mine = new FutureTask<>(() -> generateIntent(e, seed, entry, pos));
            task = resolved.putIfAbsent(key, mine);
            if (task == null) {
                if (resolved.size() > CACHE_LIMIT) resolved.clear();
                mine.run();
                task = mine;
            }
        }
        try {
            return task.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException ex) {
            // 生成に失敗した候補は「先読みなし」として扱う（調停されないだけで、バニラ挙動のまま進む）
            ArbiterMod.LOGGER.warn("[Arbiter] 先読みの生成に失敗: {} chunk[{},{}]: {}",
                    entry.idString(), pos.x, pos.z, String.valueOf(ex.getCause()));
            return Optional.empty();
        }
    }

    private Optional<StructureIntent> generateIntent(GenEnv e, long seed, Entry entry, ChunkPos pos) {
        if (ArbiterConfig.biomePrefilter() && !biomePossible(e, entry.structure(), pos)) {
            ArbiterDebug.onPrefilterBiome();
            return Optional.empty();
        }

        long start = System.nanoTime();
        Predicate<Holder<Biome>> biomeOk = entry.structure().biomes()::contains;
        StructureStart result = entry.structure().generate(
                e.registries(), e.generator(), e.biomeSource(), e.randomState(), e.templates(),
                seed, pos, 0, e.heightAccessor(), biomeOk);
        ArbiterDebug.onResolve(entry.idString(), System.nanoTime() - start);

        if (!result.isValid()) return Optional.empty();
        return Optional.of(IntentFactory.fromBox(entry.id(), result.getBoundingBox()));
    }

    /**
     * 許可バイオームに入りうるか。チャンク中央を複数の高さで見て、1つでも合えば true。
     * 失敗したときは true（先読みを省かない）に倒す。
     */
    private static boolean biomePossible(GenEnv e, Structure structure, ChunkPos pos) {
        try {
            Climate.Sampler sampler = e.randomState().sampler();
            HolderSet<Biome> allowed = structure.biomes();
            int qx = QuartPos.fromBlock((pos.x << 4) + 8);
            int qz = QuartPos.fromBlock((pos.z << 4) + 8);
            for (int y : PROBE_Y) {
                Holder<Biome> biome = e.biomeSource().getNoiseBiome(qx, QuartPos.fromBlock(y), qz, sampler);
                if (allowed.contains(biome)) return true;
            }
            return false;
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
