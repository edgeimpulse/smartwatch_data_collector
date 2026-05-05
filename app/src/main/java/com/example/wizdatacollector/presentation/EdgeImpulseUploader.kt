package com.example.eidatacollector.presentation

import android.hardware.Sensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EdgeImpulseUploader {

    private const val UPLOAD_URL   = "https://ingestion.edgeimpulse.com/api/training/data"
    private const val AUDIO_URL    = "https://ingestion.edgeimpulse.com/api/training/files"
    private const val FREQUENCY_HZ = 50

    private fun axesFor(type: Int): List<Pair<String, String>> = when (type) {
        Sensor.TYPE_ACCELEROMETER        -> listOf("accX" to "m/s2", "accY" to "m/s2", "accZ" to "m/s2")
        Sensor.TYPE_GYROSCOPE            -> listOf("gyroX" to "dps", "gyroY" to "dps", "gyroZ" to "dps")
        Sensor.TYPE_HEART_RATE           -> listOf("bpm" to "bpm")
        Sensor.TYPE_LINEAR_ACCELERATION  -> listOf("linAccX" to "m/s2", "linAccY" to "m/s2", "linAccZ" to "m/s2")
        Sensor.TYPE_GRAVITY              -> listOf("gravX" to "m/s2", "gravY" to "m/s2", "gravZ" to "m/s2")
        Sensor.TYPE_ROTATION_VECTOR      -> listOf("rotX" to "unit", "rotY" to "unit", "rotZ" to "unit")
        Sensor.TYPE_MAGNETIC_FIELD       -> listOf("magX" to "uT", "magY" to "uT", "magZ" to "uT")
        Sensor.TYPE_PRESSURE             -> listOf("pressure" to "hPa")
        Sensor.TYPE_LIGHT                -> listOf("light" to "lux")
        Sensor.TYPE_PROXIMITY            -> listOf("proximity" to "cm")
        Sensor.TYPE_AMBIENT_TEMPERATURE  -> listOf("temp" to "C")
        Sensor.TYPE_RELATIVE_HUMIDITY    -> listOf("humidity" to "%")
        else                             -> listOf("val0" to "unit", "val1" to "unit", "val2" to "unit")
    }

    suspend fun upload(
        label: String,
        sensorData: Map<Int, List<FloatArray>>,
        sensors: List<Sensor>,
        apiKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val activeSensors = sensors.filter { (sensorData[it.type]?.size ?: 0) > 0 }
            if (activeSensors.isEmpty()) return@withContext false

            val axesInfo = JSONArray()
            activeSensors.forEach { sensor ->
                axesFor(sensor.type).forEach { (name, unit) ->
                    axesInfo.put(JSONObject().put("name", name).put("units", unit))
                }
            }

            val minSamples = activeSensors.mapNotNull { sensorData[it.type]?.size }.minOrNull() ?: 0
            val values = JSONArray()
            for (i in 0 until minSamples) {
                val row = JSONArray()
                activeSensors.forEach { sensor ->
                    val reading = sensorData[sensor.type]?.getOrNull(i)
                    axesFor(sensor.type).forEachIndexed { axisIdx, _ ->
                        row.put((reading?.getOrElse(axisIdx) { 0f } ?: 0f).toDouble())
                    }
                }
                values.put(row)
            }

            val payload = JSONObject().apply {
                put("protected", JSONObject().apply {
                    put("ver", "v1")
                    put("alg", "none")
                    put("iat", System.currentTimeMillis() / 1000)
                })
                put("signature", "0".repeat(64))
                put("payload", JSONObject().apply {
                    put("device_name", "galaxy-watch4")
                    put("device_type", "GALAXY_WATCH4")
                    put("interval_ms", 1000.0 / FREQUENCY_HZ)
                    put("sensors", axesInfo)
                    put("values", values)
                })
            }

            val connection = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("x-label", label)
                setRequestProperty("x-file-name", "${label}_${System.currentTimeMillis()}.json")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            OutputStreamWriter(connection.outputStream).use {
                it.write(payload.toString())
                it.flush()
            }
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..201
        } catch (e: Exception) {
            false
        }
    }

    suspend fun uploadAudio(
        label: String,
        samples: List<Short>,
        apiKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext false
        try {
            val wav      = buildWav(samples)
            val boundary = "----EIBoundary${System.currentTimeMillis()}"
            val filename = "${label}_${System.currentTimeMillis()}.wav"

            val connection = (URL(AUDIO_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("x-label", label)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
            }
            connection.outputStream.use { out ->
                val nl   = "\r\n"
                val part = "--$boundary$nl" +
                    "Content-Disposition: form-data; name=\"data\"; filename=\"$filename\"$nl" +
                    "Content-Type: audio/wav$nl$nl"
                out.write(part.toByteArray(Charsets.US_ASCII))
                out.write(wav)
                out.write("$nl--$boundary--$nl".toByteArray(Charsets.US_ASCII))
            }
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..201
        } catch (e: Exception) {
            false
        }
    }

    private fun buildWav(samples: List<Short>): ByteArray {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)                                  // PCM
        buf.putShort(1)                                  // mono
        buf.putInt(AudioDataCollector.SAMPLE_RATE)
        buf.putInt(AudioDataCollector.SAMPLE_RATE * 2)   // byte rate
        buf.putShort(2)                                  // block align
        buf.putShort(16)                                 // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }

    suspend fun uploadPpg(
        label: String,
        ppgGreen: List<Int>,
        ppgIr: List<Int>,
        ppgRed: List<Int>,
        apiKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (ppgGreen.isEmpty()) return@withContext false
        try {
            val axesInfo = JSONArray().apply {
                put(JSONObject().put("name", "ppgGreen").put("units", "adcVal"))
                put(JSONObject().put("name", "ppgIr").put("units", "adcVal"))
                put(JSONObject().put("name", "ppgRed").put("units", "adcVal"))
            }
            val minSamples = minOf(ppgGreen.size, ppgIr.size, ppgRed.size)
            val values = JSONArray()
            for (i in 0 until minSamples) {
                values.put(JSONArray().apply {
                    put(ppgGreen[i].toDouble())
                    put(ppgIr[i].toDouble())
                    put(ppgRed[i].toDouble())
                })
            }
            val payload = JSONObject().apply {
                put("protected", JSONObject().apply {
                    put("ver", "v1")
                    put("alg", "none")
                    put("iat", System.currentTimeMillis() / 1000)
                })
                put("signature", "0".repeat(64))
                put("payload", JSONObject().apply {
                    put("device_name", "galaxy-watch4")
                    put("device_type", "GALAXY_WATCH4")
                    put("interval_ms", 10.0)  // PPG_ON_DEMAND = 100 Hz
                    put("sensors", axesInfo)
                    put("values", values)
                })
            }
            val connection = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("x-label", label)
                setRequestProperty("x-file-name", "${label}_ppg_${System.currentTimeMillis()}.json")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            OutputStreamWriter(connection.outputStream).use {
                it.write(payload.toString())
                it.flush()
            }
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..201
        } catch (e: Exception) {
            false
        }
    }
}
