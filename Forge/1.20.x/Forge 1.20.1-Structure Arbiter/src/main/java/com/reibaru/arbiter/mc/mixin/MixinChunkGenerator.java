package com.reibaru.arbiter.mc.mixin;

import com.reibaru.arbiter.IntentSource;
import com.reibaru.arbiter.Placement;
import com.reibaru.arbiter.RegionSolver;
import com.reibaru.arbiter.StructureIntent;
import com.reibaru.arbiter.mc.ArbiterAccess;
import com.reibaru.arbiter.mc.ArbiterDebug;
import com.reibaru.arbiter.mc.GenEnv;
import com.reibaru.arbiter.mc.IntentFactory;
import com.reibaru.arbiter.mc.VanillaIntentSource;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * ChunkGenerator#tryGenerateStructure 内の Structure#generate を横取りして調停する。
 * 要点:
 *   1. generate は通常どおり実行して StructureStart を得る
 *   2. その BoundingBox から intent を作り（先読みと同じ IntentFactory）、ソルバに聞く
 *   3. 棄却 → 空のStructureStart / 無移動 → そのまま / 移動 → 全ピースを move して作り直す
 *   4. 結果は ArbiterDebug に記録する（/arbiter stats|recent|here で見られる）
 *
 * ArbiterAccess を実装するので、コマンド側が (ChunkGenerator instanceof ArbiterAccess) で
 * ディメンションごとのソルバを取り出せる。
 *
 * 「target not found」が出たら、署名をIDEの逆コンパイルで確認して直す。injectors.defaultRequire=1
 * にしてあるので、合わなければ起動時に必ず落ちる（黙って効かない状態にはならない）。
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator implements ArbiterAccess {

    /** createStructures の引数から拾う。ChunkGenerator 自身は保持していない。 */
    @Unique
    private volatile ChunkGeneratorStructureState arbiter$structureState;

    @Unique
    private final AtomicReference<GenEnv> arbiter$env = new AtomicReference<>();

    @Unique
    private volatile RegionSolver arbiter$solver;

    @Unique
    private volatile VanillaIntentSource arbiter$source;

    @Unique
    private volatile long arbiter$seed;

    @Override
    public RegionSolver arbiter$getSolver() {
        return this.arbiter$solver;
    }

    @Override
    public IntentSource arbiter$getSource() {
        return this.arbiter$source;
    }

    @Override
    public long arbiter$getSeed() {
        return this.arbiter$seed;
    }

    @Inject(method = "createStructures", at = @At("HEAD"))
    private void arbiter$captureState(RegistryAccess registries, ChunkGeneratorStructureState state,
                                      StructureManager structureManager, ChunkAccess chunk,
                                      StructureTemplateManager templates, CallbackInfo ci) {
        this.arbiter$structureState = state;
    }

    @Redirect(
            method = "tryGenerateStructure",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/Structure;generate("
                            + "Lnet/minecraft/core/RegistryAccess;"
                            + "Lnet/minecraft/world/level/chunk/ChunkGenerator;"
                            + "Lnet/minecraft/world/level/biome/BiomeSource;"
                            + "Lnet/minecraft/world/level/levelgen/RandomState;"
                            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;"
                            + "JLnet/minecraft/world/level/ChunkPos;I"
                            + "Lnet/minecraft/world/level/LevelHeightAccessor;"
                            + "Ljava/util/function/Predicate;)"
                            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;"))
    private StructureStart arbiter$arbitrate(Structure structure, RegistryAccess registries,
                                             ChunkGenerator generator, BiomeSource biomeSource,
                                             RandomState randomState, StructureTemplateManager templates,
                                             long seed, ChunkPos pos, int references,
                                             LevelHeightAccessor heightAccessor,
                                             Predicate<Holder<Biome>> biomeOk) {
        StructureStart start = structure.generate(registries, generator, biomeSource, randomState,
                templates, seed, pos, references, heightAccessor, biomeOk);
        if (!start.isValid()) return start;

        ResourceLocation id = registries.registryOrThrow(Registries.STRUCTURE).getKey(structure);
        ChunkGeneratorStructureState state = this.arbiter$structureState;
        if (id == null || state == null) {
            // 想定外。調停せず素通し（バニラ挙動）
            ArbiterDebug.onPassthrough(String.valueOf(id));
            return start;
        }

        this.arbiter$env.set(new GenEnv(registries, generator, biomeSource, randomState, templates, state,
                GenEnv.heightOnly(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight())));

        RegionSolver solver = this.arbiter$solver;
        if (solver == null) {
            synchronized (this) {
                if (this.arbiter$solver == null) {
                    VanillaIntentSource source = new VanillaIntentSource(this.arbiter$env);
                    this.arbiter$seed = seed;
                    this.arbiter$source = source;
                    this.arbiter$solver = new RegionSolver(seed, source);
                }
                solver = this.arbiter$solver;
            }
        }

        StructureIntent intent = IntentFactory.fromBox(id, start.getBoundingBox());
        long t0 = System.nanoTime();
        Optional<Placement> placement = solver.decide(intent);
        long elapsed = System.nanoTime() - t0;
        ArbiterDebug.onDecision(solver, intent, pos.x, pos.z, placement, elapsed);

        if (placement.isEmpty()) {
            // 棄却: ピースを空にした無効なStartを返す（isValid() が false になる）
            return new StructureStart(structure, pos, references, new PiecesContainer(List.of()));
        }
        Placement p = placement.get();
        if (!p.moved()) return start;

        List<StructurePiece> pieces = start.getPieces();
        for (StructurePiece piece : pieces) {
            piece.move(p.dx(), 0, p.dz());
        }
        return new StructureStart(structure, pos, references, new PiecesContainer(pieces));
    }
}
