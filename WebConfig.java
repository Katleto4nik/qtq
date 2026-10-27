package com.gabdab.weboptimizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("weboptimizer.json");

    public static WebConfig INSTANCE = new WebConfig();

    // --- web
    public boolean enabled = false;
    /** 0..1000, 0 = instant, 1000 = maximum smoothness */
    public int smoothness = 100;
    /** distance to the enemy in blocks, 2.0 .. 4.5 */
    public double reach = 3.4;
    public boolean returnSword = true;

    // --- auto drain
    public boolean drainEnabled = false;
    /** 0..1000, head turn speed (server-side) while taking the liquid; 0 = instant */
    public int drainSmoothness = 0;
    /** 0..500 ms: reaction time before taking the bucket and before giving the item back; 0 = same tick */
    public int drainDelayMs = 0;

    /** config format version (old files without it are migrated to the instant drain defaults) */
    public int configVersion;

    // --- hud
    public int hudX = 20;
    public int hudY = 20;

    public static void load() {
        if (!Files.exists(FILE)) {
            INSTANCE.configVersion = 2;
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(FILE)) {
            WebConfig loaded = GSON.fromJson(reader, WebConfig.class);
            if (loaded != null) {
                INSTANCE = loaded;
                INSTANCE.smoothness = Math.max(0, Math.min(1000, INSTANCE.smoothness));
                INSTANCE.drainSmoothness = Math.max(0, Math.min(1000, INSTANCE.drainSmoothness));
                INSTANCE.drainDelayMs = Math.max(0, Math.min(500, INSTANCE.drainDelayMs));
                INSTANCE.reach = Math.max(2.0, Math.min(4.5, INSTANCE.reach));
                if (INSTANCE.configVersion < 2) {          // older config: switch Auto Drain to "instant"
                    INSTANCE.drainSmoothness = 0;
                    INSTANCE.drainDelayMs = 0;
                    INSTANCE.configVersion = 2;
                    save();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
