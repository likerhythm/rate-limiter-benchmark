package experiment.limiter;

import java.time.Duration;

/**
 * 모든 limiter에 똑같이 적용할 설정: {@code period} 동안 최대 {@code permits}건.
 */
public record RateLimitSpec(int permits, Duration period) {

    public RateLimitSpec {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("period must be positive");
        }
    }

    @Override
    public String toString() {
        return permits + " per " + period.toMillis() + "ms";
    }
}
