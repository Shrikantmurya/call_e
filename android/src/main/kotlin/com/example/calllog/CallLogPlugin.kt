package com.example.calllog

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File

class CallLogPlugin : FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {

    companion object {
        private const val TAG = "flutter/CALL_LOG"
        private const val REQUEST_CODE = 0
        private const val REQUEST_CALL_PERMISSION = 1

        private val CURSOR_PROJECTION = arrayOf(
            CallLog.Calls.CACHED_FORMATTED_NUMBER,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )
    }

    private var activity: Activity? = null
    private var context: Context? = null
    private var result: Result? = null
    private var request: MethodCall? = null
    private lateinit var channel: MethodChannel
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: PhoneStateListener

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.example.calllog")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        context = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
        cleanup()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (this.result != null) {
            result.error("ALREADY_RUNNING", "A method call is already running.", null)
            return
        }
        this.request = call
        this.result = result

        val permissions = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)
        if (hasPermissions(permissions)) {
            handleMethodCall()
        } else {
            activity?.let {
                ActivityCompat.requestPermissions(it, permissions, REQUEST_CODE)
            } ?: result.error("PERMISSION_NOT_GRANTED", "Permission request failed. Activity is null.", null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                handleMethodCall()
            } else {
                result?.error("PERMISSION_NOT_GRANTED", "Permissions not granted.", null)
                cleanup()
            }
            return true
        }
        return false
    }

    private fun handleMethodCall() {
        val requestCopy = request // Create a local copy for safe access
        val resultCopy = result // Create a local copy for safe access

        when (requestCopy?.method) {
            "getCallRecordings" -> {
                val filterString = requestCopy.argument<String>("filterRecording")
                val selectedPath = requestCopy.argument<String>("selectedPath")
                if (filterString != null) {
                    val recordings = fetchCallRecordings(filterString, selectedPath)
                    resultCopy?.success(recordings)
                } else {
                    resultCopy?.error("MISSING_ARGS", "Missing filterRecording argument", null)
                }
            }
            "makeCall" -> {
                val number = requestCopy.argument<String>("number")
                if (number != null) {
                    if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        makeCall(number)
                        resultCopy?.success(null)
                    } else {
                        ActivityCompat.requestPermissions(activity!!, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PERMISSION)
                        resultCopy?.success(null)
                    }
                } else {
                    resultCopy?.error("MISSING_ARGS", "Missing phone number argument", null)
                }
            }
            "endCall" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val telecomManager = context?.getSystemService(Context.TELEPHONY_SERVICE) as TelecomManager
                    if (telecomManager != null) {
                        if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                            telecomManager.endCall()
                            resultCopy?.success(null)
                        } else {
                            ActivityCompat.requestPermissions(activity!!, arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), REQUEST_CALL_PERMISSION)
                            resultCopy?.success(null)
                        }
                    } else {
                        resultCopy?.error("UNAVAILABLE", "TelecomManager not available.", null)
                    }
                } else {
                    resultCopy?.error("UNSUPPORTED_VERSION", "Android version does not support ending calls programmatically", null)
                }
            }
            "endUserDisconected" -> {
                startPhoneStateListener(resultCopy)
            }
            "checkForActiveCall" -> {
                val isActive = isCallActive()
                resultCopy?.success(isActive)
            }
            "get" -> queryLogs(null)
            "query" -> {
                val predicates = mutableListOf<String>()
                val args = requestCopy.arguments as? Map<String, String> ?: emptyMap()
                generatePredicate(predicates, CallLog.Calls.DATE, ">", args["dateFrom"])
                generatePredicate(predicates, CallLog.Calls.DATE, "<", args["dateTo"])
                queryLogs(predicates.joinToString(" AND "))
            }
            else -> {
                resultCopy?.notImplemented()
                cleanup()
            }
        }
    }

    private fun queryLogs(query: String?) {
        context?.contentResolver?.query(
            CallLog.Calls.CONTENT_URI,
            CURSOR_PROJECTION,
            query,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val logs = mutableListOf<Map<String, Any>>()

            while (cursor.moveToNext()) {
                val log: Map<String, Any> = mapOf(
                    "formattedNumber" to (cursor.getString(0) ?: ""),
                    "number" to (cursor.getString(1) ?: ""),
                    "callType" to cursor.getInt(2),
                    "timestamp" to cursor.getLong(3),
                    "duration" to cursor.getInt(4),
                    "name" to (cursor.getString(5) ?: "")
                )
                logs.add(log)
            }
            result?.success(logs)
        } ?: run {
            result?.error("INTERNAL_ERROR", "Error querying call logs.", null)
        }
        cleanup()
    }

    private fun fetchCallRecordings(filter: String?, selectedPath: String?): List<Uri> {
        val recordings = mutableListOf<Uri>()
        val directory = File(Environment.getExternalStorageDirectory(), selectedPath ?: "")
        if (directory.exists() && directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                if (file.name.contains(filter ?: "", ignoreCase = true)) {
                    recordings.add(Uri.fromFile(file))
                }
            }
        }
        return recordings
    }

    private fun makeCall(number: String) {
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
        }
        activity?.startActivity(callIntent)
    }

    private fun startPhoneStateListener(result: Result?) {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "Call ended")
                        result?.success(null)
                        telephonyManager.listen(this, PhoneStateListener.LISTEN_NONE)
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(TAG, "Call in progress")
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d(TAG, "Incoming call from $phoneNumber")
                    }
                }
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun isCallActive(): Boolean {
        val cursor = context?.contentResolver?.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER),
            "${CallLog.Calls.TYPE} = ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
            "${CallLog.Calls.DATE} DESC"
        )

        val hasActiveCall = cursor != null && cursor.count > 0
        cursor?.close()
        return hasActiveCall
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context!!, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun cleanup() {
        result = null
        request = null
    }

    private fun generatePredicate(predicates: MutableList<String>, column: String, operator: String, value: String?) {
        value?.let {
            predicates.add("$column $operator '$it'")
        }
    }
}
