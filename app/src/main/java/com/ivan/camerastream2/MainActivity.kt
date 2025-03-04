package com.ivan.camerastream2

import android.Manifest
import android.app.Activity
import android.content.Context

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.ivan.camerastream2.ui.theme.Camerastream2Theme
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.Analyzer
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class MainActivity : ComponentActivity() {

    private var webSocketClient: WebSocketClient? = null
    private val serverUri = "ws://192.168.0.211:3000"  // Set your PC IP and WebSocket port here

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestCameraPermission(this) // Ensure permissions are granted

        setupWebSocket() // Initialize WebSocket

        setContent {
            Camerastream2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CameraScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun setupWebSocket() {
        try {
            val uri = URI(serverUri)
            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i("WebSocket", "Opened")
                }

                override fun onMessage(message: String?) {
                    Log.i("WebSocket", "Message received: $message")
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.i("WebSocket", "Closed: $reason")
                }

                override fun onError(ex: Exception?) {
                    Log.e("WebSocket", "Error: ${ex?.message}")
                }
            }
            webSocketClient?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    public fun startCameraStream(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetResolution(Size(1280, 720))  // or whatever resolution you prefer
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(
                ContextCompat.getMainExecutor(this),
                ImageAnalysis.Analyzer { imageProxy ->
                    processImageFrame(imageProxy)
                }
            )

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Bind use cases to lifecycle
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

            // Set the preview view
            preview.setSurfaceProvider(previewView.surfaceProvider)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageFrame(imageProxy: ImageProxy) {
        try {
            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> {
                    val yBuffer = imageProxy.planes[0].buffer
                    val uBuffer = imageProxy.planes[1].buffer
                    val vBuffer = imageProxy.planes[2].buffer

                    val ySize = yBuffer.remaining()
                    val uSize = uBuffer.remaining()
                    val vSize = vBuffer.remaining()

                    val nv21 = ByteArray(ySize + (ySize / 2))

                    // Copy Y
                    yBuffer.get(nv21, 0, ySize)

                    // Interleave VU
                    val vPixelStride = imageProxy.planes[2].pixelStride
                    val uPixelStride = imageProxy.planes[1].pixelStride
                    val vRowStride = imageProxy.planes[2].rowStride
                    val uRowStride = imageProxy.planes[1].rowStride

                    val uvWidth = imageProxy.width / 2
                    val uvHeight = imageProxy.height / 2

                    var uvPos = ySize
                    var uvIndex = 0

                    for (row in 0 until uvHeight) {
                        for (col in 0 until uvWidth) {
                            nv21[uvPos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                            nv21[uvPos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
                        }
                    }

                    // Convert to JPEG
                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
                    val jpegBytes = out.toByteArray()

                    Log.d("CameraStream", "Sending frame, size: ${jpegBytes.size} bytes")
                    webSocketClient?.send(jpegBytes)
                }
                else -> {
                    Log.e("CameraStream", "Unsupported format: ${imageProxy.format}")
                }
            }
        } catch (e: Exception) {
            Log.e("CameraStream", "Error processing image: ${e.message}")
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }



    private fun requestCameraPermission(contextCompat: Activity) {
        if (ContextCompat.checkSelfPermission(contextCompat, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                contextCompat,
                arrayOf(Manifest.permission.CAMERA),
                101
            )
        }
    }

    @Composable
    fun Greeting(name: String, modifier: Modifier = Modifier) {
        Text(
            text = "Hello $name!",
            modifier = modifier
        )
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        Camerastream2Theme {
            Greeting("Android")
        }
    }

    @Composable
    fun CameraScreen(modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                PreviewView(context).apply {
                    post {
                        (context as? MainActivity)?.startCameraStream(this)
                    }
                }
            }
        )
    }
}