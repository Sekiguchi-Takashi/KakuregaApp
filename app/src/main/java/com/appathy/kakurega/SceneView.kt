package com.appathy.kakurega

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

class Hotspot(
    val id: String,
    val label: String,
    val rx: Float,
    val ry: Float,
    val rw: Float,
    val rh: Float,
    val hidden: Boolean
)

class SceneView(
    ctx: Context,
    private val onTap: (String) -> Unit,
    private val counts: () -> Map<String, Int>
) : View(ctx) {

    private val spots = listOf(
        Hotspot("bookshelf", "本棚", 0.05f, 0.30f, 0.20f, 0.45f, false),
        Hotspot("stereo", "ステレオ", 0.30f, 0.54f, 0.16f, 0.13f, false),
        Hotspot("tvstand", "テレビ台", 0.50f, 0.63f, 0.26f, 0.12f, false),
        Hotspot("drawer", "引き出し", 0.79f, 0.47f, 0.17f, 0.28f, false),
        Hotspot("floor", "床下", 0.36f, 0.86f, 0.18f, 0.09f, true)
    )

    private var hintUntil = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (width == 0 || height == 0) return true
            val x = e.x / width
            val y = e.y / height
            for (s in spots) {
                if (x >= s.rx && x <= s.rx + s.rw && y >= s.ry && y <= s.ry + s.rh) {
                    onTap(s.id)
                    return true
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            hintUntil = System.currentTimeMillis() + 2500
            invalidate()
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gd.onTouchEvent(event)
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#2E2A3A")
        c.drawRect(0f, 0f, w, h * 0.82f, p)

        p.color = Color.parseColor("#4A3B2E")
        c.drawRect(0f, h * 0.82f, w, h, p)

        p.color = Color.parseColor("#3C3025")
        p.strokeWidth = 3f
        var fy = h * 0.86f
        while (fy < h) {
            c.drawLine(0f, fy, w, fy, p)
            fy += h * 0.045f
        }

        p.color = Color.parseColor("#5A6E9E")
        c.drawRect(w * 0.55f, h * 0.12f, w * 0.85f, h * 0.38f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        p.color = Color.parseColor("#22202C")
        c.drawRect(w * 0.55f, h * 0.12f, w * 0.85f, h * 0.38f, p)
        c.drawLine(w * 0.70f, h * 0.12f, w * 0.70f, h * 0.38f, p)
        p.style = Paint.Style.FILL

        for (s in spots) drawFurniture(c, s, w, h)

        if (System.currentTimeMillis() < hintUntil) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.color = Color.parseColor("#FFD54F")
            for (s in spots) {
                c.drawRect(s.rx * w, s.ry * h, (s.rx + s.rw) * w, (s.ry + s.rh) * h, p)
            }
            p.style = Paint.Style.FILL
            postInvalidateDelayed(300L)
        }

        val cnt = counts()
        tp.textSize = h * 0.028f
        for (s in spots) {
            val n = cnt[s.id] ?: 0
            if (n > 0) {
                val cx = (s.rx + s.rw) * w - 8f
                val cy = s.ry * h + 8f
                p.color = Color.parseColor("#E53935")
                c.drawCircle(cx, cy, h * 0.022f, p)
                tp.color = Color.WHITE
                val t = n.toString()
                c.drawText(t, cx - tp.measureText(t) / 2f, cy + tp.textSize * 0.35f, tp)
            }
        }
    }

    private fun drawFurniture(c: Canvas, s: Hotspot, w: Float, h: Float) {
        val l = s.rx * w
        val t = s.ry * h
        val r = (s.rx + s.rw) * w
        val b = (s.ry + s.rh) * h
        when (s.id) {
            "bookshelf" -> {
                p.color = Color.parseColor("#6B4A2F")
                c.drawRect(l, t, r, b, p)
                p.color = Color.parseColor("#503722")
                for (i in 1 until 4) {
                    val y = t + (b - t) * i / 4
                    c.drawRect(l, y - 4f, r, y, p)
                }
                val cols = intArrayOf(
                    Color.parseColor("#C75B4A"),
                    Color.parseColor("#4A7CC7"),
                    Color.parseColor("#6FA05A"),
                    Color.parseColor("#C7A44A")
                )
                var bx = l + 8f
                var ri = 0
                while (bx < r - 14f) {
                    p.color = cols[ri % cols.size]
                    val rowIdx = ri % 4
                    val top = t + (b - t) * rowIdx / 4 + 8f
                    val bot = t + (b - t) * (rowIdx + 1) / 4 - 6f
                    c.drawRect(bx, top, bx + 10f, bot, p)
                    bx += 14f
                    ri++
                }
            }
            "stereo" -> {
                p.color = Color.parseColor("#333340")
                c.drawRect(l, t, r, b, p)
                p.color = Color.parseColor("#111118")
                val sp = (r - l) * 0.28f
                c.drawCircle(l + sp * 0.6f, (t + b) / 2f, sp * 0.42f, p)
                c.drawCircle(r - sp * 0.6f, (t + b) / 2f, sp * 0.42f, p)
                p.color = Color.parseColor("#66FFAA")
                c.drawRect(l + (r - l) * 0.42f, t + 8f, r - (r - l) * 0.42f, t + 14f, p)
            }
            "tvstand" -> {
                p.color = Color.parseColor("#5A4630")
                c.drawRect(l, t, r, b, p)
                p.color = Color.parseColor("#191922")
                c.drawRect(l + (r - l) * 0.1f, t - (b - t) * 1.5f, r - (r - l) * 0.1f, t - 6f, p)
            }
            "drawer" -> {
                p.color = Color.parseColor("#7A5A3A")
                c.drawRect(l, t, r, b, p)
                for (i in 0 until 3) {
                    val yt = t + (b - t) * i / 3 + 6f
                    val yb = t + (b - t) * (i + 1) / 3 - 6f
                    p.color = Color.parseColor("#5E442B")
                    c.drawRect(l + 6f, yt, r - 6f, yb, p)
                    p.color = Color.parseColor("#3E2C1A")
                    c.drawCircle((l + r) / 2f, (yt + yb) / 2f, 6f, p)
                }
            }
            "floor" -> {
                p.color = Color.parseColor("#50402F")
                c.drawRect(l, t, r, b, p)
            }
        }
        if (!s.hidden) {
            tp.color = Color.parseColor("#CCCCDD")
            tp.textSize = h * 0.026f
            c.drawText(s.label, l, b + tp.textSize + 6f, tp)
        }
    }
}
