package com.example.flash.ui.workbench

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box as ComposeBox
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.ui.animation.SharedTransitionLayout
import androidx.compose.ui.animation.fadeIn
import androidx.compose.ui.animation.fadeOut
import androidx.compose.ui.animation.sharedBounds
import androidx.compose.ui.animation.rememberSharedContentState
import androidx.compose.ui.unit.mm
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // ── Dynamic NFC mode: receiver when idle, sender (HCE) when photos selected ─
    LaunchedEffect(uiState.selectedPhotos.isEmpty()) {
        val act = context as? android.app.Activity ?: return@LaunchedEffect
        if (uiState.selectedPhotos.isEmpty()) {
            nfcManager.enableReaderMode(act) { ndef -> viewModel.onNdefHandshakeReceived(ndef) }
        } else {
            nfcManager.disableReaderMode(act)
        }
    }

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
        val cutoutLocal = cutoutCenterInWindow - Offset(0f, statusBarTopPx)
        val blobCenter  = Offset(view.width / 2f, blobSizePx / 2f)
        cutoutLocal - blobCenter
    }

    var coreCenter   by remember { mutableStateOf(Offset.Zero) }
    var showSettings by remember { mutableStateOf(false) }

    // Block exit button for 600ms so onboarding touch-up can't pass through
    var exitEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600L); exitEnabled = true }

    val photoPicker  = rememberPhotoPicker { uris -> viewModel.onPhotosSelected(uris) }
    val exitBackdrop = rememberLayerBackdrop()
    val settingsBackdrop = rememberLayerBackdrop()

    SharedTransitionLayout {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
        ) {
            // ── Full-screen photo grid (background layer) ────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement   = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
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

            // ── Mother Core — spawn/exit morphs through the camera cutout ────────
            ComposeBox(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInParent()
                        val sz  = coords.size
                        coreCenter = Offset(pos.x + sz.width / 2f, pos.y + sz.height / 2f)
                    }
            ) {
                MotherCore(
                    progress     = uiState.transferProgress,
                    isReceiving  = uiState.isReceiving,
                    shouldExit   = uiState.shouldExit,
                    cutoutOffset = cutoutOffset,
                    onAnimationComplete = { (context as? android.app.Activity)?.finish() }
                )
            }

            // ── Orbiting selected photos ─────────────────────────────────────────
            PhotoOrbit(
                photos     = uiState.selectedPhotos.toList(),
                coreCenter = coreCenter
            )

            // ── Two liquid buttons at bottom center: Exit + Settings ─────────
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                // Exit button
                LiquidButton(
                    onClick   = { viewModel.onExitRequested() },
                    backdrop  = exitBackdrop,
                    enabled   = exitEnabled,
                    surfaceColor = OceanAqua.copy(alpha = 0.18f),
                    modifier  = Modifier.size(width = 120.dp, height = 48.dp)
                ) {
                    Text(
                        text  = stringResource(R.string.exit_button),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // ~3mm gap between buttons
                Spacer(modifier = Modifier.width(3.mm))

                // Settings button with container transform
                AnimatedContent(
                    targetState = showSettings,
                    label = "settings_transform"
                ) { isExpanded ->
                    if (!isExpanded) {
                        LiquidButton(
                            onClick   = { showSettings = true },
                            backdrop  = settingsBackdrop,
                            enabled   = true,
                            surfaceColor = OceanAqua.copy(alpha = 0.18f),
                            modifier  = Modifier
                                .size(width = 120.dp, height = 48.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = "settings-bounds"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = fadeIn(animationSpec = tween(600, easing = FastOutSlowInEasing)),
                                    exit = fadeOut(animationSpec = tween(600, easing = FastOutSlowInEasing))
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                                tint = Color.White
                            )
                        }
                    } else {
                        // Settings overlay (expands to fill screen with bottom sheet style)
                        SettingsOverlay(
                            backdrop = settingsBackdrop,
                            onClose = { showSettings = false },
                            themeRepository = app.themeRepository,
                            modifier = Modifier
                                .fillMaxSize()
                                .sharedBounds(
                                    rememberSharedContentState(key = "settings-bounds"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = fadeIn(animationSpec = tween(600, easing = FastOutSlowInEasing)),
                                    exit = fadeOut(animationSpec = tween(600, easing = FastOutSlowInEasing))
                                )
                        )
                    }
                }
            }

            // ── "+" FAB — secondary, for manual picks ────────────────────────────
            FloatingActionButton(
                onClick        = { photoPicker.launch() },
                containerColor = OceanAqua,
                shape          = CircleShape,
                modifier       = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .offset(y = (-40).dp)
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
    ComposeBox(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
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
            ComposeBox(modifier = Modifier
                .fillMaxSize()
                .background(OceanAqua.copy(alpha = 0.35f)))
        }
    }
}

@Composable
private fun SettingsOverlay(
    backdrop: com.kyant.backdrop.Backdrop,
    onClose: () -> Unit,
    themeRepository: com.example.flash.ui.theme.ThemeRepository,
    modifier: Modifier = Modifier
) {
    ComposeBox(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Close button at top
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        // Liquid glass settings panel at bottom
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Theme selection placeholder
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Language selection placeholder
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
