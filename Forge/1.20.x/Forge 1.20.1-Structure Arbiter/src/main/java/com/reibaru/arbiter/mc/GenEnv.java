package com.reibaru.arbiter.mc;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;


public record GenEnv(
        RegistryAccess registries,
        ChunkGenerator generator,
        BiomeSource biomeSource,
        RandomState randomState,
        StructureTemplateManager templates,
        ChunkGeneratorStructureState structureState,
        LevelHeightAccessor heightAccessor) {

    public static LevelHeightAccessor heightOnly(int minY, int height) {
        return new LevelHeightAccessor() {
            @Override
            public int getHeight() {
                return height;
            }

            @Override
            public int getMinBuildHeight() {
                return minY;
            }
        };
    }
}
