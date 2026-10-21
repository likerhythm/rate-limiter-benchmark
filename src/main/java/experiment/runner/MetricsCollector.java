package experiment.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 요청 결과를 모아 CSV로 쓰고, 콘솔용 요약을 만든다. 여러 스레드에서 동시에 record 해도 안전하다.
 */
public final class MetricsCollector {

    private final ConcurrentLinkedQueue<RequestRecord> records = new ConcurrentLinkedQueue<>();

    public void record(RequestRecord record) {
        records.add(record);
    }

    public List<RequestRecord> snapshot() {
        List<RequestRecord> list = new ArrayList<>(records);
        list.sort(Comparator.comparing(RequestRecord::scenario)
                .thenComparing(RequestRecord::limiter)
                .thenComparingDouble(RequestRecord::decidedMs));
        return list;
    }

    public void writeCsv(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(RequestRecord.CSV_HEADER);
                w.write('\n');
                for (RequestRecord r : snapshot()) {
                    w.write(r.toCsv());
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 시나리오 × limiter별 허용/거절 수와,
     * "어떤 windowMs 길이 구간을 잘라도 허용된 최대 건수"를 표로 만든다.
     */
    public String summary(long windowMs) {
        Map<String, Map<String, List<RequestRecord>>> grouped = snapshot().stream()
                .collect(Collectors.groupingBy(RequestRecord::scenario, LinkedHashMap::new,
                        Collectors.groupingBy(RequestRecord::limiter, LinkedHashMap::new, Collectors.toList())));

        StringBuilder sb = new StringBuilder();
        String rowFormat = "  %-16s %8s %8s %9s %18s%n";
        grouped.forEach((scenario, byLimiter) -> {
            sb.append(scenario).append('\n');
            sb.append(String.format(rowFormat, "limiter", "offered", "allowed", "rejected",
                    "max in any " + windowMs + "ms"));
            byLimiter.forEach((limiter, list) -> {
                long allowed = list.stream().filter(RequestRecord::allowed).count();
                sb.append(String.format(rowFormat, limiter, list.size(), allowed,
                        list.size() - allowed, maxAllowedInWindow(list, windowMs)));
            });
            sb.append('\n');
        });
        return sb.toString();
    }

    /** 허용된 요청의 판정 시각을 정렬한 뒤 투 포인터로 최대 구간 밀도를 구한다. */
    static int maxAllowedInWindow(List<RequestRecord> list, long windowMs) {
        double[] times = list.stream().filter(RequestRecord::allowed)
                .mapToDouble(RequestRecord::decidedMs).sorted().toArray();
        int best = 0;
        int left = 0;
        for (int right = 0; right < times.length; right++) {
            while (times[right] - times[left] >= windowMs) {
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
