package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Silent rotation: the camera of the local player never changes,
 * only the rotation that is sent to the server (what other players see) is turned.
 * Shared by WebLogic and AutoDrain (works in 360 degrees).
 */
public final class Rotation {
    private static final float ARRIVE_DEG = 0.5f;

    private static boolean active = false;
    private static float yaw;
    private static float pitch;
    private static int lastSmoothness = 100;

    private Rotation() {}

    public static boolean isActive() { return active; }
    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }

    public static void reset() { active = false; }

    public static void begin(ClientPlayerEntity me) {
        if (!active) {
            yaw = me.getYaw();
            pitch = me.getPitch();
            active = true;
        }
    }

    /** max degrees per tick: 0 -> 180 (instant), 1000 -> 3 (very slow head turn) */
    public static double maxStepDeg(int smoothness) {
        return 180.0 * Math.pow(3.0 / 180.0, MathHelper.clamp(smoothness, 0, 1000) / 1000.0);
    }

    public static float[] angles(Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float y = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float p = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{y, p};
    }

    public static void step(float targetYaw, float targetPitch, int smoothness) {
        lastSmoothness = smoothness;
        float dy = MathHelper.wrapDegrees(targetYaw - yaw);
        float dp = targetPitch - pitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        double max = maxStepDeg(smoothness);
        if (dist <= max) {
            yaw = targetYaw;
            pitch = targetPitch;
        } else {
            double f = max / dist;
            yaw += (float) (dy * f);
            pitch += (float) (dp * f);
        }
    }

    public static double remaining(float targetYaw, float targetPitch) {
        float dy = MathHelper.wrapDegrees(targetYaw - yaw);
        float dp = targetPitch - pitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    public static boolean arrived(float targetYaw, float targetPitch) {
        return remaining(targetYaw, targetPitch) <= ARRIVE_DEG;
    }

    public static void send(MinecraftClient mc, ClientPlayerEntity me) {
        sendRaw(mc, me, yaw, pitch);
    }

    private static void sendRaw(MinecraftClient mc, ClientPlayerEntity me, float y, float p) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return;
        handler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(y, p, me.isOnGround(), me.horizontalCollision));
    }

    /** nobody needs the rotation any more: turn the server-side rotation back to the real camera rotation */
    public static void release(MinecraftClient mc, ClientPlayerEntity me) {
        if (!active) return;

        float realYaw = me.getYaw();
        float realPitch = me.getPitch();
        step(realYaw, realPitch, lastSmoothness);

        if (remaining(realYaw, realPitch) <= ARRIVE_DEG) {
            sendRaw(mc, me, realYaw, realPitch);
            active = false;
        } else {
            send(mc, me);
        }
    }
}
