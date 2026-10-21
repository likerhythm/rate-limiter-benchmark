"""
Rate limiter 실험 결과 시각화.

사용법:
    pip install pandas matplotlib numpy
    python scripts/plot_results.py                      # results/requests.csv -> results/charts/
    python scripts/plot_results.py --input results/requests.csv --out results/charts

출력:
    timeline_<scenario>.png  시나리오별 타임라인
        - 위: limiter마다 한 줄씩, 50ms 구간별 허용(파랑)/거절(회색) 요청 수
        - 아래: "직전 1초 동안 허용된 요청 수" — 한도(점선)를 넘으면 그 순간 한도보다 많이 통과한 것
    summary.png              시나리오 × limiter 총 허용/거절 수와 "어느 1초 구간이든 최대 허용 수"
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

# ---- 색과 스타일 -------------------------------------------------------------
# limiter 색은 고정 순서로 배정한다 (실행 결과에 limiter가 빠져도 남은 것의 색은 그대로).
LIMITER_ORDER = ["Resilience4j", "Failsafe bursty", "Failsafe smooth", "Redisson"]
LIMITER_COLORS = {
    "Resilience4j": "#2a78d6",
    "Failsafe bursty": "#eb6834",
    "Failsafe smooth": "#1baf7a",
    "Redisson": "#eda100",
}
ALLOWED_COLOR = "#2a78d6"
REJECTED_COLOR = "#c3c2b7"
SURFACE = "#fcfcfb"
TEXT_PRIMARY = "#0b0b0b"
TEXT_SECONDARY = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"

BIN_MS = 50

# ---- 한글 라벨 ----------------------------------------------------------------
# Java 쪽 CSV에는 영문 식별자/설명이 들어 있다. 그래프에 표시할 때만 한글로 바꾼다.
SCENARIO_TITLES = {
    "single_burst": "단일 버스트",
    "boundary_burst": "경계 버스트",
    "steady_with_spike": "정상 트래픽 + 스파이크",
    "repeated_bursts": "반복 버스트",
}
SCENARIO_DESCRIPTIONS = {
    "single_burst": "쉬다가 t=1.5s에 30건이 한꺼번에 몰림",
    "boundary_burst": "1초 경계 직전(t=0.9s)과 직후(t=1.1s)에 15건씩",
    "steady_with_spike": "130ms마다 1건(약 7.7건/초) + t=3.3s에 25건 스파이크",
    "repeated_bursts": "5초 동안 300ms마다 20건 (한도의 약 6.7배)",
}
ALGORITHM_NAMES = {
    "fixed window": "고정 윈도우",
    "leaky bucket (GCRA)": "리키 버킷 (GCRA)",
    "sliding window log": "슬라이딩 윈도우 로그",
}

# 앞에 있는 것부터 찾는다. Windows / macOS / Linux 순.
KOREAN_FONT_CANDIDATES = [
    "Malgun Gothic",
    "Apple SD Gothic Neo",
    "AppleGothic",
    "NanumGothic",
    "NanumBarunGothic",
    "Noto Sans CJK KR",
    "Noto Sans KR",
    "Noto Sans CJK JP",
    "Noto Sans CJK SC",
]


def setup_korean_font() -> str | None:
    """설치된 한글 글꼴을 찾아 기본 글꼴로 지정한다. 못 찾으면 None."""
    installed = {f.name for f in font_manager.fontManager.ttflist}
    for name in KOREAN_FONT_CANDIDATES:
        if name in installed:
            plt.rcParams["font.family"] = name
            plt.rcParams["axes.unicode_minus"] = False  # 한글 글꼴에서 '-'가 깨지는 문제
            return name
    print("경고: 한글 글꼴을 찾지 못했습니다. 글자가 네모(□)로 보이면 "
          "맑은 고딕/나눔고딕/Noto Sans KR 중 하나를 설치하세요.")
    return None


def scenario_title(scenario: str) -> str:
    return SCENARIO_TITLES.get(scenario, scenario)


def scenario_description(scenario: str, fallback: str) -> str:
    return SCENARIO_DESCRIPTIONS.get(scenario, fallback)


def algorithm_name(algorithm: str) -> str:
    return ALGORITHM_NAMES.get(algorithm, algorithm)

plt.rcParams.update({
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID,
    "axes.labelcolor": TEXT_SECONDARY,
    "axes.titlecolor": TEXT_PRIMARY,
    "axes.titlesize": 11,
    "axes.titleweight": "bold",
    "axes.titlelocation": "left",
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.grid": True,
    "axes.grid.axis": "y",
    "axes.axisbelow": True,
    "grid.color": GRID,
    "grid.linewidth": 0.8,
    "xtick.color": MUTED,
    "ytick.color": MUTED,
    "xtick.labelcolor": TEXT_SECONDARY,
    "ytick.labelcolor": TEXT_SECONDARY,
    "font.size": 9.5,
    "legend.frameon": False,
    "legend.labelcolor": TEXT_SECONDARY,
})


def ordered_limiters(df: pd.DataFrame) -> list[str]:
    present = set(df["limiter"].unique())
    known = [name for name in LIMITER_ORDER if name in present]
    return known + sorted(present - set(known))


def color_of(limiter: str) -> str:
    return LIMITER_COLORS.get(limiter, MUTED)


# ---- 지표 계산 ----------------------------------------------------------------
def binned_counts(rows: pd.DataFrame, duration_ms: float) -> pd.DataFrame:
    """BIN_MS 구간별 허용/거절 수. 요청을 보낸 시각(sent_ms) 기준."""
    edges = np.arange(0, duration_ms + BIN_MS, BIN_MS)
    allowed, _ = np.histogram(rows.loc[rows.allowed == 1, "sent_ms"], bins=edges)
    rejected, _ = np.histogram(rows.loc[rows.allowed == 0, "sent_ms"], bins=edges)
    return pd.DataFrame({"start": edges[:-1], "allowed": allowed, "rejected": rejected})


def trailing_window_allowed(rows: pd.DataFrame, period_ms: float, duration_ms: float,
                            step_ms: float = 2.0) -> tuple[np.ndarray, np.ndarray]:
    """각 시각 t에서 (t - period, t] 동안 허용된 요청 수."""
    times = np.sort(rows.loc[rows.allowed == 1, "decided_ms"].to_numpy())
    grid = np.arange(0, duration_ms + step_ms, step_ms)
    upper = np.searchsorted(times, grid, side="right")
    lower = np.searchsorted(times, grid - period_ms, side="right")
    return grid, upper - lower


def max_allowed_in_any_window(rows: pd.DataFrame, period_ms: float) -> int:
    """어떤 period 길이 구간을 잘라도 허용된 최대 건수 (Java 쪽 요약과 같은 계산)."""
    times = np.sort(rows.loc[rows.allowed == 1, "decided_ms"].to_numpy())
    best, left = 0, 0
    for right in range(len(times)):
        while times[right] - times[left] >= period_ms:
            left += 1
        best = max(best, right - left + 1)
    return best


# ---- 그래프 -------------------------------------------------------------------
def add_window_boundaries(ax, duration_ms: float, period_ms: float) -> None:
    """고정 윈도우 계열의 경계 (limiter 생성 시점 기준 period 배수)."""
    for x in np.arange(period_ms, duration_ms, period_ms):
        ax.axvline(x, color=MUTED, linewidth=0.8, linestyle=(0, (2, 3)), zorder=0)


def plot_timeline(df: pd.DataFrame, scenario: str, meta: pd.Series, out_dir: Path) -> Path:
    rows = df[df.scenario == scenario]
    limiters = ordered_limiters(rows)
    duration = float(meta.duration_ms)
    period = float(meta.period_ms)
    limit = int(meta.permits)

    n = len(limiters)
    fig = plt.figure(figsize=(11, 1.7 * n + 3.8))
    grid = fig.add_gridspec(n + 1, 1, height_ratios=[1] * n + [2.0], hspace=0.8)

    bins = {lim: binned_counts(rows[rows.limiter == lim], duration) for lim in limiters}
    y_max = max(int((b.allowed + b.rejected).max()) for b in bins.values())
    bar_width = BIN_MS - 8  # 막대 사이 간격

    axes = []
    for i, lim in enumerate(limiters):
        ax = fig.add_subplot(grid[i], sharex=axes[0] if axes else None)
        axes.append(ax)
        b = bins[lim]
        algo = rows.loc[rows.limiter == lim, "algorithm"].iloc[0]
        total_allowed = int(b.allowed.sum())
        total_rejected = int(b.rejected.sum())

        ax.bar(b.start, b.allowed, width=bar_width, align="edge", color=ALLOWED_COLOR,
               edgecolor=SURFACE, linewidth=1, label="허용", zorder=2)
        ax.bar(b.start, b.rejected, width=bar_width, align="edge", bottom=b.allowed,
               color=REJECTED_COLOR, edgecolor=SURFACE, linewidth=1, label="거절", zorder=2)
        add_window_boundaries(ax, duration, period)

        ax.set_ylim(0, y_max * 1.15)
        ax.set_title(f"{lim}  ·  {algorithm_name(algo)}", fontsize=10)
        ax.text(1.0, 1.04, f"허용 {total_allowed}  ·  거절 {total_rejected}",
                transform=ax.transAxes, ha="right", va="bottom", color=TEXT_SECONDARY, fontsize=9)
        ax.set_ylabel("요청 수", fontsize=8.5)
        ax.tick_params(labelbottom=False)
        if i == 0:
            ax.legend(loc="upper left", bbox_to_anchor=(0.0, 1.62), ncol=2, fontsize=9,
                      handlelength=1.2, handleheight=0.8)

    # 직전 1초 동안 허용된 수
    ax = fig.add_subplot(grid[n], sharex=axes[0])
    # 같은 알고리즘끼리는 선이 완전히 겹친다. 앞 순서일수록 두껍게, 뒤에 그려서
    # 겹쳐도 테두리처럼 보이게 한다.
    widths = np.linspace(5.5, 2.0, num=max(len(limiters), 2))
    for k, lim in enumerate(limiters):
        t, c = trailing_window_allowed(rows[rows.limiter == lim], period, duration)
        ax.step(t, c, where="post", color=color_of(lim), linewidth=widths[k], label=lim,
                zorder=3 + k, solid_joinstyle="miter")
    ax.axhline(limit, color=TEXT_PRIMARY, linewidth=1, linestyle=(0, (4, 3)), zorder=2)
    add_window_boundaries(ax, duration, period)

    peak = 0
    for lim in limiters:
        peak = max(peak, max_allowed_in_any_window(rows[rows.limiter == lim], period))
    ax.set_ylim(0, max(limit * 2, peak) * 1.2)
    ax.set_title(f"직전 {int(period)}ms 동안 허용된 요청 수  ·  가로 점선 = 한도 {limit}건, "
                 f"점선 위로 올라가면 한도보다 많이 통과한 것", fontsize=10, pad=24)
    ax.set_ylabel("허용 수", fontsize=8.5)
    ax.set_xlabel("limiter 생성 후 경과 시간 (ms)   ·   세로 점선 = 고정 윈도우 경계")
    ax.set_xlim(0, duration)
    ax.legend(loc="lower left", bbox_to_anchor=(0.0, 1.0), ncol=len(limiters), fontsize=9,
              handlelength=1.6, borderaxespad=0.2)

    fig.suptitle(f"{scenario_title(scenario)}  —  {scenario_description(scenario, meta.description)}", x=0.06, ha="left", y=0.995,
                 fontsize=13, fontweight="bold", color=TEXT_PRIMARY)
    out = out_dir / f"timeline_{scenario}.png"
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)
    return out


def plot_summary(df: pd.DataFrame, meta: pd.DataFrame, out_dir: Path) -> Path:
    scenarios = list(meta.scenario)
    limiters = ordered_limiters(df)
    limit = int(meta.permits.iloc[0])
    period = float(meta.period_ms.iloc[0])

    fig, axes = plt.subplots(2, len(scenarios), figsize=(3.3 * len(scenarios), 6.6),
                             gridspec_kw={"hspace": 0.75, "wspace": 0.35})
    axes = np.atleast_2d(axes)
    y = np.arange(len(limiters))

    for j, scenario in enumerate(scenarios):
        rows = df[df.scenario == scenario]

        # 위: 총 허용/거절
        ax = axes[0, j]
        allowed = [int(rows[(rows.limiter == lim) & (rows.allowed == 1)].shape[0]) for lim in limiters]
        rejected = [int(rows[(rows.limiter == lim) & (rows.allowed == 0)].shape[0]) for lim in limiters]
        ax.barh(y, allowed, height=0.62, color=ALLOWED_COLOR, edgecolor=SURFACE, linewidth=2,
                label="허용")
        ax.barh(y, rejected, height=0.62, left=allowed, color=REJECTED_COLOR, edgecolor=SURFACE,
                linewidth=2, label="거절")
        for yi, a in zip(y, allowed):
            ax.annotate(str(a), (a, yi), xytext=(4, 0), textcoords="offset points",
                        va="center", ha="left", color=TEXT_PRIMARY, fontsize=8.5)
        ax.set_yticks(y, limiters if j == 0 else [""] * len(limiters))
        ax.invert_yaxis()
        ax.grid(axis="x")
        ax.grid(axis="y", visible=False)
        ax.set_title(scenario_title(scenario), fontsize=10)
        ax.set_xlabel("요청 수", fontsize=8.5)
        if j == 0:
            ax.legend(loc="lower left", bbox_to_anchor=(0, 1.13), ncol=2, fontsize=9)

        # 아래: 어느 1초 구간이든 최대 허용 수
        ax = axes[1, j]
        peaks = [max_allowed_in_any_window(rows[rows.limiter == lim], period) for lim in limiters]
        ax.barh(y, peaks, height=0.62, color=[color_of(lim) for lim in limiters],
                edgecolor=SURFACE, linewidth=2)
        for yi, p in zip(y, peaks):
            # 한도 선 바로 앞에서 끝나는 막대는 라벨이 점선에 겹치므로 선 뒤로 민다
            label_x = limit if limit - 1.5 < p < limit else p
            ax.annotate(str(p), (label_x, yi), xytext=(4, 0), textcoords="offset points",
                        va="center", ha="left", color=TEXT_PRIMARY, fontsize=8.5)
        ax.axvline(limit, color=TEXT_PRIMARY, linewidth=1, linestyle=(0, (4, 3)))
        ax.set_xlim(0, max(limit * 2, max(peaks)) * 1.25)
        ax.set_yticks(y, limiters if j == 0 else [""] * len(limiters))
        ax.invert_yaxis()
        ax.grid(axis="x")
        ax.grid(axis="y", visible=False)
        ax.set_title(scenario_title(scenario), fontsize=10)
        ax.set_xlabel(f"{int(period)}ms 구간 최대 허용 수", fontsize=8.5)

    fig.text(0.06, 0.985, "총 허용 / 거절 수", fontsize=12.5, fontweight="bold",
             color=TEXT_PRIMARY, ha="left")
    fig.text(0.06, 0.475,
             f"최대 밀도: 어느 {int(period)}ms 구간을 잘라도 허용된 최대 요청 수 "
             f"(점선 = 한도 {limit}건)",
             fontsize=12.5, fontweight="bold", color=TEXT_PRIMARY, ha="left")
    out = out_dir / "summary.png"
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", default="results/requests.csv")
    parser.add_argument("--meta", default=None, help="기본값: --input과 같은 폴더의 meta.csv")
    parser.add_argument("--out", default="results/charts")
    args = parser.parse_args()
    setup_korean_font()

    input_path = Path(args.input)
    meta_path = Path(args.meta) if args.meta else input_path.with_name("meta.csv")
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(input_path)
    meta = pd.read_csv(meta_path)
    meta = meta[meta.scenario.isin(df.scenario.unique())].reset_index(drop=True)

    outputs = [plot_timeline(df, row.scenario, row, out_dir) for row in meta.itertuples()]
    outputs.append(plot_summary(df, meta, out_dir))
    for path in outputs:
        print(f"wrote {path}")


if __name__ == "__main__":
    main()