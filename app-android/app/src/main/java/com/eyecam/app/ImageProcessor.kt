package com.eyecam.app

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** Configuração de quais operações de tratamento estão ativas. */
data class ConfigProcessamento(
    val grayscale: Boolean = false,
    val blur: Boolean = false,
    val threshold: Boolean = false,
    val engrossarTracos: Boolean = false,
    val resize: Boolean = false,
    val removerPontos: Boolean = false,
    val fatorResize: Double = 1.5
)

/**
 * Resultado do processamento: a imagem tratada + a variância do Laplaciano (medida de
 * nitidez/foco).
 */
data class ResultadoProcessamento(
    val bitmap: Bitmap,
    val varianciaLaplaciano: Double
)

/**
 * Faz o pré-processamento da imagem com OpenCV, etapa por etapa, conforme a configuração
 * escolhida.
 */
object ImageProcessor {

    /** Mede a nitidez da imagem pela variância do Laplaciano. */
    suspend fun medirNitidez(bitmap: Bitmap): Double = withContext(Dispatchers.Default) {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val cinza = Mat()
        Imgproc.cvtColor(mat, cinza, Imgproc.COLOR_RGBA2GRAY)

        val laplaciano = Mat()
        Imgproc.Laplacian(cinza, laplaciano, CvType.CV_64F)

        val media = MatOfDouble()
        val desvio = MatOfDouble()
        Core.meanStdDev(laplaciano, media, desvio)

        val variancia = desvio.get(0, 0)[0].pow(2)

        mat.release()
        cinza.release()
        laplaciano.release()
        media.release()
        desvio.release()

        variancia
    }

    /**
     * Nitidez (variância do Laplaciano) só do centro da imagem: o retângulo central com
     * [fracao] da largura e da altura.
     */
    suspend fun medirNitidezCentro(bitmap: Bitmap, fracao: Double = 0.4): Double {
        val largura = (bitmap.width * fracao).toInt().coerceAtLeast(1)
        val altura = (bitmap.height * fracao).toInt().coerceAtLeast(1)
        val centro = Bitmap.createBitmap(
            bitmap, (bitmap.width - largura) / 2, (bitmap.height - altura) / 2, largura, altura
        )
        return try {
            medirNitidez(centro)
        } finally {
            if (centro !== bitmap) centro.recycle()
        }
    }

    /** Processa a imagem aplicando as operações ativadas na configuração. */
    suspend fun processar(
        bitmap: Bitmap,
        config: ConfigProcessamento
    ): ResultadoProcessamento = withContext(Dispatchers.Default) {
        // Mede a nitidez da imagem ORIGINAL (antes de processar)
        val variancia = medirNitidez(bitmap)

        // Converte Bitmap -> Mat (formato do OpenCV)
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // 1.
        if (config.resize) {
            val novoTamanho = Size(
                mat.cols() * config.fatorResize,
                mat.rows() * config.fatorResize
            )
            Imgproc.resize(mat, mat, novoTamanho, 0.0, 0.0, Imgproc.INTER_CUBIC)
        }

        // 2.
        if (config.grayscale) {
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
        }

        // 3. BLUR - reduz ruído (precisa estar em cinza ou cor)
        if (config.blur) {
            Imgproc.GaussianBlur(mat, mat, Size(5.0, 5.0), 0.0)
        }

        // 4.
        if (config.threshold) {
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            }
            Imgproc.adaptiveThreshold(
                mat, mat,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                11,   // tamanho do bloco (deve ser ímpar)
                2.0   // constante subtraída
            )
        }

        // ATENÇÃO À POLARIDADE nas duas operações morfológicas abaixo.

        // 5.
        if (config.removerPontos) {
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            }
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(2.0, 2.0)
            )
            Imgproc.morphologyEx(mat, mat, Imgproc.MORPH_CLOSE, kernel)
        }

        // 6. ENGROSSAR TRAÇOS - erosão: o mínimo local faz o preto crescer
        if (config.engrossarTracos) {
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            }
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(2.0, 2.0)
            )
            Imgproc.erode(mat, mat, kernel)
        }

        // Converte Mat -> Bitmap de volta Precisa garantir que o bitmap de saída tenha o
        // tipo certo
        val bitmapSaida = Bitmap.createBitmap(
            mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888
        )
        // Se a imagem está em cinza (1 canal), converte de volta pra cor para o Bitmap
        // conseguir exibir
        if (mat.channels() == 1) {
            val matColorido = Mat()
            Imgproc.cvtColor(mat, matColorido, Imgproc.COLOR_GRAY2RGBA)
            Utils.matToBitmap(matColorido, bitmapSaida)
            matColorido.release()
        } else {
            Utils.matToBitmap(mat, bitmapSaida)
        }

        mat.release()

        ResultadoProcessamento(bitmapSaida, variancia)
    }

    /** Amplia a imagem por interpolação bicúbica, preservando as cores. */
    suspend fun ampliar(bitmap: Bitmap, fator: Double): Bitmap = withContext(Dispatchers.Default) {
        val origem = Mat()
        Utils.bitmapToMat(bitmap, origem)
        val ampliada = Mat()
        Imgproc.resize(
            origem, ampliada,
            Size(Math.round(origem.cols() * fator).toDouble(), Math.round(origem.rows() * fator).toDouble()),
            0.0, 0.0, Imgproc.INTER_CUBIC
        )
        val saida = Bitmap.createBitmap(ampliada.cols(), ampliada.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(ampliada, saida)
        origem.release()
        ampliada.release()
        saida
    }
}