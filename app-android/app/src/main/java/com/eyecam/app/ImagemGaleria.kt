package com.eyecam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Abre uma imagem da galeria do celular para o modo desenvolvedor.
 *
 * Serve para separar o PROGRAMA da CÂMERA nos testes:
 *   - imagens de teste prontas (por exemplo, as da câmera virtual) passam
 *     pelo mesmo tratamento e pelo mesmo ML Kit do app, sem o óculos;
 *   - uma foto da página de teste tirada com a câmera do próprio celular
 *     mostra até onde o programa chega com uma câmera boa. Se ela é lida e
 *     a foto do óculos não, o limite está na captura.
 */
// android.media.ExifInterface tinha falhas só até o Android 7.0 (API 24-25);
// o app exige API 26+, então a versão do sistema basta e evita mais uma
// dependência (androidx.exifinterface)
@SuppressLint("ExifInterface")
object ImagemGaleria {

    /**
     * Maior lado da imagem carregada. 2592 px é o lado de 5 MP da OV5640:
     * mantém fotos de celular (12 MP ou mais) comparáveis às do óculos e
     * evita bitmaps de dezenas de megabytes.
     */
    private const val DIMENSAO_MAXIMA = 2592

    suspend fun carregar(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val resolvedor = context.contentResolver

            // 1. Só as dimensões, para escolher a redução na decodificação
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolvedor.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
            if (limites.outWidth <= 0 || limites.outHeight <= 0) return@withContext null

            var amostra = 1
            while (max(limites.outWidth, limites.outHeight) / amostra > DIMENSAO_MAXIMA) amostra *= 2

            // 2. Decodifica já reduzida (potência de 2)
            val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
            var bitmap = resolvedor.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opcoes)
            } ?: return@withContext null

            // 3. Ajuste fino até o limite, se a potência de 2 não bastou
            val maior = max(bitmap.width, bitmap.height)
            if (maior > DIMENSAO_MAXIMA) {
                val escala = DIMENSAO_MAXIMA.toFloat() / maior
                bitmap = Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * escala).toInt(), (bitmap.height * escala).toInt(), true
                )
            }

            // 4. Rotação gravada no EXIF: fotos de celular costumam vir
            //    "deitadas", e o OCR precisa do texto na horizontal
            val graus = resolvedor.openInputStream(uri)?.use { rotacaoExif(ExifInterface(it)) } ?: 0
            if (graus == 0) {
                bitmap
            } else {
                val matriz = Matrix().apply { postRotate(graus.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matriz, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rotacaoExif(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
