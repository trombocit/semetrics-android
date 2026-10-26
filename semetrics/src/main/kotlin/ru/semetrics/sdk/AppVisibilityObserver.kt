package ru.semetrics.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Вызывает [onVisibilityChanged] при уходе приложения в фон и возврате из него —
 * аналог didEnterBackground/willEnterForeground на iOS, без зависимости от lifecycle-process.
 */
internal class AppVisibilityObserver(
    private val onVisibilityChanged: () -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0
    private var recreatingActivity = false

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ > 0) return
        if (recreatingActivity) recreatingActivity = false else onVisibilityChanged()
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--
        if (startedActivities > 0) return
        // Поворот экрана пересоздаёт Activity — это не уход в фон
        if (activity.isChangingConfigurations) recreatingActivity = true else onVisibilityChanged()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
