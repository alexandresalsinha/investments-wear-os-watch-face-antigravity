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
) {
    class SharedAssets : Renderer.SharedAssets {
        override fun onDestroy() {}
    }

    override suspend fun createSharedAssets(): SharedAssets {
        return SharedAssets()
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var btcPrice: String = "Loading..."
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val timePaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val pricePaint = Paint().apply {
        color = Color.GREEN
        textSize = 50f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    init {
        scope.launch {
            while (true) {
                if (watchState.isVisible.value == true) {
                    fetchBtcPrice()
                }
                delay(5 * 60 * 1000L) // Fetch every 5 minutes
            }
        }
    }

    private suspend fun fetchBtcPrice() {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.binance.com/api/v3/ticker/price?symbol=BTCEUR")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val jsonObject = JSONObject(response)
                    val priceStr = jsonObject.getString("price")
                    val priceFloat = priceStr.toFloat()
                    btcPrice = String.format("€%.2f", priceFloat)
                    invalidate()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        // Draw background
        canvas.drawColor(Color.BLACK)

        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()

        // Time (Top, small)
        val timeText = zonedDateTime.format(timeFormatter)
        canvas.drawText(timeText, centerX, centerY - 40f, timePaint)

        // BTC Label
        canvas.drawText("BTC / EUR", centerX, centerY + 10f, labelPaint)

        // BTC Price (Below)
        canvas.drawText(btcPrice, centerX, centerY + 60f, pricePaint)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        // No highlight layer for now
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
