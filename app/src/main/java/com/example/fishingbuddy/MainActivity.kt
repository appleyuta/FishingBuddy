package com.example.fishingbuddy

import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*


class MainActivity : AppCompatActivity() {
    /** 当たり判定結果を表示するTextView */
    private val hitTextView by lazy {
        findViewById<TextView>(R.id.hitTextView)
    }
    /** 加速度センサーとジャイロセンサーの値を表示するTextView */
    private val sensorTextView by lazy {
        findViewById<TextView>(R.id.sensorTextView)
    }
    /** RSSI(電波強度)を表示するTextView */
    private val rssiTextView by lazy {
        findViewById<TextView>(R.id.rssiTextView)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        if (allPermissionsGranted()) {
            // 位置情報のパーミッションが取得できている場合は、BLEのスキャンを開始
            bleScanStart()
        } else {
            // 位置情報のパーミッションが取得できていない場合は、位置情報取得のパーミッションの許可を求める
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS,
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // 位置情報のパーミッションが取得できている場合は、BLEのスキャンを開始
                bleScanStart()
            } else {
                sensorTextView.text = "パーミッションが許可されていません"
                rssiTextView.text = null
            }
        }
    }
    /** REQUIRED_PERMISSIONSで指定したパーミッション全てが許可済みかを取得する */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** BLEのスキャンを開始 */
    private fun bleScanStart() {
        val manager: BluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            sensorTextView.text = "Bluetoothがサポートされていません"
            rssiTextView.text = null
            return
        }
        if (!adapter.isEnabled) {
            sensorTextView.text = "Bluetoothがオフになっています。\nオンにしてからアプリを再起動してください。"
            rssiTextView.text = null
            return
        }
        val bluetoothLeScanner = adapter.bluetoothLeScanner
        // "Fishing Buddy BLE Server" というデバイス名のみの通知を受け取るように設定
        val scanFilter = ScanFilter.Builder()
            .setDeviceName("Fishing Buddy BLE Server")
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        Log.d(TAG, "Start BLE scan.")
        rssiTextView.text = "Bluetoothスキャン中..."
        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    /** スキャンでデバイスが見つかった際のコールバック */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            rssiTextView.text = "RSSI(受信信号強度) ${result.rssi}"

            // デバイスのGattサーバに接続
            val bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
            val resultConnectGatt = bluetoothGatt.connect()
            if (resultConnectGatt) {
                Log.d(TAG, "Success to connect gatt.")
            } else {
                Log.w(TAG, "Failed to connect gatt.")
            }
        }
    }

    /** デバイスのGattサーバに接続された際のコールバック */
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (gatt == null) {
                Log.w(TAG, "Gatt is empty. Maybe Bluetooth adapter not initialized.")
                return
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Discover services.")
                // GATTサーバのサービスを探索する。
                // サービスが見つかったら onServicesDiscovered が呼ばれる。
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)

            Log.d(TAG, "Services discovered.")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (gatt == null) {
                    Log.w(TAG, "Gatt is empty. Maybe Bluetooth adapter not initialized.")
                    return
                }
                val service = gatt.getService(BLE_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BLE_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.w(TAG, "Characteristic is empty. Maybe Bluetooth adapter not initialized.")
                    return
                }

                // Characteristic BLE_CHARACTERISTIC_UUID のNotifyを監視する。
                // 変化があったら onCharacteristicChanged が呼ばれる。
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)

                Log.v("debug", "接続！")
            }
        }

        // 当たり判定表示有無の制御カウンター（設計として良い方法でないため、出来れば改善したい）
        private var vis_cnt = 0
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)

            Log.v(TAG, "onCharacteristicChanged")

            this@MainActivity.runOnUiThread {
                val value = characteristic?.value
                if (value == null) {
                    Log.e(TAG, "Characteristic value is null")
                    return@runOnUiThread
                }

                val data = Data.parse(value)
                if (data == null) {
                    Log.e(TAG, "Failed to parse data")
                    return@runOnUiThread
                }
                // val data = Data.parse(characteristic?.value) ?: return@runOnUiThread

                val sb = StringBuilder()
                sb.append("gyroX: ${String.format("%.2f", data.gyroX)}")
                sb.append(" gyroY: ${String.format("%.2f", data.gyroY)}")
                sb.append(" gyroZ: ${String.format("%.2f", data.gyroZ)}\n")
                sb.append("accX: ${String.format("%.2f", data.accX)}")
                sb.append(" accY: ${String.format("%.2f", data.accY)}")
                sb.append(" accZ: ${String.format("%.2f", data.accZ)}\n")
                sensorTextView.text = sb.toString()

                // 当たり判定をチェックして表示
                if (data.judgeHit) {
                    hitTextView.text = "当たり！"
                    vis_cnt = 0
                } else if(vis_cnt >= 50) {
                    hitTextView.text = ""
                }
                vis_cnt++
                // sensorTextView.text = characteristic?.getStringValue(0) ?: return@runOnUiThread
            }
        }
    }

    /** ジャイロセンサーと加速度センサーの情報を持つデータクラス */
    private data class Data(
        val gyroX: Float, val gyroY: Float, val gyroZ: Float,
        val accX: Float, val accY: Float, val accZ: Float,
        val judgeHit: Boolean,
    ) {
        companion object {
            /**
             * BLEから飛んできたデータをDataクラスにパースする
             */
            fun parse(data: ByteArray?): Data? {
                if (data == null || data.size < 13) {
                    Log.e("Data", "Invalid data size: ${data?.size}")
                    return null
                }

                try {
                    val gyroXBytes = ByteBuffer.wrap(data, 0, 2).order(ByteOrder.LITTLE_ENDIAN)
                    val gyroYBytes = ByteBuffer.wrap(data, 2, 2).order(ByteOrder.LITTLE_ENDIAN)
                    val gyroZBytes = ByteBuffer.wrap(data, 4, 2).order(ByteOrder.LITTLE_ENDIAN)
                    val accXBytes = ByteBuffer.wrap(data, 6, 2).order(ByteOrder.LITTLE_ENDIAN)
                    val accYBytes = ByteBuffer.wrap(data, 8, 2).order(ByteOrder.LITTLE_ENDIAN)
                    val accZBytes = ByteBuffer.wrap(data, 10, 2).order(ByteOrder.LITTLE_ENDIAN)

                    // 16bit浮動小数点のデータを32bit浮動小数点に変換
                    val gyroX = float16ToFloat32(gyroXBytes.short)
                    val gyroY = float16ToFloat32(gyroYBytes.short)
                    val gyroZ = float16ToFloat32(gyroZBytes.short)
                    val accX = float16ToFloat32(accXBytes.short)
                    val accY = float16ToFloat32(accYBytes.short)
                    val accZ = float16ToFloat32(accZBytes.short)

                    // 当たり判定
                    val judgeHit = data[12].toInt() == 1

                    Log.d(
                        "Data",
                        "Parsed data: gyroX=$gyroX, gyroY=$gyroY, gyroZ=$gyroZ, accX=$accX, accY=$accY, accZ=$accZ"
                    )

                    return Data(gyroX, gyroY, gyroZ, accX, accY, accZ, judgeHit)
                } catch (e: Exception) {
                    Log.e("Data", "Error parsing data", e)
                    return null
                }
            }

            fun float16ToFloat32(value: Short): Float {
                val sign = (value.toInt() and 0x8000) shl 16
                var exponent = (value.toInt() and 0x7C00) shr 10
                val fraction = (value.toInt() and 0x03FF) shl 13

                if (exponent == 0) {
                    // Subnormal number or zero
                    exponent = 0
                } else if (exponent == 31) {
                    // Infinity or NaN
                    // 仮数部が0の場合は疑似的に0を設定
                    return if (fraction == 0) {
                        (0).toFloat()
                    } else {
                        Float.NaN
                    }
                } else {
                    exponent += 112
                }

                val bits = sign or (exponent shl 23) or fraction
                return Float.fromBits(bits)
            }
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        /** BLEのサービスUUID */
        private val BLE_SERVICE_UUID = UUID.fromString("a01d9034-21c3-4618-b9ee-d6d785b218c9")

        /** BLEのCharacteristic UUID */
        private val BLE_CHARACTERISTIC_UUID = UUID.fromString("f98bb903-5c5a-4f46-a0f2-dbbcf658b445")
    }
}