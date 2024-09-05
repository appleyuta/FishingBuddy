package com.example.fishingbuddy

import androidx.activity.enableEdgeToEdge

import android.os.Bundle
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
                    loadFragment(PairingFragment())
                    true
                }
                R.id.navigation_receive -> {
                    loadFragment(receivefragment)
                    true
                }
                else -> false
            }
        }

        // デフォルトでペアリング画面を表示
        if (savedInstanceState == null) {
            navView.selectedItemId = R.id.navigation_pairing
        }
//        // 通知チャンネルを作成
//        createNotificationChannel()
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
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