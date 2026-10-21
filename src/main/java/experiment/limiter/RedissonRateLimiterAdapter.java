package experiment.limiter;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

/**
 * Redisson RRateLimiter.
 *
 * <p>permit을 가져갈 때마다 (시각, 개수)를 sorted set에 기록하고,
 * 기록이 interval보다 오래되면 그만큼 permit을 돌려준다 → 슬라이딩 윈도우 로그.
 * 판정은 Redis 안의 Lua 스크립트에서 원자적으로 이뤄진다.
 *
 * <p>실행마다 새 키를 쓰고, 끝나면 지운다. 이전 실행의 기록이 남으면 결과가 오염된다.
 */
public final class RedissonRateLimiterAdapter implements RateLimiterAdapter {

    private final RRateLimiter delegate;

    public RedissonRateLimiterAdapter(RedissonClient client, String key, RateLimitSpec spec) {
        this.delegate = client.getRateLimiter(key);
        delegate.delete();
        if (!delegate.trySetRate(RateType.OVERALL, spec.permits(), spec.period())) {
            throw new IllegalStateException("Rate limiter already configured: " + key);
        }
    }

    @Override
    public String name() {
        return "Redisson";
    }

    @Override
    public String algorithm() {
        return "sliding window log";
    }

    @Override
    public boolean tryAcquire() {
        return delegate.tryAcquire();
    }

    @Override
    public void close() {
        delegate.delete();
    }
}
