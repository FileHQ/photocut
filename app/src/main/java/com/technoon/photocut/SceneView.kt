package com.technoon.photocut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One object on the canvas. bitmap is mutable (brush edits write into it);
 * orig is the un-edited source used by Brush Restore / Fill.
 * x/y are canvas coordinates (top-left), scale multiplies bitmap size.
 * bright/contrast/saturation are percent (100 = neutral), white/black are
 * 0..100 lifts, hue is degrees -180..180. All adjustments are PER OBJECT.
 */
class SceneObj(
    val id: Int,
    val kind: String,
    val name: String,
    var bitmap: Bitmap,
    var orig: Bitmap
) {
    var x = 0f
    var y = 0f
    var scale = 1f
    var bright = 100
    var contrast = 100
    var saturation = 100
    var white = 0
    var black = 0
    var hue = 0
    var visible = true
    fun w(): Float = bitmap.width * scale
    fun h(): Float = bitmap.height * scale
    fun contains(cx: Float, cy: Float): Boolean =
        visible && cx >= x && cx <= x + w() && cy >= y && cy <= y + h()
}

/** Touch events forwarded to the activity in canvas coordinates. */
interface SceneTouchListener {
    fun onSceneDown(cx: Float, cy: Float)
    fun onSceneMove(cx: Float, cy: Float, dx: Float, dy: Float)
    fun onSceneUp(cx: Float, cy: Float, moved: Boolean)
    fun onZoomChanged(zoom: Float)
}

/**
 * Canvas view: draws background + scene objects in layer order (list order,
 * last = top), a dashed outline with corner handles on the selected object,
 * and, in insert mode, only the photo being prepared for insertion.
 * Pinch-to-zoom (100%..400%) and two-finger pan are handled here; the
 * zoom/pan are part of the view transform, and viewToCanvas() is the exact
 * inverse mapping used for brush painting, so brush strokes land where the
 * user sees them even while zoomed.
 */
class SceneView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var objects: MutableList<SceneObj> = mutableListOf()
    var selectedId = -1
    var canvasW = 1024
    var canvasH = 1024
    var exportW = 1024
    var exportH = 1024
    var bgMode = 0 // 0 transparent, 1 solid, 2 gradient, 3 blurred, 4 photo, 5 white, 6 studio, 7 flat lay
    var bgSolidColor = Color.WHITE
    var bgPhoto: Bitmap? = null
    var origBitmap: Bitmap? = null
    var showOriginal = false
    var shadowEnabled = false
    var shadowMode = 1 // 1 soft, 2 hard, 3 floating
    var insertMode = false
    var insertBitmap: Bitmap? = null
    var touchListener: SceneTouchListener? = null

    var zoom = 1f
    var panX = 0f
    var panY = 0f
    private var pinchStartDist = 0f
    private var pinchStartZoom = 1f
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private var pinchPanX = 0f
    private var pinchPanY = 0f
    private var downMoved = false
    private var lastCx = 0f
    private var lastCy = 0f

    private val paint = Paint()
    private val outlinePaint = Paint().apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val handlePaint = Paint().apply { color = Color.parseColor("#1565C0"); style = Paint.Style.FILL }

    /** View size -> canvas space, the exact inverse of the onDraw transform. */
    fun viewToCanvas(vx: Float, vy: Float): Pair<Float, Float> {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return Pair(0f, 0f)
        val cw = if (insertMode) (insertBitmap?.width ?: canvasW) else canvasW
        val ch = if (insertMode) (insertBitmap?.height ?: canvasH) else canvasH
        val ew = if (insertMode) cw else exportW
        val eh = if (insertMode) ch else exportH
        val fit = min(vw / ew, vh / eh)
        val dw = ew * fit
        val dh = eh * fit
        val ox = (vw - dw) / 2f + panX
        val oy = (vh - dh) / 2f + panY
        val ex = (vx - ox) / (fit * zoom)
        val ey = (vy - oy) / (fit * zoom)
        return Pair(ex * cw / ew, ey * ch / eh)
    }

    private fun perObjectPaint(o: SceneObj): Paint {
        val p = Paint()
        p.isAntiAlias = true
        val cm = ColorMatrix()
        cm.setSaturation(o.saturation / 100f)
        val c = o.contrast / 100f
        val offset = (1f - c) * 128f + (o.bright - 100) * 1.28f + o.white * 1.28f - o.black * 1.28f
        cm.postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, offset,
            0f, c, 0f, 0f, offset,
            0f, 0f, c, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
        if (o.hue != 0) cm.postConcat(hueMatrix(o.hue.toFloat()))
        p.colorFilter = ColorMatrixColorFilter(cm)
        return p
    }

    private fun hueMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val lumR = 0.213f
        val lumG = 0.715f
        val lumB = 0.072f
        return ColorMatrix(floatArrayOf(
            lumR + cos * (1 - lumR) + sin * (-lumR), lumG + cos * (-lumG) + sin * (-lumG), lumB + cos * (-lumB) + sin * (1 - lumB), 0f, 0f,
            lumR + cos * (-lumR) + sin * 0.143f, lumG + cos * (1 - lumG) + sin * 0.140f, lumB + cos * (-lumB) + sin * (-0.283f), 0f, 0f,
            lumR + cos * (-lumR) + sin * (-(1 - lumR)), lumG + cos * (-lumG) + sin * lumG, lumB + cos * (1 - lumB) + sin * lumB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun blurBitmap(src: Bitmap, factor: Int): Bitmap {
        val smallW = max(16, src.width / factor)
        val smallH = max(16, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        canvas.drawColor(Color.parseColor("#DDDDDD"))
        val cw: Int
        val ch: Int
        val ew: Int
        val eh: Int
        if (insertMode) {
            val ib = insertBitmap ?: return
            cw = ib.width; ch = ib.height; ew = cw; eh = ch
        } else {
            cw = canvasW; ch = canvasH; ew = exportW; eh = exportH
        }
        val fit = min(vw / ew, vh / eh)
        val dw = ew * fit
        val dh = eh * fit
        val ox = (vw - dw) / 2f + panX
        val oy = (vh - dh) / 2f + panY
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(fit * zoom, fit * zoom)
        canvas.scale(ew.toFloat() / cw, eh.toFloat() / ch)
        if (insertMode) {
            val ib = insertBitmap ?: run { canvas.restore(); return }
            drawChecker(canvas, cw, ch)
            val p = Paint()
            canvas.drawBitmap(ib, 0f, 0f, p)
            canvas.restore()
            return
        }
        if (showOriginal) {
            val ob = origBitmap
            if (ob != null) canvas.drawBitmap(Bitmap.createScaledBitmap(ob, cw, ch, true), 0f, 0f, null)
            canvas.restore()
            return
        }
        // Background (canvas space).
        paint.shader = null
        paint.colorFilter = null
        when (bgMode) {
            0 -> drawChecker(canvas, cw, ch)
            1 -> { paint.color = bgSolidColor; canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), paint) }
            2 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, ch.toFloat(), bgSolidColor, Color.WHITE, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), paint)
                paint.shader = null
            }
            3 -> {
                val ob = origBitmap
                if (ob != null) canvas.drawBitmap(blurBitmap(Bitmap.createScaledBitmap(ob, cw, ch, true), 32), 0f, 0f, null)
                else { paint.color = Color.WHITE; canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), paint) }
            }
            4 -> {
                val bg = bgPhoto
                if (bg != null) {
                    val s = max(cw.toFloat() / bg.width, ch.toFloat() / bg.height)
                    val sw = (bg.width * s).toInt()
                    val sh = (bg.height * s).toInt()
                    canvas.drawBitmap(Bitmap.createScaledBitmap(bg, sw, sh, true), (cw - sw) / 2f, (ch - sh) / 2f, null)
                } else { paint.color = Color.WHITE; canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), paint) }
            }
            5, 6 -> { paint.color = Color.WHITE; canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), paint) }
            7 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, ch.toFloat(), Color.parseColor("#F5F5F5"), Color.parseColor("#E0E0E0"), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), paint)
                paint.shader = null
            }
        }
        // Objects in layer order: list order, last drawn = top.
        for (o in objects) {
            if (!o.visible) continue
            if (shadowEnabled && shadowMode != 0 && o.kind == "MAIN") {
                drawShadow(canvas, o, cw, ch)
            }
            val scaled = Bitmap.createScaledBitmap(o.bitmap, max(1, o.w().toInt()), max(1, o.h().toInt()), true)
            canvas.drawBitmap(scaled, o.x, o.y, perObjectPaint(o))
        }
        // Selected outline + handles (topmost, never exported: export uses its own composite).
        val sel = objects.firstOrNull { it.id == selectedId && it.visible }
        if (sel != null) {
            canvas.drawRect(sel.x, sel.y, sel.x + sel.w(), sel.y + sel.h(), outlinePaint)
            val hs = 16f
            for ((hx, hy) in arrayOf(
                Pair(sel.x, sel.y), Pair(sel.x + sel.w(), sel.y),
                Pair(sel.x, sel.y + sel.h()), Pair(sel.x + sel.w(), sel.y + sel.h())
            )) {
                canvas.drawRect(hx - hs / 2, hy - hs / 2, hx + hs / 2, hy + hs / 2, handlePaint)
            }
        }
        canvas.restore()
    }

    private fun drawChecker(canvas: Canvas, w: Int, h: Int) {
        val sq = 32
        var row = 0
        var y = 0
        while (y < h) {
            var col = 0
            var x = 0
            while (x < w) {
                paint.color = if ((row + col) % 2 == 0) Color.WHITE else Color.parseColor("#CCCCCC")
                canvas.drawRect(x.toFloat(), y.toFloat(), min(w, x + sq).toFloat(), min(h, y + sq).toFloat(), paint)
                x += sq; col++
            }
            y += sq; row++
        }
    }

    private fun drawShadow(canvas: Canvas, o: SceneObj, cw: Int, ch: Int) {
        val w = o.bitmap.width
        val h = o.bitmap.height
        val pixels = IntArray(w * h)
        o.bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val alphaScale = when (shadowMode) { 2 -> 140; 3 -> 110; else -> 90 }
        val spixels = IntArray(w * h)
        for (i in pixels.indices) spixels[i] = ((Color.alpha(pixels[i]) * alphaScale / 255) shl 24)
        val shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        shadow.setPixels(spixels, 0, w, 0, 0, w, h)
        val factor = when (shadowMode) { 2 -> 8; 3 -> 16; else -> 32 }
        val offX = when (shadowMode) { 2 -> 8f; 3 -> 16f; else -> 12f }
        val offY = when (shadowMode) { 2 -> 12f; 3 -> 28f; else -> 18f }
        val blurred = blurBitmap(shadow, factor)
        val scaled = Bitmap.createScaledBitmap(blurred, max(1, o.w().toInt()), max(1, o.h().toInt()), true)
        canvas.drawBitmap(scaled, o.x + offX / o.scale, o.y + offY / o.scale, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = touchListener ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    pinchStartDist = dist(event)
                    pinchStartZoom = zoom
                    pinchMidX = (event.getX(0) + event.getX(1)) / 2f
                    pinchMidY = (event.getY(0) + event.getY(1)) / 2f
                    pinchPanX = panX
                    pinchPanY = panY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && pinchStartDist > 0f) {
                    val d = dist(event)
                    if (d > 0f) {
                        zoom = (pinchStartZoom * d / pinchStartDist).coerceIn(1f, 4f)
                        val midX = (event.getX(0) + event.getX(1)) / 2f
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        panX = pinchPanX + (midX - pinchMidX)
                        panY = pinchPanY + (midY - pinchMidY)
                        l.onZoomChanged(zoom)
                        invalidate()
                    }
                    return true
                }
                val (cx, cy) = viewToCanvas(event.x, event.y)
                if (!downMoved && (kotlin.math.abs(cx - lastCx) > 2f || kotlin.math.abs(cy - lastCy) > 2f)) downMoved = true
                val dx = cx - lastCx
                val dy = cy - lastCy
                lastCx = cx
                lastCy = cy
                l.onSceneMove(cx, cy, dx, dy)
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                val (cx, cy) = viewToCanvas(event.x, event.y)
                lastCx = cx
                lastCy = cy
                downMoved = false
                l.onSceneDown(cx, cy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val (cx, cy) = viewToCanvas(event.x, event.y)
                l.onSceneUp(cx, cy, downMoved)
                pinchStartDist = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}
