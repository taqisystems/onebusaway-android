package com.taqisystems.bus.android.ui.map

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.taqisystems.bus.android.R
import com.taqisystems.bus.android.data.model.TransitType

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

    private val mCircleCache   = LruCache<String, Bitmap>(128)
    private val mTemplateCache  = LruCache<Int, Bitmap>(128)

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
        statusLabel: String,
        showLabel: Boolean = true,
        isPinned: Boolean = false,
        transitType: TransitType = TransitType.BUS,
    ): VehicleMarkerResult {
        val halfWind = orientationToHalfWind(orientationDeg)
        val circle   = getColoredCircle(context, statusColor, halfWind, transitType)
        if (!showLabel) {
            return VehicleMarkerResult(
                descriptor = BitmapDescriptorFactory.fromBitmap(circle),
                anchorV    = 0.5f,
            )
        }
        val label = formatUpdateLabel(statusLabel, lastUpdateMs, isPredicted)
        return composeMarker(context, circle, statusColor, routeShortName, label, isPinned)
    }

    // ── Status + elapsed-time label ──────────────────────────────────────────

    fun formatUpdateLabel(statusLabel: String, lastUpdateMs: Long?, isPredicted: Boolean): String {
        if (!isPredicted || lastUpdateMs == null || lastUpdateMs == 0L) {
            return statusLabel
        }
        val elapsedSec = (System.currentTimeMillis() - lastUpdateMs) / 1000L
        val elapsedMin = elapsedSec / 60L
        val secMod60   = elapsedSec % 60L
        val ago = if (elapsedSec < 60L) "${elapsedSec}s ago" else "${elapsedMin}m ${secMod60}s ago"
        return "$statusLabel · $ago"
    }

    // ── Coloured circle (OBA template + colorBitmap tint) ────────────────────

    private fun getColoredCircle(context: Context, color: Int, halfWind: Int,
                                   transitType: TransitType = TransitType.BUS): Bitmap {
        val key = "$halfWind $color ${transitType.name}"
        mCircleCache.get(key)?.let { return it }
        val tinted = colorBitmap(getTemplate(context, halfWind, transitType), color)
        mCircleCache.put(key, tinted)
        return tinted
    }

    private fun getTemplate(context: Context, halfWind: Int,
                             transitType: TransitType = TransitType.BUS): Bitmap {
        val cacheKey = halfWind * 100 + transitType.ordinal
        mTemplateCache.get(cacheKey)?.let { return it }
        val b = createTemplate(context, halfWind, transitType)
        mTemplateCache.put(cacheKey, b)
        return b
    }

    /**
     * BLACK template: circle body + direction fin + mode-specific icon drawn
     * programmatically so every transit type gets its own recognisable symbol.
     * White pixels are preserved through colorBitmap() (used for interior details).
     */
    private fun createTemplate(context: Context, halfWind: Int,
                                transitType: TransitType = TransitType.BUS): Bitmap {
        val dp = context.resources.displayMetrics.density

        val radius  = 14f * dp   // was 20dp — now matches stop badge scale (~28dp diameter)
        val finLen  =  7f * dp   // was 10dp
        val finBase =  6f * dp   // was  8dp
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

        // Mode-specific icon drawn directly on canvas in WHITE.
        // Drawing in WHITE means colorBitmap() leaves them untouched while
        // tinting the BLACK circle body — guaranteed on every device/theme.
        // Map each transit type to its white-fill vector drawable.
        // All drawables use #FFFFFFFF fill so colorBitmap() tints the circle
        // body but leaves the icon white — identical to how ic_bus_marker works.
        val iconRes = when (transitType) {
            TransitType.COMMUTER_RAIL -> R.drawable.ic_train_marker
            TransitType.LRT_TRAM      -> R.drawable.ic_lrt_marker
            TransitType.MRT_METRO     -> R.drawable.ic_mrt_marker
            TransitType.MONORAIL      -> R.drawable.ic_monorail_marker
            TransitType.FERRY         -> null
            else                      -> R.drawable.ic_bus_marker
        }
        val d = iconRes?.let { ContextCompat.getDrawable(context, it) }
        if (d != null) {
            val sz = (radius * 1.55f).toInt()
            d.setBounds((cx - sz / 2f).toInt(), (cy - sz / 2f).toInt(),
                        (cx + sz / 2f).toInt(), (cy + sz / 2f).toInt())
            d.draw(canvas)
        } else {
            when (transitType) {
                TransitType.FERRY -> drawFerryIcon(canvas, cx, cy, radius)
                else              -> drawBusFallback(canvas, cx, cy, radius)
            }
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
        isPinned: Boolean = false,
    ): VehicleMarkerResult {
        val dp = context.resources.displayMetrics.density

        val textColor = if (isPinned) Color.WHITE else statusColor
        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = textColor
            textSize       = if (isPinned) 10f * dp else 9f * dp
            isFakeBoldText = true
            textAlign      = Paint.Align.CENTER
            typeface       = Typeface.DEFAULT_BOLD
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = textColor
            alpha     = if (isPinned) 255 else 210
            textSize  = if (isPinned) 8f * dp else 7f * dp
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
            color = if (isPinned) statusColor else Color.WHITE; style = Paint.Style.FILL
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

    // ── Mode-specific inner icons (all WHITE so colorBitmap leaves them intact) ──

    /**
     * KTM Commuter / train — front-face view:
     *  - White rounded cab body
     *  - Two black windshield panes (colorBitmap tints them to status color = glass effect)
     *  - Two white wheels at the bottom
     * Mirrors the visual language of the Material 'train' icon at map scale.
     */
    private fun drawTrainFront(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s     = r * 0.52f
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        // Cab body
        canvas.drawRoundRect(cx - s*.78f, cy - s*.80f, cx + s*.78f, cy + s*.52f,
            s*.22f, s*.22f, white)
        // Left windshield pane (becomes status-color via colorBitmap)
        canvas.drawRoundRect(cx - s*.68f, cy - s*.68f, cx - s*.08f, cy - s*.08f,
            s*.10f, s*.10f, black)
        // Right windshield pane
        canvas.drawRoundRect(cx + s*.08f, cy - s*.68f, cx + s*.68f, cy - s*.08f,
            s*.10f, s*.10f, black)
        // Two wheels
        val wy = cy + s*.52f + s*.16f
        canvas.drawCircle(cx - s*.44f, wy, s*.16f, white)
        canvas.drawCircle(cx + s*.44f, wy, s*.16f, white)
    }

    /** LRT: compact tram car body + track line + two wheels — white on tinted circle. */
    private fun drawLrtIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val s = r * 0.60f
        // car body
        val bodyW = s * 1.30f; val bodyH = s * 0.62f
        val bodyTop = cy - s * 0.62f
        canvas.drawRoundRect(cx - bodyW/2, bodyTop, cx + bodyW/2, bodyTop + bodyH,
            bodyH*0.28f, bodyH*0.28f, p)
        // track line
        val trackY = bodyTop + bodyH + s*0.12f
        canvas.drawRoundRect(cx - bodyW*0.60f, trackY, cx + bodyW*0.60f, trackY + s*0.18f,
            s*0.09f, s*0.09f, p)
        // two wheels
        val wR = s * 0.16f
        canvas.drawCircle(cx - bodyW*0.28f, trackY + s*0.09f, wR, p)
        canvas.drawCircle(cx + bodyW*0.28f, trackY + s*0.09f, wR, p)
        // window strip cut-out — draw in BLACK so colorBitmap turns it back to status color
        val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        val winH = bodyH * 0.34f
        val winTop = bodyTop + bodyH * 0.12f
        canvas.drawRoundRect(cx - bodyW*0.38f, winTop, cx + bodyW*0.38f, winTop + winH,
            winH*0.25f, winH*0.25f, winPaint)
    }

    /** MRT/Metro: bold circle ring + centre dot — white on tinted circle. */
    private fun drawMrtIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r * 0.24f
        }
        canvas.drawCircle(cx, cy - r*0.06f, r * 0.50f, ringPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy - r*0.06f, r * 0.14f, dotPaint)
    }

    /** KTM Commuter: two heavy parallel rails + three cross-ties — white on tinted circle. */
    private fun drawRailIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val s = r * 0.62f
        val railW = s * 1.30f; val railH = s * 0.22f; val railR = railH / 2
        val topY = cy - s * 0.54f; val botY = cy + s * 0.10f
        canvas.drawRoundRect(cx-railW/2, topY, cx+railW/2, topY+railH, railR, railR, p)
        canvas.drawRoundRect(cx-railW/2, botY, cx+railW/2, botY+railH, railR, railR, p)
        val tieW = s * 0.22f; val tieH = botY - topY + railH
        for (ox in listOf(-railW*0.38f, 0f, railW*0.38f))
            canvas.drawRoundRect(cx+ox-tieW/2, topY, cx+ox+tieW/2, topY+tieH, tieW/2, tieW/2, p)
    }

    /** Monorail: single elevated beam + short vertical strut — white on tinted circle. */
    private fun drawMonorailIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val s = r * 0.60f
        val beamW = s * 1.32f; val beamH = s * 0.26f
        val beamTop = cy - s * 0.20f
        canvas.drawRoundRect(cx-beamW/2, beamTop, cx+beamW/2, beamTop+beamH, beamH/2, beamH/2, p)
        val strutW = s * 0.22f
        canvas.drawRoundRect(cx-strutW/2, beamTop+beamH, cx+strutW/2, cy+s*0.56f, strutW/2, strutW/2, p)
    }

    /** Ferry: two wave arcs — white on tinted circle. */
    private fun drawFerryIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val wp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = r * 0.20f; strokeCap = Paint.Cap.ROUND
        }
        val s = r * 0.55f
        for (offsetY in listOf(-s*0.30f, s*0.30f)) {
            val path = Path()
            path.moveTo(cx - s, cy + offsetY)
            path.quadTo(cx - s*0.5f, cy + offsetY - s*0.28f, cx, cy + offsetY)
            path.quadTo(cx + s*0.5f, cy + offsetY + s*0.28f, cx + s, cy + offsetY)
            canvas.drawPath(path, wp)
        }
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
