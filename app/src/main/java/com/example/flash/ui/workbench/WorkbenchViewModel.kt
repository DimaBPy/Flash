package com.example.flash.ui.workbench

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.flash.nfc.NfcManager
import com.example.flash.nfc.PeerHandshake
import com.example.flash.transfer.TransferRepository
import com.example.flash.transfer.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface NfcUiState {
    object Idle         : NfcUiState
    object Advertising  : NfcUiState
    data class PeerDetected(val handshake: PeerHandshake) : NfcUiState
    object Transferring : NfcUiState
    object Complete     : NfcUiState
    data class Error(val message: String) : NfcUiState
}

data class WorkbenchUiState(
    val photos: List<Uri>        = emptyList(),
    val selectedPhotos: Set<Uri> = emptySet(),
    val nfcState: NfcUiState     = NfcUiState.Idle,
    val transferProgress: Float  = 0f,
    val isReceiving: Boolean     = false,
    val crystallizedPhotoUri: Uri? = null,
    val showRipple: Boolean      = false,
    val shouldExit: Boolean      = false,
    val receivedPhotos: List<Uri> = emptyList(),
    val receivingPhotos: List<Uri> = emptyList(),
    val corruptedPhotos: List<Uri> = emptyList(),
    val isWifiConnected: Boolean = false,
    val showHotspotPrompt: Boolean = false
)

class WorkbenchViewModel(
    private val transferRepository: TransferRepository,
    private val nfcManager: NfcManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkbenchUiState())
    val uiState: StateFlow<WorkbenchUiState> = _uiState.asStateFlow()

    private var currentToken: String = ""
    private var currentIp: String = ""
    private var currentPort: Int = 0

    init {
        nfcManager.peerHandshakeFlow
            .onEach { handshake -> onNfcPeerDetected(handshake) }
            .launchIn(viewModelScope)

        transferRepository.transferState
            .onEach { state -> onTransferStateChanged(state) }
            .launchIn(viewModelScope)

        transferRepository.progressFlow
            .onEach { progress ->
                _uiState.update { it.copy(transferProgress = progress) }
            }
            .launchIn(viewModelScope)

        transferRepository.fileVerifiedFlow
            .onEach { (index, uri, isValid) ->
                onPhotoVerified(index, uri, isValid)
            }
            .launchIn(viewModelScope)
    }

    /** Handle file verification result: track corruption. */
    private fun onPhotoVerified(index: Int, uri: Uri, isValid: Boolean) {
        if (!isValid) {
            _uiState.update {
                it.copy(corruptedPhotos = it.corruptedPhotos + uri)
            }
        } else {
            viewModelScope.launch {
                delay(100)
                _uiState.update { state ->
                    state.copy(
                        photos = (state.photos + uri).distinct(),
                        receivingPhotos = state.receivingPhotos - uri
                    )
                }
            }
        }
    }

    /** Check if the device has an active Wi-Fi connection. */
    private fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Update Wi-Fi connectivity status and recompute hotspot prompt visibility. */
    fun updateWifiStatus(context: Context) {
        val connected = isWifiConnected(context)
        _uiState.update { it.copy(isWifiConnected = connected) }
        checkHotspotPromptVisibility()
    }

    /** Show hotspot prompt only when photos are selected, Wi-Fi is down, and not receiving. */
    private fun checkHotspotPromptVisibility() {
        val state = _uiState.value
        val shouldShow = state.selectedPhotos.isNotEmpty() && !state.isWifiConnected && !state.isReceiving
        _uiState.update { it.copy(showHotspotPrompt = shouldShow) }
    }

    /** Hide the hotspot prompt modal. */
    fun dismissHotspotPrompt() {
        _uiState.update { it.copy(showHotspotPrompt = false) }
    }

    fun loadGalleryPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val photos = mutableListOf<Uri>()
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder  = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && photos.size < 60) {
                    photos.add(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(col)
                        )
                    )
                }
            }
            _uiState.update { it.copy(photos = photos) }
        }
    }

    fun onPhotosSelected(uris: List<Uri>, context: Context) {
        _uiState.update {
            it.copy(
                photos = (it.photos + uris).distinct(),
                selectedPhotos = it.selectedPhotos + uris
            )
        }
        checkHotspotPromptVisibility()
        addToOrUpdateServer(uris)
    }

    fun onPhotoAddedToOrbit(uri: Uri, context: Context) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos + uri) }
        checkHotspotPromptVisibility()
        addToOrUpdateServer(listOf(uri))
    }

    fun onPhotoRemovedFromOrbit(uri: Uri) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos - uri) }
        checkHotspotPromptVisibility()
        if (_uiState.value.selectedPhotos.isEmpty()) {
            nfcManager.clearOutboundHandshake()
            currentPort = 0
        }
    }

    private fun addToOrUpdateServer(uris: List<Uri>) {
        viewModelScope.launch {
            try {
                val context = com.example.flash.FlashApplication.instance
                val localIp = transferRepository.getLocalIp(context)

                if (currentPort != 0) {
                    // Server already running — add files without restarting
                    uris.forEach { uri -> transferRepository.addFileToServing(currentToken, uri) }
                    nfcManager.setOutboundHandshake(
                        localIp, currentPort, currentToken, "en",
                        transferRepository.servingFileCount
                    )
                } else {
                    // No server yet — start fresh
                    currentToken = UUID.randomUUID().toString()
                    currentIp = localIp
                    currentPort = transferRepository.startServing(currentToken, uris, context)
                    nfcManager.setOutboundHandshake(
                        localIp, currentPort, currentToken, "en",
                        transferRepository.servingFileCount
                    )
                    _uiState.update { it.copy(nfcState = NfcUiState.Advertising) }
                }
            } catch (_: Exception) { }
        }
    }

    fun onNdefHandshakeReceived(message: android.nfc.NdefMessage) {
        nfcManager.handleNdefMessage(message)
    }

    fun onPhotoDraggedToCore(uri: Uri, context: Context) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos + uri) }
        checkHotspotPromptVisibility()
        addToOrUpdateServer(listOf(uri))
    }

    private fun onNfcPeerDetected(handshake: PeerHandshake) {
        if (_uiState.value.selectedPhotos.isEmpty()) {
            _uiState.update { it.copy(nfcState = NfcUiState.PeerDetected(handshake), isReceiving = true) }
        }
    }

    fun startDownload(handshake: PeerHandshake, context: Context) {
        transferRepository.startDownload(handshake.ip, handshake.port, handshake.token, handshake.fileCount, context)
        _uiState.update { it.copy(nfcState = NfcUiState.Transferring) }
    }

    private fun onTransferStateChanged(state: TransferState) {
        when (state) {
            is TransferState.Complete -> {
                val allFileUris = state.receivedFiles.map { file ->
                    android.net.Uri.fromFile(file)
                }
                val corruptedSet = state.corruptedIndices.toSet()
                val corruptedPhotos = allFileUris.filterIndexed { idx, _ -> idx in corruptedSet }

                _uiState.update {
                    it.copy(
                        nfcState = NfcUiState.Complete,
                        transferProgress = 1f,
                        showRipple = true,
                        receivingPhotos = allFileUris,
                        corruptedPhotos = corruptedPhotos
                    )
                }
            }
            is TransferState.Failed -> {
                _uiState.update {
                    it.copy(nfcState = NfcUiState.Error(state.reason), isReceiving = false)
                }
            }
            else -> {}
        }
    }

    fun onRippleComplete() {
        _uiState.update { it.copy(showRipple = false, isReceiving = false, transferProgress = 0f) }
        transferRepository.reset()
        currentPort = 0
    }

    fun onReceivingPhotosReadyForGallery() {
        val receiving = _uiState.value.receivingPhotos
        if (receiving.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    photos = (it.photos + receiving).distinct(),
                    receivingPhotos = emptyList()
                )
            }
        }
    }

    fun startGalleryTransitionForReceivingPhotos() {
        viewModelScope.launch {
            delay(2100L)  // Wait for gallery flight animation to complete
            onReceivingPhotosReadyForGallery()
        }
    }

    fun onExitRequested() {
        _uiState.update { it.copy(shouldExit = true) }
    }

    fun dismissCorruptionAlert() {
        _uiState.update { it.copy(corruptedPhotos = emptyList()) }
    }

    fun retryCorruptedPhotos() {
        val currentState = _uiState.value
        if (currentState.corruptedPhotos.isNotEmpty()) {
            dismissCorruptionAlert()
            _uiState.update { it.copy(nfcState = NfcUiState.Idle) }
        }
    }
}
