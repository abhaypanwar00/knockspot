package io.github.abhaypanwar00.knockspot

import android.app.Application
import android.content.Context
import io.github.abhaypanwar00.knockspot.service.Notifications
import io.github.abhaypanwar00.knockspot.system.HotspotController

class KnockspotApp : Application() {

    lateinit var guard: Guard
        private set

    override fun onCreate() {
        super.onCreate()
        HotspotController.exemptHiddenApis()
        Notifications.createChannels(this)
        guard = Guard(this)
    }
}

val Context.guard: Guard get() = (applicationContext as KnockspotApp).guard
