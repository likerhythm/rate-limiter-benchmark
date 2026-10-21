package experiment.limiter;

import dev.failsafe.RateLimiter;

/**
 * Failsafe RateLimiter. smooth / bursty 두 모드를 하나의 어댑터로 감싼다.
 *
 * <ul>
 *   <li>SMOOTH: period/permits 간격의 슬롯마다 1건. 쉬어도 permit이 쌓이지 않는다.</li>
 *   <li>BURSTY: period마다 permit을 permits개로 리셋 → Resilience4j와 같은 고정 윈도우.</li>
 * </ul>
 *
 * <p>Failsafe의 RateLimiter는 Failsafe.with(...) 없이 단독으로 쓸 수 있어서
 * {@link RateLimiter#tryAcquirePermit()}를 직접 호출한다. 이 메서드는 기다리지 않는다.
 */
public final class FailsafeRateLimiterAdapter implements RateLimiterAdapter {

    public enum Mode { SMOOTH, BURSTY }

    private final Mode mode;
    private final RateLimiter<Object> delegate;

    public FailsafeRateLimiterAdapter(Mode mode, RateLimitSpec spec) {
        this.mode = mode;
        this.delegate = switch (mode) {
            case SMOOTH -> RateLimiter.smoothBuilder(spec.permits(), spec.period()).build();
            case BURSTY -> RateLimiter.burstyBuilder(spec.permits(), spec.period()).build();
        };
    }

    @Override
    public String name() {
        return mode == Mode.SMOOTH ? "Failsafe smooth" : "Failsafe bursty";
    }

    @Override
    public String algorithm() {
        return mode == Mode.SMOOTH ? "leaky bucket (GCRA)" : "fixed window";
    }

    @Override
    public boolean tryAcquire() {
        return delegate.tryAcquirePermit();
    }
}
