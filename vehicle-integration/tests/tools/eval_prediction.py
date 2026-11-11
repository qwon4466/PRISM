"""01 예측 결과를 03 시뮬레이션의 실제 미래 위치와 비교한다 (통합 점검용, 앱에 포함되지 않음).

- 입력: app/src/main/assets/scenarios/<id>/{vehicle_frames,prediction_frames}.jsonl, ../simulation-engine/samples/<id>.ego_pose.jsonl
- 실제 미래 위치를 예측 기준 시점(source timestamp)의 자차 좌표계로 옮긴다 (03 README 의 변환식).
- 지표: dt=1s·3s 에서 후보 궤적 중 가장 가까운 것의 거리 오차(minDE), 차량·시점 평균과 95 백분위.
  합성 데이터 기준이며 실제 도로 성능이 아니다.

사용: python -I tests/tools/eval_prediction.py   (vehicle-integration 폴더에서)
"""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "scenarios"
SAMPLES = ROOT.parent / "simulation-engine" / "samples"


def rot(x, y, a):
    c, s = math.cos(a), math.sin(a)
    return c * x - s * y, s * x + c * y


def load_jsonl(p):
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def evaluate(sid):
    frames = {f["timestamp_ms"]: f for f in load_jsonl(ASSETS / sid / "vehicle_frames.jsonl")}
    preds = {p["timestamp_ms"]: p for p in load_jsonl(ASSETS / sid / "prediction_frames.jsonl")}
    poses = {p["timestamp_ms"]: p for p in load_jsonl(SAMPLES / f"{sid}.ego_pose.jsonl")}

    def to_world(ts, x, y):
        p = poses[ts]
        dx, dy = rot(x, y, p["heading_rad"] - math.pi / 2)
        return p["x"] + dx, p["y"] + dy

    def to_local(ts, wx, wy):
        p = poses[ts]
        return rot(wx - p["x"], wy - p["y"], -(p["heading_rad"] - math.pi / 2))

    errs = {1000: [], 3000: []}
    for ts, pf in preds.items():
        for vp in pf["predictions"]:
            for dt in errs:
                fut = frames.get(ts + dt)
                if not fut:
                    continue
                actual = next((v for v in fut["vehicles"] if v["id"] == vp["vehicle_id"]), None)
                if actual is None:
                    continue  # 관측 누락 구간은 비교하지 않는다
                ax, ay = to_local(ts, *to_world(ts + dt, actual["x"], actual["y"]))
                best = min(
                    (math.hypot(pt["x"] - ax, pt["y"] - ay)
                     for tr in vp["trajectories"] for pt in tr["points"] if pt["dt_ms"] == dt),
                    default=None,
                )
                if best is not None:
                    errs[dt].append(best)
    return errs


def summary(v):
    if not v:
        return "-"
    s = sorted(v)
    return f"mean {sum(s) / len(s):.2f} m, p95 {s[int(0.95 * (len(s) - 1))]:.2f} m (n={len(s)})"


if __name__ == "__main__":
    index = json.loads((ASSETS / "index.json").read_text(encoding="utf-8"))
    print("01 예측 vs 03 실제 미래 위치 (후보 궤적 중 최소 거리 오차)")
    for sid in [i for i in index if not i.startswith("mock_")]:
        e = evaluate(sid)
        print(f"{sid:15s} 1s: {summary(e[1000])} | 3s: {summary(e[3000])}")
