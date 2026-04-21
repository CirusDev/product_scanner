package fr.code.productlist

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class Product(
    val barcode: String,
    var quantity: Int,
    val price: Double,
    val imagePath: String? = null
)

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        enableEdgeToEdge()
        setContent {
            ProductListTheme(dynamicColor = false) {
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
    var quantityInput by remember { mutableStateOf("") }
    var priceInput by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var isScanning by remember { mutableStateOf(true) }

    // Chargement au démarrage:
    LaunchedEffect(Unit) {
        products.clear()
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
                    // MODE SCANNER
                    Box(modifier = Modifier.weight(0.7f).fillMaxWidth()) {
                        CameraScannerView(
                            executor = cameraExecutor,
                            onBarcodeScanned = { barcode ->
                                scannedBarcode = barcode
                                isScanning = false
                            }
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        ) {
                            Text("Visez un code-barres", modifier = Modifier.padding(16.dp), color = Color.White)
                        }
                    }
                } else {
                    // MODE EDITION / CREATION
                    Box(modifier = Modifier.weight(0.7f).fillMaxWidth()) {
                        val productToShow = products.find { it.barcode == scannedBarcode }
                        if (productToShow != null) {
                            ProductLookupView(
                                product = productToShow,
                                onUpdateQuantity = { newQty ->
                                    val index = products.indexOfFirst { it.barcode == scannedBarcode }
                                    if (index != -1) {
                                        products[index] = products[index].copy(quantity = newQty)
                                        saveProductsLocally(context, products)
                                    }
                                },
                                onBack = { isScanning = true; scannedBarcode = "" }
                            )
                        } else {
                            ProductRegistrationView(
                                barcode = scannedBarcode,
                                quantity = quantityInput,
                                price = priceInput,
                                photoUri = photoUri,
                                onQuantityChange = { quantityInput = it },
                                onPriceChange = { priceInput = it },
                                onPhotoCaptured = { uri -> photoUri = uri },
                                onSave = {
                                    val q = quantityInput.toIntOrNull() ?: 0
                                    val p = priceInput.toDoubleOrNull() ?: 0.0
                                    products.add(Product(scannedBarcode, q, p, photoUri?.toString()))
                                    saveProductsLocally(context, products)
                                    scannedBarcode = ""; quantityInput = ""; priceInput = ""; photoUri = null; isScanning = true
                                },
                                onCancel = { scannedBarcode = ""; quantityInput = ""; priceInput = ""; photoUri = null; isScanning = true },
                                cameraExecutor = cameraExecutor
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.weight(0.5f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) { Text("Activer la Caméra") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Historique",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
//                val totalValue = products.sumOf { it.price * it.quantity }
//                Text(
//                    "Valeur: ${String.format(Locale.US, "%.2f", totalValue)} €",
//                    fontWeight = FontWeight.Bold,
//                    style = MaterialTheme.typography.titleMedium,
//                    color = MaterialTheme.colorScheme.primary
//                )
            }
            LazyColumn(modifier = Modifier.weight(0.3f)) {
                items(products.asReversed()) { product ->
                    ProductItem(
                        product = product,
                        onUpdateQuantity = { newQty ->
                            val index = products.indexOfFirst { it.barcode == product.barcode }
                            if (index != -1) {
                                products[index] = products[index].copy(quantity = newQty)
                                saveProductsLocally(context, products)
                            }
                        },
                        onDelete = {
                            products.removeIf { it.barcode == product.barcode }
                            saveProductsLocally(context, products)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ProductItem(product: Product, onUpdateQuantity: (Int) -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = product.imagePath,
                contentDescription = null,
                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(product.barcode, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Stock: ${product.quantity}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Prix: ${String.format(Locale.US, "%.2f", product.price)} €",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (product.quantity > 0) onUpdateQuantity(product.quantity - 1) }) {
                    Text(
                        "-",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Text("${product.quantity}", fontWeight = FontWeight.Bold)
                IconButton(onClick = { onUpdateQuantity(product.quantity + 1) }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Green
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Supprimer",
                        modifier = Modifier.size(20.dp),
                        tint = Color.Red
                    )
                }
            }
        }
    }
}

@Composable
fun ProductLookupView(product: Product, onUpdateQuantity: (Int) -> Unit, onBack: () -> Unit) {
    var editValue by remember(product.barcode) { mutableStateOf(product.quantity.toString()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("EN STOCK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        AsyncImage(model = product.imagePath, contentDescription = null, modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Spacer(modifier = Modifier.height(8.dp))
        Text(product.barcode, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Prix: ${String.format(Locale.US, "%.2f", product.price)} €",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = {
                    val newQty = (product.quantity - 1).coerceAtLeast(0)
                    editValue = newQty.toString()
                    onUpdateQuantity(newQty)
                }) { Text(
                    "-",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium
                )}

                OutlinedTextField(
                    value = editValue,
                    onValueChange = {
                        editValue = it
                        it.toIntOrNull()?.let { newQty -> onUpdateQuantity(newQty) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(100.dp),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )

                IconButton(onClick = {
                    val newQty = product.quantity + 1
                    editValue = newQty.toString()
                    onUpdateQuantity(newQty)
                }) { Icon(Icons.Default.Add, "Plus") }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("SCANNER SUIVANT") }
    }
}

@Composable
fun ProductRegistrationView(barcode: String, quantity: String, price: String, photoUri: Uri?, onQuantityChange: (String) -> Unit, onPriceChange: (String) -> Unit, onPhotoCaptured: (Uri) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, cameraExecutor: ExecutorService) {
    var showCamera by remember { mutableStateOf(false) }
    if (showCamera) {
        CameraCaptureView(
            executor = cameraExecutor,
            onPhotoCaptured = { onPhotoCaptured(it); showCamera = false }
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOUVEAU PRODUIT", fontWeight = FontWeight.Bold)
                Text(barcode)
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.size(170.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    if (photoUri != null) {
                        AsyncImage(
                            model = photoUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(), 
                            contentScale = ContentScale.Crop
                        )
                        Button(
                            onClick = { showCamera = true },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)) {
                                Text(
                                    "Changer",
                                    style = MaterialTheme.typography.labelSmall
                                )}
                    } else {
                        Button(
                            onClick = { showCamera = true },
                            modifier = Modifier.fillMaxSize()) {
                                Text("Prendre Photo")
                            }
                    }
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantity,
                    onValueChange = onQuantityChange,
                    label = { Text("Quantité") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = onPriceChange,
                    label = { Text("Prix (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)) { Text("Annuler") }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = quantity.isNotEmpty() && price.isNotEmpty() && photoUri != null) { Text("Enregistrer") }
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
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
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
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
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
        ) { Text("Capturer") }
    }
}

private fun saveProductsLocally(context: Context, products: List<Product>) {
    try {
        val json = products.joinToString(separator = ",", prefix = "[", postfix = "]") {
            "{\"barcode\":\"${it.barcode}\",\"quantity\":${it.quantity},\"price\":${it.price},\"imagePath\":\"${it.imagePath}\"}"
        }
        context.openFileOutput("products.json", Context.MODE_PRIVATE).use { it.write(json.toByteArray()) }
    } catch (e: Exception) { Log.e("Storage", "Save error", e) }
}

private fun loadProductsLocally(context: Context): List<Product> {
    val productsList = mutableListOf<Product>()
    try {
        val file = File(context.filesDir, "products.json")
        if (file.exists()) {
            val content = file.readText()
            val regex = Regex("""\{"barcode":"(.*?)","quantity":(\d+),"price":([\d.]+),"imagePath":"(.*?)"\}""")
            regex.findAll(content).forEach { match ->
                val path = if (match.groupValues[4] == "null") null else match.groupValues[4]
                productsList.add(Product(
                    match.groupValues[1],
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toDoubleOrNull() ?: 0.0,
                    path
                ))
            }
        }
    } catch (e: Exception) { Log.e("Storage", "Load error", e) }
    return productsList
}
