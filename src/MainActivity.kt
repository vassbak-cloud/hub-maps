package gr.vasili.hudmaps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), LocationListener {

    private lateinit var hud: HudView
    private lateinit var panel: View
    private lateinit var setup: View
    private lateinit var setupText: TextView
    private lateinit var btnNotif: Button
    private lateinit var btnRestricted: Button
    private lateinit var btnLocation: Button

    private val prefs by lazy { getSharedPreferences("hud", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val hidePanel = Runnable { panel.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            hud.gpsOk = System.currentTimeMillis() - lastFix < 4000
            // Έλεγχος της ειδοποίησης του Maps κάθε δευτερόλεπτο
            if (!MapsListener.poll() && hasNotifAccess()) rebindListener()
            hud.invalidate()
            main.postDelayed(this, 1000)
        }
    }

    private val colors = listOf("#38FF8B" to "Πράσινο", "#FFFFFF" to "Λευκό", "#FFB020" to "Πορτοκαλί", "#38D4FF" to "Γαλάζιο")
    private var colorIdx = 0
    private var setupDismissed = false

    private var lastLoc: Location? = null
    private var lastFix = 0L
    private var smooth: Float? = null
    private val navListener: () -> Unit = { hud.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Μέγιστη φωτεινότητα μόνο όσο είναι ανοιχτή η εφαρμογή· στην έξοδο το Android επαναφέρει τη δική σου
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }

        hud = HudView(this)
        colorIdx = prefs.getInt("color", 0).coerceIn(0, colors.lastIndex)
        hud.mirror = prefs.getBoolean("mirror", true)
        hud.flipX = prefs.getBoolean("flipx", false)
        hud.limit = prefs.getInt("limit", 0)
        hud.dim = prefs.getFloat("dim", 1f)
        hud.color = Color.parseColor(colors[colorIdx].first)
        requestedOrientation = prefs.getInt("orient", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

        hud.setOnClickListener { togglePanel() }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        panel = buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setup = buildSetup()
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        NavState.listeners.add(navListener)
        startLocation()
        updateSetup()
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        NavState.listeners.remove(navListener)
        main.removeCallbacks(tick)
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) { }
    }

    // ---------- Τοποθεσία / ταχύτητα ----------

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasLocation()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) { }
    }

    override fun onLocationChanged(loc: Location) {
        lastFix = System.currentTimeMillis()
        val prev = lastLoc
        var v: Float? = if (loc.hasSpeed()) loc.speed * 3.6f else null
        if (v == null && prev != null) {
            val dt = (loc.time - prev.time) / 1000f
            if (dt > 0) v = prev.distanceTo(loc) / dt * 3.6f
        }
        val now = v
        if (now != null) {
            val prevSmooth = smooth
            val s = if (prevSmooth == null) now else prevSmooth * 0.4f + now * 0.6f
            smooth = s
            hud.speedKmh = if (s < 2f) 0f else s
        }
        if (loc.hasBearing() && (hud.speedKmh ?: 0f) > 5f) {
            val dirs = arrayOf("Β", "ΒΑ", "Α", "ΝΑ", "Ν", "ΝΔ", "Δ", "ΒΔ")
            hud.heading = dirs[((loc.bearing + 22.5f) / 45f).toInt().mod(8)]
        }
        lastLoc = loc
        hud.gpsOk = true
        hud.refresh()
    }

    @Deprecated("Απαιτείται για παλιές εκδόσεις Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) { }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
        updateSetup()
    }

    // ---------- Άδειες / οδηγός πρώτης χρήσης ----------

    private var lastRebind = 0L

    /** Αν το Android έχει «κοιμίσει» τον αναγνώστη ειδοποιήσεων, τον ξυπνάμε. */
    private fun rebindListener() {
        val now = System.currentTimeMillis()
        if (now - lastRebind < 10_000) return
        lastRebind = now
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, MapsListener::class.java))
        } catch (_: Exception) { }
    }

    private fun hasNotifAccess(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun updateSetup() {
        val loc = hasLocation()
        val notif = hasNotifAccess()
        btnLocation.visibility = if (loc) View.GONE else View.VISIBLE
        btnNotif.visibility = if (notif) View.GONE else View.VISIBLE
        btnRestricted.visibility = if (notif) View.GONE else View.VISIBLE
        setupText.text = buildString {
            append("Για να δουλέψει χρειάζονται δύο άδειες:\n\n")
            append(if (loc) "✓ Τοποθεσία (για την ταχύτητα)\n" else "• Τοποθεσία (για την ταχύτητα)\n")
            append(if (notif) "✓ Πρόσβαση σε ειδοποιήσεις\n" else "• Πρόσβαση σε ειδοποιήσεις (για τις οδηγίες του Google Maps)\n")
            if (!notif) {
                append("\nΑν στη λίστα το HUD Maps είναι γκρι και δεν ανοίγει: πάτα «Πληροφορίες εφαρμογής», ")
                append("μετά τις τρεις τελείες ⋮ πάνω δεξιά → «Να επιτρέπονται οι περιορισμένες ρυθμίσεις», και ξαναδοκίμασε.\n")
            }
            append("\nΗ εφαρμογή διαβάζει μόνο την ειδοποίηση πλοήγησης του Google Maps / Waze. ")
            append("Δεν καταγράφει την οθόνη και δεν αποθηκεύει ούτε στέλνει τίποτα.")
        }
        setup.visibility = if ((loc && notif) || setupDismissed) View.GONE else View.VISIBLE
    }

    private fun buildSetup(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(TextView(this).apply {
            text = "HUD MAPS"
            setTextColor(Color.parseColor("#38FF8B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        setupText = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(16), 0, dp(16))
        }
        col.addView(setupText)
        btnLocation = bigButton("Άδεια τοποθεσίας") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        btnNotif = bigButton("Άνοιγμα «Πρόσβαση σε ειδοποιήσεις»") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnRestricted = bigButton("Πληροφορίες εφαρμογής") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val skip = bigButton("Συνέχεια μόνο με ταχύτητα", primary = false) {
            setupDismissed = true; updateSetup()
        }
        for (b in listOf(btnLocation, btnNotif, btnRestricted, skip)) {
            col.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(col)
        }
    }

    // ---------- Πάνελ ρυθμίσεων (εμφανίζεται με ένα άγγιγμα) ----------

    private fun togglePanel() {
        main.removeCallbacks(hidePanel)
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
        } else {
            panel.visibility = View.VISIBLE
            main.postDelayed(hidePanel, 7000)
        }
    }

    private fun keepPanel() {
        main.removeCallbacks(hidePanel)
        main.postDelayed(hidePanel, 7000)
    }

    private fun buildPanel(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        fun add(v: View) = row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })

        add(smallButton("Καθρέφτης ↕") { hud.mirror = !hud.mirror; save(); hud.refresh() })
        add(smallButton("Αναστροφή ↔") { hud.flipX = !hud.flipX; save(); hud.refresh() })
        add(smallButton("Χρώμα") {
            colorIdx = (colorIdx + 1) % colors.size
            hud.color = Color.parseColor(colors[colorIdx].first); save()
        })
        add(smallButton("Φωτ. −") { hud.dim = (hud.dim - 0.15f).coerceAtLeast(0.25f); save() })
        add(smallButton("Φωτ. +") { hud.dim = (hud.dim + 0.15f).coerceAtMost(1f); save() })
        add(smallButton("Οριζόντια / Κάθετα") {
            requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            save()
        })
        val limitIn = EditText(this).apply {
            hint = "Όριο km/h"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (hud.limit > 0) hud.limit.toString() else "")
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            minWidth = dp(110)
            setOnFocusChangeListener { _, _ -> keepPanel() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    hud.limit = s?.toString()?.toIntOrNull() ?: 0; save(); hud.refresh(); keepPanel()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(limitIn)
        add(smallButton("Άδειες") { setupDismissed = false; setup.visibility = View.VISIBLE; updateSetup() })
        add(smallButton("Έξοδος") { finishAndRemoveTask() })

        return HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#CC111111"))
            addView(row)
            visibility = View.GONE
        }
    }

    private fun save() {
        prefs.edit()
            .putBoolean("mirror", hud.mirror)
            .putBoolean("flipx", hud.flipX)
            .putInt("color", colorIdx)
            .putFloat("dim", hud.dim)
            .putInt("limit", hud.limit)
            .putInt("orient", requestedOrientation)
            .apply()
        keepPanel()
    }

    // ---------- Βοηθητικά ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#262626")); setStroke(dp(1), Color.parseColor("#444444"))
        }
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun bigButton(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) Color.parseColor("#38FF8B") else Color.parseColor("#262626"))
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }
}
