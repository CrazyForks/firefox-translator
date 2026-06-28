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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.yalantis.ucrop.UCrop
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.copyDocumentUriToCache
import dev.davidv.translator.displayNameForUri
import dev.davidv.translator.isImageUri
import dev.davidv.translator.sizeBytesForUri
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
    "application/epub+zip",
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

private fun isRemoteUri(uri: Uri): Boolean = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)

private fun shouldStartInDocAlign(
  context: Context,
  uri: Uri,
): Boolean {
  val mime = context.contentResolver.getType(uri)?.lowercase() ?: return false
  val isPhotoMime = mime == "image/jpeg" || mime == "image/jpg" || mime == "image/heic" || mime == "image/heif"
  if (!isPhotoMime) return false
  val catalog =
    (context.applicationContext as? dev.davidv.translator.TranslatorApplication)?.languageCatalog
  return catalog?.supportInstalledByKind("doc_detect") == true
}

@Composable
fun ImageCaptureHandler(
  onMessage: (TranslatorMessage) -> Unit,
  showImageSourceSheet: Boolean,
  onDismissImageSourceSheet: () -> Unit,
  onCameraClick: () -> Unit,
  from: dev.davidv.translator.Language,
  to: dev.davidv.translator.Language,
  isAutoSource: Boolean,
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

  val activeSourceUri = remember { mutableStateOf<Uri?>(null) }
  val cropLauncherState = remember { mutableStateOf<((Intent) -> Unit)?>(null) }

  fun buildRectIntent(
    sourceUri: Uri,
    destUri: Uri,
  ): Intent {
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
    return UCrop
      .of(sourceUri, destUri)
      .withOptions(options)
      .getIntent(context)
      .setClass(context, AppCropActivity::class.java)
  }

  fun buildDocAlignIntent(
    sourceUri: Uri,
    destUri: Uri,
  ): Intent =
    Intent(context, DocAlignActivity::class.java).apply {
      putExtra(DocAlignActivity.EXTRA_INPUT_URI, sourceUri)
      putExtra(DocAlignActivity.EXTRA_OUTPUT_URI, destUri)
    }

  val cropImage =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val activeImport = pendingImport.value
      val resultIntent = result.data
      val sourceUri = activeSourceUri.value
      val ucropOutput = resultIntent?.let { UCrop.getOutput(it) }
      val ucropError = resultIntent?.let { UCrop.getError(it) }
      val docAlignOutput = resultIntent?.data
      val launch = cropLauncherState.value
      when {
        result.resultCode == android.app.Activity.RESULT_OK && ucropOutput != null -> {
          activeSourceUri.value = null
          activeImport?.sourceUri?.let { deleteTemporaryImageUri(context, it) }
          pendingImport.value = null
          Log.d("ImageCrop", "Image cropped (rect): $ucropOutput")
          onMessage(TranslatorMessage.SetImageUri(ucropOutput, deleteAfterLoad = true))
        }
        result.resultCode == android.app.Activity.RESULT_OK && docAlignOutput != null -> {
          activeSourceUri.value = null
          activeImport?.sourceUri?.let { deleteTemporaryImageUri(context, it) }
          pendingImport.value = null
          Log.d("ImageCrop", "Image cropped (doc-align): $docAlignOutput")
          onMessage(TranslatorMessage.SetImageUri(docAlignOutput, deleteAfterLoad = true))
        }
        result.resultCode == AppCropActivity.RESULT_SWITCH_TO_DOC_ALIGN &&
          activeImport != null && sourceUri != null && launch != null -> {
          Log.d("ImageCrop", "Switching to doc-align mode")
          launch(buildDocAlignIntent(sourceUri, activeImport.cropOutputUri))
        }
        result.resultCode == DocAlignActivity.RESULT_SWITCH_TO_RECT &&
          activeImport != null && sourceUri != null && launch != null -> {
          Log.d("ImageCrop", "Switching to rectangular crop")
          launch(buildRectIntent(sourceUri, activeImport.cropOutputUri))
        }
        else -> {
          activeSourceUri.value = null
          activeImport?.sourceUri?.let { deleteTemporaryImageUri(context, it) }
          activeImport?.cropOutputUri?.let { deleteTemporaryImageUri(context, it) }
          pendingImport.value = null
          Log.d("ImageCrop", "Crop cancelled or failed: ${ucropError?.message}")
        }
      }
    }
  cropLauncherState.value = { intent -> cropImage.launch(intent) }

  fun launchCrop(
    sourceUri: Uri,
    destUri: Uri,
  ) {
    if (isRemoteUri(sourceUri)) {
      deleteTemporaryImageUri(context, destUri)
      pendingImport.value = null
      Log.w("ImageCrop", "Remote image crop is unsupported: $sourceUri")
      Toast.makeText(context, context.getString(R.string.image_no_crop_remote), Toast.LENGTH_SHORT).show()
      return
    }

    activeSourceUri.value = sourceUri
    val intent =
      if (shouldStartInDocAlign(context, sourceUri)) {
        buildDocAlignIntent(sourceUri, destUri)
      } else {
        buildRectIntent(sourceUri, destUri)
      }
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
              Toast.makeText(context, context.getString(R.string.image_open_failed), Toast.LENGTH_SHORT).show()
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
      screenLiveEnabled = !isAutoSource,
      onScreenLiveClick = {
        if (isAutoSource) {
          Toast.makeText(context, context.getString(R.string.screen_translate_needs_source), Toast.LENGTH_LONG).show()
        } else {
          onDismissImageSourceSheet()
          context.startActivity(
            dev.davidv.translator.screenTranslate.ScreenCaptureRequestActivity.intent(
              context,
              sourceCode = from.code,
              targetCode = to.code,
              isAutoSource = false,
            ),
          )
        }
      },
      onCameraClick = {
        onDismissImageSourceSheet()
        onCameraClick()
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
  screenLiveEnabled: Boolean,
  onScreenLiveClick: () -> Unit,
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
          .padding(bottom = 16.dp)
          .testTag("export-section:Image source"),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        // Live screen translate (leftmost). Disabled when the source is auto —
        // live can't run the per-frame script classifier.
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clickable { onScreenLiveClick() },
        ) {
          val screenTint =
            if (screenLiveEnabled) {
              MaterialTheme.colorScheme.onSurface
            } else {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
          Icon(
            painter = painterResource(id = R.drawable.videocam),
            contentDescription = "Translate screen live",
            modifier =
              Modifier
                .size(48.dp)
                .padding(bottom = 8.dp),
            tint = screenTint,
          )
          Text(
            text = stringResource(R.string.image_source_screen),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = screenTint,
          )
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
            text = stringResource(R.string.image_source_document),
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
              text = stringResource(R.string.image_source_photos),
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
              text = stringResource(R.string.image_source_gallery),
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
            )
          }
        }

        // Camera (rightmost — live camera surface)
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
            text = stringResource(R.string.image_source_camera),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}
