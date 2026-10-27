package com.gabdab.weboptimizer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cobweb under the enemy.
 * Flow (legit): target found -> web goes into the hand (visible, 1 tick) -> next tick silent aim + place -> item back.
 * Only while the player stands on the ground.
 */
public final class WebLogic {
    private static final double HIT_REACH = 4.5;       // eye -> clicked point (vanilla limit)
    private static final int PLACE_COOLDOWN = 2;       // ticks
    private static final int ENEMY_COOLDOWN = 8;       // ticks between webs for the same enemy
    private static final long ATTACK_DELAY_MS = 200;   // pause after the player's own hit
    private static final int PATH_STEPS = 28;

    private static final double[] WEIGHTS = {0.6, 1.0, 0.8, 0.5, 0.3};
    private static final double[] OFFSETS = {-0.3, 0.0, 0.3};

    private static final Map<UUID, Long> PLACED_AT = new HashMap<>();
    private static volatile long lastAttackMs = 0;

    private static int cooldown = 0;

    // item handling
    private static boolean modHolding = false;   // the web is in the hand because of us
    private static int prevSlot = 0;
    private static long switchedTick = -10;
    private static long returnAtTick = -1;
    private static int noTargetTicks = 0;

    // sprint stop
    private static boolean engaged = false;
    private static int holdTicks = 0;
    private static boolean resumeSprint = false;
    private static boolean keysBlocked = false;

    private WebLogic() {}

    private record Placement(BlockPos support, Vec3d hit, UUID enemy) {}

    public static void markAttack() {
        lastAttackMs = System.currentTimeMillis();
    }

    public static void reset() {
        PLACED_AT.clear();
        cooldown = 0;
        modHolding = false;
        returnAtTick = -1;
        noTargetTicks = 0;
        engaged = false;
        holdTicks = 0;
        resumeSprint = false;
        keysBlocked = false;
    }

    // ------------------------------------------------------------------ tick

    /** @return true if the web wants the (silent) rotation in this tick */
    public static boolean tick(MinecraftClient mc, ClientPlayerEntity me, ClientWorld world, long tick, boolean allowAct) {
        if (cooldown > 0) cooldown--;

        WebConfig cfg = WebConfig.INSTANCE;
        PlayerInventory inv = me.getInventory();

        // keep the player standing for a moment after the placement, then let him sprint again
        if (holdTicks > 0) {
            blockMovement(mc, me);
            holdTicks--;
            if (holdTicks == 0) resumeSprint = true;
        } else if (resumeSprint) {
            resumeSprint = false;
            if (mc.options.forwardKey.isPressed()) me.setSprinting(true);
        }

        // delayed return of the previous item (sword)
        if (returnAtTick >= 0 && tick >= returnAtTick) {
            returnAtTick = -1;
            giveBack(inv, cfg);
        }

        Placement best = null;
        int webSlot = -1;

        boolean active = allowAct
                && cfg.enabled
                && mc.currentScreen == null
                && mc.interactionManager != null
                && me.isAlive()
                && !me.isSpectator()
                // ONLY on the ground: not jumping, flying, swimming, in knockback...
                && me.isOnGround()
                && !me.isTouchingWater()
                && !me.isInLava()
                && !me.getAbilities().flying
                && !me.hasVehicle()
                && !shieldHeld(mc, me)
                && System.currentTimeMillis() - lastAttackMs >= ATTACK_DELAY_MS;

        if (active) {
            webSlot = findHotbar(inv, Items.COBWEB);
            if (webSlot >= 0) best = findTarget(mc, me, world, cfg, tick);
        }

        if (best == null) {
            if (engaged) {
                engaged = false;
                resumeSprint = true;
            }
            if (modHolding && returnAtTick < 0 && ++noTargetTicks > 3) returnAtTick = tick;
            return false;
        }
        noTargetTicks = 0;

        // sprinting -> the player just stops, the web is placed, then he runs again
        if (!engaged && me.isSprinting()) engaged = true;
        if (engaged) blockMovement(mc, me);

        // 1) take the web into the hand (visible and fast); the placing follows in the next tick
        if (inv.getSelectedSlot() != webSlot) {
            prevSlot = inv.getSelectedSlot();
            inv.setSelectedSlot(webSlot);
            modHolding = true;
            switchedTick = tick;
        }

        // 2) silent aim at the block under the enemy
        Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
        float[] aim = Rotation.angles(eye, best.hit());
        Rotation.begin(me);
        Rotation.step(aim[0], aim[1], cfg.smoothness);
        Rotation.send(mc, me);

        // 3) place (web is already in the hand since the previous tick)
        boolean ready = inv.getSelectedSlot() == webSlot && tick > switchedTick;
        if (ready && cooldown == 0 && Rotation.arrived(aim[0], aim[1])) {
            BlockHitResult hit = new BlockHitResult(best.hit(), Direction.UP, best.support(), false);
            mc.interactionManager.interactBlock(me, Hand.MAIN_HAND, hit);
            me.swingHand(Hand.MAIN_HAND);

            cooldown = PLACE_COOLDOWN;
            PLACED_AT.put(best.enemy(), tick);
            if (cfg.returnSword) returnAtTick = tick + 1;
            if (engaged) {
                engaged = false;
                holdTicks = 1;
            }
        }
        return true;
    }

    /** end of tick: give the movement keys back */
    public static void afterTick(MinecraftClient mc) {
        if (keysBlocked) {
            KeyBinding.updatePressedStates();
            keysBlocked = false;
        }
    }

    private static void giveBack(PlayerInventory inv, WebConfig cfg) {
        if (!modHolding) return;
        modHolding = false;
        if (cfg.returnSword && inv.getStack(inv.getSelectedSlot()).isOf(Items.COBWEB)) {
            inv.setSelectedSlot(swordSlotToReturn(inv, prevSlot));
        }
    }

    // ------------------------------------------------------------------ conditions

    /** shield raised / use key held with a shield / eating / any item in use */
    static boolean shieldHeld(MinecraftClient mc, ClientPlayerEntity me) {
        if (me.isBlocking() || me.isUsingItem()) return true;
        if (!mc.options.useKey.isPressed()) return false;
        return me.getMainHandStack().isOf(Items.SHIELD) || me.getOffHandStack().isOf(Items.SHIELD);
    }

    private static void blockMovement(MinecraftClient mc, ClientPlayerEntity me) {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        o.sprintKey.setPressed(false);
        me.setSprinting(false);
        keysBlocked = true;
    }

    // ------------------------------------------------------------------ target selection

    private static Placement findTarget(MinecraftClient mc, ClientPlayerEntity me, ClientWorld world, WebConfig cfg, long tick) {
        Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
        double pingTicks = pingTicks(mc, me);
        double maxStep = Rotation.maxStepDeg(cfg.smoothness);
        double reach = MathHelper.clamp(cfg.reach, 2.0, HIT_REACH);

        Placement best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity other : world.getPlayers()) {
            if (other == me || !other.isAlive() || other.isSpectator() || me.isTeammate(other)) continue;

            // only inside the configured reach zone (default 3.4), not further
            if (me.distanceTo(other) > reach) continue;

            // never in water / lava
            if (other.isTouchingWater() || other.isSubmergedInWater() || other.isInLava()) continue;

            // already in / next to a web, or we just placed one for him
            if (webNear(world, other)) continue;
            Long placed = PLACED_AT.get(other.getUuid());
            if (placed != null && tick - placed < ENEMY_COOLDOWN) continue;

            // how far into the future: ping + client interpolation + the extra tick for taking the web + head turn
            double aimTicks = aimTicks(me, eye, other, maxStep);
            double lead = MathHelper.clamp(pingTicks + 2.0 + 1.0 + aimTicks, 1.0, 14.0);

            Predictor.Prediction pr = Predictor.predict(world, other, PATH_STEPS);
            for (Placement p : candidates(world, other, pr, lead)) {
                double d = eye.distanceTo(p.hit());
                if (d > HIT_REACH) continue;
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
                break; // sorted: first reachable one is the best for this enemy
            }
        }
        return best;
    }

    private static double pingTicks(MinecraftClient mc, ClientPlayerEntity me) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return 0;
        PlayerListEntry entry = handler.getPlayerListEntry(me.getUuid());
        return entry == null ? 0 : entry.getLatency() / 50.0;
    }

    private static double aimTicks(ClientPlayerEntity me, Vec3d eye, PlayerEntity enemy, double maxStep) {
        float[] a = Rotation.angles(eye, new Vec3d(enemy.getX(), enemy.getY(), enemy.getZ()));
        float curYaw = Rotation.isActive() ? Rotation.yaw() : me.getYaw();
        float curPitch = Rotation.isActive() ? Rotation.pitch() : me.getPitch();
        double dy = MathHelper.wrapDegrees(a[0] - curYaw);
        double dp = a[1] - curPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        return Math.max(0.0, Math.ceil(dist / maxStep) - 1.0);
    }

    /** true if any cobweb touches the enemy's body or lies right around his feet */
    private static boolean webNear(ClientWorld world, PlayerEntity enemy) {
        Box box = enemy.getBoundingBox().expand(0.9, 0.3, 0.9);
        int x0 = MathHelper.floor(box.minX), x1 = MathHelper.floor(box.maxX);
        int y0 = MathHelper.floor(box.minY), y1 = MathHelper.floor(box.maxY);
        int z0 = MathHelper.floor(box.minZ), z1 = MathHelper.floor(box.maxZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    if (world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ where to put the web

    private static List<Placement> candidates(ClientWorld world, PlayerEntity enemy, Predictor.Prediction pr, double lead) {
        Vec3d[] path = pr.path();
        List<Placement> out = new ArrayList<>();

        // in the air -> the web goes where he will LAND
        if (!enemy.isOnGround()) {
            if (pr.landStep() < 0) return out;
            Vec3d lp = path[pr.landStep()];
            Placement p = placementAt(world, enemy, MathHelper.floor(lp.x), MathHelper.floor(lp.z), lp.y);
            if (p != null) out.add(p);
            return out;
        }

        // on the ground -> the cell his hitbox will overlap the most around the predicted tick
        int tc = MathHelper.clamp((int) Math.round(lead), 1, path.length - 4);
        double refY = path[tc].y;

        Map<Long, double[]> cells = new HashMap<>();
        for (int k = -1; k <= 3; k++) {
            int t = tc + k;
            if (t < 0 || t >= path.length) continue;
            Vec3d pt = path[t];
            for (double ox : OFFSETS) {
                for (double oz : OFFSETS) {
                    int cx = MathHelper.floor(pt.x + ox);
                    int cz = MathHelper.floor(pt.z + oz);
                    long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                    if (!cells.containsKey(key)) cells.put(key, new double[]{cx, cz, 0.0});
                }
            }
        }

        List<double[]> list = new ArrayList<>(cells.values());
        for (double[] c : list) {
            double score = 0;
            for (int k = -1; k <= 3; k++) {
                int t = tc + k;
                if (t < 0 || t >= path.length) continue;
                score += WEIGHTS[k + 1] * overlap((int) c[0], path[t].x) * overlap((int) c[1], path[t].z);
            }
            c[2] = score;
        }
        list.sort((a, b) -> Double.compare(b[2], a[2]));

        for (double[] c : list) {
            if (c[2] <= 0.0001) break;
            Placement p = placementAt(world, enemy, (int) c[0], (int) c[1], refY);
            if (p != null) out.add(p);
            if (out.size() >= 3) break;
        }
        return out;
    }

    /** 1D overlap of the cell [cell, cell+1] with the player's hitbox (0.6 wide) centered at c */
    private static double overlap(int cell, double c) {
        return Math.max(0.0, Math.min(cell + 1.0, c + 0.3) - Math.max((double) cell, c - 0.3));
    }

    /** web cell + the floor block it is clicked on, or null when it makes no sense */
    private static Placement placementAt(ClientWorld world, PlayerEntity enemy, int cx, int cz, double y) {
        BlockPos feet = new BlockPos(cx, MathHelper.floor(y + 0.001), cz);

        BlockState fs = world.getBlockState(feet);
        if (fs.isOf(Blocks.COBWEB)) return null;
        if (!fs.getFluidState().isEmpty()) return null;               // water / lava
        if (!(fs.isAir() || fs.isReplaceable())) return null;

        for (int i = 1; i <= 5; i++) {
            BlockPos support = feet.down(i);
            BlockState s = world.getBlockState(support);
            if (!s.getFluidState().isEmpty()) return null;            // water / lava below
            if (s.isSideSolidFullSquare(world, support, Direction.UP)) {
                BlockPos place = support.up();
                BlockState ps = world.getBlockState(place);
                if (ps.isOf(Blocks.COBWEB)) return null;
                if (!ps.getFluidState().isEmpty()) return null;
                if (!(ps.isAir() || ps.isReplaceable())) return null;
                if (webAround(world, place)) return null;
                return new Placement(support,
                        new Vec3d(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5),
                        enemy.getUuid());
            }
            if (s.isOf(Blocks.COBWEB)) return null;
            if (!(s.isAir() || s.isReplaceable())) return null;
        }
        return null;
    }

    private static boolean webAround(ClientWorld world, BlockPos center) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ inventory helpers

    /** previous slot if it holds a sword, otherwise the first sword in the hotbar, otherwise the previous slot */
    static int swordSlotToReturn(PlayerInventory inv, int previous) {
        if (inv.getStack(previous).isIn(ItemTags.SWORDS)) return previous;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) return i;
        }
        return previous;
    }

    static int findHotbar(PlayerInventory inv, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) return i;
        }
        return -1;
    }
}
