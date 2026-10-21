package experiment.limiter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;

/**
 * Resilience4j RateLimiter (기본 구현체 AtomicRateLimiter).
 *
 * <p>limiter 생성 시점부터 {@code limitRefreshPeriod} 단위 사이클로 시간을 나누고,
 * 사이클이 바뀔 때 permit을 {@code limitForPeriod}로 리셋한다 → 고정 윈도우.
 */
public final class Resilience4jRateLimiterAdapter implements RateLimiterAdapter {

    private final RateLimiter delegate;

    public Resilience4jRateLimiterAdapter(String instanceName, RateLimitSpec spec) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(spec.permits())
                .limitRefreshPeriod(spec.period())
                .timeoutDuration(Duration.ZERO) // 기다리지 않고 즉시 판정
                .build();
        this.delegate = RateLimiter.of(instanceName, config);
    }

    @Override
    public String name() {
        return "Resilience4j";
    }

    @Override
    public String algorithm() {
        return "fixed window";
    }

    @Override
    public boolean tryAcquire() {
        return delegate.acquirePermission();
    }
}
