package com.safescreen.guard

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.safescreen.guard.ml.DetectionEngineManager
import com.safescreen.guard.service.CaptureService

class MainActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_MEDIA_PROJECTION = 1001
    private val REQUEST_OVERLAY_PERMISSION = 1002
    private lateinit var rootLayout: LinearLayout
    private lateinit var btnCapture: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val paddingDp = 16f
        val paddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            paddingDp,
            resources.displayMetrics
        ).toInt()

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        val titleText = TextView(this).apply {
            text = "SafeScreen Guard"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, paddingPx * 2)
        }

        statusText = TextView(this).apply {
            text = "Ready to start"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, paddingPx)
        }

        val btnOverlayPermission = Button(this).apply {
            text = "Grant Overlay Permission"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, paddingPx, 0, paddingPx / 2)
            }
            setOnClickListener {
                requestOverlayPermission()
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "Enable Accessibility Service"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, paddingPx / 2, 0, paddingPx / 2)
            }
            setOnClickListener {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

//        val btnTestDetection = Button(this).apply {
//            text = "ðŸ§ª Test Word Detection"
//            layoutParams = LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT,
//                LinearLayout.LayoutParams.WRAP_CONTENT
//            ).apply {
//                setMargins(0, paddingPx / 2, 0, paddingPx / 2)
//            }
////            setOnClickListener {
////                testDetectionLogic()
////            }
//        }

        btnCapture = Button(this).apply {
            text = "Start Screen Capture"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, paddingPx / 2, 0, 0)
            }
            setOnClickListener {
                if (checkOverlayPermission()) {
                    initializeAndStart()
                } else {
                    Toast.makeText(this@MainActivity, "Please grant overlay permission first", Toast.LENGTH_LONG).show()
                    requestOverlayPermission()
                }
            }
        }

        val infoText = TextView(this).apply {
            text = "Note: Models load on-demand to optimize memory usage.\nCheck logcat for detailed detection logs."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, paddingPx * 2, 0, 0)
            alpha = 0.7f
        }

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(btnOverlayPermission)
        rootLayout.addView(btnAccessibility)
        //rootLayout.addView(btnTestDetection)
        rootLayout.addView(btnCapture)
        rootLayout.addView(infoText)
        setContentView(rootLayout)

        updatePermissionStatus()
    }

    private fun testDetectionLogic() {
        try {
            val engine = DetectionEngineManager.getInstance(this)
            statusText.text = "Running tests... Check logcat"
            Toast.makeText(this, "Running detection tests... Check logcat for results", Toast.LENGTH_SHORT).show()

            // Run tests on background thread
            Thread {
                //engine.testDetection()
                runOnUiThread {
                    statusText.text = "Tests complete! Check logcat"
                    Toast.makeText(this, "Tests complete! Check logcat", Toast.LENGTH_LONG).show()
                }
            }.start()
        } catch (e: Exception) {
            statusText.text = "Test failed: ${e.message}"
            Toast.makeText(this, "Test failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = checkOverlayPermission()
        statusText.text = if (hasOverlay) {
            "Ready to start"
        } else {
            "Please grant overlay permission"
        }
    }

    private fun initializeAndStart() {
        try {
            statusText.text = "Checking detection engine..."

            val engine = DetectionEngineManager.getInstance(this)
            statusText.text = "Ready - models will load on-demand"

            requestScreenCapture()
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            Toast.makeText(this, "Failed to initialize: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestScreenCapture() {
        val captureIntent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK) {
                    val serviceIntent = Intent(this, CaptureService::class.java).apply {
                        action = CaptureService.ACTION_START
                        putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    }
                    startForegroundService(serviceIntent)
                    statusText.text = "Screen capture active"
                    Toast.makeText(this, "Screen capture enabled", Toast.LENGTH_SHORT).show()
                } else {
                    statusText.text = "Screen capture cancelled"
                }
            }
            REQUEST_OVERLAY_PERMISSION -> {
                updatePermissionStatus()
                if (checkOverlayPermission()) {
                    Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
