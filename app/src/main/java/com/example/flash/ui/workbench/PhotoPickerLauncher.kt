package com.example.flash.ui.workbench

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

data class PhotoPickerState(
    val launch: () -> Unit
)

@Composable
fun rememberPhotoPicker(onResult: (List<Uri>) -> Unit): PhotoPickerState {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        onResult(uris)
    }

    return remember(launcher) {
        PhotoPickerState(
            launch = {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )
    }
}
