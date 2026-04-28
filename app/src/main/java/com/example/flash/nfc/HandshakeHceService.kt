package com.example.flash.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * A robust Host Card Emulation (HCE) service that emulates an NFC Type 4 Tag
 * containing our handshake NDEF message.
 */
class HandshakeHceService : HostApduService() {

    companion object {
        // AID for NFC Forum Type 4 Tag
        private val AID_T4T = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(),
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(),
            0x01.toByte(), 0x01.toByte(), 0x00.toByte()
        )

        private val SELECT_CC_FILE = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x02.toByte(),
            0xE1.toByte(), 0x03.toByte()
        )

        private val SELECT_NDEF_FILE = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x02.toByte(),
            0xE1.toByte(), 0x04.toByte()
        )

        private val CC_FILE = byteArrayOf(
            0x00, 0x0F, // CCLEN
            0x20,       // Mapping version
            0x00, 0x3B, // MLe (Maximum response payload size)
            0x00, 0x34, // MLc (Maximum command payload size)
            0x04, 0x06, 0xE1.toByte(), 0x04, // NDEF File Control TLV
            0x04, 0x00, // Max NDEF size (1024 bytes)
            0x00, 0x00  // Read access, write access
        )

        private val SUCCESS_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val FAILURE_SW = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        @Volatile
        var ndefMessageBytes: ByteArray? = null
    }

    private var selectedFile = 0 // 0: None, 1: CC, 2: NDEF

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return FAILURE_SW

        // Handle SELECT by AID
        if (commandApdu.size >= AID_T4T.size && commandApdu.take(AID_T4T.size).toByteArray().contentEquals(AID_T4T)) {
            selectedFile = 0
            return SUCCESS_SW
        }

        // Handle SELECT by File ID
        if (commandApdu.contentEquals(SELECT_CC_FILE)) {
            selectedFile = 1
            return SUCCESS_SW
        }
        if (commandApdu.contentEquals(SELECT_NDEF_FILE)) {
            selectedFile = 2
            return SUCCESS_SW
        }

        // Handle READ BINARY
        if (commandApdu.size >= 5 && commandApdu[1] == 0xB0.toByte()) {
            val offset = ((commandApdu[2].toInt() and 0xFF) shl 8) or (commandApdu[3].toInt() and 0xFF)
            val length = commandApdu[4].toInt() and 0xFF

            val data = when (selectedFile) {
                1 -> CC_FILE
                2 -> {
                    val msg = ndefMessageBytes ?: return FAILURE_SW
                    // T4T NDEF file format: 2-byte length + NDEF message
                    val ndefFile = ByteArray(msg.size + 2)
                    ndefFile[0] = ((msg.size shr 8) and 0xFF).toByte()
                    ndefFile[1] = (msg.size and 0xFF).toByte()
                    msg.copyInto(ndefFile, 2)
                    ndefFile
                }
                else -> return FAILURE_SW
            }

            if (offset >= data.size) return FAILURE_SW
            val chunkLength = minOf(length, data.size - offset)
            val response = ByteArray(chunkLength + 2)
            data.copyInto(response, 0, offset, offset + chunkLength)
            response[chunkLength] = 0x90.toByte()
            response[chunkLength + 1] = 0x00.toByte()
            return response
        }

        return SUCCESS_SW
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = 0
    }
}
