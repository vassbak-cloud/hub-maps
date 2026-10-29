package gr.vasili.hudmaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Σχεδιάζει όλο το HUD πάνω σε μαύρο φόντο (το μαύρο δεν φαίνεται στο τζάμι).
 * Ο καθρέφτης γίνεται εδώ, με μετασχηματισμό του καμβά — καμία καταγραφή οθόνης.
 */
class HudView(ctx: Context) : View(ctx) {

    var speedKmh: Float? = null
    var heading: String = "--"
    var gpsOk = false
    var mirror = true
    var flipX = false
    var limit = 0
    var color = Color.parseColor("#38FF8B")
        set(v) { field = v; refreshPaints() }
    var dim = 1f
        set(v) { field = v; refreshPaints() }

    private val warn = Color.parseColor("#FF3B3B")
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val reg = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.CENTER }
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.LEFT }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = reg; textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init { refreshPaints() }

    private fun withAlpha(c: Int) = Color.argb((255 * dim).roundToInt().coerceIn(40, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun refreshPaints() {
        val over = limit > 0 && (speedKmh ?: 0f) > limit
        val c = withAlpha(if (over) warn else color)
        for (p in listOf(big, mid, small, dot)) {
            p.color = c
        }
        // Χωρίς λάμψη: καθαρά περιγράμματα, ώστε το διπλό είδωλο του τζαμιού να μη θολώνει τα ψηφία
        big.clearShadowLayer()
        mid.clearShadowLayer()
        iconPaint.colorFilter = PorterDuffColorFilter(withAlpha(color), PorterDuff.Mode.SRC_IN)
        iconPaint.alpha = (255 * dim).roundToInt().coerceIn(40, 255)
        invalidate()
    }

    fun refresh() = refreshPaints()

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        c.save()
        c.scale(if (flipX) -1f else 1f, if (mirror) -1f else 1f, w / 2, h / 2)

        if (NavState.active) drawNav(c, w, h) else drawSpeedOnly(c, w, h)

        c.restore()
    }

    private fun speedText() = speedKmh?.roundToInt()?.toString() ?: "--"
    private fun clock() = clockFmt.format(Date())

    /** Χωρίς πλοήγηση: μεγάλη ταχύτητα στο κέντρο. */
    private fun drawSpeedOnly(c: Canvas, w: Float, h: Float) {
        val m = min(w, h)
        big.textSize = min(m * 0.55f, w * 0.5f)
        val cy = h * 0.5f
        c.drawText(speedText(), w / 2, cy + big.textSize * 0.32f, big)
        small.textSize = m * 0.06f
        c.drawText("KM/H", w / 2, cy + big.textSize * 0.32f + small.textSize * 1.4f, small)
        small.textSize = m * 0.08f
        c.drawText("${clock()}     $heading", w / 2, cy + big.textSize * 0.32f + small.textSize * 2.6f, small)
        drawGpsDot(c, w / 2 - big.textSize * 0.9f, cy - big.textSize * 0.35f, m * 0.012f)
    }

    /** Με πλοήγηση: βέλος + απόσταση/οδηγία, δρόμος, ταχύτητα, άφιξη. */
    private fun drawNav(c: Canvas, w: Float, h: Float) {
        val landscape = w > h
        val pad = min(w, h) * 0.05f

        if (landscape) {
            // Αριστερά: βέλος · Μέση: οδηγία · Δεξιά: ταχύτητα
            val iconSize = h * 0.42f
            val iconRect = RectF(pad, h * 0.12f, pad + iconSize, h * 0.12f + iconSize)
            drawIcon(c, NavState.icon, iconRect)

            val speedW = w * 0.26f
            val textX = iconRect.right + pad
            val textW = w - textX - speedW - pad
            drawFit(c, NavState.title, textX, h * 0.34f, textW, h * 0.2f, mid)
            mid.typeface = reg
            drawFit(c, NavState.text, textX, h * 0.52f, textW, h * 0.11f, mid)
            mid.typeface = bold

            big.textSize = min(h * 0.38f, speedW * 0.75f)
            c.drawText(speedText(), w - speedW / 2 - pad / 2, h * 0.45f, big)
            small.textSize = h * 0.06f
            c.drawText("KM/H", w - speedW / 2 - pad / 2, h * 0.45f + small.textSize * 1.4f, small)

            drawEta(c, w / 2, h * 0.88f, w - 2 * pad, h * 0.14f)
            drawGpsDot(c, w - pad, pad, h * 0.015f)
        } else {
            // Κάθετα: βέλος + απόσταση πάνω, δρόμος, μεγάλη ταχύτητα, άφιξη κάτω
            val iconSize = w * 0.32f
            val iconRect = RectF(pad, h * 0.08f, pad + iconSize, h * 0.08f + iconSize)
            drawIcon(c, NavState.icon, iconRect)
            val textX = iconRect.right + pad
            drawFit(c, NavState.title, textX, iconRect.centerY() + w * 0.05f, w - textX - pad, w * 0.14f, mid)
            mid.typeface = reg
            drawFit(c, NavState.text, pad, iconRect.bottom + w * 0.12f, w - 2 * pad, w * 0.08f, mid)
            mid.typeface = bold

            big.textSize = w * 0.36f
            c.drawText(speedText(), w / 2, h * 0.64f, big)
            small.textSize = w * 0.045f
            c.drawText("KM/H", w / 2, h * 0.64f + small.textSize * 1.5f, small)

            drawEta(c, w / 2, h * 0.86f, w - 2 * pad, w * 0.11f)
            drawGpsDot(c, w - pad, pad, w * 0.015f)
        }
    }

    /** Ώρα άφιξης / χρόνος διαδρομής: μεγάλα και έντονα, η ώρα μόνο αν το Maps δεν δίνει άφιξη. */
    private fun drawEta(c: Canvas, cx: Float, y: Float, maxW: Float, size: Float) {
        val line = NavState.sub ?: clock()
        small.typeface = bold
        small.textSize = size
        drawFitCenter(c, line, cx, y, maxW, small)
        small.typeface = reg
    }

    private fun drawIcon(c: Canvas, bmp: Bitmap?, r: RectF) {
        if (bmp == null || bmp.isRecycled) return
        // Αν το εικονίδιο έχει διάφανο φόντο το βάφουμε στο χρώμα του HUD, αλλιώς το δείχνουμε ως έχει
        val transparentBg = try { Color.alpha(bmp.getPixel(0, 0)) < 40 } catch (_: Exception) { true }
        val p = if (transparentBg) iconPaint else Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = iconPaint.alpha }
        val scale = min(r.width() / bmp.width, r.height() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dst = RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2)
        c.drawBitmap(bmp, null, dst, p)
    }

    /** Γράφει κείμενο από αριστερά, μικραίνοντας το μέγεθος ώσπου να χωρέσει. */
    private fun drawFit(c: Canvas, text: String?, x: Float, y: Float, maxW: Float, size: Float, p: Paint) {
        if (text.isNullOrEmpty() || maxW <= 0) return
        p.textSize = size
        while (p.measureText(text) > maxW && p.textSize > size * 0.45f) p.textSize *= 0.92f
        var t: String = text
        if (p.measureText(t) > maxW) {
            while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
            t = "$t…"
        }
        c.drawText(t, x, y, p)
    }

    private fun drawFitCenter(c: Canvas, text: String, cx: Float, y: Float, maxW: Float, p: Paint) {
        val base = p.textSize
        while (p.measureText(text) > maxW && p.textSize > base * 0.5f) p.textSize *= 0.92f
        c.drawText(text, cx, y, p)
    }

    private fun drawGpsDot(c: Canvas, x: Float, y: Float, r: Float) {
        dot.alpha = if (gpsOk) (255 * dim).roundToInt().coerceIn(40, 255) else 60
        c.drawCircle(x, y, r, dot)
    }
}
