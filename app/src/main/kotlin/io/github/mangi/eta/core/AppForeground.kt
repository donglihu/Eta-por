package io.github.mangi.eta.core

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 进程级前台判定：按可见 Activity 计数（onStart/onStop 配对）。
 * 审批卡通知与完成通知用它决定「应用内静默、应用外提醒」。
 */
internal object AppForeground {

    @Volatile
    private var visibleActivities: Int = 0

    val isForeground: Boolean
        get() = visibleActivities > 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities += 1
            }

            override fun onActivityStopped(activity: Activity) {
                visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
