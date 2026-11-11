package com.reibaru.arbiter;

import com.reibaru.arbiter.mc.ArbiterCommands;
import com.reibaru.arbiter.mc.ArbiterConfig;
import com.reibaru.arbiter.mc.ArbiterDebug;
import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

import org.slf4j.Logger;


@Mod(ArbiterMod.MODID)
public class ArbiterMod {

    public static final String MODID = "arbiter";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ArbiterMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ArbiterConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("Structure Arbiter loaded (debug={}): use /arbiter debug on|off, /arbiter stats, /arbiter here",
                ArbiterDebug.enabled);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ArbiterCommands.register(event.getDispatcher());
    }

    private void onServerStarting(ServerStartingEvent event) {
        ArbiterDebug.setServer(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        ArbiterDebug.setServer(null);
    }
}
