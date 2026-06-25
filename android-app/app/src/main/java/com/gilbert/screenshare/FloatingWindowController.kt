package com.gilbert.screenshare

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingWindowController(
    private val context: Context,
    private val onStopRequested: () -> Unit,
    private val onCloseRequested: () -> Unit = {}
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = true

    fun show() {
        if (!Settings.canDrawOverlays(context) || view != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(72)
        }
        render()
    }

    fun hide() {
        val currentView = view ?: return
        runCatching { windowManager.removeView(currentView) }
        view = null
        params = null
    }

    private fun render() {
        val layoutParams = params ?: return
        val oldView = view
        if (oldView != null) {
            runCatching { windowManager.removeView(oldView) }
        }

        if (expanded) {
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            layoutParams.width = dp(COLLAPSED_SIZE_DP)
            layoutParams.height = dp(COLLAPSED_SIZE_DP)
        }

        val nextView = if (expanded) buildExpandedView() else buildCollapsedView()
        attachDragBehavior(nextView, layoutParams)
        windowManager.addView(nextView, layoutParams)
        view = nextView
    }

    private fun buildCollapsedView(): View {
        return TextView(context).apply {
            text = "G"
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = rounded(COLOR_TEAL, dp(COLLAPSED_SIZE_DP / 2), 0x3320D8D2, 1)
            elevation = dp(8).toFloat()
            setOnClickListener {
                expanded = true
                render()
            }
        }.also {
            it.minimumWidth = dp(COLLAPSED_SIZE_DP)
            it.minimumHeight = dp(COLLAPSED_SIZE_DP)
        }
    }

    private fun buildExpandedView(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = rounded(COLOR_INK, dp(24), 0x3320D8D2, 1)
            elevation = dp(8).toFloat()
            setOnClickListener {
                expanded = false
                render()
            }

            addView(TextView(context).apply {
                text = "G"
                gravity = Gravity.CENTER
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(COLOR_TEAL, dp(16))
            }, LinearLayout.LayoutParams(dp(32), dp(32)))

            addView(TextView(context).apply {
                text = "共享中"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(10), 0, dp(10), 0)
            })

            addView(actionButton("收起", false) {
                expanded = false
                render()
            }, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
                rightMargin = dp(6)
            })

            addView(actionButton("关闭", false) {
                hide()
                onCloseRequested()
            }, LinearLayout.LayoutParams(dp(52), dp(32)).apply {
                rightMargin = dp(6)
            })

            addView(actionButton("停止", true) {
                onStopRequested()
            }, LinearLayout.LayoutParams(dp(52), dp(32)))
        }
    }

    private fun actionButton(text: String, warning: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (warning) COLOR_INK else Color.WHITE)
            background = rounded(if (warning) COLOR_AMBER else COLOR_MUTED, dp(16))
            setOnClickListener { onClick() }
        }
    }

    private fun attachDragBehavior(target: View, layoutParams: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > dp(4) || abs(dy) > dp(4))) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = startX + dx.roundToInt()
                        layoutParams.y = startY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(target, layoutParams) }
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
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

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val COLLAPSED_SIZE_DP = 72

        private val COLOR_INK = 0xFF17212B.toInt()
        private val COLOR_TEAL = 0xFF12C8C8.toInt()
        private val COLOR_AMBER = 0xFFF4A62A.toInt()
        private val COLOR_MUTED = 0xFF3B4B5C.toInt()
    }
}
