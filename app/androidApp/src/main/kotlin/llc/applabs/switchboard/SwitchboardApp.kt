package llc.applabs.switchboard

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import java.lang.ref.WeakReference
import llc.applabs.switchboard.data.AppContainer

/** Holds the app's single [AppContainer]; tracks the visible Activity (passkey sheets need one). */
class SwitchboardApp : Application() {
    var currentActivity: WeakReference<Activity> = WeakReference(null)
        private set

    val container: AppContainer by lazy {
        AppContainer(
            passkeys = AndroidPasskeys(this) { currentActivity.get() },
            store = KeystoreStore(this),
            devSignIn = BuildConfig.DEV_SIGN_IN,
            deviceName = (Build.MODEL ?: "Android").take(60),
            pushTokens = FcmTokens(),
            scanQr = { scanQrCode(this) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { currentActivity = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) { currentActivity = WeakReference(a) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
