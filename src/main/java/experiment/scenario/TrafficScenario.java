package experiment.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 트래픽 시나리오: "언제(ms) 몇 건을 동시에 보낼지"의 목록.
 *
 * @param name        CSV/그래프에 쓰는 식별자 (영문, 공백 없음)
 * @param description 그래프 제목에 쓰는 설명
 * @param durationMs  시나리오 길이. 그래프 x축 범위로 쓴다.
 * @param waves       시각별 요청 묶음
 */
public record TrafficScenario(String name, String description, long durationMs, List<Wave> waves) {

    /** offsetMs 시점에 count건을 동시에 보낸다. */
    public record Wave(long offsetMs, int count) {
    }

    public TrafficScenario {
        waves = waves.stream().sorted(Comparator.comparingLong(Wave::offsetMs)).toList();
    }

    public int totalRequests() {
        return waves.stream().mapToInt(Wave::count).sum();
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final List<Wave> waves = new ArrayList<>();
        private long durationMs;

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /** atMs 시점에 count건을 한꺼번에 보낸다. */
        public Builder burst(long atMs, int count) {
            waves.add(new Wave(atMs, count));
            return this;
        }

        /** [fromMs, toMs) 동안 everyMs 간격으로 1건씩 보낸다. */
        public Builder steady(long fromMs, long toMs, long everyMs) {
            for (long t = fromMs; t < toMs; t += everyMs) {
                waves.add(new Wave(t, 1));
            }
            return this;
        }

        public Builder duration(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public TrafficScenario build() {
            long last = waves.stream().mapToLong(Wave::offsetMs).max().orElse(0);
            return new TrafficScenario(name, description, Math.max(durationMs, last + 1), waves);
        }
    }
}
