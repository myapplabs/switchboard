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

    /** Activities on screen; above 0, the app is open and updates live, so notifications aren't shown. */
    @Volatile private var started = 0
    val inForeground: Boolean get() = started > 0

    val container: AppContainer by lazy {
        AppContainer(
            passkeys = AndroidPasskeys(this) { currentActivity.get() },
            store = KeystoreStore(this),
            devSignIn = BuildConfig.DEV_SIGN_IN,
            deviceName = (Build.MODEL ?: "Android").take(60),
            pushTokens = FcmTokens(),
            scanQr = { scanQrCode(this) },
            appVersion = BuildConfig.VERSION_NAME,
            pickPhoto = { Pickers.pickPhoto(this) },
            pickFile = { Pickers.pickFile(this) },
            openFile = { name, type, bytes -> Pickers.open(this, name, type, bytes) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { currentActivity = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) { currentActivity = WeakReference(a) }
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
