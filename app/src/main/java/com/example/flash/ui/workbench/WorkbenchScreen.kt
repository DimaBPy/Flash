package com.example.flash.ui.workbench

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.flash.ui.shader.RippleOverlay
import com.example.flash.ui.theme.OceanAqua
import com.example.flash.ui.theme.ThemeMode
import com.example.flash.ui.theme.ThemeRepository
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // ── Dynamic NFC mode ────────────────────────────────────────────────────
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

    // ── Camera cutout → blob-local offset ────────────────────────────────────
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val blobSizePx     = with(density) { BLOB_SIZE_DP.toPx() }

    val cutoutOffset: Offset = remember(view, statusBarTopPx, blobSizePx) {
        val cutoutCenterInWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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

    var exitEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600L); exitEnabled = true }

    val screenExitY = remember { Animatable(0f) }
    var screenExitStarted by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.shouldExit) {
        if (uiState.shouldExit && !screenExitStarted) {
            screenExitStarted = true
            screenExitY.animateTo(
                targetValue = with(density) { 1200.dp.toPx() },
                animationSpec = tween(3000, easing = FastOutSlowInEasing)
            )
        }
    }

    // Close settings panel on back press
    BackHandler(enabled = showSettings) { showSettings = false }

    val photoPicker      = rememberPhotoPicker { uris -> viewModel.onPhotosSelected(uris) }

    // ── Single shared backdrop — grid is the capture source for all glass ───
    val backdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Sliding content (everything except MotherCore) ───────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = screenExitY.value }
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
        ) {
        // ── Full-screen photo grid — source for all glass effects ────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement   = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            items(uiState.photos) { uri ->
                PhotoGridItem(
                    uri       = uri,
                    isInOrbit = uri in uiState.selectedPhotos,
                    onTap = {
                        if (uri in uiState.selectedPhotos) viewModel.onPhotoRemovedFromOrbit(uri)
                        else                               viewModel.onPhotoAddedToOrbit(uri)
                    }
                )
            }
        }

        // ── Orbiting selected photos ─────────────────────────────────────────
        PhotoOrbit(
            photos           = uiState.selectedPhotos.toList(),
            coreCenter       = coreCenter,
            transferProgress = uiState.transferProgress,
            shouldExit       = uiState.shouldExit
        )

        // ── Received photos flying into gallery ──────────────────────────────
        uiState.receivedPhotos.forEachIndexed { index, uri ->
            ReceivedPhotoFlyer(
                uri = uri,
                coreCenter = coreCenter,
                gridItemIndex = index
            )
        }

        // ── Bottom area: two buttons or settings panel ───────────────────────
        SharedTransitionLayout(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            AnimatedContent(
                targetState = showSettings,
                transitionSpec = {
                    fadeIn(tween(500, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(350, easing = FastOutSlowInEasing))
                },
                label = "settings_transform"
            ) { isOpen ->
                if (!isOpen) {
                    // ── Two liquid buttons with spacer ───────────────────────
                    Row(
                        modifier = Modifier.padding(bottom = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Exit button
                        LiquidButton(
                            onClick      = { viewModel.onExitRequested() },
                            backdrop     = backdrop,
                            enabled      = exitEnabled,
                            surfaceColor = OceanAqua.copy(alpha = 0.25f),
                            modifier     = Modifier.width(128.dp)
                        ) {
                            Text(
                                text  = stringResource(R.string.exit_button),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        // Settings button — sharedBounds connects it to the panel
                        LiquidButton(
                            onClick      = { showSettings = true },
                            backdrop     = backdrop,
                            enabled      = true,
                            surfaceColor = OceanAqua.copy(alpha = 0.25f),
                            modifier     = Modifier
                                .width(128.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = "settings-panel"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = fadeIn(tween(500)),
                                    exit  = fadeOut(tween(350))
                                )
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                                tint               = Color.White
                            )
                        }
                    }
                } else {
                    // ── Settings panel expands from the button ───────────────
                    SettingsPanel(
                        themeRepository = app.themeRepository,
                        backdrop        = backdrop,
                        onClose         = { showSettings = false },
                        modifier        = Modifier
                            .fillMaxWidth()
                            .sharedBounds(
                                rememberSharedContentState(key = "settings-panel"),
                                animatedVisibilityScope = this@AnimatedContent,
                                enter = fadeIn(tween(500)),
                                exit  = fadeOut(tween(350))
                            )
                    )
                }
            }
        }

        // ── "+" liquid glass button — hidden when settings panel is open ─────
        AnimatedVisibility(
            visible = !showSettings,
            enter   = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.85f),
            exit    = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 88.dp)
        ) {
            LiquidButton(
                onClick      = { photoPicker.launch() },
                backdrop     = backdrop,
                surfaceColor = OceanAqua.copy(alpha = 0.30f),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_add_photos),
                    tint = Color.White
                )
            }
        }

        // ── AGSL ripple on transfer complete ─────────────────────────────────
        if (uiState.showRipple) {
            RippleOverlay(
                coreCenter = coreCenter,
                onComplete = { viewModel.onRippleComplete() }
            )
        }
        } // end sliding Box

        // ── MotherCore: outside sliding box, counter-translated to stay fixed ─
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .graphicsLayer { translationY = -screenExitY.value }
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
                backdrop     = backdrop,
                onAnimationComplete = {
                    val activity = context as? android.app.Activity
                    @Suppress("DEPRECATION") activity?.overridePendingTransition(0, 0)
                    activity?.finish()
                }
            )
        }
    }
}

// ── Liquid glass segmented button row ──────────────────────────────────────
@Composable
private fun <T> LiquidSegmentedButtonRow(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    backdrop: Backdrop,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.size - 1
            val isSelected = selectedItem == item

            val buttonShape: @Composable () -> Shape = when {
                isFirst && isLast -> { { com.kyant.shapes.Capsule() } }
                isFirst -> { {
                    RoundedCornerShape(
                        topStart = 50, bottomStart = 50,
                        topEnd = 15,   bottomEnd = 15
                    )
                } }
                isLast -> { {
                    RoundedCornerShape(
                        topStart = 15, bottomStart = 15,
                        topEnd = 50,   bottomEnd = 50
                    )
                } }
                else -> { { RoundedCornerShape(15) } }
            }

            LiquidButton(
                onClick = { onItemSelected(item) },
                backdrop = backdrop,
                surfaceColor = if (isSelected) OceanAqua.copy(alpha = 0.45f) else OceanAqua.copy(alpha = 0.15f),
                buttonHeight = 40.dp,
                shape = buttonShape,
                modifier = Modifier.weight(1f),
                isInteractive = true
            ) {
                Text(
                    text = label(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ── Settings panel: liquid glass bottom container ───────────────────────────
@Composable
private fun SettingsPanel(
    themeRepository: ThemeRepository,
    backdrop: Backdrop,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope     = rememberCoroutineScope()
    val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape    = { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) },
                effects  = {
                    vibrancy()
                    blur(18f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(OceanAqua.copy(alpha = 0.07f))
                }
            )
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ── Liquid glass close button ────────────────────────────────────────
        LiquidButton(
            onClick      = onClose,
            backdrop     = backdrop,
            surfaceColor = OceanAqua.copy(alpha = 0.20f),
            buttonHeight = 40.dp,
            modifier     = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = "Close settings",
                tint               = Color.White
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // ── Liquid glass "Settings" title pill ───────────────────────────
            Box(
                modifier = Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape    = { RoundedCornerShape(50) },
                        effects  = {
                            vibrancy()
                            blur(6f.dp.toPx())
                            lens(8f.dp.toPx(), 16f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(OceanAqua.copy(alpha = 0.12f)) }
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text  = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text  = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                LiquidSegmentedButtonRow(
                    items = ThemeMode.entries,
                    selectedItem = themeMode,
                    onItemSelected = { scope.launch { themeRepository.setThemeMode(it) } },
                    backdrop = backdrop,
                    label = { mode ->
                        when (mode) {
                            ThemeMode.LIGHT  -> stringResource(R.string.theme_light)
                            ThemeMode.DARK   -> stringResource(R.string.theme_dark)
                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Received photo flying into gallery ──────────────────────────────────────────
@Composable
private fun ReceivedPhotoFlyer(
    uri: Uri,
    coreCenter: Offset,
    gridItemIndex: Int
) {
    val density = LocalDensity.current
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(1f, tween(2000, easing = FastOutSlowInEasing))
    }

    val progress = animProgress.value

    val gridItemSizeDp = 100.dp
    val paddingDp = 8.dp
    val gapDp = 4.dp
    val col = gridItemIndex % 3
    val row = gridItemIndex / 3

    val targetX = with(density) {
        paddingDp.toPx() + col * (gridItemSizeDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
    }
    val targetY = with(density) {
        paddingDp.toPx() + row * (gridItemSizeDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
    }

    // Phase 1 (0-0.3): Move horizontally to clear blob
    val horizontalPhase = (progress / 0.3f).coerceIn(0f, 1f)
    val horizontalDir = if (targetX < coreCenter.x) -1f else 1f
    val clearDistance = with(density) { 150.dp.toPx() }
    val clearX = coreCenter.x + (clearDistance * horizontalDir * horizontalPhase)
    val zHorizontal = lerp(3f, 1f, horizontalPhase)

    // Phase 2 (0.3-1): Fly to target grid position
    val flightPhase = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
    val x = lerp(clearX, targetX, flightPhase)
    val y = lerp(coreCenter.y, targetY, flightPhase)
    val z = lerp(zHorizontal, 0f, flightPhase)

    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(gridItemSizeDp)
            .offset {
                IntOffset(
                    (x - gridItemSizeDp.toPx() / 2f).roundToInt(),
                    (y - gridItemSizeDp.toPx() / 2f).roundToInt()
                )
            }
            .clip(RoundedCornerShape(12.dp))
            .graphicsLayer {
                translationZ = z
            }
    )
}

// ── Photo grid item ─────────────────────────────────────────────────────────────
@Composable
private fun PhotoGridItem(
    uri: Uri,
    isInOrbit: Boolean,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
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
