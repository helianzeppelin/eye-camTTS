package com.eyecam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Salva imagens na galeria do celular (pasta Pictures/EyeCam). */
object ImageSaver {

    /** Salva um bitmap na galeria com um prefixo no nome (ex: "original", "processada"). */
    suspend fun salvarNaGaleria(
        context: Context,
        bitmap: Bitmap,
        prefixo: String
    ): Boolean = withContext(Dispatchers.IO) {
        // Nome do arquivo com data/hora para não sobrescrever
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val nomeArquivo = "eyecam_${prefixo}_$timestamp.png"

        try {
            val valores = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, nomeArquivo)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                // A partir do Android 10, salva numa subpasta dedicada
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EyeCam"
                    )
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                valores
            )

            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { saida ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, saida)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
