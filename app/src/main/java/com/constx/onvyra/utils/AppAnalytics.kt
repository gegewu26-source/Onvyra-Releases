package com.constx.onvyra.utils

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/** Analytics is optional for builds without a Firebase project configuration. */
class AppAnalytics(private val context: Context) {
    fun logEvent(name: String) {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            FirebaseAnalytics.getInstance(context).logEvent(name, null)
        }
    }
}
