package com.appathy.kakurega

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

class SceneView(
    ctx: Context,
    private val scene: Scene,
    private val bg: Bitmap?,
    private val counts: () -> Map<String, Int>,
    private val onTap: (Hotspot) -> Unit
) : View(ctx) {

    var editMode = false
    var onDrawn: ((Float, Float, Float, Float) -> Unit)? = null

    private var hintUntil = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false
    private var dx0 = 0f
    private var dy0 = 0f
    private var dx1 = 0f
    private var dy1 = 0f

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val h = hit(e.x, e.y)
            if (h != null) onTap(h)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (editMode) return
            hintUntil = System.currentTimeMillis() + 2500
            invalidate()
        }
    })

    fun showHints() {
        hintUntil = System.currentTimeMillis() + 2500
        invalidate()
    }

    private fun hit(x: Float, y: Float): Hotspot? {
        if (width == 0 || height == 0) return null
        val fx = x / width
        val fy = y / height
        for (i in scene.hotspots.indices.reversed()) {
            val h = scene.hotspots[i]
            if (fx >= h.rx && fx <= h.rx + h.rw && fy >= h.ry && fy <= h.ry + h.rh) return h
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (hit(event.x, event.y) == null) {
                        dragging = true
                        dx0 = event.x
                        dy0 = event.y
                        dx1 = event.x
                        dy1 = event.y
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        dx1 = event.x
                        dy1 = event.y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        val w = width.toFloat()
                        val h = height.toFloat()
                        val l = minOf(dx0, dx1) / w
                        val t = minOf(dy0, dy1) / h
                        val rw = Math.abs(dx1 - dx0) / w
                        val rh = Math.abs(dy1 - dy0) / h
                        invalidate()
                        if (rw > 0.04f && rh > 0.03f) {
                            val cb = onDrawn
                            if (cb != null) cb(l, t, rw, rh)
                        }
                        return true
                    }
                }
            }
        }
        gd.onTouchEvent(event)
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (bg != null) {
            drawCover(c, bg, w, h)
        } else {
            drawRoom(c, w, h)
            for (s in scene.hotspots) drawFurniture(c, s, w, h)
        }

        if (bg != null && !editMode) {
            tp.textSize = h * 0.024f
            for (s in scene.hotspots) {
                if (s.hidden) continue
                if (s.label.length == 0) continue
                val bx = s.rx * w
                val by = (s.ry + s.rh) * h + tp.textSize + 4f
                tp.color = Color.parseColor("#000000")
                c.drawText(s.label, bx + 2f, by + 2f, tp)
                tp.color = Color.parseColor("#EEEEFF")
                c.drawText(s.label, bx, by, tp)
            }
        }

        if (editMode) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            tp.textSize = h * 0.024f
            for (s in scene.hotspots) {
                p.color = spotColor(s)
                c.drawRect(s.rx * w, s.ry * h, (s.rx + s.rw) * w, (s.ry + s.rh) * h, p)
                tp.color = p.color
                c.drawText(s.label, s.rx * w + 6f, s.ry * h + tp.textSize + 4f, tp)
            }
            if (dragging) {
                p.color = Color.parseColor("#FFFFFF")
                c.drawRect(minOf(dx0, dx1), minOf(dy0, dy1), maxOf(dx0, dx1), maxOf(dy0, dy1), p)
            }
            p.style = Paint.Style.FILL
        } else if (System.currentTimeMillis() < hintUntil) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            for (s in scene.hotspots) {
                p.color = spotColor(s)
                c.drawRect(s.rx * w, s.ry * h, (s.rx + s.rw) * w, (s.ry + s.rh) * h, p)
            }
            p.style = Paint.Style.FILL
            postInvalidateDelayed(300L)
        }

        val cnt = counts()
        tp.textSize = h * 0.026f
        for (s in scene.hotspots) {
            if (s.kind != Hotspot.KIND_SLOT) continue
            val n = cnt[s.target]
            if (n == null || n <= 0) continue
            val cx = (s.rx + s.rw) * w - 8f
            val cy = s.ry * h + 8f
            p.color = Color.parseColor("#E53935")
            c.drawCircle(cx, cy, h * 0.021f, p)
            tp.color = Color.WHITE
            val t = n.toString()
            c.drawText(t, cx - tp.measureText(t) / 2f, cy + tp.textSize * 0.35f, tp)
        }
    }

    private fun spotColor(s: Hotspot): Int {
        if (s.kind == Hotspot.KIND_GOTO) return Color.parseColor("#64B5F6")
        if (s.hidden) return Color.parseColor("#BA68C8")
        return Color.parseColor("#FFD54F")
    }

    private fun drawCover(c: Canvas, b: Bitmap, w: Float, h: Float) {
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        val scale = maxOf(w / bw, h / bh)
        val vw = bw * scale
        val vh = bh * scale
        val l = (w - vw) / 2f
        val t = (h - vh) / 2f
        c.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(l, t, l + vw, t + vh), null)
    }

    private fun drawRoom(c: Canvas, w: Float, h: Float) {
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
    }

    private fun drawFurniture(c: Canvas, s: Hotspot, w: Float, h: Float) {
        val l = s.rx * w
        val t = s.ry * h
        val r = (s.rx + s.rw) * w
        val b = (s.ry + s.rh) * h
        when (s.art) {
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
            "door" -> {
                p.color = Color.parseColor("#4A3A55")
                c.drawRect(l, t, r, b, p)
                p.color = Color.parseColor("#8899CC")
                c.drawCircle(r - (r - l) * 0.18f, (t + b) / 2f, 7f, p)
            }
            "floor" -> {
                p.color = Color.parseColor("#50402F")
                c.drawRect(l, t, r, b, p)
            }
            else -> {
                p.color = Color.parseColor("#3A3A48")
                c.drawRect(l, t, r, b, p)
            }
        }
        if (!s.hidden && s.label.length > 0) {
            tp.color = Color.parseColor("#CCCCDD")
            tp.textSize = h * 0.026f
            c.drawText(s.label, l, b + tp.textSize + 6f, tp)
        }
    }
}
