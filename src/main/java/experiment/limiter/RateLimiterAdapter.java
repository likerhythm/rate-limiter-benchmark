package experiment.limiter;

/**
 * 실험 대상 rate limiter를 감싸는 공통 인터페이스.
 *
 * <p>모든 구현체는 "기다리지 않는(non-blocking)" 판정만 한다.
 * permit이 지금 당장 있으면 true, 없으면 즉시 false를 돌려준다.
 * 대기 시간이 끼면 라이브러리마다 대기 방식(블로킹/비동기)이 달라
 * 알고리즘 자체의 차이를 비교하기 어렵기 때문이다.
 */
public interface RateLimiterAdapter extends AutoCloseable {

    /** 그래프와 CSV에 표시될 이름. */
    String name();

    /** 알고리즘 분류 (예: "fixed window"). 그래프 범례에 쓴다. */
    String algorithm();

    /** permit 1개를 즉시 획득 시도한다. 기다리지 않는다. */
    boolean tryAcquire();

    /** 외부 자원(예: Redis 키)을 정리한다. */
    @Override
    default void close() {
    }
}
