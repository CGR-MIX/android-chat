package com.ddzhilian.chat

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

// Activity and foreground service share ONE socket/identity, never competing hello connections.
class ChatApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val chat: ChatViewModel by lazy { ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[ChatViewModel::class.java] }
    val notifications by lazy { ChatNotifications(this) }
    val rtc by lazy { RtcRuntime(this) }
}
