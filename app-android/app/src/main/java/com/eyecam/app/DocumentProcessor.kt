package com.eyecam.app

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Resultado do enquadramento automático da imagem.
 *
 * @param bitmap     imagem corrigida
 * @param metodo     qual estratégia funcionou ("perspectiva", "inclinacao", "nenhum")
 * @param anguloGraus ângulo corrigido, quando aplicável
 */
data class ResultadoEnquadramento(
    val bitmap: Bitmap,
    val metodo: String,
    val anguloGraus: Double = 0.0
)

/**
 * Corrige automaticamente o enquadramento da imagem capturada.
 *
 * Motivação: em um dispositivo vestível, o usuário não consegue enquadrar a
 * página (especialmente sendo deficiente visual). A imagem chega torta, com
 * distorção de perspectiva e com elementos irrelevantes ao redor do texto.
 * Este módulo resolve isso em software, antes do OCR.
 *
 * Estratégias, em ordem de tentativa:
 *   1. Detecção de página + correção de perspectiva (melhor resultado)
 *   2. Correção de inclinação das linhas de texto (fallback)
 */
object DocumentProcessor {

    // Largura usada na etapa de DETECÇÃO (não no resultado final).
    // Detectar bordas numa imagem reduzida é muito mais rápido, e os cantos
    // encontrados são depois reescalados para a imagem original.
    private const val LARGURA_DETECCAO = 600.0

    // A página detectada precisa ocupar ao menos esta fração da imagem.
    // Evita que o algoritmo recorte um retângulo pequeno qualquer.
    private const val AREA_MINIMA_RELATIVA = 0.15

    // Limites do deskew: abaixo do mínimo não vale a pena, acima do máximo
    // provavelmente é uma leitura errada do ângulo.
    private const val ANGULO_MINIMO = 0.5
    private const val ANGULO_MAXIMO = 30.0

    /**
     * Ponto de entrada: tenta enquadrar a imagem automaticamente.
     *
     * Roda em Dispatchers.Default: a correção de perspectiva em UXGA com
     * INTER_CUBIC é pesada demais para a thread principal.
     *
     * @param corrigirPerspectiva tenta detectar a página e corrigir perspectiva
     * @param corrigirInclinacao  tenta endireitar as linhas de texto
     */
    suspend fun enquadrar(
        bitmap: Bitmap,
        corrigirPerspectiva: Boolean = true,
        corrigirInclinacao: Boolean = true
    ): ResultadoEnquadramento = withContext(Dispatchers.Default) {

        // Estratégia 1: detectar a página e corrigir a perspectiva
        if (corrigirPerspectiva) {
            detectarPagina(bitmap)?.let { return@withContext it }
        }

        // Estratégia 2: endireitar as linhas de texto
        if (corrigirInclinacao) {
            endireitarTexto(bitmap)?.let { return@withContext it }
        }

        // Nenhuma correção aplicada: devolve a original
        ResultadoEnquadramento(bitmap, "nenhum")
    }

    // ========================================================================
    // ESTRATÉGIA 1 — DETECÇÃO DE PÁGINA E CORREÇÃO DE PERSPECTIVA
    // ========================================================================
    /**
     * Procura o maior quadrilátero da imagem (a folha de papel) e aplica uma
     * transformação de perspectiva para transformá-lo num retângulo reto.
     *
     * Retorna null se não encontrar um quadrilátero convincente.
     */
    private fun detectarPagina(bitmap: Bitmap): ResultadoEnquadramento? {
        val original = Mat()
        Utils.bitmapToMat(bitmap, original)

        // --- Prepara uma versão reduzida só para detectar ---
        val escala = LARGURA_DETECCAO / original.cols()
        val reduzida = Mat()
        Imgproc.resize(
            original, reduzida,
            Size(LARGURA_DETECCAO, original.rows() * escala)
        )

        val cinza = Mat()
        Imgproc.cvtColor(reduzida, cinza, Imgproc.COLOR_RGBA2GRAY)

        // Suaviza para o detector de bordas não pegar textura do papel
        Imgproc.GaussianBlur(cinza, cinza, Size(5.0, 5.0), 0.0)

        // Fecha pequenas falhas nas bordas do papel antes do Canny
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(cinza, cinza, Imgproc.MORPH_CLOSE, kernel)

        val bordas = Mat()
        Imgproc.Canny(cinza, bordas, 60.0, 180.0)

        // --- Procura contornos ---
        val contornos = ArrayList<MatOfPoint>()
        val hierarquia = Mat()
        Imgproc.findContours(
            bordas, contornos, hierarquia,
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
        )

        // Ordena do maior para o menor e examina só os maiores candidatos
        contornos.sortByDescending { Imgproc.contourArea(it) }

        val areaImagem = reduzida.rows().toDouble() * reduzida.cols().toDouble()
        var cantos: Array<Point>? = null

        for (contorno in contornos.take(6)) {
            val curva = MatOfPoint2f(*contorno.toArray())
            val perimetro = Imgproc.arcLength(curva, true)
            val aproximado = MatOfPoint2f()

            // approxPolyDP simplifica o contorno; com 2% do perímetro,
            // um retângulo levemente irregular colapsa em 4 pontos.
            Imgproc.approxPolyDP(curva, aproximado, 0.02 * perimetro, true)

            if (aproximado.total() == 4L) {
                val pontos = aproximado.toArray()
                val comoMatOfPoint = MatOfPoint(*pontos)
                val area = Imgproc.contourArea(comoMatOfPoint)

                // Precisa ser grande e convexo (um retângulo de verdade)
                if (area >= areaImagem * AREA_MINIMA_RELATIVA &&
                    Imgproc.isContourConvex(comoMatOfPoint)
                ) {
                    cantos = pontos
                    comoMatOfPoint.release()
                    curva.release()
                    aproximado.release()
                    break
                }
                comoMatOfPoint.release()
            }
            curva.release()
            aproximado.release()
        }

        // Libera o que já não é necessário
        reduzida.release()
        cinza.release()
        bordas.release()
        hierarquia.release()
        contornos.forEach { it.release() }

        if (cantos == null) {
            original.release()
            return null
        }

        // --- Reescala os cantos para a imagem original ---
        val cantosOriginais = cantos.map {
            Point(it.x / escala, it.y / escala)
        }.toTypedArray()

        val ordenados = ordenarCantos(cantosOriginais)
        val resultado = aplicarPerspectiva(original, ordenados)

        original.release()
        return ResultadoEnquadramento(resultado, "perspectiva")
    }

    /**
     * Ordena 4 pontos como: topo-esquerda, topo-direita, baixo-direita, baixo-esquerda.
     *
     * Truque: a soma (x+y) é mínima no canto superior esquerdo e máxima no
     * inferior direito; a diferença (y-x) é mínima no superior direito e
     * máxima no inferior esquerdo.
     */
    private fun ordenarCantos(pontos: Array<Point>): Array<Point> {
        val somas = pontos.map { it.x + it.y }
        val difs = pontos.map { it.y - it.x }

        val topoEsq = pontos[somas.indexOf(somas.minOrNull()!!)]
        val baixoDir = pontos[somas.indexOf(somas.maxOrNull()!!)]
        val topoDir = pontos[difs.indexOf(difs.minOrNull()!!)]
        val baixoEsq = pontos[difs.indexOf(difs.maxOrNull()!!)]

        return arrayOf(topoEsq, topoDir, baixoDir, baixoEsq)
    }

    /**
     * Aplica a transformação de perspectiva: mapeia os 4 cantos detectados
     * para um retângulo perfeito, "desamassando" a página.
     */
    private fun aplicarPerspectiva(origem: Mat, cantos: Array<Point>): Bitmap {
        val (topoEsq, topoDir, baixoDir, baixoEsq) = cantos

        // A largura/altura de destino é a maior medida entre os lados opostos
        val larguraTopo = distancia(topoEsq, topoDir)
        val larguraBase = distancia(baixoEsq, baixoDir)
        val largura = max(larguraTopo, larguraBase)

        val alturaEsq = distancia(topoEsq, baixoEsq)
        val alturaDir = distancia(topoDir, baixoDir)
        val altura = max(alturaEsq, alturaDir)

        val src = MatOfPoint2f(topoEsq, topoDir, baixoDir, baixoEsq)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(largura - 1, 0.0),
            Point(largura - 1, altura - 1),
            Point(0.0, altura - 1)
        )

        val transformacao = Imgproc.getPerspectiveTransform(src, dst)
        val corrigida = Mat()
        Imgproc.warpPerspective(
            origem, corrigida, transformacao,
            Size(largura, altura),
            Imgproc.INTER_CUBIC
        )

        val saida = matParaBitmap(corrigida)

        src.release()
        dst.release()
        transformacao.release()
        corrigida.release()

        return saida
    }

    private fun distancia(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    // ========================================================================
    // ESTRATÉGIA 2 — CORREÇÃO DE INCLINAÇÃO (DESKEW)
    // ========================================================================
    /**
     * Mede o ângulo das linhas de texto e rotaciona a imagem para deixá-las
     * horizontais. Usado quando a página preenche o quadro e não há bordas
     * detectáveis.
     *
     * Como funciona: binariza o texto, une as letras em blocos horizontais
     * (dilatação horizontal), e mede o ângulo do menor retângulo que envolve
     * todos os pixels de texto.
     */
    private fun endireitarTexto(bitmap: Bitmap): ResultadoEnquadramento? {
        val original = Mat()
        Utils.bitmapToMat(bitmap, original)

        val cinza = Mat()
        Imgproc.cvtColor(original, cinza, Imgproc.COLOR_RGBA2GRAY)

        // Binariza invertido: texto vira branco (valor alto), fundo vira preto.
        // O OTSU escolhe o limiar automaticamente.
        val binaria = Mat()
        Imgproc.threshold(
            cinza, binaria, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
        )

        // Une as letras de cada linha num traço contínuo. Um kernel largo e
        // baixo (30x3) conecta na horizontal sem juntar linhas vizinhas.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(30.0, 3.0))
        Imgproc.morphologyEx(binaria, binaria, Imgproc.MORPH_CLOSE, kernel)

        // Coleta todos os pixels de texto
        val naoZero = Mat()
        Core.findNonZero(binaria, naoZero)

        if (naoZero.empty()) {
            original.release(); cinza.release(); binaria.release(); naoZero.release()
            return null
        }

        val pontos = MatOfPoint2f()
        naoZero.convertTo(pontos, CvType.CV_32FC2)

        // O menor retângulo rotacionado que envolve o texto indica a inclinação
        val retangulo = Imgproc.minAreaRect(pontos)
        var angulo = retangulo.angle

        // Normaliza para a faixa [-45, 45]: o retângulo pode ser descrito
        // de quatro formas equivalentes, e queremos a menor rotação.
        if (angulo > 45) angulo -= 90
        if (angulo < -45) angulo += 90

        cinza.release()
        binaria.release()
        naoZero.release()
        pontos.release()

        // Ângulo desprezível ou implausível: não mexe
        if (abs(angulo) < ANGULO_MINIMO || abs(angulo) > ANGULO_MAXIMO) {
            original.release()
            return null
        }

        val centro = Point(original.cols() / 2.0, original.rows() / 2.0)
        val rotacao = Imgproc.getRotationMatrix2D(centro, angulo, 1.0)
        val rotacionada = Mat()
        // BORDER_REPLICATE estende as bordas em vez de deixar faixas negras
        // nos cantos após a rotação.
        Imgproc.warpAffine(
            original, rotacionada, rotacao, original.size(),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
        )

        val saida = matParaBitmap(rotacionada)

        original.release()
        rotacao.release()
        rotacionada.release()

        return ResultadoEnquadramento(saida, "inclinacao", angulo)
    }

    // ========================================================================
    // AUXILIAR
    // ========================================================================
    /**
     * Converte um Mat para Bitmap, tratando imagens de 1 canal (cinza).
     */
    private fun matParaBitmap(mat: Mat): Bitmap {
        val saida = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)

        if (mat.channels() == 1) {
            val colorido = Mat()
            Imgproc.cvtColor(mat, colorido, Imgproc.COLOR_GRAY2RGBA)
            Utils.matToBitmap(colorido, saida)
            colorido.release()
        } else {
            Utils.matToBitmap(mat, saida)
        }

        return saida
    }
}
