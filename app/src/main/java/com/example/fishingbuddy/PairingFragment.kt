package com.example.fishingbuddy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
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

    // BluetoothGattのインスタンス
    private var bluetoothGatt: BluetoothGatt? = null

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

        // 保存されたMACAddressを読み込み、表示
        displaySavedMACAddress()

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
        if (::bluetoothLeScanner.isInitialized) {
            bluetoothLeScanner.stopScan(scanCallback)
        }
        // BLE接続をクリーンアップ
        if(bluetoothGatt != null) {
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
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

    // MACAddressをSharedPreferencesに保存する関数
    private fun saveMACAddress(device: BluetoothDevice) {
        val sharedPreferences = requireContext().getSharedPreferences("BLE_PREFS", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString("MAC_ADDRESS", device.address)
        editor.apply()
        Log.d(TAG, "MAC Address saved: MAC Address = ${device.address}")
        activity?.runOnUiThread {
            pairingResultTextView.text = "ステータス: ペアリング成功\nMAC Address: ${device.address}"
            Toast.makeText(context, "ペアリング成功", Toast.LENGTH_SHORT).show()
        }
    }

    // 保存されたMACAddressを表示する関数
    private fun displaySavedMACAddress() {
        val sharedPreferences = requireContext().getSharedPreferences("BLE_PREFS", Context.MODE_PRIVATE)
        val MAC_ADDRESS = sharedPreferences.getString("MAC_ADDRESS", null)
        if (MAC_ADDRESS != null) {
            pairingResultTextView.text = "ステータス: ペアリング済み\nMAC Address: $MAC_ADDRESS"
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
            .setMessage("${device.name}-${device.address} とペアリングしますか？")
            .setPositiveButton("はい") { _, _ ->
                saveMACAddress(device)
            }
            .setNegativeButton("いいえ") { dialog, _ ->
                dialog.dismiss()
//                // スキャンを再開
//                bleScanStart()
            }
            .show()
    }
}