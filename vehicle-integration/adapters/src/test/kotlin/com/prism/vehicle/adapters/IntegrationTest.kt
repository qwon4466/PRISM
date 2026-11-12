package com.prism.vehicle.adapters

import com.prism.vehicle.replay.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs

private class FileTextSource(private val base: File) : TextSource {
    override fun read(path: String) = File(base, path).takeIf { it.isFile }?.readText()
}

private fun loaderFor(base: String): Pair<JsonFileProviders, ScenarioLoader> {
    val p = JsonFileProviders(FileTextSource(File(base)))
    return p to ScenarioLoader(p, p, p)
}

/** 앱에 실제로 들어가는 assets 시나리오 전체를 Android 없이 검증한다. */
class AssetScenarioIntegrationTest {
    private val pl = loaderFor("../app/src/main/assets")
    private val providers = pl.first
    private val loader = pl.second

    private fun all() = providers.scenarioIds().also { assertTrue("index.json 비어 있음", it.isNotEmpty()) }
        .map { id -> id to loader.load(id) }

    private val engineIds = listOf("normal", "sudden_braking", "rear_approach", "lane_change", "merge", "chain_reaction", "sensor_dropout")
    private val mockIds = listOf("mock_normal", "mock_rear_approach", "mock_sudden_brake", "mock_lane_change", "mock_chain_reaction", "mock_dropout")

    @Test fun indexListsEngineScenariosThenMocks() {
        val ids = providers.scenarioIds()
        assertEquals("03 실제 엔진 시나리오 7종이 앞에 온다", engineIds.toSet(), ids.take(7).toSet())
        assertTrue(ids.containsAll(mockIds))
    }

    @Test fun everyScenarioParsesAndAllIdsLink() {
        all().forEach { (id, r) ->
            if (r !is LoadResult.Ready) fail("$id: ${(r as LoadResult.Failed).issues}")
        }
    }

    @Test fun egoIsOriginFacingForwardInEveryFrame() {
        all().forEach { (id, r) ->
            val t = (r as LoadResult.Ready).timeline
            t.vehicleFrames.forEach { f ->
                val ego = f.vehicles.single { it.id == f.egoId }
                assertEquals("$id@${f.timestampMs} ego.x", 0.0, ego.x, 1e-9)
                assertEquals("$id@${f.timestampMs} ego.y", 0.0, ego.y, 1e-9)
                assertEquals("$id@${f.timestampMs} ego heading", PI / 2, ego.headingRad, 0.01)
            }
        }
    }

    /** 03 → 01 통합: 모든 VehicleFrame 시점에 01 예측이 같은 timestamp 로 붙고, 예측 차량은 그 프레임에 실제로 있다. */
    @Test fun engineScenariosHavePredictionFromEngine01AtEveryFrame() {
        engineIds.forEach { id ->
            val t = (loader.load(id) as LoadResult.Ready).timeline
            assertEquals("$id 은 03 엔진 데이터(mock 아님)", false, t.manifest.mock)
            t.vehicleFrames.forEach { f ->
                val p = t.snapshotAt(f.timestampMs).prediction
                assertEquals("$id@${f.timestampMs}", f.timestampMs, p?.timestampMs)
                val ids = f.vehicles.map { it.id }.toSet()
                assertTrue("$id@${f.timestampMs}", p!!.predictions.all { it.vehicleId in ids })
                assertTrue("$id@${f.timestampMs} 후보 궤적", p.predictions.all { vp -> vp.trajectories.isNotEmpty() && vp.trajectories.all { it.points.isNotEmpty() } })
            }
        }
    }

    /** 02 위험 엔진 결과가 없으므로 실제 엔진 시나리오에는 RiskFrame 이 없고, 그 사실이 경고로 남는다(05 는 위험을 만들지 않는다). */
    @Test fun engineScenariosHaveNoRiskUntilEngine02Delivers() {
        engineIds.forEach { id ->
            val t = (loader.load(id) as LoadResult.Ready).timeline
            assertEquals(null, t.snapshotAt(1000).risk)
            assertTrue(t.issues.any { it.severity == Severity.WARNING && "risk_frames" in it.where })
        }
    }

    @Test fun mockScenariosShareVehicleTimestamps() {
        mockIds.forEach { id ->
            val t = (loader.load(id) as LoadResult.Ready).timeline
            t.vehicleFrames.forEach { f ->
                val s = t.snapshotAt(f.timestampMs)
                // Mock 예측은 실제 미래 위치를 옮긴 것이라 마지막 1초(옮길 미래 없음)에는 없다
                if (f.timestampMs <= t.durationMs - 1000) assertEquals("$id@${f.timestampMs}", f.timestampMs, s.prediction?.timestampMs)
                else assertEquals("$id@${f.timestampMs}", null, s.prediction)
                assertEquals("$id@${f.timestampMs}", f.timestampMs, s.risk?.timestampMs)
            }
        }
    }

    @Test fun mockRiskScenariosActuallyRaiseRisk() {
        listOf("mock_rear_approach", "mock_sudden_brake", "mock_lane_change", "mock_chain_reaction").forEach { id ->
            val t = (loader.load(id) as LoadResult.Ready).timeline
            val levels = (0..t.durationMs step 100).map { t.snapshotAt(it).topLevel.name }.toSet()
            assertTrue("$id levels=$levels", "WARNING" in levels || "WATCH" in levels)
        }
    }

    @Test fun mockDropoutHasStaleWindowAndUnknown() {
        val t = (loader.load("mock_dropout") as LoadResult.Ready).timeline
        assertTrue(t.snapshotAt(5000).stale)
        assertTrue((0..t.durationMs step 100).any { ms -> t.snapshotAt(ms).risk?.riskObjects?.any { it.riskLevel.name == "UNKNOWN" } == true })
        assertTrue(t.snapshotAt(7500).vehicle!!.vehicles.none { it.id == "car_02" })
    }

    /** 03 sensor_dropout: car_01 3~5s 관측 누락 → 그 구간 프레임에 car_01 이 없고 01 도 car_01 을 예측하지 않는다. */
    @Test fun engineSensorDropoutMissingVehicleNotPredicted() {
        val t = (loader.load("sensor_dropout") as LoadResult.Ready).timeline
        val s = t.snapshotAt(4000)
        assertTrue(s.vehicle!!.vehicles.none { it.id == "car_01" })
        assertTrue(s.prediction!!.predictions.none { it.vehicleId == "car_01" })
    }

    @Test fun replayWalksWholeScenarioMonotonically() {
        val t = (loader.load("chain_reaction") as LoadResult.Ready).timeline
        var last = -1L
        for (ms in 0..t.durationMs step 33) {
            val ts = t.snapshotAt(ms).vehicle!!.timestampMs
            assertTrue(ts >= last && abs(ms - ts) < 100)
            last = ts
        }
    }
}

/** missing / invalid / stale JSON 오류 처리. tests/fixtures/scenarios/<case> */
class FixtureErrorHandlingTest {
    private val loader = loaderFor("../tests/fixtures").second

    private fun failed(case: String) = loader.load(case) as? LoadResult.Failed ?: fail("$case 는 실패해야 함") as Nothing
    private fun ready(case: String) = loader.load(case) as? LoadResult.Ready ?: fail("$case 는 재생 가능해야 함: ${loader.load(case)}") as Nothing

    @Test fun valid() { assertTrue(ready("valid").timeline.issues.isEmpty()) }
    @Test fun missingVehicleFileFails() { assertTrue(failed("missing_vehicle_file").issues.any { "파일 없음" in it.message }) }
    @Test fun missingPredictionFileStillPlaysWithWarning() {
        assertTrue(ready("missing_prediction_file").timeline.issues.any { it.severity == Severity.WARNING && "파일 없음" in it.message })
    }
    @Test fun badSchemaVersionFails() { assertTrue(failed("bad_schema_version").issues.any { "schema_version" in it.message }) }
    @Test fun unknownVehicleRefFails() { assertTrue(failed("unknown_vehicle_ref").issues.any { "ghost" in it.message }) }
    @Test fun egoMissingFails() { assertTrue(failed("ego_missing").issues.any { "ego" in it.message }) }
    @Test fun badRiskLevelFails() { failed("bad_risk_level") }
    @Test fun brokenJsonFailsWithLine() { assertTrue(failed("broken_json").issues.any { it.where.endsWith(":2") }) }
    @Test fun stalePredictionIsDroppedNotMixed() {
        val t = ready("stale_prediction").timeline
        assertTrue(t.issues.any { "9999" in it.where })
        val s = t.snapshotAt(9999)
        assertEquals(s.vehicle!!.timestampMs, s.prediction!!.timestampMs) // 9999 예측이 아닌, 표시 중인 VehicleFrame 시점의 예측
    }
    @Test fun gapIsPlayableButStale() { assertTrue(ready("gap").timeline.snapshotAt(250).stale) }
    @Test fun unknownScenarioFails() { failed("does_not_exist") }
    @Test fun singleJsonFilesAreAccepted() {
        val t = ready("single_json").timeline
        assertTrue(t.issues.isEmpty())
        assertEquals(200L, t.snapshotAt(200).prediction?.timestampMs) // prediction_frame.json = 200ms 프레임 하나
        assertEquals(null, t.snapshotAt(100).prediction)
        assertEquals(100L, t.snapshotAt(100).risk?.timestampMs)        // risk_frames.json = 배열
    }
}
