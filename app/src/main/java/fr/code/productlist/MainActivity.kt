package fr.code.productlist

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import fr.code.productlist.ui.theme.ProductListTheme
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class Product(
    val barcode: String,
    val quantity: Int,
    val imagePath: String? = null
)

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        enableEdgeToEdge()
        setContent {
            ProductListTheme {
                MainScreen(cameraExecutor)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(cameraExecutor: ExecutorService) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current
    val products = remember { mutableStateListOf<Product>() }
    
    var scannedBarcode by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    var existingProduct by remember { mutableStateOf<Product?>(null) }

    LaunchedEffect(Unit) {
        products.addAll(loadProductsLocally(context))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.padding(top = 40.dp, start = 16.dp, end = 16.dp)) {
                Text("Stock App", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (cameraPermissionState.status.isGranted) {
                if (isScanning) {
                    Box(modifier = Modifier.weight(0.5f).fillMaxWidth()) {
                        CameraScannerView(
                            executor = cameraExecutor,
                            onBarcodeScanned = { barcode ->
                                scannedBarcode = barcode
                                existingProduct = products.find { it.barcode == barcode }
                                isScanning = false
                            }
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        ) {
                            Text(
                                "Visez un code-barres",
                                modifier = Modifier.padding(16.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(0.5f).fillMaxWidth()) {
                        if (existingProduct != null) {
                            ProductLookupView(
                                product = existingProduct!!,
                                onBack = {
                                    isScanning = true
                                    scannedBarcode = ""
                                    existingProduct = null
                                }
                            )
                        } else {
                            ProductRegistrationView(
                                barcode = scannedBarcode,
                                quantity = quantity,
                                photoUri = photoUri,
                                onQuantityChange = { quantity = it },
                                onPhotoCaptured = { uri -> photoUri = uri },
                                onSave = {
                                    val q = quantity.toIntOrNull() ?: 0
                                    val newProduct = Product(scannedBarcode, q, photoUri?.toString())
                                    products.add(newProduct)
                                    saveProductsLocally(context, products)
                                    scannedBarcode = ""
                                    quantity = ""
                                    photoUri = null
                                    isScanning = true
                                },
                                onCancel = {
                                    isScanning = true
                                    scannedBarcode = ""
                                    quantity = ""
                                    photoUri = null
                                },
                                cameraExecutor = cameraExecutor
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.weight(0.5f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                        Text("Activer la Caméra")
                    }
                }
            }

            Text("Historique", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            LazyColumn(modifier = Modifier.weight(0.5f)) {
                items(products.reversed()) { product ->
                    ProductItem(product)
                }
            }
        }
    }
}

@Composable
fun ProductItem(product: Product) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = product.imagePath,
                contentDescription = null,
                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(product.barcode, fontWeight = FontWeight.Bold)
                Text("Quantité: ${product.quantity}")
            }
        }
    }
}

@Composable
fun ProductLookupView(product: Product, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PRODUIT EN STOCK", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        AsyncImage(
            model = product.imagePath,
            contentDescription = null,
            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Code: ${product.barcode}", style = MaterialTheme.typography.bodyMedium)
        Text("${product.quantity}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Text("UNITÉS", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("SCANNER SUIVANT")
        }
    }
}

@Composable
fun ProductRegistrationView(
    barcode: String,
    quantity: String,
    photoUri: Uri?,
    onQuantityChange: (String) -> Unit,
    onPhotoCaptured: (Uri) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    cameraExecutor: ExecutorService
) {
    var showCameraForPhoto by remember { mutableStateOf(false) }

    if (showCameraForPhoto) {
        CameraCaptureView(
            executor = cameraExecutor,
            onPhotoCaptured = { uri ->
                onPhotoCaptured(uri)
                showCameraForPhoto = false
            }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NOUVEAU PRODUIT", style = MaterialTheme.typography.labelLarge)
            Text(barcode, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            if (photoUri != null) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = null,
                    modifier = Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                TextButton(onClick = { showCameraForPhoto = true }) { Text("Changer la photo") }
            } else {
                Button(
                    onClick = { showCameraForPhoto = true },
                    modifier = Modifier.size(150.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Prendre Photo", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = quantity,
                onValueChange = onQuantityChange,
                label = { Text("Quantité initiale") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Annuler") }
                Button(onClick = onSave, modifier = Modifier.weight(1f), enabled = quantity.isNotEmpty() && photoUri != null) {
                    Text("Enregistrer")
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun CameraScannerView(executor: ExecutorService, onBarcodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()) }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image).addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) barcode.rawValue?.let { onBarcodeScanned(it) }
                }.addOnCompleteListener { imageProxy.close() }
            } else imageProxy.close()
        }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (e: Exception) { Log.e("Camera", "Error", e) }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
fun CameraCaptureView(executor: ExecutorService, onPhotoCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) { Log.e("Camera", "Error", e) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Button(
            onClick = {
                val file = File(context.getExternalFilesDir(null), "photo_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { onPhotoCaptured(Uri.fromFile(file)) }
                    override fun onError(exc: ImageCaptureException) { Log.e("Camera", "Error", exc) }
                })
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) { Text("Prendre la Photo") }
    }
}

private fun saveProductsLocally(context: Context, products: List<Product>) {
    try {
        val json = products.joinToString(separator = ",", prefix = "[", postfix = "]") {
            "{\"barcode\":\"${it.barcode}\", \"quantity\":${it.quantity}, \"imagePath\":\"${it.imagePath}\"}"
        }
        context.openFileOutput("products.json", Context.MODE_PRIVATE).use { it.write(json.toByteArray()) }
    } catch (e: Exception) { Log.e("Storage", "Error", e) }
}

private fun loadProductsLocally(context: Context): List<Product> {
    val products = mutableListOf<Product>()
    try {
        val file = File(context.filesDir, "products.json")
        if (file.exists()) {
            val content = file.readText()
            val regex = Regex("\\{\"barcode\":\"(.*?)\", \"quantity\":(\\d+), \"imagePath\":\"(.*?)\"\\}")
            regex.findAll(content).forEach { match ->
                products.add(Product(match.groupValues[1], match.groupValues[2].toInt(), if (match.groupValues[3] == "null") null else match.groupValues[3]))
            }
        }
    } catch (e: Exception) { Log.e("Storage", "Error", e) }
    return products
}
