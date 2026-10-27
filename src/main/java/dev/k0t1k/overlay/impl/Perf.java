package dev.k0t1k.overlay.impl;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.Arrays;
import java.util.List;

/**
 * Frame and JVM metrics. Frame times come from the interval between two buffer swaps, so they are
 * what the player sees, including vsync and frame limiter waits.
 */
final class Perf {
    static final int HISTORY = 600;

    /** Frame times in milliseconds, a ring buffer; {@link #head} is the next slot. */
    final float[] frames = new float[HISTORY];
    int head;
    int count;
    private long lastSwap;

    // Refreshed twice a second.
    int fps;
    float average;
    /** The frame time 99% of recent frames stay under ("1% low" as a frame time). */
    float worst;
    long heapUsed;
    long heapMax;
    long heapCommitted;
    long gcCount;
    long gcMillis;
    /** GC time spent in the last refresh interval. */
    long gcRecentMillis;
    double cpu = -1;
    int threads;
    private long lastRefresh;
    private int framesSinceRefresh;
    private long lastGcMillis = -1;

    /** Cleared when the runtime lacks {@code com.sun.management} (no jdk.management module). */
    private boolean cpuAvailable = true;
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    private final float[] sorted = new float[HISTORY];

    void frame() {
        long now = System.nanoTime();
        if (lastSwap != 0) {
            frames[head] = (now - lastSwap) / 1e6f;
            head = (head + 1) % HISTORY;
            count = Math.min(count + 1, HISTORY);
        }
        lastSwap = now;
        framesSinceRefresh++;
        if (now - lastRefresh >= 500_000_000L) {
            refresh(now);
        }
    }

    /** The frame time {@code back} frames ago (0 = the last one). */
    float frame(int back) {
        return frames[((head - 1 - back) % HISTORY + HISTORY) % HISTORY];
    }

    private void refresh(long now) {
        if (lastRefresh != 0) {
            fps = Math.round(framesSinceRefresh * 1e9f / (now - lastRefresh));
        }
        lastRefresh = now;
        framesSinceRefresh = 0;

        int n = Math.min(count, 240);
        if (n > 0) {
            float sum = 0;
            for (int i = 0; i < n; i++) {
                sorted[i] = frame(i);
                sum += sorted[i];
            }
            average = sum / n;
            Arrays.sort(sorted, 0, n);
            worst = sorted[Math.min(n - 1, (int) Math.floor(n * 0.99))];
        }

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        heapUsed = heap.getUsed();
        heapCommitted = heap.getCommitted();
        heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();

        long count = 0;
        long millis = 0;
        for (GarbageCollectorMXBean gc : collectors) {
            count += Math.max(0, gc.getCollectionCount());
            millis += Math.max(0, gc.getCollectionTime());
        }
        gcCount = count;
        gcMillis = millis;
        gcRecentMillis = lastGcMillis < 0 ? 0 : millis - lastGcMillis;
        lastGcMillis = millis;

        if (cpuAvailable) {
            try {
                if (os instanceof com.sun.management.OperatingSystemMXBean) {
                    cpu = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuLoad();
                }
            } catch (LinkageError e) {
                cpuAvailable = false;
                cpu = -1;
            }
        }
        threads = ManagementFactory.getThreadMXBean().getThreadCount();
    }
}
