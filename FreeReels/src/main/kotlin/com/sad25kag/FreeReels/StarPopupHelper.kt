package com.sad25kag.FreeReels

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object StarPopupHelper {
    private const val PREFS_NAME = "client_license_prefs"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_LAST_POPUP_TIME = "last_popup_time"

    fun showStarPopupIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val licenseKey = prefs.getString(KEY_LICENSE_KEY, "") ?: ""
        if (licenseKey.isNotBlank()) return

        val now = System.currentTimeMillis()
        val lastPopup = prefs.getLong(KEY_LAST_POPUP_TIME, 0L)
        if (now - lastPopup < 10_000L) return

        prefs.edit().putLong(KEY_LAST_POPUP_TIME, now).apply()

        Handler(Looper.getMainLooper()).post {
            try {
                val activity = getTopActivity() ?: return@post
                showPopup(activity, context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getTopActivity(): android.app.Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as Map<*, *>
            for (activityRecord in activities.values) {
                val activityRecordClass = activityRecordClass() ?: activityRecord::class.java
                val pausedField = activityRecordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(activityRecord) as android.app.Activity
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun activityRecordClass(): Class<*>? {
        return try {
            Class.forName("android.app.ActivityThread\$ActivityClientRecord")
        } catch (e: Exception) {
            try {
                Class.forName("android.app.ActivityThread\$ActivityRecord")
            } catch (ex: Exception) {
                null
            }
        }
    }

    private fun showPopup(activity: android.app.Activity, context: Context) {
        val builder = android.app.AlertDialog.Builder(activity)
        val inflater = LayoutInflater.from(activity)
        
        val layoutId = activity.resources.getIdentifier("star_license_popup", "layout", activity.packageName)
        val dialogView = if (layoutId != 0) {
            inflater.inflate(layoutId, null)
        } else {
            createDefaultView(activity)
        }

        builder.setView(dialogView)
        builder.setCancelable(false)
        val dialog = builder.create()

        val inputKey = dialogView.findViewById<EditText>(activity.resources.getIdentifier("input_license_key", "id", activity.packageName))
            ?: dialogView.findViewWithTag<EditText>("input_license_key")
        val btnSubmit = dialogView.findViewById<Button>(activity.resources.getIdentifier("btn_submit_license", "id", activity.packageName))
            ?: dialogView.findViewWithTag<Button>("btn_submit_license")
        val txtStatus = dialogView.findViewById<TextView>(activity.resources.getIdentifier("txt_license_status", "id", activity.packageName))
            ?: dialogView.findViewWithTag<TextView>("txt_license_status")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentKey = prefs.getString(KEY_LICENSE_KEY, "") ?: ""
        inputKey?.setText(currentKey)

        btnSubmit?.setOnClickListener {
            val key = inputKey?.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                txtStatus?.text = "License key cannot be empty"
                txtStatus?.setTextColor(android.graphics.Color.RED)
                return@setOnClickListener
            }
            txtStatus?.text = "Verifying..."
            txtStatus?.setTextColor(android.graphics.Color.BLUE)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = LicenseClient.verifyLicense(key, "Anichin")
                    withContext(Dispatchers.Main) {
                        if (response.status == "success") {
                            LicenseClient.saveLicenseKey(key)
                            txtStatus?.text = "Success!"
                            txtStatus?.setTextColor(android.graphics.Color.GREEN)
                            Handler(Looper.getMainLooper()).postDelayed({
                                dialog.dismiss()
                            }, 1000)
                        } else {
                            txtStatus?.text = response.message ?: "Verification failed"
                            txtStatus?.setTextColor(android.graphics.Color.RED)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        txtStatus?.text = e.message ?: "Error occurred"
                        txtStatus?.setTextColor(android.graphics.Color.RED)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun createDefaultView(activity: android.app.Activity): View {
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(activity).apply {
            text = "Enter License Key"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        root.addView(title)

        val input = EditText(activity).apply {
            hint = "License Key"
            tag = "input_license_key"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(input)

        val status = TextView(activity).apply {
            tag = "txt_license_status"
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }
        root.addView(status)

        val btn = Button(activity).apply {
            text = "Submit"
            tag = "btn_submit_license"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(btn)

        return root
    }
}
