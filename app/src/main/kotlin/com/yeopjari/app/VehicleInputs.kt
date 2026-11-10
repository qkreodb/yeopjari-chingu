package com.yeopjari.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.yeopjari.core.Gear
import com.yeopjari.core.TurnSignal

/** 실제 차량(또는 Virtual Console)에서 들어온 최신 값. 읽지 못한 값은 null. */
data class LiveVehicle(
    val speedKmh: Double? = null,
    val gear: Gear? = null,
    val signal: TurnSignal? = null,
    val brakePressed: Boolean? = null,
    val steeringDeg: Double? = null,
)

/**
 * Pleos Vehicle SDK(방향지시등·브레이크·조향각)와 AAOS Car API(차속·기어)를 읽어 [LiveVehicle] 로 모은다.
 * 차종이나 권한 때문에 읽지 못하는 값은 null 로 남겨, 엔진이 "모름"으로 다루게 한다.
 */
class VehicleInputs(private val context: Context, private val onChange: (LiveVehicle) -> Unit) {
    private val tag = "VehicleInputs"

    @Volatile
    var latest = LiveVehicle()
        private set

    private var vehicle: Vehicle? = null
    private var car: Car? = null

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            update { it.copy(signal = value?.toCore()) }
        }

        override fun onFailed(e: Exception) {
            Log.w(tag, "turn signal", e)
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) {
            update { it.copy(brakePressed = value) }
        }

        override fun onFailed(e: Exception) {
            Log.w(tag, "brake", e)
        }
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) {
            update { it.copy(steeringDeg = value?.toDouble()) }
        }

        override fun onFailed(e: Exception) {
            Log.w(tag, "steering", e)
        }
    }

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    (value.value as? Float)?.let { mps -> update { it.copy(speedKmh = mps * 3.6) } }
                VehiclePropertyIds.GEAR_SELECTION ->
                    (value.value as? Int)?.let { g -> update { it.copy(gear = gearOf(g)) } }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(tag, "car property error $propertyId")
        }
    }

    fun start() {
        runCatching {
            Vehicle(context).also { v ->
                v.initialize()
                vehicle = v
                v.getTurnSignal().registerTurnSignalState(turnSignalListener)
                v.getBrake().registerBrakePedalStatus(brakeListener)
                v.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
                v.getTurnSignal().getCurrentTurnSignalState(
                    { value -> update { it.copy(signal = value?.toCore()) } },
                    { e -> Log.w(tag, "turn signal get", e) },
                )
            }
        }.onFailure { Log.w(tag, "Pleos Vehicle SDK 사용 불가", it) }

        runCatching {
            val c = Car.createCar(context)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm.registerCallback(propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            pm.registerCallback(propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(tag, "AAOS Car API 사용 불가", it) }
    }

    fun stop() {
        runCatching {
            vehicle?.let { v ->
                v.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
                v.release()
            }
        }
        runCatching {
            (car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(propertyCallback)
            car?.disconnect()
        }
        vehicle = null
        car = null
    }

    private fun update(change: (LiveVehicle) -> LiveVehicle) {
        latest = change(latest)
        onChange(latest)
    }

    private fun TurnSignalValue.toCore(): TurnSignal = when (this) {
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        else -> TurnSignal.OFF
    }

    /** android.car VehicleGear 값: PARK=4, REVERSE=2, NEUTRAL=1, DRIVE=8 이상 */
    private fun gearOf(value: Int): Gear = when (value) {
        4 -> Gear.P
        2 -> Gear.R
        1 -> Gear.N
        else -> Gear.D
    }
}
