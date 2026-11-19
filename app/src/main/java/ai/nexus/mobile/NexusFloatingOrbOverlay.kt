package ai.nexus.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Build
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small, non-focusable Android overlay shown while the wake-word service waits
 * for the user's spoken request. It mirrors the orb drawn in MainActivity.
 */
internal class NexusFloatingOrbOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: OrbView? = null

    fun show(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false
        }
        if (view != null) return true

        val orbView = OrbView(context)
        val params = WindowManager.LayoutParams(
            dp(236),
            dp(218),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(74)
            windowAnimations = android.R.style.Animation_Toast
        }

        return try {
            windowManager.addView(orbView, params)
            view = orbView
            orbView.start()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        current.stop()
        try { windowManager.removeView(current) } catch (_: Exception) {}
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class OrbView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var pulse = 1f
        private var animator: ValueAnimator? = null

        fun start() {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0f, (2f * PI).toFloat()).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    pulse = 1f + 0.085f * sin(phase * 0.48f).toFloat()
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h * 0.40f
            val radius = 34f * density * pulse

            // Translucent floating glass panel.
            paint.shader = null
            paint.color = 0xD9101728.toInt()
            canvas.drawRoundRect(RectF(5f * density, 5f * density, w - 5f * density, h - 5f * density), 30f * density, 30f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = 0x5557D7FF
            canvas.drawRoundRect(RectF(5f * density, 5f * density, w - 5f * density, h - 5f * density), 30f * density, 30f * density, paint)
            paint.style = Paint.Style.FILL

            val glow = radius * 2.0f
            paint.shader = RadialGradient(cx, cy, glow,
                intArrayOf(0x7757D7FF, 0x332D8BFF, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, glow, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * density
            paint.color = 0x9957D7FF.toInt()
            canvas.drawCircle(cx, cy, radius * 1.28f, paint)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
                intArrayOf(0xFF63E6FF.toInt(), 0xFF657BFF.toInt(), 0xFFB56CFF.toInt()),
                null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
            paint.color = 0x99FFFFFF.toInt()
            canvas.drawCircle(cx - radius * 0.20f, cy - radius * 0.22f, radius * 0.52f, paint)

            val bars = 31
            val barWidth = 2.2f * density
            val baseY = cy + radius + 14f * density
            for (i in 0 until bars) {
                val normalized = i.toFloat() / (bars - 1)
                val envelope = sin(normalized * PI).toFloat()
                val oscillation = 0.25f + 0.75f * abs(sin(phase + i * 0.48f))
                val barHeight = 3f * density + 13f * density * envelope * oscillation
                val x = cx - (bars - 1) * barWidth * 1.45f / 2f + i * barWidth * 1.45f
                paint.color = Color.argb((255 * (0.45f + 0.5f * envelope)).toInt(), 131, 223, 255)
                paint.strokeWidth = barWidth
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x, baseY - barHeight / 2f, x, baseY + barHeight / 2f, paint)
            }

            paint.shader = null
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 14f * density
            canvas.drawText("NEXUS", cx, h - 39f * density, paint)
            paint.color = 0xFFB8C9E5.toInt()
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 11f * density
            canvas.drawText("Слушаю вас", cx, h - 20f * density, paint)
        }
    }
}
