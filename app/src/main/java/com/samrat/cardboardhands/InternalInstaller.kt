package com.samrat.cardboardhands

import android.app.Activity
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Installs a prepared game through NextVR's Shizuku service without opening Android's APK UI. */
object InternalInstaller {
    fun install(activity: Activity, apk: File, finished: (String?) -> Unit) {
        if (VirtualScreen.access() != VirtualScreen.Access.READY) {
            finished("Installing inside NextVR needs Shizuku running with access allowed")
            return
        }
        val delivered = AtomicBoolean(false)
        lateinit var connection: android.content.ServiceConnection
        connection = VirtualScreen.bind(activity) { service ->
            if (service == null) {
                if (delivered.compareAndSet(false, true)) finished("The Shizuku service disconnected")
                return@bind
            }
            thread(name = "PhoneXR internal installer") {
                val problem = runCatching {
                    ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        service.installApk(descriptor, apk.length())
                    }
                }.getOrElse { it.localizedMessage ?: it.javaClass.simpleName }
                activity.runOnUiThread {
                    runCatching { VirtualScreen.unbind(activity, connection) }
                    if (delivered.compareAndSet(false, true)) finished(problem)
                }
            }
        }
    }
}
