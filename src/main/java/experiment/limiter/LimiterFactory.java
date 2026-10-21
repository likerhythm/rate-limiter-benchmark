package experiment.limiter;

import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 시나리오를 돌릴 때마다 "새" limiter를 만든다.
 *
 * <p>고정 윈도우 계열은 생성 시점을 기준으로 윈도우 경계가 정해지므로,
 * 매 실행마다 새로 만들어야 모든 limiter가 같은 시간축(t=0 = 생성 시점)을 공유한다.
 */
@FunctionalInterface
public interface LimiterFactory {

    RateLimiterAdapter create(RateLimitSpec spec, String runId);

    /** 비교 대상 전체. redisson이 null이면 Redisson은 제외한다. */
    static List<LimiterFactory> all(RedissonClient redisson) {
        List<LimiterFactory> factories = new ArrayList<>();
        factories.add((spec, runId) -> new Resilience4jRateLimiterAdapter("r4j-" + runId, spec));
        factories.add((spec, runId) -> new FailsafeRateLimiterAdapter(FailsafeRateLimiterAdapter.Mode.BURSTY, spec));
        factories.add((spec, runId) -> new FailsafeRateLimiterAdapter(FailsafeRateLimiterAdapter.Mode.SMOOTH, spec));
        if (redisson != null) {
            factories.add((spec, runId) -> new RedissonRateLimiterAdapter(redisson, "experiment:rl:" + runId, spec));
        }
        return factories;
    }
}
