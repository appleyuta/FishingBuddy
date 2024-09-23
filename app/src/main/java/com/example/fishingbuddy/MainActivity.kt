package com.example.fishingbuddy

import android.content.Context
import androidx.activity.enableEdgeToEdge

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView


class MainActivity : AppCompatActivity() {
    private lateinit var receivefragment: ReceiveFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val navView: BottomNavigationView = findViewById(R.id.bottom_navigation)

        receivefragment = ReceiveFragment()

        navView.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_pairing -> {
                    if(receivefragment.isMeasuring()){
                        showWarningDialog(navView,this)
                        true
                    } else {
                        loadFragment(PairingFragment())
                        true
                    }
                }
                R.id.navigation_receive -> {
                    loadFragment(receivefragment)
                    true
                }
                else -> false
            }
        }

        // デフォルトで受信画面を表示
        if (savedInstanceState == null) {
            navView.selectedItemId = R.id.navigation_receive
        }
//        // 通知チャンネルを作成
//        createNotificationChannel()
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }


    fun showWarningDialog(navView: BottomNavigationView, context: Context) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("警告")
            .setMessage("ペアリング画面に遷移すると、計測が停止します。\nペアリング画面に遷移しますか？")
            .setPositiveButton("OK") { _, _ ->
                loadFragment(PairingFragment())
                receivefragment.setMeasuring(false)
            }
            .setNegativeButton("キャンセル") { _, _ ->
                navView.selectedItemId = R.id.navigation_receive
            }
            .create()

        dialog.show()
    }

//    /* 当たりイベント発生時の通知チャンネルを作成 */
//    private fun createNotificationChannel() {
//        val name = "Hit Notification"
//        val descriptionText = "Notification for hit events"
//        val importance = NotificationManager.IMPORTANCE_DEFAULT
//        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
//            description = descriptionText
//        }
//        val notificationManager: NotificationManager =
//            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.createNotificationChannel(channel)
//    }
}