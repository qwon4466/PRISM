package com.prism.vehicle.sdk

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.prism.vehicle.adapters.Gear
import com.prism.vehicle.adapters.Location
import com.prism.vehicle.adapters.SdkFeature
import com.prism.vehicle.adapters.SupportStatus
import com.prism.vehicle.adapters.VehicleSdkProvider
import com.prism.vehicle.adapters.VehicleSdkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Context Mode 용 어댑터. 읽기 전용(기어·위치·내비 상태)과 TTS 만 사용한다.
 * 기어: Pleos Vehicle SDK 의 Gear API 는 deprecated("use the Google GEAR_SELECTION property")이고 공식 문서 도메인 표에도 없어서,
 * Pleos 정책(AAOS 와 동일 기능은 AAOS 에 맞춤)대로 AAOS 표준 CarPropertyManager 의 GEAR_SELECTION 을 읽는다.
 * 수명주기: initialize → capability/read → register listener … unregister → release (공식 샘플 README 순서).
 * 모든 SDK 호출은 실패해도 앱을 죽이지 않고 해당 기능 상태를 FAILED 로 남긴다.
 */
class PleosVehicleSdkProvider(context: Context) : VehicleSdkProvider {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(VehicleSdkState())
    override val state: StateFlow<VehicleSdkState> = _state.asStateFlow()

    private var vehicle: Vehicle? = null
    private var brakeRegistered = false
    private var car: Car? = null
    private var carProperties: CarPropertyManager? = null
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun mark(f: SdkFeature, s: SupportStatus, detail: String = "") {
        Log.i(TAG, "$f -> $s $detail")
        _state.update { it.with(f, s, detail) }
    }

    private fun fail(f: SdkFeature, e: Throwable) =
        mark(f, SupportStatus.FAILED, "${e.javaClass.simpleName}: ${e.message}")

    // 같은 인스턴스로 register/unregister 해야 한다.
    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onGear(value.value as? Int)
        override fun onErrorEvent(propId: Int, areaId: Int) =
            mark(SdkFeature.GEAR, SupportStatus.FAILED, "GEAR_SELECTION onErrorEvent area=$areaId")
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = onBrake(pressed, "listener")
        override fun onFailed(exception: Exception) = fail(SdkFeature.BRAKE_PEDAL, exception)
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null) return
            _state.update { it.copy(location = Location(data.latitude, data.longitude, data.heading, data.timestampMillis)) }
            mark(SdkFeature.FUSED_LOCATION, SupportStatus.CONFIRMED, "lat=%.5f lon=%.5f".format(data.latitude, data.longitude))
        }
        override fun onError(exception: Exception) = fail(SdkFeature.FUSED_LOCATION, exception)
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            _state.update { it.copy(navigationStatus = status.toString()) }
            mark(SdkFeature.NAVI_HELPER, SupportStatus.CONFIRMED, "onNavigationStatus=${status.toString()}")
        }
        override fun onError(errorCode: NaviErrorCode) =
            mark(SdkFeature.NAVI_HELPER, SupportStatus.FAILED, "NaviErrorCode=${errorCode.toString()}")
    }

    override fun start() {
        startVehicle()
        startFusedLocation()
        startNavi()
        startTts()
    }

    private fun startVehicle() {
        if (vehicle != null) return
        mark(SdkFeature.VEHICLE_SERVICE, SupportStatus.PENDING)
        runCatching { Vehicle(appContext).also { it.initialize() } }
            .onSuccess { vehicle = it; mark(SdkFeature.VEHICLE_SERVICE, SupportStatus.CONFIRMED, "initialize() 완료"); startBrake(it) }
            .onFailure { fail(SdkFeature.VEHICLE_SERVICE, it); mark(SdkFeature.BRAKE_PEDAL, SupportStatus.FAILED, "Vehicle 세션 없음") }
        startGear()
    }

    /** Pleos 문서 순서: capability 확인 → 현재 값 읽기 → 리스너 등록 (… stop() 에서 해제). 권한 CAR_DRIVE(Brake 도메인). */
    private fun startBrake(v: Vehicle) {
        mark(SdkFeature.BRAKE_PEDAL, SupportStatus.PENDING)
        runCatching {
            val brake = v.getBrake()
            brake.checkBrakePedalStatusCapabilities(
                { config ->
                    val access = config.accessType
                    if (access != AccessType.READ && access != AccessType.READ_WRITE) {
                        mark(SdkFeature.BRAKE_PEDAL, SupportStatus.UNSUPPORTED, "capability accessType=$access")
                        return@checkBrakePedalStatusCapabilities
                    }
                    runCatching {
                        brake.getCurrentBrakePedalStatus({ onBrake(it, "capability=$access, read") }, { fail(SdkFeature.BRAKE_PEDAL, it) })
                        brake.registerBrakePedalStatus(brakeListener)
                        brakeRegistered = true
                    }.onFailure { fail(SdkFeature.BRAKE_PEDAL, it) }
                },
                { fail(SdkFeature.BRAKE_PEDAL, it) },
            )
        }.onFailure { fail(SdkFeature.BRAKE_PEDAL, it) }
    }

    private fun onBrake(pressed: Boolean?, source: String) {
        _state.update { it.copy(brakePressed = pressed) }
        mark(SdkFeature.BRAKE_PEDAL, SupportStatus.CONFIRMED, "$source pressed=$pressed")
    }

    private fun startGear() {
        if (car != null) return
        mark(SdkFeature.GEAR, SupportStatus.PENDING)
        // 일반 휴대폰에는 android.car 가 없어 NoClassDefFoundError 가 날 수 있다 → runCatching(Throwable) 으로 FAILED 처리
        runCatching {
            val c = Car.createCar(appContext) ?: error("Car.createCar() = null (AAOS 아님)")
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            carProperties = pm
            onGear(pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0))
            pm.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { fail(SdkFeature.GEAR, it) }
    }

    private fun onGear(g: Int?) {
        val mapped = when (g) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            VehicleGear.GEAR_UNKNOWN, null -> Gear.UNKNOWN
            VehicleGear.GEAR_DRIVE -> Gear.DRIVE
            else -> Gear.OTHER
        }
        _state.update { it.copy(gear = mapped) }
        mark(SdkFeature.GEAR, SupportStatus.CONFIRMED, "AAOS GEAR_SELECTION=${g?.let { VehicleGear.toString(it) }}")
    }

    private fun startFusedLocation() {
        if (fused != null) return
        mark(SdkFeature.FUSED_LOCATION, SupportStatus.PENDING)
        runCatching {
            val f = FusedLocation(appContext)
            f.initialize()
            fused = f
            if (!f.isSupported()) {
                mark(SdkFeature.FUSED_LOCATION, SupportStatus.UNSUPPORTED, "isSupported()=false")
                return
            }
            f.getLastKnownLocation(locationListener)
            f.registerFusedLocationCallback(locationListener)
        }.onFailure { fail(SdkFeature.FUSED_LOCATION, it) }
    }

    private fun startNavi() {
        if (navi != null) return
        mark(SdkFeature.NAVI_HELPER, SupportStatus.PENDING)
        runCatching {
            val n = NaviHelper(appContext)
            navi = n
            n.initialize(
                {
                    runCatching {
                        n.addListener(naviListener)
                        n.getRouteStateInfo()
                        mark(SdkFeature.NAVI_HELPER, SupportStatus.CONFIRMED, "initialize 성공, 상태 응답 대기")
                    }.onFailure { fail(SdkFeature.NAVI_HELPER, it) }
                },
                { fail(SdkFeature.NAVI_HELPER, it) },
            )
        }.onFailure { fail(SdkFeature.NAVI_HELPER, it) }
    }

    private fun startTts() {
        if (tts != null) return
        mark(SdkFeature.TEXT_TO_SPEECH, SupportStatus.PENDING)
        runCatching {
            val t = TextToSpeech(appContext, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    ttsReady = true
                    mark(SdkFeature.TEXT_TO_SPEECH, SupportStatus.CONFIRMED, "ON_DEVICE initialize 성공")
                }
                override fun onFailure(error: ErrorType) =
                    mark(SdkFeature.TEXT_TO_SPEECH, SupportStatus.FAILED, "ErrorType=${error.name}")
            })
        }.onFailure { fail(SdkFeature.TEXT_TO_SPEECH, it) }
    }

    override fun speak(text: String): Boolean {
        val t = tts ?: return false
        if (!ttsReady) return false
        return runCatching { t.speak(text, true) }.onFailure { fail(SdkFeature.TEXT_TO_SPEECH, it) }.isSuccess
    }

    override fun stop() {
        carProperties?.let { pm -> runCatching { pm.unregisterCallback(gearCallback) } }
        carProperties = null
        car?.let { runCatching { it.disconnect() } }
        car = null
        vehicle?.let { v ->
            // 리스너는 등록한 Vehicle 세션보다 오래 살면 안 된다 (공식 샘플 README)
            if (brakeRegistered) runCatching { v.getBrake().unregisterBrakePedalStatus(brakeListener) }
            brakeRegistered = false
            runCatching { v.release() }
        }
        vehicle = null
        fused?.let { f ->
            runCatching { f.unregisterFusedLocationCallback(locationListener) }
            runCatching { f.release() }
        }
        fused = null
        navi?.let { n ->
            runCatching { n.removeListener(naviListener) }
            runCatching { n.release() }
        }
        navi = null
        tts?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
        tts = null
        ttsReady = false
        Log.i(TAG, "released all Pleos SDK sessions")
    }

    private companion object { const val TAG = "PrismPleosSdk" }
}
