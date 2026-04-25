package com.example.flash.ui.workbench

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.flash.FlashApplication
import com.example.flash.R
import com.example.flash.nfc.NfcManager
import com.example.flash.transfer.TransferRepository
import com.example.flash.ui.core.MotherCore
import com.example.flash.ui.gesture.breakawayDrag
import com.example.flash.ui.settings.SettingsScreen
import com.example.flash.ui.shader.RippleOverlay
import com.example.flash.ui.theme.OceanAqua

// Blob size constants mirrored from MotherCore (keep in sync)
private const val BLOB_BASE_RADIUS_DP  = 60f
private const val BLOB_NOISE_OFFSET_DP = 14f
private const val BLOB_PADDING_DP      = 32f
private val BLOB_SIZE_DP = (BLOB_BASE_RADIUS_DP * 2 + BLOB_NOISE_OFFSET_DP * 2 + BLOB_PADDING_DP).dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    transferRepository: TransferRepository,
    nfcManager: NfcManager,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view    = LocalView.current
    val app     = context.applicationContext as FlashApplication

    val viewModel: WorkbenchViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WorkbenchViewModel(transferRepository, nfcManager) as T
        }
    )
    val uiState by viewModel.uiState.collectAsState()

    // ── Permission + auto-load camera roll ───────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) viewModel.loadGalleryPhotos(context)
    }
    LaunchedEffect(Unit) {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.loadGalleryPhotos(context) else permissionLauncher.launch(needed)
    }

    // ── Zero-click NFC download ──────────────────────────────────────────────
    LaunchedEffect(uiState.nfcState) {
        val s = uiState.nfcState
        if (s is NfcUiState.PeerDetected) viewModel.startDownload(s.handshake, context)
    }

    // ── Camera cutout center → blob-local offset ─────────────────────────────
    // cutoutCenterPx is in window coordinates (includes status bar).
    // We convert to BoxWithConstraints-local coordinates by subtracting the
    // status bar height, then subtract the blob's own center so the vector
    // tells MotherCore "how far to translate to reach the camera hole".
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val blobSizePx     = with(density) { BLOB_SIZE_DP.toPx() }

    val cutoutOffset: Offset = remember(view, statusBarTopPx, blobSizePx) {
        val cutoutCenterInWindow: Offset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val rect = view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull()
            if (rect != null) Offset(rect.centerX().toFloat(), rect.centerY().toFloat())
            else Offset(view.width / 2f, statusBarTopPx / 2f)
        } else {
            Offset(view.width / 2f, statusBarTopPx / 2f)
        }
        // Convert to BoxWithConstraints-local (shift up by status bar) then
        // subtract blob's own center (TopCenter alignment → blob cx = half screen width)
        val cutoutLocal = cutoutCenterInWindow - Offset(0f, statusBarTopPx)
        val blobCenter  = Offset(view.width / 2f, blobSizePx / 2f)
        cutoutLocal - blobCenter
    }

    var coreCenter   by remember { mutableStateOf(Offset.Zero) }
    var showSettings by remember { mutableStateOf(false) }

    // Block the exit button for 600 ms after the screen loads so a touch-up
    // event from the onboarding "Got it" button can't pass through to it.
    var exitEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600L); exitEnabled = true }

    val photoPicker = rememberPhotoPicker { uris -> viewModel.onPhotosSelected(uris) }

    val surfaceColor = MaterialTheme.colorScheme.surface

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        val screenHeight = maxHeight

        // ── Glass tray ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(screenHeight * 0.70f)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(surfaceColor.copy(alpha = 0.92f))
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement   = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenHeight * 0.57f)
            ) {
                items(uiState.photos) { uri ->
                    PhotoGridItem(
                        uri          = uri,
                        isInOrbit    = uri in uiState.selectedPhotos,
                        coreCenter   = coreCenter,
                        onDragToCore = { viewModel.onPhotoDraggedToCore(uri, context) },
                        onTap = {
                            if (uri in uiState.selectedPhotos) viewModel.onPhotoRemovedFromOrbit(uri)
                            else                               viewModel.onPhotoAddedToOrbit(uri)
                        }
                    )
                }
            }

            // Status text
            val statusText = when (val s = uiState.nfcState) {
                is NfcUiState.Idle         -> stringResource(R.string.transfer_waiting)
                is NfcUiState.Advertising  -> stringResource(R.string.transfer_connecting)
                is NfcUiState.Transferring -> stringResource(
                    R.string.transfer_progress, (uiState.transferProgress * 100).toInt()
                )
                is NfcUiState.Complete     -> stringResource(R.string.transfer_complete)
                is NfcUiState.Error        -> s.message
                else -> ""
            }
            Text(
                text     = statusText,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
            )

            // ── Exit pill ────────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(OceanAqua.copy(alpha = 0.15f))
                    .clickable(enabled = exitEnabled) { viewModel.onExitRequested() }
                    .padding(horizontal = 32.dp, vertical = 12.dp)
            ) {
                Text(
                    text  = stringResource(R.string.exit_button),
                    color = OceanAqua,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // ── Mother Core — spawn/exit morphs through the camera cutout ────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInParent()
                    val sz  = coords.size
                    coreCenter = Offset(pos.x + sz.width / 2f, pos.y + sz.height / 2f)
                }
        ) {
            MotherCore(
                progress    = uiState.transferProgress,
                isReceiving = uiState.isReceiving,
                shouldExit  = uiState.shouldExit,
                cutoutOffset = cutoutOffset,
                onAnimationComplete = { (context as? android.app.Activity)?.finish() }
            )
        }

        // ── Orbiting selected photos ─────────────────────────────────────────
        PhotoOrbit(
            photos     = uiState.selectedPhotos.toList(),
            coreCenter = coreCenter
        )

        // ── Settings icon ────────────────────────────────────────────────────
        IconButton(
            onClick  = { showSettings = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint               = MaterialTheme.colorScheme.onBackground
            )
        }

        // ── "+" FAB — secondary, for manual picks ────────────────────────────
        FloatingActionButton(
            onClick        = { photoPicker.launch() },
            containerColor = OceanAqua,
            shape          = CircleShape,
            modifier       = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .offset(y = (-56).dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add photos",
                tint = MaterialTheme.colorScheme.onPrimary)
        }

        // ── AGSL ripple on transfer complete ─────────────────────────────────
        if (uiState.showRipple) {
            RippleOverlay(
                coreCenter = coreCenter,
                onComplete = { viewModel.onRippleComplete() }
            )
        }
    }

    // ── Settings sheet ────────────────────────────────────────────────────────
    if (showSettings) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState       = sheetState
        ) {
            SettingsScreen(
                themeRepository = app.themeRepository,
                onBack          = { showSettings = false }
            )
        }
    }
}

@Composable
private fun PhotoGridItem(
    uri: Uri,
    isInOrbit: Boolean,
    coreCenter: Offset,
    onDragToCore: () -> Unit,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onTap() }
            .breakawayDrag(
                coreCenter   = coreCenter,
                onDragToCore = { _ -> onDragToCore() }
            )
    ) {
        AsyncImage(
            model              = uri,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize()
        )
        if (isInOrbit) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(OceanAqua.copy(alpha = 0.35f)))
        }
    }
}
