package com.neoplus.ai.integration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class AppSpec(val label: String, val packageName: String, val how: String)

data class AppEntry(val spec: AppSpec, val installed: Boolean)

object AppCatalog {
    val apps = listOf(
        AppSpec("WhatsApp", "com.whatsapp", "Notification access, share intents"),
        AppSpec("WhatsApp Business", "com.whatsapp.w4b", "Notification access, share intents"),
        AppSpec("Telegram", "org.telegram.messenger", "Notification access, share intents"),
        AppSpec("Messenger", "com.facebook.orca", "Notification access"),
        AppSpec("Facebook", "com.facebook.katana", "Notification access"),
        AppSpec("Instagram", "com.instagram.android", "Notification access"),
        AppSpec("Gmail", "com.google.android.gm", "Gmail API (OAuth) and notification access"),
        AppSpec("Google Calendar", "com.google.android.calendar", "Calendar provider (READ_CALENDAR)"),
        AppSpec("Google Drive", "com.google.android.apps.docs", "Drive API (OAuth)"),
        AppSpec("Google Contacts", "com.google.android.contacts", "Contacts provider (READ_CONTACTS)"),
        AppSpec("Google Messages", "com.google.android.apps.messaging", "Notification access, SMS role"),
        AppSpec("YouTube", "com.google.android.youtube", "Open via intents"),
        AppSpec("Chrome", "com.android.chrome", "Open via intents")
    )
}

class IntegrationManager(private val ctx: Context) {
    fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun scan(): List<AppEntry> =
        AppCatalog.apps.map { AppEntry(it, isInstalled(it.packageName)) }

    /** Returns true only if Android can resolve a launch intent for the package. */
    fun open(pkg: String): Boolean {
        val i: Intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
