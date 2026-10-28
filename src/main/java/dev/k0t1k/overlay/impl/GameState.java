package dev.k0t1k.overlay.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The player's position, dimension and health, read by reflection on the render thread. Only works
 * where Minecraft runs with readable names: 26.1+ (shipped unobfuscated) and loaders that remap to
 * Mojang names (NeoForge). Elsewhere {@link #available} stays false and the overlay does without.
 */
final class GameState {
    private static final long INTERVAL = 250;

    boolean available;
    boolean inWorld;
    double x;
    double y;
    double z;
    /** "overworld", "the_nether", "the_end", or the dimension's full id for others. */
    String dimension = "";
    float health = -1;

    private boolean tried;
    private long nextUpdate;
    private Method getInstance;
    private Field player;
    private Method getX;
    private Method getY;
    private Method getZ;
    private Method level;
    private Method dimensionOf;
    private Method getHealth;

    /** Refreshes the values a few times a second; never throws. */
    void update() {
        long now = System.currentTimeMillis();
        if (now < nextUpdate || (tried && !available)) {
            return;
        }
        nextUpdate = now + INTERVAL;
        try {
            if (!tried) {
                tried = true;
                resolve();
            }
            read();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (available) {
                Overlay.log("game state unavailable: " + e);
            }
            available = false;
            inWorld = false;
        }
    }

    private void resolve() throws ReflectiveOperationException {
        Class<?> minecraft = find("net.minecraft.client.Minecraft");
        if (minecraft == null) {
            Overlay.log("no readable Minecraft classes, coordinates are not shown");
            return;
        }
        getInstance = minecraft.getMethod("getInstance");
        player = minecraft.getField("player");
        Class<?> entity = find("net.minecraft.world.entity.Entity");
        Class<?> living = find("net.minecraft.world.entity.LivingEntity");
        if (entity == null || living == null) {
            return;
        }
        getX = entity.getMethod("getX");
        getY = entity.getMethod("getY");
        getZ = entity.getMethod("getZ");
        level = entity.getMethod("level");
        dimensionOf = level.getReturnType().getMethod("dimension");
        getHealth = living.getMethod("getHealth");
        available = true;
    }

    private static Class<?> find(String name) {
        ClassLoader[] loaders = {GameState.class.getClassLoader(), Thread.currentThread().getContextClassLoader()};
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                // Try the next loader.
            }
        }
        return null;
    }

    private void read() throws ReflectiveOperationException {
        if (!available) {
            return;
        }
        Object mc = getInstance.invoke(null);
        Object p = mc == null ? null : player.get(mc);
        inWorld = p != null;
        if (p == null) {
            return;
        }
        x = (Double) getX.invoke(p);
        y = (Double) getY.invoke(p);
        z = (Double) getZ.invoke(p);
        health = (Float) getHealth.invoke(p);
        Object world = level.invoke(p);
        dimension = world == null ? "" : dimensionName(String.valueOf(dimensionOf.invoke(world)));
    }

    /** {@code ResourceKey[minecraft:dimension / minecraft:the_nether]} to {@code the_nether}. */
    static String dimensionName(String key) {
        int slash = key.lastIndexOf(" / ");
        String id = slash >= 0 ? key.substring(slash + 3) : key;
        if (id.endsWith("]")) {
            id = id.substring(0, id.length() - 1);
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** "X Y Z" rounded down to blocks, as F3 shows the block position. */
    String blockPosition() {
        return (long) Math.floor(x) + " " + (long) Math.floor(y) + " " + (long) Math.floor(z);
    }
}
