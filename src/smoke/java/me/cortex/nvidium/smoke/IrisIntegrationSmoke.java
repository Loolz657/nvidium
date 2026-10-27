package me.cortex.nvidium.smoke;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.IrisCheck;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Launched only as a separate development mod; no existing game instance or world is opened. */
public final class IrisIntegrationSmoke implements ClientModInitializer {
    private static final boolean EUPHORIA = Boolean.getBoolean("nvidium.smoke.euphoria");
    private static final String REIMAGINED = "ComplementaryReimagined_r5.9.3" + (EUPHORIA ? " + EuphoriaPatches_1.10.5" : ".zip");
    private static final String UNBOUND = "ComplementaryUnbound_r5.9.3" + (EUPHORIA ? " + EuphoriaPatches_1.10.5" : ".zip");
    private int phase;
    private long changedAt;
    private String screenshot;
    private long lastMove;
    private final long runId = System.currentTimeMillis();
    @Override
    public void onInitializeClient() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            for (String target : new String[]{
                    "net.irisshaders.iris.pipeline.programs.ShaderCreator",
                    "net.irisshaders.iris.pipeline.IrisRenderingPipeline",
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
                    "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager",
                    "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"}) {
                Class.forName(target, false, loader);
                System.out.println("NVIDIUM_IRIS_SMOKE_TRANSFORMED " + target);
            }
            System.out.println("NVIDIUM_IRIS_SMOKE_SUCCESS");
            if (System.getProperty("nvidium.smoke.mode", "mixin").equals("world")) {
                CompletableFuture.delayedExecutor(600, TimeUnit.SECONDS).execute(() -> {
                    System.err.println("NVIDIUM_IRIS_RENDER_TIMEOUT");
                    System.exit(2);
                });
                ClientTickEvents.END_CLIENT_TICK.register(this::tick);
                return;
            }
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.out.println("NVIDIUM_IRIS_SMOKE_FAILURE");
            System.exit(2);
        }
    }

    private void tick(Minecraft client) {
        try {
            long now = System.currentTimeMillis();
            if (phase == 0 && client.isGameLoadFinished()) {
                UploadBurstCheck.verify();
                phase = 1;
                client.options.pauseOnLostFocus = false;
                System.out.println("NVIDIUM_IRIS_RENDER_CREATING_WORLD");
                client.createWorldOpenFlows().createFreshLevel("nvidium-iris-e2e-" + now,
                        new LevelSettings("Nvidium Iris test", GameType.CREATIVE,
                                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                        new WorldOptions(3080L, false, false),
                        registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                        new TitleScreen());
                return;
            }
            if (client.level == null || client.player == null || client.getConnection() == null) return;
            if (phase == 1) {
                for (String command : new String[]{
                        "time set day", "weather clear", "fill -16 -60 -16 16 -60 16 stone",
                        "fill -6 -59 -4 6 -56 -4 bricks", "fill -7 -59 -3 -5 -57 -1 oak_leaves[persistent=true]",
                        "fill -3 -59 -3 -1 -57 -1 glass", "fill 1 -59 -3 3 -59 -1 water",
                        "setblock 5 -59 -2 sea_lantern", "gamemode spectator", "tp @s 0 -55 10 180 20"}) {
                    client.getConnection().sendCommand(command);
                }
                changedAt = now;
                phase = 2;
            } else if (phase == 2 && now - changedAt > 10000) capture(client, true, "reimagined-bridge.png", 3);
            else if (phase == 3 && saved(client)) switchPack(false, REIMAGINED, 4, now);
            else if (phase == 4 && now - changedAt > 8000) capture(client, false, "reimagined-sodium.png", 5);
            else if (phase == 5 && saved(client)) switchPack(true, UNBOUND, 6, now);
            else if (phase == 6 && now - changedAt > 10000) capture(client, true, "unbound-bridge.png", 7);
            else if (phase == 7 && saved(client)) switchPack(false, UNBOUND, 8, now);
            else if (phase == 8 && now - changedAt > 8000) capture(client, false, "unbound-sodium.png", 9);
            else if (phase == 9 && saved(client)) {
                switchPack(true, UNBOUND, 10, now);
                client.getConnection().sendCommand("execute in minecraft:the_nether run tp @s 0 90 0 180 20");
            }
            else if (phase == 10 && client.level.dimension().equals(Level.NETHER) && now - changedAt > 10000) {
                capture(client, true, "unbound-nether-bridge.png", 11);
            }
            else if (phase == 11 && saved(client)) {
                client.getConnection().sendCommand("execute in minecraft:the_end run tp @s 0 80 0 180 20");
                changedAt = now;
                phase = 12;
            }
            else if (phase == 12 && client.level.dimension().equals(Level.END) && now - changedAt > 10000) {
                capture(client, true, "unbound-end-bridge.png", 13);
            }
            else if (phase == 13 && saved(client)) {
                Iris.getIrisConfig().setShadersEnabled(false);
                Iris.getIrisConfig().save();
                Iris.reload();
                changedAt = now;
                phase = 14;
            }
            else if (phase == 14 && now - changedAt > 8000) {
                if (!Nvidium.IS_ENABLED || IrisCheck.shaderPackActive()) throw new IllegalStateException("Native Nvidium did not resume after disabling shaders");
                System.out.println("NVIDIUM_IRIS_RENDER_NATIVE_RESUMED");
                Iris.getIrisConfig().setShadersEnabled(true);
                Iris.getIrisConfig().save();
                Iris.reload();
                client.getConnection().sendCommand("execute in minecraft:overworld run tp @s 0 -55 10 180 20");
                changedAt = now;
                phase = 15;
            }
            else if (phase == 15 && client.level.dimension().equals(Level.OVERWORLD) && now - changedAt > 10000) {
                capture(client, true, "unbound-reenabled-bridge.png", 16);
            }
            else if (phase == 16 && saved(client)) {
                phase = 17;
                changedAt = lastMove = now;
                FrameTimeProbe.begin();
                System.out.println("NVIDIUM_CHUNK_FLIGHT_START");
            }
            else if (phase == 17) {
                if (now - lastMove >= 1000) {
                    client.getConnection().sendCommand("tp @s ~96 ~ ~");
                    lastMove = now;
                }
                if (now - changedAt < 20000) return;
                FrameTimeProbe.finish(client.gameDirectory.toPath().resolve("test-results/chunk-flight-" + (EUPHORIA ? "euphoria-" : "complementary-") + runId + ".json"));
                if (!Nvidium.IS_ENABLED || !IrisCheck.bridgeActive()) throw new IllegalStateException("Bridge disabled during chunk loading");
                phase = 18;
                System.out.println("NVIDIUM_IRIS_RENDER_SUCCESS");
                client.stop();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.err.println("NVIDIUM_IRIS_RENDER_FAILURE");
            System.exit(2);
        }
    }

    private void capture(Minecraft client, boolean expectedBridge, String name, int nextPhase) {
        if (Nvidium.IS_ENABLED != expectedBridge || IrisCheck.bridgeActive() != expectedBridge) {
            throw new IllegalStateException("Expected bridge=" + expectedBridge + ", Nvidium=" + Nvidium.IS_ENABLED + ", bridge=" + IrisCheck.bridgeActive());
        }
        System.out.println("NVIDIUM_IRIS_RENDER_CAPTURE " + name + " fps=" + client.getFps());
        screenshot = name.replace(".png", (EUPHORIA ? "-euphoria-" : "-") + runId + ".png");
        Screenshot.grab(client.gameDirectory, screenshot, client.gameRenderer.mainRenderTarget(), 1,
                result -> System.out.println("NVIDIUM_IRIS_RENDER_SCREENSHOT " + result.getString()));
        phase = nextPhase;
    }

    private boolean saved(Minecraft client) { return Files.isRegularFile(client.gameDirectory.toPath().resolve("screenshots").resolve(screenshot)); }

    private void switchPack(boolean bridge, String pack, int nextPhase, long now) throws Exception {
        System.setProperty("nvidium.iris.experimental", Boolean.toString(bridge));
        Iris.getIrisConfig().setShaderPackName(pack);
        Iris.getIrisConfig().save();
        Iris.reload();
        // Pack compilation can take longer than the capture delay. Start settling
        // after reload returns so the game gets real frames to rebuild terrain.
        changedAt = System.currentTimeMillis();
        phase = nextPhase;
        System.out.println("NVIDIUM_IRIS_RENDER_SWITCH " + pack + " bridge=" + bridge);
    }
}
