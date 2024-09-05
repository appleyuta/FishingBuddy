package com.example.fishingbuddy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
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
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.UUID

// PairingFragment.kt
class PairingFragment : Fragment(R.layout.fragment_pairing) {
    // ペアリング画面のロジックをここに記述
    private val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    )
    private val TAG = MainActivity::class.java.simpleName

    private val CHANNEL_ID = "fishing_buddy_channel"

    // スキャン結果を保存するリスト
    private val scanResults = mutableListOf<ScanResult>()

    // スキャン結果を表示するリストビューのアダプター
    private lateinit var adapter: ArrayAdapter<String>

    // BluetoothLeScannerのインスタンス
    private lateinit var bluetoothLeScanner: BluetoothLeScanner

    // 紐づけ結果を表示するTextView
    private lateinit var pairingResultTextView: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_pairing, container, false)

        // スキャン結果を表示するリストビューのアダプターを初期化
        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf())
        val listView: ListView = view.findViewById(R.id.device_list_view)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = scanResults[position].device
            // デバイスを選択したらスキャンを停止
//             bluetoothLeScanner.stopScan(scanCallback)
            showPairingDialog(selectedDevice)
        }

        pairingResultTextView = view.findViewById(R.id.pairing_result_text_view)

        // 保存されたUUIDを読み込み、表示
        displaySavedUUIDs()

        // パーミッションの確認
        if (allPermissionsGranted()) {
            bleScanStart()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        return view
    }

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
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // BLEスキャンを停止
        bluetoothLeScanner.stopScan(scanCallback)
    }

    /** BLEのスキャンを開始 */
    private fun bleScanStart() {
        val manager: BluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            Log.d(TAG, "Bluetoothがサポートされていません")
            return
        }
        if (!adapter.isEnabled) {
            Log.d(TAG, "Bluetoothがオフになっています")
            return
        }
        bluetoothLeScanner = adapter.bluetoothLeScanner
        val scanFilter = ScanFilter.Builder()
            .setDeviceName("Fishing Buddy BLE Server")
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        Log.d(TAG, "Start BLE scan.")
        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    // スキャンコールバックでスキャン結果をリストに追加
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            // 重複を防ぐために既に存在するデバイスかどうかをチェック
            val existingDevice = scanResults.find { it.device.address == result.device.address }
            if (existingDevice == null) {
                scanResults.add(result)
                // アダプターのデータセットを更新
//                val deviceNames = scanResults.map { it.device.name ?: "Unknown Device" }
                val deviceNamesAndUuids = scanResults.map {
                    val name = it.device.name ?: "Unknown Device"
                    val address = it.device.address ?: "Unknown Address"
                    "$name - $address"
                }
                adapter.clear()
                adapter.addAll(deviceNamesAndUuids)
                adapter.notifyDataSetChanged()
            }
        }
    }
    // デバイスに接続する関数
    private fun connectToDevice(device: BluetoothDevice) {
        val bluetoothGatt = device.connectGatt(requireContext(), false, bluetoothGattCallback)
        val resultConnectGatt = bluetoothGatt.connect()
        if (resultConnectGatt) {
            Log.d(TAG, "Success to connect gatt.")
        } else {
            Log.w(TAG, "Failed to connect gatt.")
        }
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server.")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered: ${gatt.services}")
                for (service in gatt.services) {
                    for (characteristic in service.characteristics) {
                        if (isAdded) {
                            saveUUIDs(service.uuid, characteristic.uuid)
                            activity?.runOnUiThread {
                                pairingResultTextView.text = "ステータス: ペアリング成功\nService UUID:\n  ${service.uuid}\nCharacteristic UUID:\n  ${characteristic.uuid}"
                                // 接続を解除
                                gatt.disconnect()
                            }

                        }
                    }
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {

        }
    }

    // UUIDをSharedPreferencesに保存する関数
    private fun saveUUIDs(serviceUUID: UUID, characteristicUUID: UUID) {
        val sharedPreferences = requireContext().getSharedPreferences("BLE_PREFS", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString("SERVICE_UUID", serviceUUID.toString())
        editor.putString("CHARACTERISTIC_UUID", characteristicUUID.toString())
        editor.apply()
        Log.d(TAG, "UUIDs saved: Service UUID = $serviceUUID, Characteristic UUID = $characteristicUUID")
    }

    // 保存されたUUIDを表示する関数
    private fun displaySavedUUIDs() {
        val sharedPreferences = requireContext().getSharedPreferences("BLE_PREFS", Context.MODE_PRIVATE)
        val serviceUUID = sharedPreferences.getString("SERVICE_UUID", null)
        val characteristicUUID = sharedPreferences.getString("CHARACTERISTIC_UUID", null)
        if (serviceUUID != null && characteristicUUID != null) {
            pairingResultTextView.text = "ステータス: ペアリング済み\nService UUID:\n  $serviceUUID\nCharacteristic UUID:\n  $characteristicUUID"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Fishing Buddy Channel"
            val descriptionText = "Channel for Fishing Buddy notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    private fun showPairingDialog(device: BluetoothDevice) {
        AlertDialog.Builder(requireContext())
            .setTitle("ペアリングの確認")
            .setMessage("デバイス ${device.name} とペアリングしますか？")
            .setPositiveButton("はい") { _, _ ->
                connectToDevice(device)
            }
            .setNegativeButton("いいえ") { dialog, _ ->
                dialog.dismiss()
//                // スキャンを再開
//                bleScanStart()
            }
            .show()
    }
}