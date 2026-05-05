package com.example.eidatacollector.presentation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class SensorDataCollector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val sensorData = mutableMapOf<Int, MutableList<FloatArray>>()

    fun getAvailableSensors(): List<Sensor> {
        val allowlist = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_HEART_RATE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_RELATIVE_HUMIDITY
        )
        return allowlist.mapNotNull { type ->
            sensorManager.getDefaultSensor(type)
        }
    }

    fun start(sensors: List<Sensor>) {
        sensorData.clear()
        sensors.forEach { sensor ->
            sensorData[sensor.type] = mutableListOf()
            sensorManager.registerListener(this, sensor, 20_000) // 50 Hz
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        sensorData[event.sensor.type]?.add(event.values.copyOf())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
