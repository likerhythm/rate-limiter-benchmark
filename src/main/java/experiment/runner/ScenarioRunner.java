package experiment.runner;

import experiment.limiter.RateLimiterAdapter;
import experiment.scenario.TrafficScenario;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 시나리오의 각 wave를 정해진 시각에 "동시에" 쏘고 결과를 수집한다.
 *
 * <p>버스트를 실제로 동시에 보내기 위해, wave 시각보다 조금 일찍 작업을 스레드에 넘겨 두고
 * 각 스레드가 목표 시각까지 대기했다가 한꺼번에 tryAcquire를 호출한다.
 * 그래서 스레드 풀 크기는 가장 큰 wave의 요청 수 이상이어야 한다.
 */
public final class ScenarioRunner implements AutoCloseable {

    /** wave 시각보다 이만큼 먼저 작업을 넘겨 둔다. */
    private static final long PREPARE_AHEAD_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    /** 이 이하로 남으면 park 대신 spin 한다 (park는 수십 µs~ms 오차가 난다). */
    private static final long SPIN_THRESHOLD_NANOS = TimeUnit.MICROSECONDS.toNanos(300);

    private final ExecutorService pool;
    private final int poolSize;

    public ScenarioRunner(int poolSize) {
        this.poolSize = poolSize;
        this.pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "request-sender");
            t.setDaemon(true);
            return t;
        });
        // 스레드를 미리 띄워 둔다. 첫 wave에서 스레드 생성 비용이 끼지 않게.
        List<CompletableFuture<Void>> warm = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            warm.add(CompletableFuture.runAsync(() -> { }, pool));
        }
        warm.forEach(CompletableFuture::join);
    }

    /**
     * limiter를 방금 만든 상태에서 호출한다. 호출 시점이 t=0이다.
     */
    public void run(TrafficScenario scenario, RateLimiterAdapter limiter, MetricsCollector collector) {
        int maxWave = scenario.waves().stream().mapToInt(TrafficScenario.Wave::count).max().orElse(0);
        if (maxWave > poolSize) {
            throw new IllegalArgumentException("wave of " + maxWave + " requests exceeds pool size " + poolSize);
        }

        long t0 = System.nanoTime();
        AtomicInteger requestIds = new AtomicInteger();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>(scenario.totalRequests());

        List<TrafficScenario.Wave> waves = scenario.waves();
        for (int waveIndex = 0; waveIndex < waves.size(); waveIndex++) {
            TrafficScenario.Wave wave = waves.get(waveIndex);
            long fireAt = t0 + TimeUnit.MILLISECONDS.toNanos(wave.offsetMs());
            waitUntil(fireAt - PREPARE_AHEAD_NANOS);

            int wi = waveIndex;
            for (int i = 0; i < wave.count(); i++) {
                int requestId = requestIds.getAndIncrement();
                inFlight.add(CompletableFuture.runAsync(() -> {
                    waitUntil(fireAt);
                    long sent = System.nanoTime();
                    boolean allowed = limiter.tryAcquire();
                    long decided = System.nanoTime();
                    collector.record(new RequestRecord(
                            scenario.name(), limiter.name(), limiter.algorithm(),
                            requestId, wi, wave.offsetMs(),
                            toMs(sent - t0), toMs(decided - t0), allowed));
                }, pool));
            }
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void waitUntil(long deadlineNanos) {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            if (remaining > SPIN_THRESHOLD_NANOS) {
                LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
