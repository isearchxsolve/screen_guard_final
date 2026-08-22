// MainActivity.kt
package com.safescreen.guard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.safescreen.guard.service.CaptureService

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    private val REQ_MEDIA_PROJECTION = 1001
    private val REQ_OVERLAY = 1002
    private val REQ_AUDIO = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val paddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            resources.displayMetrics
        ).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        statusText = TextView(this).apply {
            text = "Ready"
            gravity = Gravity.CENTER
            textSize = 14f
        }

        val btnOverlay = Button(this).apply {
            text = "Grant Overlay Permission"
            setOnClickListener { requestOverlayPermission() }
        }

        val btnAccessibility = Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // ðŸ”Š AUDIO PERMISSION BUTTON
        val btnAudio = Button(this).apply {
            text = "Grant Audio Permission"
            setOnClickListener { requestAudioPermission() }
        }

        val btnStart = Button(this).apply {
            text = "Start Protection"
            setOnClickListener {
                if (!checkOverlay()) {
                    toast("Grant overlay permission first")
                    requestOverlayPermission()
                    return@setOnClickListener
                }
                if (!checkAudio()) {
                    toast("Grant audio permission first")
                    requestAudioPermission()
                    return@setOnClickListener
                }
                startScreenCapture()
            }
        }

        // âœ… FIXED: Button created inside onCreate where context is available
        val btnTestAudio = Button(this).apply {
            text = "Test Audio Blocking (Beep)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, paddingPx / 2, 0, paddingPx / 2)
            }
            setOnClickListener {
                val service = com.safescreen.guard.service.SafeScreenService.getInstance()
                if (service != null) {
                    service.testAudioBlocking()
                    Toast.makeText(
                        this@MainActivity,
                        "Audio test triggered (play media to test)",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Service not running yet",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        root.addView(statusText)
        root.addView(btnOverlay)
        root.addView(btnAccessibility)
        root.addView(btnAudio)
        root.addView(btnTestAudio)
        root.addView(btnStart)

        setContentView(root)
    }

    private fun checkOverlay(): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQ_OVERLAY
            )
        }
    }

    private fun checkAudio(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_AUDIO
        )
    }

    private fun startScreenCapture() {
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQ_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)

        if (req == REQ_MEDIA_PROJECTION && res == RESULT_OK) {
            startForegroundService(
                Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, res)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
            )
            statusText.text = "Protection active"
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}