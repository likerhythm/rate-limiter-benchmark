package experiment.scenario;

import java.util.List;

/**
 * 비교용 시나리오 모음. 설정은 모두 "1초에 10건" 기준이다.
 * (limiter 생성 시점이 t=0이고, 고정 윈도우 경계는 t=1000, 2000, ...ms에 생긴다.)
 */
public final class Scenarios {

    private Scenarios() {
    }

    public static List<TrafficScenario> defaults() {
        return List.of(singleBurst(), boundaryBurst(), steadyWithSpike(), repeatedBursts());
    }

    /** 1. 쉬다가 한 번에 몰림: 순간 버스트를 얼마나 받아주는가. */
    public static TrafficScenario singleBurst() {
        return TrafficScenario.builder("single_burst",
                        "Idle, then 30 requests at once (t=1.5s)")
                .burst(1500, 30)
                .duration(3000)
                .build();
    }

    /** 2. 윈도우 경계 직전/직후에 몰림: 짧은 구간에 한도의 2배가 통과하는가. */
    public static TrafficScenario boundaryBurst() {
        return TrafficScenario.builder("boundary_burst",
                        "15 requests just before and after the 1s boundary (t=0.9s, 1.1s)")
                .burst(900, 15)
                .burst(1100, 15)
                .duration(3000)
                .build();
    }

    /*
     * 주의: 아래 두 시나리오의 간격(130ms, 300ms)은 일부러 1000ms의 약수가 아니게 잡았다.
     * 요청이 정확히 1000ms 간격으로 겹치면, 시계 오차(Redisson은 ms 단위 시각을 쓴다) 때문에
     * "1초 구간 최대 허용 수" 지표가 ±1건씩 흔들린다.
     */

    /** 3. 한도 아래의 정상 트래픽 중간에 스파이크: 스파이크가 정상 요청을 얼마나 밀어내는가. */
    public static TrafficScenario steadyWithSpike() {
        return TrafficScenario.builder("steady_with_spike",
                        "Steady ~7.7 req/s (every 130ms) with a 25-request spike at t=3.3s")
                .steady(0, 6000, 130)
                .burst(3300, 25)
                .duration(6000)
                .build();
    }

    /** 4. 지속적인 과부하: 300ms마다 20건 (초당 약 67건 = 한도의 6.7배). */
    public static TrafficScenario repeatedBursts() {
        TrafficScenario.Builder builder = TrafficScenario.builder("repeated_bursts",
                "20 requests every 300ms for 5s (~6.7x the limit)");
        for (long t = 0; t < 5000; t += 300) {
            builder.burst(t, 20);
        }
        return builder.duration(5000).build();
    }
}
