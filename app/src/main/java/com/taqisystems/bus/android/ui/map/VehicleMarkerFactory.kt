package com.taqisystems.bus.android.ui.map

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.taqisystems.bus.android.R

/**
 * Returned by [VehicleMarkerFactory.get].
 *
 * @param descriptor  Ready-to-use [BitmapDescriptor] for Google Maps `Marker`.
 * @param anchorV     Vertical anchor fraction (0 = top, 1 = bottom) so that the
 *                    circle centre — not the bitmap edge — maps to the vehicle's
 *                    geographic coordinate.  Always pass as `Offset(0.5f, anchorV)`
 *                    to the Compose `Marker` anchor parameter.
 */
data class VehicleMarkerResult(
    val descriptor: BitmapDescriptor,
    val anchorV: Float,
)

/**
 * Kotlin port of OBA Android's VehicleOverlay vehicle-marker logic, extended
 * with a label strip showing route short name and data-freshness info.
 *
 * OBA approach (VehicleOverlay.java):
 *  1. Draw a BLACK template bitmap for each of 9 half-wind directions.
 *  2. Tint it: replace every BLACK pixel with the status colour (colorBitmap).
 *  3. Cache coloured circle bitmaps by "$halfWind $color" key (max 15).
 *  4. Direction baked into bitmap — no Marker.rotation / flat=true.
 *
 * Extensions:
 *  - Bus icon from ic_bus_marker.xml (Material directions_bus, white fill).
 *  - Label pill ABOVE circle: route short name + "Updated Xm Ys ago" or
 *    "Calculated from schedule".
 *  - Returns [VehicleMarkerResult] so callers can anchor the marker at the
 *    circle centre rather than the bitmap bottom.
 */
object VehicleMarkerFactory {

    val COLOR_ON_TIME   = Color.parseColor("#16A34A")
    val COLOR_DELAYED   = Color.parseColor("#1A73E8")
    val COLOR_EARLY     = Color.parseColor("#DC2626")
    val COLOR_SCHEDULED = Color.parseColor("#6B7280")

    private const val NUM_DIRECTIONS = 9
    const val HALF_WIND_NONE = 8

    private val mCircleCache   = LruCache<String, Bitmap>(15)
    private val mTemplateCache = LruCache<Int, Bitmap>(NUM_DIRECTIONS)

    // ── Direction conversion ─────────────────────────────────────────────────

    fun orientationToHalfWind(orientationDeg: Double?): Int {
        if (orientationDeg == null) return HALF_WIND_NONE
        var direction = (-orientationDeg + 90.0) % 360.0
        if (direction < 0) direction += 360.0
        val partitionSize = 360.0 / (NUM_DIRECTIONS - 1)
        val displaced = (direction + partitionSize / 2.0) % 360.0
        return (displaced / partitionSize).toInt()
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun get(
        context: Context,
        statusColor: Int,
        orientationDeg: Double?,
        routeShortName: String,
        lastUpdateMs: Long?,
        isPredicted: Boolean,
    ): VehicleMarkerResult {
        val halfWind = orientationToHalfWind(orientationDeg)
        val circle   = getColoredCircle(context, statusColor, halfWind)
        val label    = formatUpdateLabel(lastUpdateMs, isPredicted)
        return composeMarker(context, circle, statusColor, routeShortName, label)
    }

    // ── Elapsed-time label — mirrors OBA's CustomInfoWindowAdapter logic ──────

    fun formatUpdateLabel(lastUpdateMs: Long?, isPredicted: Boolean): String {
        if (!isPredicted || lastUpdateMs == null || lastUpdateMs == 0L) {
            return "Calculated from schedule"
        }
        val elapsedSec = (System.currentTimeMillis() - lastUpdateMs) / 1000L
        val elapsedMin = elapsedSec / 60L
        val secMod60   = elapsedSec % 60L
        return if (elapsedSec < 60L) {
            "Updated ${elapsedSec}s ago"
        } else {
            "Updated ${elapsedMin}m ${secMod60}s ago"
        }
    }

    // ── Coloured circle (OBA template + colorBitmap tint) ────────────────────

    private fun getColoredCircle(context: Context, color: Int, halfWind: Int): Bitmap {
        val key = "$halfWind $color"
        mCircleCache.get(key)?.let { return it }
        val tinted = colorBitmap(getTemplate(context, halfWind), color)
        mCircleCache.put(key, tinted)
        return tinted
    }

    private fun getTemplate(context: Context, halfWind: Int): Bitmap {
        mTemplateCache.get(halfWind)?.let { return it }
        val b = createTemplate(context, halfWind)
        mTemplateCache.put(halfWind, b)
        return b
    }

    /**
     * BLACK template: circle body + direction fin + bus icon from
     * drawable/ic_bus_marker.xml.  White pixels in the vector (windows,
     * tyres) are not pure BLACK so they are preserved through colorBitmap().
     */
    private fun createTemplate(context: Context, halfWind: Int): Bitmap {
        val dp = context.resources.displayMetrics.density

        val radius  = 20f * dp
        val finLen  = 10f * dp
        val finBase =  8f * dp
        val extent  = radius + finLen + 2f * dp
        val size    = (extent * 2f).toInt()
        val cx      = size / 2f
        val cy      = size / 2f

        val bmp    = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val black  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.FILL
        }

        // Directional fin
        if (halfWind < HALF_WIND_NONE) {
            canvas.save()
            canvas.rotate(halfWind * 45f, cx, cy)     // 0=N, 1=NE … 7=NW
            val path = Path().apply {
                moveTo(cx, cy - radius - finLen)
                lineTo(cx - finBase, cy - radius * 0.55f)
                lineTo(cx + finBase, cy - radius * 0.55f)
                close()
            }
            canvas.drawPath(path, black)
            canvas.restore()
        }

        // Circle body
        canvas.drawCircle(cx, cy, radius, black)

        // Bus icon from vector drawable — drawn on top of the circle
        val busDrawable: Drawable? = ContextCompat.getDrawable(
            context, R.drawable.ic_bus_marker
        )
        if (busDrawable != null) {
            val iconSize = (radius * 1.55f).toInt()
            val l = (cx - iconSize / 2f).toInt()
            val t = (cy - iconSize / 2f).toInt()
            busDrawable.setBounds(l, t, l + iconSize, t + iconSize)
            busDrawable.draw(canvas)
        } else {
            drawBusFallback(canvas, cx, cy, radius)
        }

        return bmp
    }

    // ── Compose final marker: label pill (top) + circle (bottom) ────────────

    private fun composeMarker(
        context: Context,
        circleBmp: Bitmap,
        statusColor: Int,
        routeShortName: String,
        labelText: String,
    ): VehicleMarkerResult {
        val dp = context.resources.displayMetrics.density

        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = statusColor
            textSize       = 11f * dp
            isFakeBoldText = true
            textAlign      = Paint.Align.CENTER
            typeface       = Typeface.DEFAULT_BOLD
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.parseColor("#374151")
            textSize  = 8f * dp
            textAlign = Paint.Align.CENTER
        }

        // Pill dimensions
        val contentW = maxOf(
            routePaint.measureText(routeShortName),
            labelPaint.measureText(labelText),
            circleBmp.width * 0.85f,
        )
        val pillW      = contentW + 14f * dp
        val routeLineH = -routePaint.ascent() + routePaint.descent()
        val labelLineH = -labelPaint.ascent() + labelPaint.descent()
        val pillH      = routeLineH + labelLineH + 10f * dp
        val pillR      = 5f * dp
        val gap        = 3f * dp

        // Layout: [pill] [gap] [circleBmp]
        val totalW = maxOf(circleBmp.width.toFloat(), pillW)
        val totalH = pillH + gap + circleBmp.height.toFloat()

        val bmp    = Bitmap.createBitmap(totalW.toInt(), totalH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ── Pill at top ──────────────────────────────────────────────────────
        val pL = (totalW - pillW) / 2f
        val pT = 0f

        // Drop shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33000000"); style = Paint.Style.FILL
        }
        canvas.drawRoundRect(pL + 1f, pT + 1.5f * dp, pL + pillW + 1f, pT + pillH + 1.5f * dp, pillR, pillR, shadowPaint)

        // Pill background
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        canvas.drawRoundRect(pL, pT, pL + pillW, pT + pillH, pillR, pillR, pillPaint)

        val centreX    = totalW / 2f
        val routeBaseY = pT + 4f * dp - routePaint.ascent()
        canvas.drawText(routeShortName, centreX, routeBaseY, routePaint)
        val labelBaseY = routeBaseY + routePaint.descent() + 2f * dp - labelPaint.ascent()
        canvas.drawText(labelText, centreX, labelBaseY, labelPaint)

        // ── Circle below pill ────────────────────────────────────────────────
        val circleTop = pillH + gap
        canvas.drawBitmap(circleBmp, (totalW - circleBmp.width) / 2f, circleTop, null)

        // ── Anchor: circle centre maps to the vehicle's geographic position ──
        // circleBmp is a square; its centre is at circleBmpH / 2 from its own top.
        val circleCentreY = circleTop + circleBmp.height / 2f
        val anchorV       = circleCentreY / totalH

        return VehicleMarkerResult(
            descriptor = BitmapDescriptorFactory.fromBitmap(bmp),
            anchorV    = anchorV,
        )
    }

    // ── Programmatic bus fallback (if drawable not found) ────────────────────

    private fun drawBusFallback(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = r * 0.52f
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        canvas.drawRoundRect(cx-s*.76f, cy-s*.76f, cx+s*.76f, cy+s*.56f, s*.18f, s*.18f, white)
        val wt = cy-s*.76f+s*.12f; val wh = s*.38f; val ww = s*.26f; val wc = s*.06f
        for (wx in listOf(cx-s*.60f, cx-s*.14f, cx+s*.32f))
            canvas.drawRoundRect(wx, wt, wx+ww, wt+wh, wc, wc, black)
        val wy = cy+s*.56f+s*.06f
        canvas.drawCircle(cx-s*.46f, wy, s*.16f, white)
        canvas.drawCircle(cx+s*.46f, wy, s*.16f, white)
    }

    // ── UIUtils.colorBitmap() exact port ─────────────────────────────────────

    private fun colorBitmap(source: Bitmap, color: Int): Bitmap {
        val w = source.width; val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) if (pixels[i] == Color.BLACK) pixels[i] = color
        val out = Bitmap.createBitmap(w, h, source.config ?: Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
