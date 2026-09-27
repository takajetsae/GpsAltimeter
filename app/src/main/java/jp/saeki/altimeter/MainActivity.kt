package jp.saeki.altimeter

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * GPS 高度計
 *  - GPS 高度: OS提供の海抜高度 → NMEA GGA → ジオイドモデル変換(Android 14+) の順で採用
 *  - 気圧高度: 気圧センサー + 基準気圧(QNH)。GPS高度で校正可能
 *  - 昇降率、最高/最低、10分間グラフ、CSV記録（Download/Altimeter）
 */
class MainActivity : Activity(), SensorEventListener, LocationListener {

    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private var pressureSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    // GPS
    private var lastLocation: Location? = null
    private var lastFixAt = 0L
    private var ellipsoidAlt: Double? = null
    private var mslAlt: Double? = null
    private var mslSource = ""
    private var vAcc: Float? = null
    private var nmeaMsl: Double? = null
    private var nmeaAt = 0L
    private var satsUsed = 0
    private var satsVisible = 0

    // 気圧
    private var pressure: Float? = null            // ローパス後 [hPa]
    private var p0 = SensorManager.PRESSURE_STANDARD_ATMOSPHERE
    private val baroHist = ArrayDeque<Pair<Long, Double>>()
    private var minAlt: Double? = null
    private var maxAlt: Double? = null

    // 記録
    private var logWriter: OutputStreamWriter? = null
    private var logName = ""
    private var logCount = 0
    private var askedPermission = false

    private lateinit var tvGps: TextView
    private lateinit var tvGpsDetail: TextView
    private lateinit var tvBaro: TextView
    private lateinit var tvBaroDetail: TextView
    private lateinit var tvVs: TextView
    private lateinit var tvMinMax: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnLog: Button
    private lateinit var chart: AltitudeChartView

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satsVisible = status.satelliteCount
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
        }
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ -> parseNmea(message) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvGps = findViewById(R.id.tvGps)
        tvGpsDetail = findViewById(R.id.tvGpsDetail)
        tvBaro = findViewById(R.id.tvBaro)
        tvBaroDetail = findViewById(R.id.tvBaroDetail)
        tvVs = findViewById(R.id.tvVs)
        tvMinMax = findViewById(R.id.tvMinMax)
        tvStatus = findViewById(R.id.tvStatus)
        btnLog = findViewById(R.id.btnLog)
        chart = findViewById(R.id.chart)

        lm = getSystemService(LocationManager::class.java)!!
        sm = getSystemService(SensorManager::class.java)!!
        pressureSensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        p0 = getPreferences(MODE_PRIVATE).getFloat("p0", SensorManager.PRESSURE_STANDARD_ATMOSPHERE)

        findViewById<Button>(R.id.btnCal).setOnClickListener { calibrateFromGps() }
        findViewById<Button>(R.id.btnQnh).setOnClickListener { showQnhDialog() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            minAlt = null; maxAlt = null; chart.clear(); toast("最高/最低とグラフをリセットしました")
        }
        btnLog.setOnClickListener { if (logWriter == null) startLog() else stopLog() }

        if (pressureSensor == null) {
            tvBaro.text = "非搭載"
            tvBaroDetail.text = "この端末には気圧センサーがありません"
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) startLocation()
        else if (!askedPermission) {
            askedPermission = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        }
        pressureSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        sm.unregisterListener(this)
        stopLocation()
    }

    override fun onDestroy() {
        stopLog()
        bg.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPermission()) startLocation()
        else tvGpsDetail.text = "位置情報の権限がありません（設定から許可してください）"
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- GPS

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnssCallback, handler)
            lm.addNmeaListener(nmeaListener, handler)
        } catch (e: Exception) {
            tvGpsDetail.text = "GPSを開始できません: ${e.message}"
        }
    }

    private fun stopLocation() {
        try {
            lm.removeUpdates(this)
            lm.unregisterGnssStatusCallback(gnssCallback)
            lm.removeNmeaListener(nmeaListener)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        lastFixAt = SystemClock.elapsedRealtime()
        ellipsoidAlt = if (location.hasAltitude()) location.altitude else null
        vAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null

        val nmeaFresh = nmeaMsl != null && lastFixAt - nmeaAt < 3000
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude() -> {
                mslAlt = location.mslAltitudeMeters; mslSource = "OS"
            }
            nmeaFresh -> {
                mslAlt = nmeaMsl; mslSource = "NMEA GGA"
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasAltitude() -> {
                convertToMsl(location)
            }
            else -> {
                mslAlt = null; mslSource = ""
            }
        }
    }

    /** Android 14+ : OS内蔵ジオイドモデルで楕円体高 → 海抜高度へ変換（I/Oがあるので別スレッド） */
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun convertToMsl(location: Location) {
        val copy = Location(location)
        bg.execute {
            try {
                AltitudeConverter().addMslAltitudeToLocation(this, copy)
                if (copy.hasMslAltitude()) {
                    val v = copy.mslAltitudeMeters
                    handler.post { mslAlt = v; mslSource = "Geoid model" }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** $GPGGA / $GNGGA の 10番目フィールド = 平均海面からの高度 */
    private fun parseNmea(message: String) {
        val body = message.trim().substringBefore('*')
        val f = body.split(',')
        if (f.size > 11 && f[0].endsWith("GGA") && f[6].isNotEmpty() && f[6] != "0") {
            f[9].toDoubleOrNull()?.let {
                nmeaMsl = it
                nmeaAt = SystemClock.elapsedRealtime()
            }
        }
    }

    // Android 10 で AbstractMethodError を避けるため明示的に実装
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        tvGpsDetail.text = "GPSがオフです"
    }

    // ---------------------------------------------------------------- 気圧

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val p = event.values[0]
        pressure = pressure?.let { it + 0.1f * (p - it) } ?: p
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun calibrateFromGps() {
        val h = mslAlt
        val p = pressure
        val fresh = SystemClock.elapsedRealtime() - lastFixAt < 5000
        when {
            p == null -> toast("気圧センサーの値がありません")
            h == null || !fresh -> toast("GPS高度が取得できていません")
            (vAcc ?: 0f) > 20f -> toast("GPS垂直精度が悪いため校正を中止しました (±${"%.0f".format(vAcc)} m)")
            else -> {
                applyP0((p / (1.0 - h / 44330.0).pow(5.255)).toFloat())
                toast("GPS高度 ${"%.1f".format(h)} m で校正しました")
            }
        }
    }

    private fun showQnhDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.1f", p0))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("基準気圧 QNH [hPa]")
            .setMessage("空港の気象情報(METAR)などの海面更正気圧を入力")
            .setView(input)
            .setPositiveButton("設定") { _, _ ->
                val v = input.text.toString().toFloatOrNull()
                if (v != null && v in 850f..1100f) applyP0(v) else toast("850〜1100 hPa の範囲で入力してください")
            }
            .setNeutralButton("標準 1013.25") { _, _ -> applyP0(SensorManager.PRESSURE_STANDARD_ATMOSPHERE) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun applyP0(v: Float) {
        p0 = v
        getPreferences(MODE_PRIVATE).edit().putFloat("p0", v).apply()
        baroHist.clear(); minAlt = null; maxAlt = null
    }

    // ---------------------------------------------------------------- 表示・記録（1秒周期）

    private fun update() {
        val now = SystemClock.elapsedRealtime()
        val fixFresh = lastFixAt > 0 && now - lastFixAt < 5000

        // GPS
        val gpsShown = if (fixFresh) mslAlt else null
        if (gpsShown != null) {
            tvGps.text = fmtM(gpsShown)
        } else if (fixFresh && ellipsoidAlt != null) {
            tvGps.text = fmtM(ellipsoidAlt!!)
        } else {
            tvGps.text = "--- m"
        }
        tvGpsDetail.text = buildString {
            if (!fixFresh) append("測位待ち… ")
            append("衛星 $satsUsed / $satsVisible")
            vAcc?.let { append("  垂直精度 ±${"%.0f".format(it)} m") }
            if (fixFresh) {
                ellipsoidAlt?.let { append("\n楕円体高 ${fmtM(it)}") }
                if (mslAlt != null) append("  [$mslSource]")
                else append("  ※海抜変換なし（楕円体高を表示）")
            }
        }

        // 気圧
        val p = pressure
        val baroAlt = p?.let { SensorManager.getAltitude(p0, it).toDouble() }
        if (baroAlt != null && p != null) {
            tvBaro.text = fmtM(baroAlt)
            tvBaroDetail.text = String.format(Locale.US, "気圧 %.2f hPa   基準 %.2f hPa", p, p0)
            baroHist.addLast(now to baroAlt)
            while (baroHist.size > 1 && now - baroHist.first().first > 10_000) baroHist.removeFirst()
        }

        // 昇降率（気圧があれば気圧、なければGPS）と最高/最低
        val ref = baroAlt ?: gpsShown
        if (baroAlt == null && gpsShown != null) {
            baroHist.addLast(now to gpsShown)
            while (baroHist.size > 1 && now - baroHist.first().first > 10_000) baroHist.removeFirst()
        }
        val vs = if (baroHist.size >= 2) {
            val a = baroHist.first(); val b = baroHist.last()
            val dt = (b.first - a.first) / 1000.0
            if (dt >= 3) (b.second - a.second) / dt * 60.0 else null
        } else null
        tvVs.text = if (vs != null) String.format(Locale.US, "昇降率 %+.1f m/min", vs) else "昇降率 --- m/min"
        if (ref != null) {
            minAlt = minOf(minAlt ?: ref, ref)
            maxAlt = maxOf(maxAlt ?: ref, ref)
        }
        tvMinMax.text = "最高 ${maxAlt?.let { fmtM(it) } ?: "---"} / 最低 ${minAlt?.let { fmtM(it) } ?: "---"}"

        chart.add(gpsShown ?: if (fixFresh) ellipsoidAlt else null, baroAlt)
        writeLog(fixFresh, gpsShown, baroAlt, vs)
    }

    private fun startLog() {
        logName = "altimeter_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, logName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Altimeter")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("ファイルを作成できません")
            val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("書き込みできません")
            logWriter = OutputStreamWriter(out, Charsets.UTF_8).also {
                it.write(
                    "time,latitude,longitude,gps_msl_m,ellipsoid_m,v_accuracy_m,msl_source," +
                        "sats_used,sats_visible,pressure_hPa,qnh_hPa,baro_alt_m,vertical_speed_m_min\n"
                )
            }
            logCount = 0
            btnLog.text = "記録停止"
            tvStatus.text = "記録中: Download/Altimeter/$logName"
        } catch (e: Exception) {
            toast("記録を開始できません: ${e.message}")
        }
    }

    private fun stopLog() {
        val w = logWriter ?: return
        try { w.close() } catch (_: Exception) {}
        logWriter = null
        btnLog.text = "記録開始"
        tvStatus.text = "保存しました: Download/Altimeter/$logName（$logCount 行）"
    }

    private fun writeLog(fixFresh: Boolean, gps: Double?, baro: Double?, vs: Double?) {
        val w = logWriter ?: return
        val loc = if (fixFresh) lastLocation else null
        fun n(v: Double?, d: Int) = v?.let { String.format(Locale.US, "%.${d}f", it) } ?: ""
        val line = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            n(loc?.latitude, 7), n(loc?.longitude, 7),
            n(gps, 1), n(if (fixFresh) ellipsoidAlt else null, 1),
            n(if (fixFresh) vAcc?.toDouble() else null, 1),
            if (gps != null) mslSource else "",
            satsUsed.toString(), satsVisible.toString(),
            n(pressure?.toDouble(), 2), n(p0.toDouble(), 2), n(baro, 1), n(vs, 1)
        ).joinToString(",")
        try {
            w.write(line + "\n"); w.flush(); logCount++
            tvStatus.text = "記録中: Download/Altimeter/$logName（$logCount 行）"
        } catch (e: Exception) {
            toast("書き込みエラー: ${e.message}"); stopLog()
        }
    }

    private fun fmtM(v: Double) = String.format(Locale.US, "%.1f m", v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
