package com.example.fishingbuddy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// ReceiveFragment.kt
class ReceiveFragment : Fragment(R.layout.fragment_receive) {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val REQUEST_CODE_PERMISSIONS = 1
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    private val TAG = MainActivity::class.java.simpleName

    /** 当たり判定結果を表示するTextView */
    private lateinit var hitTextView: TextView
    /** 加速度センサーとジャイロセンサーの値を表示するTextView */
    private lateinit var sensorTextView: TextView
    /** RSSI(電波強度)を表示するTextView */
    private lateinit var rssiTextView: TextView
    /** 測定中を表示するImageView */
    private lateinit var measurementImageView: ImageView
    /** 当たり判定を表示するImageView */
    private lateinit var hitImageView: ImageView

    /* 当たり判定時の通知チャンネルID */
    private val CHANNEL_ID = "hit_notification_channel"

    /* BluetoothのUUID */
    private val serviceUUID = UUID.fromString("a01d9034-21c3-4618-b9ee-d6d785b218c9")
    private val characteristicUUID = UUID.fromString("f98bb903-5c5a-4f46-a0f2-dbbcf658b445")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_receive, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hitTextView = view.findViewById(R.id.hitTextView)
        sensorTextView = view.findViewById(R.id.sensorTextView)
        rssiTextView = view.findViewById(R.id.rssiTextView)
        hitImageView = view.findViewById(R.id.hitImageView)
        measurementImageView = view.findViewById(R.id.measurementImageView)

        // Initialize Bluetooth
        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Check if Bluetooth is supported
        if (bluetoothAdapter == null) {
            Toast.makeText(context, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            activity?.finish()
        }

        // Check for permissions
        if (allPermissionsGranted()) {
            bleScanStart()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        // 通知チャンネルを作成
        createNotificationChannel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // BLE接続をクリーンアップ
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    /** REQUIRED_PERMISSIONSで指定したパーミッション全てが許可済みかを取得する */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                bleScanStart()
            } else {
                Toast.makeText(context, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                activity?.runOnUiThread {
                    sensorTextView.text = "パーミッションが許可されていません"
                    rssiTextView.text = null
                }
            }
        }
    }

    /* 当たりイベント発生時の通知チャンネルを作成 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Hit Notification"
            val descriptionText = "Notification for hit events"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** BLEのスキャンを開始 */
    private fun bleScanStart() {
        val manager: BluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            Log.d(TAG, "Bluetoothがサポートされていません")
            activity?.runOnUiThread {
                sensorTextView.text = "Bluetoothがサポートされていません"
                rssiTextView.text = null
            }
            return
        }
        if (!adapter.isEnabled) {
            Log.d(TAG, "Bluetoothがオフになっています")
            activity?.runOnUiThread {
                sensorTextView.text = "Bluetoothがオフになっています。\nオンにしてからアプリを再起動してください。"
                rssiTextView.text = null
            }
            return
        }

        // ペアリング状態チェック
        val sharedPreferences = requireContext().getSharedPreferences("BLE_PREFS", Context.MODE_PRIVATE)
        val macAddressString = sharedPreferences.getString("MAC_ADDRESS", null)
        if (macAddressString == null) {
            activity?.runOnUiThread {
                sensorTextView.text = "ペアリング未実施です。\nペアリング画面からデバイスを登録してください。"
                rssiTextView.text = null
            }
            return
        }

        val bluetoothLeScanner = adapter.bluetoothLeScanner
        // "Fishing Buddy BLE Server" というデバイス名のみの通知を受け取るように設定
        val scanFilter = ScanFilter.Builder()
            .setDeviceName("Fishing Buddy BLE Server")
            .setDeviceAddress(macAddressString)
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        Log.d(TAG, "Start BLE scan.")
        activity?.runOnUiThread {
            rssiTextView.text = "Bluetoothスキャン中..."
        }
        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    /** スキャンでデバイスが見つかった際のコールバック */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            if (isAdded) {
                activity?.runOnUiThread {
                    Log.d(TAG, "Device found: ${result.device.name}")

                    rssiTextView.text = "RSSI(受信信号強度) ${result.rssi}"

                    // デバイスのGattサーバに接続
                    if (bluetoothGatt == null) {
                        bluetoothGatt = result.device.connectGatt(requireContext(), false, bluetoothGattCallback)
//                        val resultConnectGatt = bluetoothGatt.connect()
//                        if (resultConnectGatt) {
//                            Log.d(TAG, "Success to connect gatt.")
//                        } else {
//                            Log.w(TAG, "Failed to connect gatt.")
//                        }
                    }

                }
            }
        }
    }

    /** デバイスのGattサーバに接続された際のコールバック */
    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Handle connection state changes
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server.")
                // サービスが見つかったら onServicesDiscovered が呼ばれる。
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
                activity?.runOnUiThread {
                    // 計測中の表示を停止
                    stopMeasurementAnimation()
                    // 当たり判定の表示を停止
                    hideHitImage()
                    Toast.makeText(context, "デバイス接続が切断されました", Toast.LENGTH_SHORT).show()
                }
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // Handle services discovered
            super.onServicesDiscovered(gatt, status)


            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered: ${gatt.services}")
                if (gatt == null) {
                    Log.w(TAG, "Gatt is empty. Maybe Bluetooth adapter not initialized.")
                    return
                }
                Log.d(TAG, "Services discovered: ${gatt.services}")
                val service = gatt.getService(serviceUUID)
                val characteristic = service.getCharacteristic(characteristicUUID)
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
                activity?.runOnUiThread {
                    startMeasurementAnimation()
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        // 当たり判定表示有無の制御カウンター（設計として良い方法でないため、出来れば改善したい）
        private var vis_cnt = 0
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Handle characteristic changes
            super.onCharacteristicChanged(gatt, characteristic)

            Log.v(TAG, "onCharacteristicChanged")

            activity?.runOnUiThread {
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
                    // 計測中の表示を停止
                    stopMeasurementAnimation()
                    // アタリ画像を表示
                    showHitImage()
                    // 通知を表示
                    showNotification()
                    vis_cnt = 0
                } else if(vis_cnt >= 50) {
                    // アタリ画像の表示を停止
                    hideHitImage()
                    // 計測中の表示を再開
                    startMeasurementAnimation()
                }
                vis_cnt++
            }
        }

    }

    /* 計測中のアニメーション画像を表示する */
    private fun startMeasurementAnimation() {
        hitTextView.text = "計測中..."
        if (measurementImageView.visibility != View.VISIBLE) {
            measurementImageView.visibility = View.VISIBLE
            val shakeAnimation = AnimationUtils.loadAnimation(context, R.anim.shake)
            measurementImageView.startAnimation(shakeAnimation)
        }
    }

    /* 計測中のアニメーション画像を停止する */
    private fun stopMeasurementAnimation() {
        hitTextView.text = ""
        if (measurementImageView.visibility == View.VISIBLE) {
            measurementImageView.clearAnimation()
            measurementImageView.visibility = View.GONE
        }
    }

    /* 当たり判定画像を表示する */
    private fun showHitImage() {
        hitTextView.text = "アタリ！"
        if (hitImageView.visibility != View.VISIBLE) {
            hitImageView.visibility = View.VISIBLE
        }
    }

    /* 当たり判定画像を非表示にする */
    private fun hideHitImage() {
        hitTextView.text = ""
        if (hitImageView.visibility == View.VISIBLE) {
            hitImageView.visibility = View.GONE
        }
    }

    /* 当たり判定が出たときに通知を表示する */
    private fun showNotification() {
        val builder = NotificationCompat.Builder(requireContext(), CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Fishing Buddy")
            .setContentText("アタリです！")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        with(NotificationManagerCompat.from(requireContext())) {
            notify(1, builder.build())
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
}
