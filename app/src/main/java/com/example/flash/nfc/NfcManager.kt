package com.example.flash.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

data class PeerHandshake(
    val ip: String,
    val port: Int,
    val token: String,
    val lang: String
)

class NfcManager(private val context: Context) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    val isNfcAvailable: Boolean get() = adapter != null
    val isNfcEnabled: Boolean   get() = adapter?.isEnabled == true

    private val _peerHandshakeFlow = MutableSharedFlow<PeerHandshake>(extraBufferCapacity = 1)
    val peerHandshakeFlow: SharedFlow<PeerHandshake> = _peerHandshakeFlow.asSharedFlow()

    private var outboundMessage: NdefMessage? = null
    private var activity: Activity? = null

    fun initialize(act: Activity) {
        activity = act
    }

    fun setOutboundHandshake(ip: String, port: Int, token: String, lang: String) {
        val json = JSONObject().apply {
            put("ip", ip)
            put("port", port)
            put("token", token)
            put("lang", lang)
        }.toString()

        val msg = NdefMessage(
            NdefRecord.createMime("application/vnd.flash.handshake", json.toByteArray(Charsets.UTF_8))
        )
        outboundMessage = msg
        // Update the HCE service data
        HandshakeHceService.ndefMessageBytes = msg.toByteArray()
    }

    fun clearOutboundHandshake() {
        outboundMessage = null
        HandshakeHceService.ndefMessageBytes = null
    }

    fun enableReaderMode(act: Activity, callback: (NdefMessage) -> Unit) {
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter?.enableReaderMode(act, { tag ->
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                try {
                    ndef.connect()
                    val msg = ndef.ndefMessage
                    if (msg != null) callback(msg)
                    ndef.close()
                } catch (_: Exception) { }
            }
        }, flags, null)
    }

    fun disableReaderMode(act: Activity) {
        adapter?.disableReaderMode(act)
    }

    fun enableForegroundDispatch(
        act: Activity,
        pendingIntent: PendingIntent,
        filters: Array<IntentFilter>
    ) {
        if (!isNfcEnabled) return
        adapter?.enableForegroundDispatch(act, pendingIntent, filters, null)
    }

    fun disableForegroundDispatch(act: Activity) {
        adapter?.disableForegroundDispatch(act)
    }

    fun handleIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED) return

        val rawMessages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

        if (!rawMessages.isNullOrEmpty()) {
            val msg = rawMessages[0] as? NdefMessage
            msg?.let { parseAndEmit(it) }
            return
        }

        // Fallback: read NDEF from tag directly
        val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let { readTagAndEmit(it) }

        // Write our outbound message to the tag if we have one
        tag?.let { writeOutboundToTag(it) }
    }

    fun handleNdefMessage(message: NdefMessage) {
        parseAndEmit(message)
    }

    private fun readTagAndEmit(tag: Tag) {
        val ndef = Ndef.get(tag) ?: return
        try {
            ndef.connect()
            val msg = ndef.ndefMessage
            ndef.close()
            msg?.let { parseAndEmit(it) }
        } catch (_: Exception) { }
    }

    private fun writeOutboundToTag(tag: Tag) {
        val msg = outboundMessage ?: return
        val ndef = Ndef.get(tag) ?: return
        try {
            ndef.connect()
            if (ndef.isWritable) ndef.writeNdefMessage(msg)
            ndef.close()
        } catch (_: Exception) { }
    }

    private fun parseAndEmit(message: NdefMessage) {
        for (record in message.records) {
            val payload = record.payload ?: continue
            val json = runCatching {
                JSONObject(String(payload, Charsets.UTF_8))
            }.getOrNull() ?: continue

            val handshake = runCatching {
                PeerHandshake(
                    ip    = json.getString("ip"),
                    port  = json.getInt("port"),
                    token = json.getString("token"),
                    lang  = json.optString("lang", "en")
                )
            }.getOrNull() ?: continue

            _peerHandshakeFlow.tryEmit(handshake)
            return
        }
    }
}
