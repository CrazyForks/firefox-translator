/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.yalantis.ucrop.UCrop
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val filePickerMimeTypes =
  arrayOf(
    "image/*",
    "application/pdf",
    "text/plain",
    "application/vnd.oasis.opendocument.text",
  )

private data class PendingImageImport(
  val sourceUri: Uri?,
  val cropOutputUri: Uri,
)

private fun createTemporaryImageUri(
  context: Context,
  prefix: String,
  compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
): Uri {
  val suffix =
    when (compressFormat) {
      Bitmap.CompressFormat.JPEG -> ".jpg"
      Bitmap.CompressFormat.PNG -> ".png"
      Bitmap.CompressFormat.WEBP,
      Bitmap.CompressFormat.WEBP_LOSSY,
      Bitmap.CompressFormat.WEBP_LOSSLESS,
      -> ".webp"
    }
  val file = java.io.File.createTempFile(prefix, suffix, context.cacheDir)
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun deleteTemporaryImageUri(
  context: Context,
  uri: Uri,
): Boolean =
  try {
    when (uri.scheme) {
      "content" -> context.contentResolver.delete(uri, null, null) > 0
      "file" -> uri.path?.let(::File)?.delete() == true
      else -> false
    }
  } catch (e: Exception) {
    Log.w("ImageCapture", "Failed to delete temporary image URI: $uri", e)
    false
  }

private fun displayNameForUri(
  context: Context,
  uri: Uri,
): String? =
  context.contentResolver
    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    ?.use { cursor ->
      if (cursor.moveToFirst()) {
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
      } else {
        null
      }
    }

private fun sizeBytesForUri(
  context: Context,
  uri: Uri,
): Long? =
  context.contentResolver
    .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
    ?.use { cursor ->
      if (cursor.moveToFirst()) {
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
      } else {
        null
      }
    }

private fun documentFileExtension(
  context: Context,
  uri: Uri,
): String {
  val displayName = displayNameForUri(context, uri)
  val displayExtension =
    displayName
      ?.substringAfterLast('.', missingDelimiterValue = "")
      ?.takeIf { it.isNotBlank() && it.length <= 8 }
  if (displayExtension != null) return ".$displayExtension"

  return when (context.contentResolver.getType(uri)) {
    "application/pdf" -> ".pdf"
    "text/plain" -> ".txt"
    "application/vnd.oasis.opendocument.text" -> ".odt"
    else -> ".bin"
  }
}

private fun copyDocumentUriToCache(
  context: Context,
  uri: Uri,
): File {
  val outputFile = File.createTempFile("input_document_", documentFileExtension(context, uri), context.cacheDir)
  context.contentResolver.openInputStream(uri).use { input ->
    requireNotNull(input) { "Unable to open selected document" }
    outputFile.outputStream().use { output ->
      input.copyTo(output)
    }
  }
  return outputFile
}

private fun isImageUri(
  context: Context,
  uri: Uri,
): Boolean = context.contentResolver.getType(uri)?.startsWith("image/") == true

private fun isRemoteUri(uri: Uri): Boolean = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)

@Composable
fun ImageCaptureHandler(
  onMessage: (TranslatorMessage) -> Unit,
  showImageSourceSheet: Boolean,
  onDismissImageSourceSheet: () -> Unit,
  pendingSharedImage: SharedFlow<Uri>? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val pendingImport = remember { mutableStateOf<PendingImageImport?>(null) }

  val colorScheme = MaterialTheme.colorScheme
  val toolbarColor = colorScheme.surface.toArgb()
  val toolbarWidgetColor = colorScheme.onSurface.toArgb()
  val activeWidgetColor = colorScheme.primary.toArgb()
  val rootBackgroundColor = colorScheme.background.toArgb()

  val cropImage =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val activeImport = pendingImport.value
      val resultIntent = result.data
      val croppedUri = resultIntent?.let { UCrop.getOutput(it) }
      val error = resultIntent?.let { UCrop.getError(it) }
      if (result.resultCode == android.app.Activity.RESULT_OK && croppedUri != null) {
        activeImport?.sourceUri?.let { deleteTemporaryImageUri(context, it) }
        pendingImport.value = null
        Log.d("ImageCrop", "Image cropped: $croppedUri")
        onMessage(TranslatorMessage.SetImageUri(croppedUri, deleteAfterLoad = true))
      } else {
        activeImport?.sourceUri?.let { deleteTemporaryImageUri(context, it) }
        activeImport?.cropOutputUri?.let { deleteTemporaryImageUri(context, it) }
        pendingImport.value = null
        Log.d("ImageCrop", "Crop cancelled or failed: ${error?.message}")
      }
    }

  fun launchCrop(
    sourceUri: Uri,
    destUri: Uri,
  ) {
    if (isRemoteUri(sourceUri)) {
      deleteTemporaryImageUri(context, destUri)
      pendingImport.value = null
      Log.w("ImageCrop", "Remote image crop is unsupported: $sourceUri")
      Toast.makeText(context, "Remote images cannot be cropped directly", Toast.LENGTH_SHORT).show()
      return
    }

    val options =
      UCrop.Options().apply {
        setCompressionFormat(Bitmap.CompressFormat.JPEG)
        setCompressionQuality(95)
        setFreeStyleCropEnabled(true)
        setHideBottomControls(false)
        setToolbarColor(toolbarColor)
        setToolbarWidgetColor(toolbarWidgetColor)
        setActiveControlsWidgetColor(activeWidgetColor)
        setRootViewBackgroundColor(rootBackgroundColor)
      }
    val intent =
      UCrop
        .of(sourceUri, destUri)
        .withOptions(options)
        .getIntent(context)
        .setClass(context, AppCropActivity::class.java)
    cropImage.launch(intent)
  }

  if (pendingSharedImage != null) {
    LaunchedEffect(pendingSharedImage) {
      pendingSharedImage.collect { uri ->
        val cropOutputUri = createTemporaryImageUri(context, "cropped_image")
        pendingImport.value = PendingImageImport(sourceUri = null, cropOutputUri = cropOutputUri)
        Log.d("SharedImage", "Launching crop for shared URI: $uri")
        launchCrop(uri, cropOutputUri)
      }
    }
  }

  val takePictureIntent =
    rememberLauncherForActivityResult(
      ActivityResultContracts.StartActivityForResult(),
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        val activeImport = pendingImport.value
        val cameraImageUri = activeImport?.sourceUri
        if (cameraImageUri == null) {
          Log.d("Camera", "Photo capture completed without an active import session")
          return@rememberLauncherForActivityResult
        }
        Log.d("Camera", "Photo captured: $cameraImageUri")
        launchCrop(cameraImageUri, activeImport.cropOutputUri)
      } else {
        pendingImport.value?.sourceUri?.let { deleteTemporaryImageUri(context, it) }
        pendingImport.value?.cropOutputUri?.let { deleteTemporaryImageUri(context, it) }
        pendingImport.value = null
        Log.d("Camera", "Photo capture cancelled or failed")
      }
    }

  val pickMedia =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
      if (uri != null) {
        val cropOutputUri = createTemporaryImageUri(context, "cropped_image")
        pendingImport.value = PendingImageImport(sourceUri = null, cropOutputUri = cropOutputUri)
        Log.d("PhotoPicker", "Selected URI: $uri")
        launchCrop(uri, cropOutputUri)
      } else {
        Log.d("PhotoPicker", "No media selected")
      }
    }

  val pickFromGallery =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        val imageUri = result.data?.data
        if (imageUri != null) {
          val cropOutputUri = createTemporaryImageUri(context, "cropped_image")
          pendingImport.value = PendingImageImport(sourceUri = null, cropOutputUri = cropOutputUri)
          Log.d("Gallery", "Selected URI: $imageUri")
          launchCrop(imageUri, cropOutputUri)
        } else {
          Log.d("Gallery", "No image selected")
        }
      }
    }

  val pickFromFiles =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri != null) {
        Log.d("FilePicker", "Selected URI: $uri")
        if (isImageUri(context, uri)) {
          val cropOutputUri = createTemporaryImageUri(context, "cropped_image")
          pendingImport.value = PendingImageImport(sourceUri = null, cropOutputUri = cropOutputUri)
          launchCrop(uri, cropOutputUri)
        } else {
          scope.launch {
            try {
              val documentFile = withContext(Dispatchers.IO) { copyDocumentUriToCache(context, uri) }
              Log.d("FilePicker", "Copied document to: ${documentFile.absolutePath}")
              onMessage(
                TranslatorMessage.SetDocumentPath(
                  path = documentFile.absolutePath,
                  displayName = displayNameForUri(context, uri) ?: documentFile.name,
                  sizeBytes = sizeBytesForUri(context, uri) ?: documentFile.length(),
                  deleteAfterLoad = true,
                ),
              )
            } catch (e: Exception) {
              Log.e("FilePicker", "Failed to import document: $uri", e)
              Toast.makeText(context, "Failed to open file", Toast.LENGTH_SHORT).show()
            }
          }
        }
      } else {
        Log.d("FilePicker", "No file selected")
      }
    }

  // Image source selection bottom sheet
  if (showImageSourceSheet) {
    ImageSourceBottomSheet(
      onDismiss = onDismissImageSourceSheet,
      onCameraClick = {
        onDismissImageSourceSheet()
        val cameraImageUri = createTemporaryImageUri(context, "camera_image")
        val cropOutputUri = createTemporaryImageUri(context, "cropped_image")
        pendingImport.value = PendingImageImport(sourceUri = cameraImageUri, cropOutputUri = cropOutputUri)
        val cameraIntent =
          Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
          }
        try {
          takePictureIntent.launch(cameraIntent)
        } catch (e: ActivityNotFoundException) {
          Log.e("Camera", "No camera app found to handle IMAGE_CAPTURE intent", e)
          Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show()
        }
      },
      onMediaPickerClick = {
        onDismissImageSourceSheet()
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
      onGalleryClick = {
        onDismissImageSourceSheet()
        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickFromGallery.launch(galleryIntent)
      },
      onFilePickerClick = {
        onDismissImageSourceSheet()
        pickFromFiles.launch(filePickerMimeTypes)
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSourceBottomSheet(
  onDismiss: () -> Unit,
  onCameraClick: () -> Unit,
  onMediaPickerClick: () -> Unit,
  onGalleryClick: () -> Unit,
  onFilePickerClick: () -> Unit,
) {
  val bottomSheetState = rememberModalBottomSheetState()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = bottomSheetState,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(16.dp)
          .padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        // Camera (always present)
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clickable { onCameraClick() },
        ) {
          Icon(
            painter = painterResource(id = R.drawable.camera),
            contentDescription = "Camera",
            modifier =
              Modifier
                .size(48.dp)
                .padding(bottom = 8.dp),
            tint = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Camera",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
          )
        }

        // Conditional: Photos (Android 13+) or Gallery (older versions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          // Modern Photos picker for Android 13+
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onMediaPickerClick() },
          ) {
            Icon(
              painter = painterResource(id = R.drawable.gallery),
              contentDescription = "Photos",
              modifier =
                Modifier
                  .size(48.dp)
                  .padding(bottom = 8.dp),
              tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Photos",
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
            )
          }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
          // Traditional Gallery for older Android versions
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onGalleryClick() },
          ) {
            Icon(
              painter = painterResource(id = R.drawable.gallery),
              contentDescription = "Gallery",
              modifier =
                Modifier
                  .size(48.dp)
                  .padding(bottom = 8.dp),
              tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Gallery",
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
            )
          }
        }

        // File picker
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clickable { onFilePickerClick() },
        ) {
          Icon(
            painter = painterResource(id = R.drawable.draft),
            contentDescription = "Document",
            modifier =
              Modifier
                .size(48.dp)
                .padding(bottom = 8.dp),
            tint = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Document",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}
