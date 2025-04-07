package com.example.obdsound

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val TAG = "OBD2Audio"
    private val DEVICE_NAME = "OBDII" // Название устройства в Bluetooth
    private val RPM_PID = "010C\r"
    private val THROTTLE_PID = "0111\r"

    private var bluetoothSocket: BluetoothSocket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    private lateinit var rpmView: TextView
    private lateinit var connectBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private var soundPool: SoundPool? = null
    private var blowOffSoundId = 0 // Заглушка, позже будет массив

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rpmView = findViewById(R.id.rpmText)
        connectBtn = findViewById(R.id.connectBtn)

        setupSound()

        connectBtn.setOnClickListener {
            thread { connectToOBD() }
        }
    }

    private fun setupSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setAudioAttributes(attrs)
            .setMaxStreams(4)
            .build()

        blowOffSoundId = soundPool!!.load(this, R.raw.blowoff1, 1) // Заменить на нужные
    }

    private fun connectToOBD() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device = adapter?.bondedDevices?.firstOrNull { it.name.contains(DEVICE_NAME) }
        if (device == null) {
            Log.e(TAG, "OBD2 device not found")
            return
        }

        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            bluetoothSocket?.connect()
            input = bluetoothSocket!!.inputStream
            output = bluetoothSocket!!.outputStream

            startReading()
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
        }
    }

    private fun startReading() {
        thread {
            while (true) {
                try {
                    sendCommand(RPM_PID)
                    val rpm = readRPM()
                    sendCommand(THROTTLE_PID)
                    val throttle = readThrottle()

                    handler.post {
                        rpmView.text = "RPM: $rpm\nThrottle: $throttle%"
                        if (throttle > 80 && rpm > 2500) {
                            soundPool?.play(blowOffSoundId, 1f, 1f, 1, 0, 1f)
                        }
                    }

                    Thread.sleep(300)
                } catch (e: Exception) {
                    Log.e(TAG, "Read error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        output.write(cmd.toByteArray())
        output.flush()
    }

    private fun readRPM(): Int {
        val response = readResponse()
        val bytes = extractBytes(response)
        return if (bytes.size >= 2) ((bytes[0] * 256) + bytes[1]) / 4 else 0
    }

    private fun readThrottle(): Int {
        val response = readResponse()
        val bytes = extractBytes(response)
        return if (bytes.isNotEmpty()) (bytes[0] * 100) / 255 else 0
    }

    private fun readResponse(): String {
        val buffer = ByteArray(64)
        val bytes = input.read(buffer)
        return String(buffer, 0, bytes)
    }

    private fun extractBytes(response: String): List<Int> {
        return response
            .replace("\r", "")
            .replace("\n", "")
            .split(" ")
            .mapNotNull {
                try {
                    Integer.parseInt(it, 16)
                } catch (_: Exception) {
                    null
                }
            }
    }

    override fun onDestroy() {
        bluetoothSocket?.close()
        soundPool?.release()
        super.onDestroy()
    }
}
