package gr.vasili.hudmaps

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Τρέχουσα οδηγία πλοήγησης, όπως τη διαβάσαμε από την ειδοποίηση. */
object NavState {
    @Volatile var title: String? = null      // π.χ. "300 m" ή "Στρίψτε δεξιά"
    @Volatile var text: String? = null       // π.χ. "Λ. Κηφισίας"
    @Volatile var sub: String? = null        // π.χ. "12 λεπτά · 5,2 χλμ · 19:24"
    @Volatile var icon: Bitmap? = null       // βελάκι στροφής
    @Volatile var source: String? = null
    @Volatile var key: String? = null

    val active get() = title != null || text != null

    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun changed() = main.post { listeners.forEach { it() } }

    fun clear() {
        title = null; text = null; sub = null; icon = null; source = null; key = null
        changed()
    }
}

class MapsListener : NotificationListenerService() {

    private val navApps = setOf("com.google.android.apps.maps", "com.waze")

    override fun onListenerConnected() {
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == NavState.key) NavState.clear()
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in navApps) return
        val n = sbn.notification ?: return
        // Η πλοήγηση εμφανίζεται ως μόνιμη (ongoing) ειδοποίηση· αγνοούμε τις υπόλοιπες
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim()
        val sub = (ex.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_INFO_TEXT))?.toString()?.trim()
        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return

        NavState.title = title?.ifEmpty { null }
        NavState.text = text?.ifEmpty { null }
        NavState.sub = sub?.ifEmpty { null }
        NavState.icon = largeIcon(n)
        NavState.source = if (sbn.packageName == "com.waze") "Waze" else "Maps"
        NavState.key = sbn.key
        NavState.changed()
    }

    private fun largeIcon(n: Notification): Bitmap? = try {
        n.getLargeIcon()?.loadDrawable(this)?.let { toBitmap(it) }
    } catch (_: Exception) { null }

    private fun toBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 128
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 128
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }
}
