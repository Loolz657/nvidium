package me.cortex.nvidium.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

/** Development-only frame pacing observation, including the configured frame limiter. */
public final class FrameTimeProbe {
    private static final ArrayList<Double> samples = new ArrayList<>();
    private static long previous;
    private static boolean active;

    public static void begin() { samples.clear(); previous = 0; active = true; }

    public static void frame() {
        if (!active) return;
        long now = System.nanoTime();
        if (previous != 0) samples.add((now - previous) / 1_000_000.0);
        previous = now;
    }

    public static void finish(Path output) throws Exception {
        active = false;
        if (samples.size() < 30) throw new IllegalStateException("Too few chunk loading frames: " + samples.size());
        var sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        long over50 = samples.stream().filter(ms -> ms > 50).count();
        String json = String.format(Locale.ROOT,
                "{\"frames\":%d,\"p50_ms\":%.3f,\"p95_ms\":%.3f,\"p99_ms\":%.3f,\"max_ms\":%.3f,\"frames_over_50_ms\":%d,\"frame_limit\":60,\"render_distance\":6,\"scenario\":\"flat world, 96 blocks per second, 20 seconds\"}\n",
                sorted.length, sorted[sorted.length / 2], sorted[(int)(sorted.length * 0.95)],
                sorted[(int)(sorted.length * 0.99)], sorted[sorted.length - 1], over50);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json);
        System.out.println("NVIDIUM_CHUNK_FLIGHT " + json.strip());
    }
}
