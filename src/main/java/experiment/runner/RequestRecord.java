package experiment.runner;

/**
 * 요청 1건의 결과. CSV 한 줄에 대응한다.
 *
 * @param scenario    시나리오 이름
 * @param limiter     limiter 이름
 * @param algorithm   limiter 알고리즘 분류
 * @param requestId   실행 내 요청 번호
 * @param waveIndex   몇 번째 묶음(wave)에 속한 요청인지
 * @param scheduledMs 보내기로 예정된 시각 (limiter 생성 시점 기준 ms)
 * @param sentMs      실제로 tryAcquire를 호출한 시각
 * @param decidedMs   판정이 끝난 시각
 * @param allowed     허용 여부
 */
public record RequestRecord(
        String scenario,
        String limiter,
        String algorithm,
        int requestId,
        int waveIndex,
        double scheduledMs,
        double sentMs,
        double decidedMs,
        boolean allowed) {

    static final String CSV_HEADER =
            "scenario,limiter,algorithm,request_id,wave_index,scheduled_ms,sent_ms,decided_ms,allowed";

    String toCsv() {
        return String.join(",",
                scenario,
                quote(limiter),
                quote(algorithm),
                Integer.toString(requestId),
                Integer.toString(waveIndex),
                format(scheduledMs),
                format(sentMs),
                format(decidedMs),
                allowed ? "1" : "0");
    }

    private static String format(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
