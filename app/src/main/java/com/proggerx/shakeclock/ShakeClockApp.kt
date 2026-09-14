package com.proggerx.shakeclock

import android.app.Application
import com.google.android.material.color.DynamicColors

class ShakeClockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
