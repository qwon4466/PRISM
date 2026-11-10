"""PRISM vehicle-integration Mock 시나리오 생성기.

03(시뮬레이션)·01(예측)·02(위험) 엔진 산출물이 도착하기 전, 앱 통합을 검증하기 위한 MOCK 데이터다.
- 차량 움직임: 단순 결정적 운동 스크립트 (HighwayEnv 아님)
- 예측: 계산하지 않는다. 같은 스크립트의 실제 미래 위치를 그대로 옮겨 적은 것 (mode="mock_future_truth")
- 위험: 계산하지 않는다. 시나리오별로 사람이 정한 시간 구간 표(RISK_SCRIPT)를 그대로 쓴다. ttc_s 는 근거가 없으므로 null
05번(통합)은 예측·TTC 알고리즘을 만들지 않는다는 역할 경계를 지키기 위해서다(지시서 PART 05 A).
실제 엔진 결과가 오면 app/src/main/assets/scenarios/ 의 파일만 교체한다.

사용: python tests/tools/make_mock_scenarios.py   (vehicle-integration 폴더에서)
랜덤 없음 → 항상 같은 출력.
"""
import json
import math
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app" / "src" / "main" / "assets" / "scenarios"
FIXTURES = ROOT / "tests" / "fixtures" / "scenarios"
STEP_MS = 100
DT = STEP_MS / 1000
LANE = {"L1": -3.6, "L2": 0.0, "L3": 3.6}
PROVENANCE ="MOCK: vehicle-integration/tests/tools/make_mock_scenarios.py (03/01/02 엔진 산출물 아님)"


class Car:
    def __init__(self, cid, lane, y, v, length=4.6, width=1.8, accel=None, lane_plan=None, follow=False):
        self.id, self.x, self.y, self.v = cid, LANE[lane], y, v
        self.length, self.width = length, width
        self.accel_fn = accel or (lambda t: 0.0)  # 스크립트 가속도 (m/s^2)
        self.lane_plan = lane_plan or []          # [(시작 t, 목표 차선, 소요 s)]
        self.follow = follow                      # 같은 차선 앞차를 따라가는 단순 추종
        self.a = 0.0
        self.vx = 0.0
        self.target_x, self.lc_rate = self.x, 0.0

    def lane_id(self):
        return min(LANE, key=lambda k: abs(LANE[k] - self.x))


def lead_of(car, cars):
    ahead = [c for c in cars if c is not car and abs(c.x - car.x) < 1.8 and c.y > car.y]
    return min(ahead, key=lambda c: c.y) if ahead else None


def step(cars, t, cruise):
    for c in cars:
        a = c.accel_fn(t)
        if c.follow:
            lead = lead_of(c, cars)
            a = 0.8 * (cruise.get(c.id, 25.0) - c.v)
            if lead:
                gap = lead.y - c.y - (lead.length + c.length) / 2
                desired = 5 + 1.2 * c.v
                if gap < desired * 1.5:
                    a = min(a, 0.6 * (gap - desired) + 1.2 * (lead.v - c.v))
            a = max(-7.0, min(1.5, a))
        c.a = a
        for start, lane, dur in c.lane_plan:
            if abs(t - start) < DT / 2:
                c.target_x, c.lc_rate = LANE[lane], abs(LANE[lane] - c.x) / dur
    for c in cars:
        c.v = max(0.0, c.v + c.a * DT)
        dx = c.target_x - c.x
        c.vx = math.copysign(min(abs(dx) / DT, c.lc_rate), dx) if abs(dx) > 1e-6 else 0.0
        c.x += c.vx * DT
        c.y += c.v * DT


def r3(v):
    return round(v, 3)


def vehicle_json(c, ego):
    return {
        "id": c.id, "x": r3(c.x - ego.x), "y": r3(c.y - ego.y),
        "vx": r3(c.vx), "vy": r3(c.v),
        "heading_rad": r3(math.atan2(c.v, c.vx) if (c.v or c.vx) else math.pi / 2),
        "length_m": c.length, "width_m": c.width, "lane_id": c.lane_id(),
    }


def box(cx, cy, w, l):
    return [{"x": r3(cx - w / 2), "y": r3(cy - l / 2)}, {"x": r3(cx + w / 2), "y": r3(cy - l / 2)},
            {"x": r3(cx + w / 2), "y": r3(cy + l / 2)}, {"x": r3(cx - w / 2), "y": r3(cy + l / 2)}]


def scripted_level(script, vid, ts):
    """RISK_SCRIPT: [(vehicle_id, 시작 ms, 끝 ms, 등급, [원인 코드])]. 해당 구간이 없으면 NORMAL."""
    for v, t0, t1, level, reasons in script:
        if v == vid and t0 <= ts < t1:
            return level, reasons
    return "NORMAL", []


def run(sid, title, cars, events, risk_script, cruise=None, drop_frames=(), drop_vehicle=None, duration_s=10.0):
    cruise = cruise or {}
    n = int(duration_s / DT)
    # 1) 스크립트 전체를 먼저 돌려 월드 좌표 상태를 모은다
    states = []
    for i in range(n + 1):
        if i > 0:
            step(cars, i * DT, cruise)
        states.append({c.id: (c.x, c.y, c.vx, c.v, c.length, c.width, c.lane_id()) for c in cars})

    def present(i, vid):
        ts = i * STEP_MS
        return not (drop_vehicle and vid == drop_vehicle[0] and drop_vehicle[1] <= ts < drop_vehicle[2])

    def local(i_src, vid, i_at):
        """i_at 시점 vid 의 위치를 i_src 시점 자차 좌표계로 (source timestamp 좌표계 고정)."""
        ex, ey = states[i_src]["ego"][:2]
        x, y = states[i_at][vid][:2]
        return x - ex, y - ey

    vf, pf, rf = [], [], []
    for i in range(n + 1):
        ts = i * STEP_MS
        if any(a <= ts < b for a, b in drop_frames):
            continue
        ids = [vid for vid in states[i] if present(i, vid)]
        vs = []
        for vid in ids:
            x, y, vx, v, length, width, lane = states[i][vid]
            lx, ly = local(i, vid, i)
            vs.append({"id": vid, "x": r3(lx), "y": r3(ly), "vx": r3(vx), "vy": r3(v),
                       "heading_rad": r3(math.atan2(v, vx) if (v or vx) else math.pi / 2),
                       "length_m": length, "width_m": width, "lane_id": lane})
        vf.append({"schema_version": "1.0", "timestamp_ms": ts, "coordinate_system": "ego_local_m", "ego_id": "ego", "vehicles": vs})
        others = [vid for vid in ids if vid != "ego"]
        preds = []
        for vid in others:
            pts = []
            for k in range(1, 6):
                j = i + int(k * 1000 / STEP_MS)
                if j > n:
                    break  # 시나리오 끝을 넘는 미래는 만들지 않는다(외삽 금지)
                px, py = local(i, vid, j)
                sx, sy, svx, sv = states[j][vid][:4]
                pts.append({"dt_ms": k * 1000, "x": r3(px), "y": r3(py), "heading_rad": r3(math.atan2(sv, svx) if (sv or svx) else math.pi / 2)})
            if pts:
                preds.append({"vehicle_id": vid, "trajectories": [{"mode": "mock_future_truth", "confidence": None, "points": pts}]})
        pf.append({"schema_version": "1.0", "timestamp_ms": ts, "predictions": preds})
        objs = []
        for vid in others:
            level, reasons = scripted_level(risk_script, vid, ts)
            zones = []
            j = i + int(1000 / STEP_MS)
            if level in ("WATCH", "WARNING") and j <= n:
                zx, zy = local(i, vid, j)
                zones = [{"dt_ms": 1000, "polygon": box(zx, zy, 2.4, states[i][vid][4] + 2.4)}]
            objs.append({"vehicle_id": vid, "risk_level": level, "reason_codes": reasons, "ttc_s": None, "risk_zones": zones})
        rf.append({"schema_version": "1.0", "timestamp_ms": ts, "risk_objects": objs})
    manifest = {"scenario_id": sid, "title": title, "duration_ms": n * STEP_MS, "step_ms": STEP_MS, "seed": 0,
                "ego_id": "ego", "event_annotation": [{"t_ms": a, "label": b} for a, b in events],
                "provenance": PROVENANCE, "mock": True}
    write(OUT / sid, manifest, vf, pf, rf)
    return manifest, vf, pf, rf


def write(d, manifest, vf, pf, rf):
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    (d / "scenario_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    for name, rows in (("vehicle_frames.jsonl", vf), ("prediction_frames.jsonl", pf), ("risk_frames.jsonl", rf)):
        if rows is not None:
            (d / name).write_text("\n".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in rows) + "\n", encoding="utf-8")


def brake(t0, t1, a):
    return lambda t: a if t0 <= t < t1 else 0.0


def ego():
    return Car("ego", "L2", 0.0, 25.0, follow=True)


def main():
    scenarios = []
    scenarios.append(run("normal", "정상 주행", [
        ego(), Car("car_01", "L2", 45, 25), Car("car_02", "L1", 15, 26), Car("car_03", "L3", -10, 24),
        Car("car_04", "L1", -25, 25), Car("car_05", "L3", 30, 25)],
        [(0, "정상 주행, 5대 일정 속도")], risk_script=[]))
    scenarios.append(run("rear_approach", "뒤차 급접근", [
        ego(), Car("car_r", "L2", -45, 36, lane_plan=[(6.5, "L1", 2.0)]), Car("car_02", "L1", 20, 25),
        Car("car_03", "L3", 10, 24), Car("car_04", "L3", -20, 25), Car("car_05", "L1", -30, 24)],
        [(0, "후방 car_r 36 m/s 로 접근"), (6500, "car_r 좌측 차선으로 회피")],
        risk_script=[("car_r", 2500, 4500, "WATCH", ["REAR_CLOSING"]), ("car_r", 4500, 6500, "WARNING", ["REAR_CLOSING"]),
                     ("car_r", 6500, 7500, "WATCH", ["REAR_CLOSING", "LANE_CHANGE"])]))
    scenarios.append(run("sudden_brake", "앞차 급감속", [
        ego(), Car("car_01", "L2", 32, 25, accel=brake(3.0, 5.5, -7.0)), Car("car_02", "L1", 10, 25),
        Car("car_03", "L3", -5, 25), Car("car_04", "L1", -25, 25), Car("car_05", "L3", 35, 25)],
        [(3000, "car_01 급감속 -7 m/s²"), (5500, "car_01 감속 종료")],
        risk_script=[("car_01", 3000, 3800, "WATCH", ["HARD_BRAKING"]), ("car_01", 3800, 6000, "WARNING", ["HARD_BRAKING", "FRONT_CLOSING"]),
                     ("car_01", 6000, 7000, "WATCH", ["FRONT_CLOSING"])]))
    scenarios.append(run("lane_change", "옆 차선 끼어들기", [
        ego(), Car("car_02", "L3", 14, 23, lane_plan=[(3.0, "L2", 2.5)]), Car("car_01", "L2", 50, 25),
        Car("car_03", "L1", 5, 25), Car("car_04", "L1", -20, 25), Car("car_05", "L3", -15, 25)],
        [(3000, "car_02 자차 차선으로 차선 변경 시작"), (5500, "차선 변경 완료")],
        risk_script=[("car_02", 3000, 4000, "WATCH", ["LANE_CHANGE_INTRUSION"]), ("car_02", 4000, 5500, "WARNING", ["LANE_CHANGE_INTRUSION", "PATH_OVERLAP"]),
                     ("car_02", 5500, 6500, "WATCH", ["FRONT_CLOSING"])]))
    scenarios.append(run("chain_reaction", "화물차 급감속 연쇄 위험", [
        ego(), Car("car_01", "L2", 24, 25, follow=True), Car("truck", "L2", 55, 24, length=12.0, width=2.5, accel=brake(2.5, 5.0, -6.0)),
        Car("car_02", "L1", 8, 25), Car("car_03", "L3", 30, 25, lane_plan=[(4.5, "L2", 3.0)]), Car("car_04", "L1", -20, 25)],
        [(2500, "truck 급감속"), (3200, "car_01 추종 감속"), (4500, "car_03 회피 차선 변경")],
        risk_script=[("truck", 2500, 5000, "WATCH", ["HARD_BRAKING"]), ("car_01", 3000, 3800, "WATCH", ["CHAIN_DECEL"]),
                     ("car_01", 3800, 5500, "WARNING", ["CHAIN_DECEL", "FRONT_CLOSING"]), ("car_01", 5500, 6500, "WATCH", ["FRONT_CLOSING"]),
                     ("car_03", 4500, 7000, "WATCH", ["LANE_CHANGE_INTRUSION"])],
        cruise={"ego": 25, "car_01": 25}))
    scenarios.append(run("dropout", "센서 누락·노이즈", [
        ego(), Car("car_01", "L2", 35, 25, accel=brake(3.0, 4.5, -3.0)), Car("car_02", "L1", 12, 25),
        Car("car_03", "L3", 20, 24), Car("car_04", "L1", -20, 25), Car("car_05", "L3", -30, 25)],
        [(2000, "car_03 센서 노이즈 (UNKNOWN)"), (4000, "전체 프레임 누락 4.0~5.5s"), (7000, "car_02 관측 누락 7.0~8.0s")],
        risk_script=[("car_03", 2000, 3000, "UNKNOWN", ["SENSOR_DROPOUT"]), ("car_01", 3000, 4000, "WATCH", ["HARD_BRAKING"])],
        drop_frames=[(4000, 5500)], drop_vehicle=("car_02", 7000, 8000)))

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "index.json").write_text(json.dumps([m["scenario_id"] for m, *_ in scenarios]), encoding="utf-8")
    make_fixtures(scenarios[0])
    for m, vf, pf, rf in scenarios:
        levels = {}
        for f in rf:
            for o in f["risk_objects"]:
                levels[o["risk_level"]] = levels.get(o["risk_level"], 0) + 1
        print(f"{m['scenario_id']:15s} frames={len(vf):3d} levels={levels}")


def make_fixtures(normal):
    """오류 처리 테스트용 소형 시나리오 (tests/fixtures/scenarios/<case>)."""
    m, vf, pf, rf = normal
    vf, pf, rf = vf[:5], pf[:5], rf[:5]
    m = dict(m, duration_ms=400)

    def case(name, manifest=m, v=vf, p=pf, r=rf, raw=None):
        d = FIXTURES / name
        write(d, manifest, v, p, r)
        for fname, text in (raw or {}).items():
            (d / fname).write_text(text, encoding="utf-8")

    case("valid")
    case("missing_vehicle_file", v=None)
    case("missing_prediction_file", p=None)
    case("bad_schema_version", v=[dict(f, schema_version="2.0") for f in vf])
    case("unknown_vehicle_ref", r=[dict(rf[0], risk_objects=[dict(rf[0]["risk_objects"][0], vehicle_id="ghost")])] + rf[1:])
    case("ego_missing", v=[dict(vf[0], vehicles=vf[0]["vehicles"][1:])] + vf[1:])
    case("stale_prediction", p=pf + [dict(pf[0], timestamp_ms=9999)])
    case("bad_risk_level", raw={"risk_frames.jsonl": json.dumps(dict(rf[0], risk_objects=[dict(rf[0]["risk_objects"][0], risk_level="DANGER")])) + "\n"})
    case("broken_json", raw={"vehicle_frames.jsonl": json.dumps(vf[0]) + "\n{\"schema_version\": \"1.0\", \"timestamp_ms\": \n"})
    case("gap", v=[vf[0], vf[4]], p=[pf[0], pf[4]], r=[rf[0], rf[4]])
    # 01/02 가 JSONL 대신 단일 .json 으로 줄 때: 예측은 프레임 하나, 위험은 프레임 배열
    case("single_json", p=None, r=None, raw={
        "prediction_frame.json": json.dumps(pf[2], ensure_ascii=False, indent=2),
        "risk_frames.json": json.dumps(rf, ensure_ascii=False, indent=2),
    })
    (FIXTURES.parent / "index.json").write_text("[]", encoding="utf-8")


if __name__ == "__main__":
    main()
