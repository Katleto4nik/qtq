package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Auto Drain: when ANOTHER player places water or lava near you, the mod takes it away
 * with an empty bucket (the liquid source is picked up, nothing is placed).
 *
 * Default = instant: in the very same tick the bucket goes into the hand, the head turns silently
 * (360 degrees, the camera never moves), the liquid is taken and the item goes back.
 * The HUD sliders can add a reaction delay / slower head turn if wanted.
 */
public final class AutoDrain {
    private static final int RADIUS = 6;
    private static final double REACH = 4.4;
    private static final double ENEMY_NEAR_SQ = 49.0;   // enemy must be within 7 blocks of the new liquid
    private static final int OWN_USE_WINDOW = 10;       // ticks: ignore liquids that come from our own bucket

    private enum Kind { WATER, LAVA }
    private enum Phase { WAIT, AIM, USED }

    private static final class Job {
        final BlockPos pos;
        final Kind kind;
        long readyTick;
        Phase phase = Phase.WAIT;
        boolean switched = false;
        int prevSlot;
        int slot = -1;
        long switchedTick = -10;
        long arrivedTick = -1;
        long returnTick;

        Job(BlockPos pos, Kind kind) {
            this.pos = pos;
            this.kind = kind;
        }
    }

    private static Map<Long, Kind> prev = new HashMap<>();
    private static BlockPos prevCenter = null;
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    private static Job job = null;
    private static long ownUseUntil = 0;
    private static boolean wantsRotation = false;

    private AutoDrain() {}

    public static boolean wantsRotation() {
        return wantsRotation;
    }

    public static void reset() {
        prev = new HashMap<>();
        prevCenter = null;
        QUEUE.clear();
        job = null;
        wantsRotation = false;
    }

    // ------------------------------------------------------------------ tick

    /** @return true while a drain job is running (the web must not touch the hotbar then) */
    public static boolean tick(MinecraftClient mc, ClientPlayerEntity me, ClientWorld world, long tick) {
        WebConfig cfg = WebConfig.INSTANCE;
        wantsRotation = false;

        if (!cfg.drainEnabled) {
            cancelAll(me, cfg);
            prev = new HashMap<>();
            prevCenter = null;
            return false;
        }

        boolean usable = mc.currentScreen == null && mc.interactionManager != null && me.isAlive() && !me.isSpectator();
        if (!usable) {
            cancelAll(me, cfg);
            return false;
        }

        trackOwnUse(mc, me, tick);
        scan(me, world, tick, cfg);

        if (job == null) job = QUEUE.poll();
        if (job == null) return false;
        return run(mc, me, world, cfg, tick);
    }

    // ------------------------------------------------------------------ detecting other players' liquids

    private static void trackOwnUse(MinecraftClient mc, ClientPlayerEntity me, long tick) {
        if (!mc.options.useKey.isPressed()) return;
        if (isBucket(me.getMainHandStack()) || isBucket(me.getOffHandStack())) {
            ownUseUntil = tick + OWN_USE_WINDOW;
        }
    }

    private static boolean isBucket(ItemStack s) {
        return s.isOf(Items.BUCKET) || s.isOf(Items.WATER_BUCKET) || s.isOf(Items.LAVA_BUCKET);
    }

    private static Kind kindOf(FluidState fs) {
        if (fs.isIn(FluidTags.LAVA)) return Kind.LAVA;
        if (fs.isIn(FluidTags.WATER)) return Kind.WATER;
        return null;
    }

    private static void scan(ClientPlayerEntity me, ClientWorld world, long tick, WebConfig cfg) {
        BlockPos center = me.getBlockPos();

        Map<Long, Kind> cur = new HashMap<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    FluidState fs = world.getFluidState(pos);
                    if (fs.isEmpty() || !fs.isStill()) continue;      // only source blocks
                    Kind k = kindOf(fs);
                    if (k != null) cur.put(pos.asLong(), k);
                }
            }
        }

        boolean first = prevCenter == null || prevCenter.getSquaredDistance(center) > 36.0;
        if (!first && tick > ownUseUntil) {
            Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
            for (Map.Entry<Long, Kind> e : cur.entrySet()) {
                long key = e.getKey();
                if (prev.containsKey(key)) continue;

                BlockPos p = BlockPos.fromLong(key);
                // appeared because the scan window moved, not because somebody placed it
                if (Math.abs(p.getX() - prevCenter.getX()) > RADIUS
                        || Math.abs(p.getY() - prevCenter.getY()) > RADIUS
                        || Math.abs(p.getZ() - prevCenter.getZ()) > RADIUS) continue;

                Vec3d c = Vec3d.ofCenter(p);
                if (eye.distanceTo(c) > REACH + 0.5) continue;
                if (!enemyNear(world, me, c)) continue;
                if (alreadyQueued(p)) continue;
                if (QUEUE.size() >= 3) continue;

                Job j = new Job(p, e.getValue());
                j.readyTick = tick + delayTicks(cfg);      // 0 -> reacts in the very same tick
                QUEUE.add(j);
            }
        }
        prev = cur;
        prevCenter = center;
    }

    private static boolean enemyNear(ClientWorld world, ClientPlayerEntity me, Vec3d c) {
        for (PlayerEntity p : world.getPlayers()) {
            if (p == me || !p.isAlive() || p.isSpectator() || me.isTeammate(p)) continue;
            if (p.squaredDistanceTo(c.x, c.y, c.z) <= ENEMY_NEAR_SQ) return true;
        }
        return false;
    }

    private static boolean alreadyQueued(BlockPos p) {
        if (job != null && job.pos.equals(p)) return true;
        for (Job j : QUEUE) {
            if (j.pos.equals(p)) return true;
        }
        return false;
    }

    private static int delayTicks(WebConfig cfg) {
        return Math.max(0, (int) Math.round(cfg.drainDelayMs / 50.0));
    }

    // ------------------------------------------------------------------ doing the job

    private static boolean run(MinecraftClient mc, ClientPlayerEntity me, ClientWorld world, WebConfig cfg, long tick) {
        PlayerInventory inv = me.getInventory();

        // after the click: wait a moment, then give the item back (head turns back by itself)
        if (job.phase == Phase.USED) {
            if (tick >= job.returnTick) {
                giveBack(inv, cfg, job);
                job = null;
                return false;
            }
            return true;
        }

        // the liquid may be gone already (removed, flowed away)
        FluidState fs = world.getFluidState(job.pos);
        if (fs.isEmpty() || !fs.isStill() || kindOf(fs) != job.kind) {
            abort(inv, cfg);
            return false;
        }

        if (tick < job.readyTick) return false;                 // optional reaction delay (HUD slider)
        if (WebLogic.shieldHeld(mc, me)) return false;          // eating / shield: wait

        int slot = WebLogic.findHotbar(inv, Items.BUCKET);      // empty bucket takes the liquid
        if (slot < 0) {
            abort(inv, cfg);
            return false;
        }

        Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
        Vec3d c = Vec3d.ofCenter(job.pos);
        if (eye.distanceTo(c) > REACH) {
            abort(inv, cfg);
            return false;
        }
        // line of sight: nothing solid between the eyes and the liquid
        BlockHitResult los = world.raycast(new RaycastContext(eye, c,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, me));
        if (los.getType() != HitResult.Type.MISS) {
            abort(inv, cfg);
            return false;
        }
        float[] aim = Rotation.angles(eye, c);

        // 1) bucket into the hand
        if (inv.getSelectedSlot() != slot) {
            if (!job.switched) job.prevSlot = inv.getSelectedSlot();
            job.switched = true;
            job.slot = slot;
            inv.setSelectedSlot(slot);
            job.switchedTick = tick;
        }

        // 2) silent aim, any direction (360 degrees)
        wantsRotation = true;
        job.phase = Phase.AIM;
        Rotation.begin(me);
        Rotation.step(aim[0], aim[1], cfg.drainSmoothness);
        Rotation.send(mc, me);

        // 3) aimed -> click right away (with smoothness 0 this is the very same tick)
        if (Rotation.arrived(aim[0], aim[1])) {
            use(mc, me, aim);
            ownUseUntil = tick + OWN_USE_WINDOW;

            int back = delayTicks(cfg);
            if (back <= 0) {
                giveBack(inv, cfg, job);                         // item back immediately
                job = null;
            } else {
                job.phase = Phase.USED;
                job.returnTick = tick + back;
            }
        }
        return true;
    }

    /**
     * The use-item packet carries the rotation of the player, so the real rotation is set for the
     * duration of the click only and restored immediately: it is never rendered, the camera does not move.
     */
    private static void use(MinecraftClient mc, ClientPlayerEntity me, float[] aim) {
        float oldYaw = me.getYaw();
        float oldPitch = me.getPitch();
        me.setYaw(aim[0]);
        me.setPitch(aim[1]);
        try {
            mc.interactionManager.interactItem(me, Hand.MAIN_HAND);
            me.swingHand(Hand.MAIN_HAND);
        } finally {
            me.setYaw(oldYaw);
            me.setPitch(oldPitch);
        }
    }

    private static void giveBack(PlayerInventory inv, WebConfig cfg, Job j) {
        if (j.switched && cfg.returnSword && inv.getSelectedSlot() == j.slot) {
            inv.setSelectedSlot(WebLogic.swordSlotToReturn(inv, j.prevSlot));
        }
    }

    private static void abort(PlayerInventory inv, WebConfig cfg) {
        if (job != null) giveBack(inv, cfg, job);
        job = null;
    }

    private static void cancelAll(ClientPlayerEntity me, WebConfig cfg) {
        if (job != null) giveBack(me.getInventory(), cfg, job);
        job = null;
        QUEUE.clear();
    }
}
