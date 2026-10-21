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

/**
 * Configuração de quais operações de tratamento estão ativas.
 * Permite ligar/desligar cada etapa para testes experimentais (ablação).
 *
 * Padrão: TUDO DESLIGADO, ou seja, a imagem vai crua para o ML Kit. Os testes
 * com a câmera virtual mostraram que o OCR neural lê melhor a imagem crua:
 * a limiarização e a morfologia reduziram o acerto em imagens boas e médias
 * (ex.: 99% -> 33% com o antigo padrão cinza+blur+limiar+dilatação). As
 * operações continuam disponíveis para o estudo comparativo do TCC.
 *
 * @param engrossarTracos engrossa as letras pretas (erosão 2x2)
 * @param removerPontos remove pontos pretos isolados (fechamento 2x2)
 */
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
 * Resultado do processamento: a imagem tratada + a variância do Laplaciano
 * (medida de nitidez/foco).
 */
data class ResultadoProcessamento(
    val bitmap: Bitmap,
    val varianciaLaplaciano: Double
)

/**
 * Faz o pré-processamento da imagem com OpenCV, etapa por etapa,
 * conforme a configuração escolhida.
 *
 * As funções são suspend e rodam em Dispatchers.Default: em UXGA o OpenCV
 * leva centenas de milissegundos, e na thread principal isso congelava a
 * tela (inclusive o indicador de progresso) e inflava os tempos medidos.
 */
object ImageProcessor {

    /**
     * Mede a nitidez da imagem pela variância do Laplaciano.
     * Valor baixo = imagem borrada. Valor alto = imagem nítida.
     */
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
     * Processa a imagem aplicando as operações ativadas na configuração.
     * Retorna o bitmap processado + a variância do Laplaciano.
     */
    suspend fun processar(
        bitmap: Bitmap,
        config: ConfigProcessamento
    ): ResultadoProcessamento = withContext(Dispatchers.Default) {
        // Mede a nitidez da imagem ORIGINAL (antes de processar)
        val variancia = medirNitidez(bitmap)

        // Converte Bitmap -> Mat (formato do OpenCV)
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // 1. RESIZE (upscale) - aplicado primeiro para as outras operações
        //    trabalharem na imagem ampliada
        if (config.resize) {
            val novoTamanho = Size(
                mat.cols() * config.fatorResize,
                mat.rows() * config.fatorResize
            )
            Imgproc.resize(mat, mat, novoTamanho, 0.0, 0.0, Imgproc.INTER_CUBIC)
        }

        // 2. GRAYSCALE - converte para tons de cinza
        //    Utils.bitmapToMat entrega o Mat em RGBA. Com COLOR_BGR2GRAY (usado
        //    até a revisão de 10/2026) os pesos do vermelho e do azul ficavam
        //    trocados; por isso todas as conversões usam COLOR_RGBA2GRAY.
        if (config.grayscale) {
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
        }

        // 3. BLUR - reduz ruído (precisa estar em cinza ou cor)
        if (config.blur) {
            Imgproc.GaussianBlur(mat, mat, Size(5.0, 5.0), 0.0)
        }

        // 4. THRESHOLD ADAPTATIVO - binariza compensando iluminação
        //    Só funciona em imagem de 1 canal (cinza), então garante grayscale
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
        // Depois do limiar o texto é PRETO (0) sobre fundo BRANCO (255). Na
        // OpenCV, dilate é um filtro de MÁXIMO e erode de MÍNIMO: quem
        // "cresce" na dilatação é o branco, ou seja, o fundo. Até a revisão
        // de 10/2026 o app usava dilate para "engrossar" e MORPH_OPEN para
        // "limpar ruído", e o efeito era o oposto: a dilatação afinava as
        // letras (removia ~37% dos pixels pretos nos testes) e a abertura
        // apagava detalhes brancos dentro das letras.

        // 5. REMOVER PONTOS - fechamento (dilata e depois erode): pontos
        //    pretos menores que o elemento estruturante somem, letras ficam
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

        // Converte Mat -> Bitmap de volta
        // Precisa garantir que o bitmap de saída tenha o tipo certo
        val bitmapSaida = Bitmap.createBitmap(
            mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888
        )
        // Se a imagem está em cinza (1 canal), converte de volta pra cor
        // para o Bitmap conseguir exibir
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

    /**
     * Amplia a imagem por interpolação bicúbica, preservando as cores.
     *
     * Não cria detalhe novo, mas dá ao detector de texto do OCR letras do
     * tamanho que ele espera; ver AmpliacaoAdaptativa para os números.
     */
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