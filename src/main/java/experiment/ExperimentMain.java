package experiment;

import experiment.limiter.LimiterFactory;
import experiment.limiter.RateLimitSpec;
import experiment.limiter.RateLimiterAdapter;
import experiment.runner.MetricsCollector;
import experiment.runner.ScenarioRunner;
import experiment.scenario.Scenarios;
import experiment.scenario.TrafficScenario;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 사용법:
 * <pre>
 *   ./gradlew run --args="--redis redis://127.0.0.1:6379 --out results"
 *   ./gradlew run --args="--no-redis"            # Redis 없이 Resilience4j/Failsafe만
 *   ./gradlew run --args="--permits 10 --period-ms 1000"
 * </pre>
 */
public final class ExperimentMain {

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        RateLimitSpec spec = new RateLimitSpec(options.permits, Duration.ofMillis(options.periodMs));
        List<TrafficScenario> scenarios = Scenarios.defaults();

        RedissonClient redisson = options.redisUri == null ? null : connectRedis(options.redisUri);
        try (ScenarioRunner runner = new ScenarioRunner(64)) {
            List<LimiterFactory> factories = LimiterFactory.all(redisson);
            warmUp(runner, factories, spec);

            MetricsCollector collector = new MetricsCollector();
            for (TrafficScenario scenario : scenarios) {
                for (LimiterFactory factory : factories) {
                    String runId = scenario.name() + "-" + UUID.randomUUID();
                    try (RateLimiterAdapter limiter = factory.create(spec, runId)) {
                        System.out.printf("running %-18s on %-16s ...%n", scenario.name(), limiter.name());
                        runner.run(scenario, limiter, collector);
                    }
                }
            }

            Path csv = options.outDir.resolve("requests.csv");
            collector.writeCsv(csv);
            writeMeta(options.outDir.resolve("meta.csv"), spec, scenarios);

            System.out.println();
            System.out.println("limit: " + spec);
            System.out.print(collector.summary(options.periodMs));
            System.out.println("raw data: " + csv.toAbsolutePath());
        } finally {
            if (redisson != null) {
                redisson.shutdown();
            }
        }
    }

    /** JIT, 클래스 로딩, Redis 커넥션 생성 비용이 첫 측정에 끼지 않게 한 번씩 돌려 둔다. */
    private static void warmUp(ScenarioRunner runner, List<LimiterFactory> factories, RateLimitSpec spec) {
        TrafficScenario warmup = TrafficScenario.builder("warmup", "warmup")
                .burst(0, 20).burst(50, 20).burst(100, 20).build();
        MetricsCollector discard = new MetricsCollector();
        for (LimiterFactory factory : factories) {
            try (RateLimiterAdapter limiter = factory.create(spec, "warmup-" + UUID.randomUUID())) {
                runner.run(warmup, limiter, discard);
            }
        }
    }

    private static RedissonClient connectRedis(String uri) {
        try {
            Config config = new Config();
            config.useSingleServer().setAddress(uri);
            return Redisson.create(config);
        } catch (RuntimeException e) {
            System.err.println("Redis에 연결하지 못해 Redisson은 제외합니다: " + e.getMessage());
            return null;
        }
    }

    /** 시각화 스크립트가 limit 선과 시나리오 설명을 그리는 데 쓴다. */
    private static void writeMeta(Path file, RateLimitSpec spec, List<TrafficScenario> scenarios) throws Exception {
        StringBuilder sb = new StringBuilder("scenario,description,duration_ms,permits,period_ms\n");
        for (TrafficScenario s : scenarios) {
            sb.append(s.name()).append(",\"").append(s.description().replace("\"", "\"\"")).append("\",")
                    .append(s.durationMs()).append(',')
                    .append(spec.permits()).append(',')
                    .append(spec.period().toMillis()).append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    private record Options(String redisUri, Path outDir, int permits, long periodMs) {

        static Options parse(String[] args) {
            String redisUri = "redis://127.0.0.1:6379";
            Path outDir = Path.of("results");
            int permits = 10;
            long periodMs = 1000;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--redis" -> redisUri = args[++i];
                    case "--no-redis" -> redisUri = null;
                    case "--out" -> outDir = Path.of(args[++i]);
                    case "--permits" -> permits = Integer.parseInt(args[++i]);
                    case "--period-ms" -> periodMs = Long.parseLong(args[++i]);
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return new Options(redisUri, outDir, permits, periodMs);
        }
    }
}
