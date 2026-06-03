package com.investments.wear.watchface

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BtcWatchFaceService : WatchFaceService() {

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = BtcWatchFaceRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository,
            canvasType = CanvasType.HARDWARE
        )

        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer
        )
    }
}

class BtcWatchFaceRenderer(
    private val context: android.content.Context,
    surfaceHolder: SurfaceHolder,
    private val watchState: WatchState,
    private val currentUserStyleRepository: CurrentUserStyleRepository,
    canvasType: Int
) : Renderer.CanvasRenderer2<BtcWatchFaceRenderer.SharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    canvasType,
    16L,
    false
), SensorEventListener {
    class SharedAssets : Renderer.SharedAssets {
        override fun onDestroy() {}
    }

    override suspend fun createSharedAssets(): SharedAssets {
        return SharedAssets()
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var btcPrice: String = "Loading..."
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

    private val timePaint = Paint().apply {
        color = Color.WHITE
        textSize = 55f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private val datePaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    private val pricePaint = Paint().apply {
        color = Color.WHITE
        textSize = 55f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#F7931A")
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private var t212Returns: String = "T212: Loading..."
    private val t212Paint = Paint().apply {
        color = Color.parseColor("#00E676")
        textSize = 38f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("watchface_prefs", Context.MODE_PRIVATE)
    private var accumulatedSteps: Float = sharedPrefs.getFloat("accumulatedSteps", 0f)
    private var lastSensorSteps: Float = sharedPrefs.getFloat("lastSensorSteps", -1f)
    private var lastDay: Int = sharedPrefs.getInt("lastDay", -1)
    private var stepsText: String = if (accumulatedSteps > 0f) "Steps: ${accumulatedSteps.toInt()}" else "Steps: ..."
    private val stepsPaint = Paint().apply {
        color = Color.parseColor("#03DAC5")
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
    }

    private var weatherText: String = "Temp: Loading..."
    private val tempPaint = Paint().apply {
        color = Color.parseColor("#FFCA28")
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private var isSensorRegistered = false

    init {
        scope.launch {
            watchState.isVisible.collect { isVisible ->
                if (isVisible == true) {
                    val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) {
                        stepsText = "Steps: Need Perm"
                        isSensorRegistered = false
                        invalidate()
                    } else {
                        if (!isSensorRegistered && stepSensor != null) {
                            isSensorRegistered = sensorManager.registerListener(this@BtcWatchFaceRenderer, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                        }
                        if (stepsText == "Steps: ..." || stepsText == "Steps: Need Perm") {
                            stepsText = "Steps: Walk..."
                            invalidate()
                        }
                    }
                    
                    fetchBtcPrice()
                    fetchT212Returns()
                    fetchWeather()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000L) // Fetch every 5 minutes
                if (watchState.isVisible.value == true) {
                    fetchBtcPrice()
                    fetchT212Returns()
                    fetchWeather()
                }
            }
        }
        if (stepSensor == null) {
            stepsText = "Steps: N/A"
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val currentSensorSteps = event.values[0]
            
            // Subtract 9 hours so that 9 AM becomes 0 AM (midnight) of a new "shift day"
            val adjustedNow = ZonedDateTime.now().minusHours(9)
            val currentDay = adjustedNow.dayOfYear + (adjustedNow.year * 1000)
            
            if (lastDay != currentDay) {
                accumulatedSteps = 0f
                lastDay = currentDay
            }
            
            if (lastSensorSteps == -1f) {
                lastSensorSteps = currentSensorSteps
            }
            
            var delta = currentSensorSteps - lastSensorSteps
            if (delta < 0) {
                // Device likely rebooted, so the sensor reset to 0
                delta = currentSensorSteps
            }
            
            accumulatedSteps += delta
            lastSensorSteps = currentSensorSteps
            
            sharedPrefs.edit()
                .putFloat("accumulatedSteps", accumulatedSteps)
                .putFloat("lastSensorSteps", lastSensorSteps)
                .putInt("lastDay", lastDay)
                .apply()
            
            stepsText = "Steps: ${accumulatedSteps.toInt()}"
            invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private suspend fun fetchBtcPrice() {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.binance.com/api/v3/ticker/price?symbol=BTCEUR")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val jsonObject = JSONObject(response)
                    val priceStr = jsonObject.getString("price")
                    val priceFloat = priceStr.toFloat()
                    btcPrice = String.format("€%.2f", priceFloat)
                    invalidate()
                } else {
                    btcPrice = "Error: ${connection.responseCode}"
                    invalidate()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                btcPrice = "Error"
                invalidate()
            }
        }
    }

    private suspend fun fetchT212Returns() {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://live.trading212.com/api/v0/equity/account/cash")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val apiKey = "45396063ZCuCwUwftfveQUgPrhVgxmwvgjcxm"
                val apiSecret = "tEo_5iz09reOThZFF9dKE_GVS49AbwfGW1ZIt6ledPA"
                val credentials = "$apiKey:$apiSecret"
                val basicAuth = "Basic " + android.util.Base64.encodeToString(credentials.toByteArray(), android.util.Base64.NO_WRAP)
                
                connection.setRequestProperty("Authorization", basicAuth)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val jsonObject = JSONObject(response)
                    
                    var returns = 0.0
                    if (jsonObject.has("ppl")) {
                        returns = jsonObject.getDouble("ppl")
                    } else if (jsonObject.has("result")) {
                        returns = jsonObject.getDouble("result")
                    }
                    
                    val sign = if (returns >= 0) "+" else ""
                    t212Paint.color = if (returns >= 0) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
                    t212Returns = "T212: $sign€%.2f".format(returns)
                    invalidate()
                } else {
                    t212Returns = "T212 Err: ${connection.responseCode}"
                    invalidate()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                t212Returns = "T212 Error"
                invalidate()
            }
        }
    }

    private suspend fun fetchWeather() {
        withContext(Dispatchers.IO) {
            try {
                // Use wttr.in for specific location without API key
                val weatherUrl = URL("https://wttr.in/Charneca+da+Caparica,+Almada,+Portugal?format=j1")
                val weatherConn = weatherUrl.openConnection() as HttpURLConnection
                weatherConn.requestMethod = "GET"
                weatherConn.connectTimeout = 5000
                weatherConn.readTimeout = 5000
                weatherConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                
                if (weatherConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val weatherReader = BufferedReader(InputStreamReader(weatherConn.inputStream))
                    val weatherResponse = weatherReader.readText()
                    weatherReader.close()
                    
                    val weatherJson = JSONObject(weatherResponse)
                    val currentCondition = weatherJson.getJSONArray("current_condition").getJSONObject(0)
                    val currentTemp = currentCondition.getString("temp_C")
                    
                    val weatherArray = weatherJson.getJSONArray("weather").getJSONObject(0)
                    val maxTemp = weatherArray.getString("maxtempC")
                    
                    weatherText = "${currentTemp}°C ➔ ${maxTemp}°C"
                    invalidate()
                } else {
                    weatherText = "Wea Err: ${weatherConn.responseCode}"
                    invalidate()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                weatherText = "Weather Error"
                invalidate()
            }
        }
    }

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        // Draw background
        canvas.drawColor(Color.BLACK)

        val centerX = bounds.exactCenterX()
        val startY = 50f

        // Time (Top, small)
        val timeText = zonedDateTime.format(timeFormatter)
        canvas.drawText(timeText, centerX, startY, timePaint)

        // Date (Beneath clock)
        val dateText = zonedDateTime.format(dateFormatter)
        canvas.drawText(dateText, centerX, startY + 30f, datePaint)

        // Divider 1
        canvas.drawLine(centerX - 70f, startY + 55f, centerX + 70f, startY + 55f, dividerPaint)

        // BTC Label
        canvas.drawText("BTC / EUR", centerX, startY + 90f, labelPaint)

        // BTC Price (Below)
        canvas.drawText(btcPrice, centerX, startY + 140f, pricePaint)

        // T212 Returns (Beneath BTC)
        canvas.drawText(t212Returns, centerX, startY + 190f, t212Paint)

        // Divider 2
        canvas.drawLine(centerX - 70f, startY + 220f, centerX + 70f, startY + 220f, dividerPaint)

        // Steps (Beneath T212)
        canvas.drawText(stepsText, centerX, startY + 260f, stepsPaint)

        // Divider 3
        canvas.drawLine(centerX - 70f, startY + 290f, centerX + 70f, startY + 290f, dividerPaint)

        // Weather
        canvas.drawText(weatherText, centerX, startY + 330f, tempPaint)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        // No highlight layer for now
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        scope.cancel()
        super.onDestroy()
    }
}
