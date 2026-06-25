package com.gilbert.screenshare

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var codeInput: EditText
    private lateinit var startButton: Button
    private lateinit var projectionManager: MediaProjectionManager

    private val qualityButtons = mutableMapOf<WebRtcClient.Quality, Button>()
    private val frameRateButtons = mutableMapOf<WebRtcClient.FrameRate, Button>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discovery = PairingDiscovery()
    private var pendingUrl: String = ""
    private var discovering = false
    private var selectedQuality = WebRtcClient.Quality.HIGH
    private var selectedFrameRate = WebRtcClient.FrameRate.FPS_30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        selectedQuality = WebRtcClient.Quality.fromId(
            getPreferences(MODE_PRIVATE).getString(KEY_QUALITY, WebRtcClient.Quality.HIGH.id)
        )
        selectedFrameRate = WebRtcClient.FrameRate.fromId(
            getPreferences(MODE_PRIVATE).getString(KEY_FRAME_RATE, WebRtcClient.FrameRate.FPS_30.id)
        )
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            setBackgroundColor(COLOR_BACKGROUND)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(this).apply {
            text = "G"
            gravity = Gravity.CENTER
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(COLOR_INK, dp(20))
        }, LinearLayout.LayoutParams(dp(60), dp(60)))

        root.addView(TextView(this).apply {
            text = "G享屏"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "连接码"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        val savedCode = getPreferences(MODE_PRIVATE).getString(KEY_PAIR_CODE, "") ?: ""
        codeInput = EditText(this).apply {
            hint = "请输入连接码"
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(8))
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(savedCode)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            setHintTextColor(0xFF9AA5B1.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.WHITE, dp(12), 0xFFD8DEE6.toInt(), 1)
        }
        root.addView(codeInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(58)
        ).apply { topMargin = dp(10) })

        root.addView(buildQualitySelector(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        root.addView(buildFrameRateSelector(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        startButton = Button(this).apply {
            text = "开始共享"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(COLOR_TEAL_DARK, dp(12))
            setOnClickListener { discoverAndRequestScreenShare() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { topMargin = dp(18) })

        root.addView(Button(this).apply {
            text = "停止共享"
            isAllCaps = false
            textSize = 15f
            setTextColor(COLOR_INK)
            background = rounded(Color.WHITE, dp(12), 0xFFD8DEE6.toInt(), 1)
            setOnClickListener { stopScreenShare() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply { topMargin = dp(10) })

        setContentView(root)
    }

    private fun requestRuntimePermissions() {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun missingRuntimePermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun discoverAndRequestScreenShare() {
        val pairCode = codeInput.text.toString().trim()
        if (pairCode.length < 4) {
            showAppDialog("连接码不完整", "请输入 PC 端显示的连接码。", "知道了")
            return
        }
        if (discovering) return

        if (missingRuntimePermissions().isNotEmpty()) {
            showAppDialog(
                title = "需要权限",
                message = "请授予录音和通知权限。",
                primaryText = "去授权",
                onPrimary = { requestRuntimePermissions() },
                secondaryText = "稍后"
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            showAppDialog(
                title = "需要悬浮窗权限",
                message = "请开启悬浮窗权限。",
                primaryText = "去开启",
                onPrimary = { openOverlaySettings() },
                secondaryText = "稍后"
            )
            return
        }

        getPreferences(MODE_PRIVATE).edit().putString(KEY_PAIR_CODE, pairCode).apply()
        discovering = true
        startButton.isEnabled = false

        discovery.find(pairCode, object : PairingDiscovery.Callback {
            override fun onFound(wsUrl: String) {
                discovering = false
                startButton.isEnabled = true
                pendingUrl = wsUrl
                startActivityForResult(
                    projectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION
                )
            }

            override fun onError(message: String) {
                discovering = false
                startButton.isEnabled = true
                showAppDialog("连接失败", toChineseDiscoveryMessage(message), "知道了")
            }
        })
    }

    @Deprecated("Deprecated in Android API, kept to avoid AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            return
        }

        val intent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_SIGNALING_URL, pendingUrl)
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_MEDIA_PROJECTION_DATA, data)
            putExtra(ScreenShareService.EXTRA_QUALITY, selectedQuality.id)
            putExtra(ScreenShareService.EXTRA_FRAME_RATE, selectedFrameRate.id)
            putExtra(ScreenShareService.EXTRA_PAIR_CODE, codeInput.text.toString().trim())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        mainHandler.postDelayed({ goHome() }, 650)
    }

    private fun stopScreenShare() {
        startService(Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        })
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun buildQualitySelector(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(TextView(this).apply {
            text = "画质"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MUTED)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        WebRtcClient.Quality.values().forEachIndexed { index, quality ->
            val button = Button(this).apply {
                text = quality.label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                textSize = 13f
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener {
                    selectedQuality = quality
                    getPreferences(MODE_PRIVATE).edit().putString(KEY_QUALITY, quality.id).apply()
                    updateQualityButtons()
                }
            }
            qualityButtons[quality] = button
            row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
        container.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        updateQualityButtons()
        return container
    }

    private fun updateQualityButtons() {
        qualityButtons.forEach { (quality, button) ->
            val selected = quality == selectedQuality
            applySegmentButtonStyle(button, selected)
        }
    }

    private fun buildFrameRateSelector(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(TextView(this).apply {
            text = "帧率"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MUTED)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        WebRtcClient.FrameRate.values().forEachIndexed { index, frameRate ->
            val button = Button(this).apply {
                text = frameRate.label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                textSize = 13f
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener {
                    selectedFrameRate = frameRate
                    getPreferences(MODE_PRIVATE).edit().putString(KEY_FRAME_RATE, frameRate.id).apply()
                    updateFrameRateButtons()
                }
            }
            frameRateButtons[frameRate] = button
            row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
        container.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        updateFrameRateButtons()
        return container
    }

    private fun updateFrameRateButtons() {
        frameRateButtons.forEach { (frameRate, button) ->
            val selected = frameRate == selectedFrameRate
            applySegmentButtonStyle(button, selected)
        }
    }

    private fun applySegmentButtonStyle(button: Button, selected: Boolean) {
            button.setTextColor(if (selected) Color.WHITE else COLOR_INK)
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            button.background = if (selected) {
                rounded(COLOR_TEAL_DARK, dp(12))
            } else {
                rounded(Color.WHITE, dp(12), 0xFFD8DEE6.toInt(), 1)
            }
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }

    private fun showAppDialog(
        title: String,
        message: String,
        primaryText: String,
        onPrimary: (() -> Unit)? = null,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null
    ) {
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(Color.WHITE, dp(18))
        }
        container.addView(label(title, 19f, COLOR_INK, true))
        container.addView(label(message, 14f, COLOR_MUTED, false).apply {
            setPadding(0, dp(10), 0, dp(18))
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (secondaryText != null) {
            buttonRow.addView(dialogButton(secondaryText, false).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onSecondary?.invoke()
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                rightMargin = dp(10)
            })
        } else {
            buttonRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        buttonRow.addView(dialogButton(primaryText, true).apply {
            setOnClickListener {
                dialog.dismiss()
                onPrimary?.invoke()
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        container.addView(buttonRow)

        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dialogButton(text: String, primary: Boolean): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (primary) Color.WHITE else COLOR_INK)
            background = if (primary) {
                rounded(COLOR_TEAL_DARK, dp(11))
            } else {
                rounded(0xFFF4F6F8.toInt(), dp(11), 0xFFE3E8EF.toInt(), 1)
            }
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(dp(strokeWidth), strokeColor)
            }
        }
    }

    private fun toChineseDiscoveryMessage(message: String): String {
        return if (message.contains("PC", ignoreCase = true)) {
            "没有发现对应连接码的 PC 端。"
        } else {
            message
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_PAIR_CODE = "pair_code"
        private const val KEY_QUALITY = "quality"
        private const val KEY_FRAME_RATE = "frame_rate"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_PERMISSIONS = 1002

        private val COLOR_BACKGROUND = 0xFFF5F7FA.toInt()
        private val COLOR_INK = 0xFF18212B.toInt()
        private val COLOR_MUTED = 0xFF617080.toInt()
        private val COLOR_TEAL_DARK = 0xFF0C8C8E.toInt()
    }
}
