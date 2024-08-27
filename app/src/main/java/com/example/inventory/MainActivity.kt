package com.example.inventory

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.inventory.ui.theme.InventoryTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InventoryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    var previewView: PreviewView? by remember { mutableStateOf(null) }
    var scannedCodes by remember { mutableStateOf(mutableListOf<String>()) }
    var isCameraOpen by remember { mutableStateOf(false) }
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        Log.d("MainScreen", "Initializing camera provider...")
        cameraProvider = ProcessCameraProvider.getInstance(context).get()
        Log.d("MainScreen", "Camera provider initialized.")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // Adiciona o PreviewView à interface do usuário em um retângulo menor
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also {
                    previewView = it
                    Log.d("MainScreen", "PreviewView initialized")
                }
            },
            modifier = Modifier
                .width(300.dp)
                .height(200.dp)
                .padding(bottom = 32.dp)
        )

        // Botão para ler código de barras
        Button(
            onClick = {
                Log.d("MainScreen", "Button clicked")
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainScreen", "Camera permission granted")
                    if (!isCameraOpen) {
                        previewView?.let { view ->
                            isCameraOpen = true
                            cameraProvider?.let { provider ->
                                Log.d("MainScreen", "Opening camera...")
                                openCameraAndScanBarcode(context, view, cameraExecutor, provider) { scannedCode ->
                                    scannedCodes.add(scannedCode)
                                    isCameraOpen = false // Fecha a câmera após a leitura
                                    Log.d("MainScreen", "Camera closed after scanning")
                                }
                            } ?: run {
                                Toast.makeText(context, "Erro ao inicializar a câmera", Toast.LENGTH_SHORT).show()
                                Log.e("MainScreen", "Camera provider is null")
                            }
                        } ?: run {
                            Toast.makeText(context, "Erro ao inicializar o PreviewView", Toast.LENGTH_SHORT).show()
                            Log.e("MainScreen", "PreviewView is null")
                        }
                    }
                } else {
                    Toast.makeText(context, "Permissão de câmera necessária", Toast.LENGTH_SHORT).show()
                    Log.w("MainScreen", "Camera permission not granted")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ler placa patrimonial")
        }

        // Tabela de códigos lidos
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            items(scannedCodes) { code ->
                Text(text = code)
            }
        }
    }
}

fun openCameraAndScanBarcode(
    context: android.content.Context,
    previewView: PreviewView,
    cameraExecutor: ExecutorService,
    cameraProvider: ProcessCameraProvider,
    onCodeScanned: (String) -> Unit
) {
    try {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val barcodeScanner = BarcodeScanning.getClient()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(barcodeScanner, imageProxy, onCodeScanned, cameraProvider)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            context as ComponentActivity, cameraSelector, preview, imageAnalysis
        )
        Toast.makeText(context, "Câmera iniciada", Toast.LENGTH_SHORT).show()
        Log.d("openCameraAndScanBarcode", "Camera started")
    } catch (exc: Exception) {
        Toast.makeText(context, "Erro ao abrir a câmera: ${exc.message}", Toast.LENGTH_SHORT).show()
        Log.e("openCameraAndScanBarcode", "Error opening camera: ${exc.message}", exc)
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: androidx.camera.core.ImageProxy,
    onCodeScanned: (String) -> Unit,
    cameraProvider: ProcessCameraProvider
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { scannedCode ->
                        onCodeScanned(scannedCode)
                        Log.d("processImageProxy", "Barcode scanned: $scannedCode")
                        // Para o loop infinito
                        cameraProvider.unbindAll()
                    }
                }
            }
            .addOnFailureListener {
                Log.e("processImageProxy", "Failed to process image", it)
            }
            .addOnCompleteListener {
                imageProxy.close()
                Log.d("processImageProxy", "Image proxy closed")
            }
    } else {
        imageProxy.close()
        Log.e("processImageProxy", "Media image is null")
    }
}
