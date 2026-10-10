package com.ddzhilian.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    private val chat get() = (application as ChatApplication).chat
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChatApp(chat) }
        openNotification(intent)
    }
    override fun onStart() { super.onStart(); chat.visible(true) }
    override fun onStop() { chat.visible(false); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openNotification(intent) }
    private fun openNotification(intent: Intent?) { intent?.getStringExtra("roomId")?.let { chat.openRoom(it); intent.removeExtra("roomId") } }
}
