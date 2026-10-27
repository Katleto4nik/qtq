package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/** Runs at the START of every client tick (before the input is read). AutoDrain has priority over the web. */
public final class Core {
    private static long tickCount = 0;

    private Core() {}

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity me = mc.player;
        ClientWorld world = mc.world;

        if (me == null || world == null || mc.getNetworkHandler() == null) {
            Predictor.clear();
            WebLogic.reset();
            AutoDrain.reset();
            Rotation.reset();
            return;
        }

        tickCount++;
        Predictor.update(world);

        boolean drainBusy = AutoDrain.tick(mc, me, world, tickCount);
        boolean webBusy = WebLogic.tick(mc, me, world, tickCount, !drainBusy);

        if (!AutoDrain.wantsRotation() && !webBusy) {
            Rotation.release(mc, me);
        }
    }

    /** end of tick */
    public static void afterTick(MinecraftClient mc) {
        WebLogic.afterTick(mc);
    }
}
