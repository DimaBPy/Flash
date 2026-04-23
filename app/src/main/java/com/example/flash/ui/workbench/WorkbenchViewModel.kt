package com.example.flash.ui.workbench

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.flash.nfc.NfcManager
import com.example.flash.nfc.PeerHandshake
import com.example.flash.transfer.TransferRepository
import com.example.flash.transfer.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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
    val shouldExit: Boolean      = false
)

class WorkbenchViewModel(
    private val transferRepository: TransferRepository,
    private val nfcManager: NfcManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkbenchUiState())
    val uiState: StateFlow<WorkbenchUiState> = _uiState.asStateFlow()

    private var currentToken: String = ""

    init {
        // Observe incoming NFC handshakes
        nfcManager.peerHandshakeFlow
            .onEach { handshake -> onNfcPeerDetected(handshake) }
            .launchIn(viewModelScope)

        // Observe transfer state changes
        transferRepository.transferState
            .onEach { state -> onTransferStateChanged(state) }
            .launchIn(viewModelScope)

        // Observe progress
        transferRepository.progressFlow
            .onEach { progress ->
                _uiState.update { it.copy(transferProgress = progress) }
            }
            .launchIn(viewModelScope)
    }

    fun onPhotosSelected(uris: List<Uri>) {
        _uiState.update { it.copy(photos = uris) }
    }

    fun onPhotoAddedToOrbit(uri: Uri) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos + uri) }
    }

    fun onPhotoRemovedFromOrbit(uri: Uri) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos - uri) }
    }

    fun onPhotoDraggedToCore(uri: Uri, context: Context) {
        currentToken = UUID.randomUUID().toString()
        viewModelScope.launch {
            try {
                val localIp = transferRepository.getLocalIp(context)
                val port = transferRepository.startServing(currentToken, uri, context)
                nfcManager.setOutboundHandshake(localIp, port, currentToken, "en")
                _uiState.update { it.copy(nfcState = NfcUiState.Advertising) }
            } catch (e: Exception) {
                _uiState.update { it.copy(nfcState = NfcUiState.Error(e.message ?: "Server error")) }
            }
        }
    }

    private fun onNfcPeerDetected(handshake: PeerHandshake) {
        _uiState.update { it.copy(nfcState = NfcUiState.PeerDetected(handshake), isReceiving = true) }
        // Auto-initiate download (zero-click)
        _uiState.value.let { state ->
            // context needed — signal for Screen to trigger download
        }
    }

    fun startDownload(handshake: PeerHandshake, context: Context) {
        transferRepository.startDownload(handshake.ip, handshake.port, handshake.token, context)
        _uiState.update { it.copy(nfcState = NfcUiState.Transferring) }
    }

    private fun onTransferStateChanged(state: TransferState) {
        when (state) {
            is TransferState.Complete -> {
                _uiState.update {
                    it.copy(
                        nfcState = NfcUiState.Complete,
                        transferProgress = 1f,
                        showRipple = true
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
    }

    fun onExitRequested() {
        _uiState.update { it.copy(shouldExit = true) }
    }
}
