package com.reibaru.arbiter;

import com.reibaru.arbiter.mc.ArbiterCommands;
import com.reibaru.arbiter.mc.ArbiterConfig;
import com.reibaru.arbiter.mc.ArbiterDebug;
import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import org.slf4j.Logger;


@Mod(ArbiterMod.MODID)
public class ArbiterMod {

    public static final String MODID = "arbiter";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ArbiterMod(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, ArbiterConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
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
