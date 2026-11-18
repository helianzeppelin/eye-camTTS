package com.eyecam.app

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot

/** Resultado simples do reconhecimento de texto. */
data class ResultadoOcr(
    val texto: String,
    val qtdBlocos: Int,
    val qtdLinhas: Int,
    val textosBlocos: List<String> = emptyList(),
    val erro: String? = null,
    val alturaMedianaLinhaPx: Double? = null,
    val linhas: List<LinhaOcr> = emptyList()
)

/**
 * Resultado do OCR adaptativo. resultado (1.0 = imagem original) registra as duas leituras
 * e mede o ganho da ampliação descartada (1.0 = não houve segunda passada)
 */
data class ResultadoOcrAdaptativo(
    val resultado: ResultadoOcr,
    val fatorAmpliacao: Double,
    val alturaLinhaOriginalPx: Double?,
    val semAmpliacao: ResultadoOcr = resultado,
    val fatorTentado: Double = fatorAmpliacao,
    val tempoSemAmpliacaoMs: Long = 0,
    val tempoTotalMs: Long = 0
)

/**
 * Resultado do OCR incluindo as caixas dos blocos de texto em coordenadas normalizadas (0.0
 * a 1.0).
 */
data class ResultadoOcrDetalhado(
    val texto: String,
    val qtdBlocos: Int,
    val qtdLinhas: Int,
    val caixas: List<CaixaNormalizada>,
    val larguraImagem: Int,
    val alturaImagem: Int,
    val erro: String? = null
)

/** Reconhecimento de texto com o Google ML Kit. */
object OcrService {

    // Reconhecedor para alfabeto latino (cobre português)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Reconhece o texto de uma imagem. */
    suspend fun reconhecerTexto(bitmap: Bitmap): ResultadoOcr =
        suspendCancellableCoroutine { continuation ->

            // rotationDegrees = 0: a imagem já vem na orientação correta, corrigida no
            // firmware pelo vflip
            val imagem = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(imagem)
                .addOnSuccessListener { visionText ->
                    val blocos = visionText.textBlocks
                    val linhas = blocos.flatMap { bloco ->
                        bloco.lines.map { linha ->
                            LinhaOcr(
                                texto = linha.text,
                                alturaPx = alturaDaLinha(linha.cornerPoints, linha.boundingBox),
                                centroY = linha.boundingBox?.exactCenterY()?.toDouble() ?: 0.0
                            )
                        }
                    }
                    val alturas = linhas.mapNotNull { it.alturaPx }.sorted()

                    continuation.resume(
                        ResultadoOcr(
                            texto = visionText.text,
                            qtdBlocos = blocos.size,
                            qtdLinhas = linhas.size,
                            textosBlocos = blocos.map { it.text },
                            alturaMedianaLinhaPx = alturas.getOrNull(alturas.size / 2),
                            linhas = linhas
                        )
                    )
                }
                .addOnFailureListener { e ->
                    continuation.resume(
                        ResultadoOcr(
                            texto = "",
                            qtdBlocos = 0,
                            qtdLinhas = 0,
                            erro = e.message ?: e.javaClass.simpleName
                        )
                    )
                }
        }

    /**
     * Altura de uma linha medida perpendicular a ela, pelos quatro cantos que o ML Kit
     * devolve (superior esquerdo, superior direito, inferior direito, inferior esquerdo): a
     * média das duas laterais.
     */
    private fun alturaDaLinha(cantos: Array<Point>?, caixa: Rect?): Double? {
        if (cantos != null && cantos.size == 4) {
            val esquerda = hypot((cantos[3].x - cantos[0].x).toDouble(), (cantos[3].y - cantos[0].y).toDouble())
            val direita = hypot((cantos[2].x - cantos[1].x).toDouble(), (cantos[2].y - cantos[1].y).toDouble())
            val media = (esquerda + direita) / 2
            if (media > 0.0) return media
        }
        return caixa?.height()?.toDouble()?.takeIf { it > 0.0 }
    }

    /** OCR com ampliação adaptativa (pipeline adaptativo). 1. */
    suspend fun reconhecerAdaptativo(bitmap: Bitmap): ResultadoOcrAdaptativo {
        val inicio = System.currentTimeMillis()
        val primeira = reconhecerTexto(bitmap)
        val tempoPrimeira = System.currentTimeMillis() - inicio
        if (primeira.erro != null) {
            return ResultadoOcrAdaptativo(
                primeira, 1.0, null,
                tempoSemAmpliacaoMs = tempoPrimeira, tempoTotalMs = tempoPrimeira
            )
        }

        val altura = primeira.alturaMedianaLinhaPx
        val fator = AmpliacaoAdaptativa.fator(altura, bitmap.width, bitmap.height)
        if (fator <= 1.0) {
            return ResultadoOcrAdaptativo(
                primeira, 1.0, altura,
                tempoSemAmpliacaoMs = tempoPrimeira, tempoTotalMs = tempoPrimeira
            )
        }

        val ampliada = ImageProcessor.ampliar(bitmap, fator)
        val segunda = reconhecerTexto(ampliada)
        ampliada.recycle()
        val tempoTotal = System.currentTimeMillis() - inicio

        val letrasPrimeira = Estabilidade.normalizar(primeira.texto).length
        val letrasSegunda = Estabilidade.normalizar(segunda.texto).length
        val escolhida = if (segunda.erro == null && letrasSegunda >= letrasPrimeira) segunda else primeira
        return ResultadoOcrAdaptativo(
            resultado = escolhida,
            fatorAmpliacao = if (escolhida === segunda) fator else 1.0,
            alturaLinhaOriginalPx = altura,
            semAmpliacao = primeira,
            fatorTentado = fator,
            tempoSemAmpliacaoMs = tempoPrimeira,
            tempoTotalMs = tempoTotal
        )
    }

    /**
     * Reconhece o texto e devolve também as caixas dos blocos, normalizadas pelo tamanho da
     * imagem.
     */
    suspend fun reconhecerComCaixas(bitmap: Bitmap): ResultadoOcrDetalhado =
        suspendCancellableCoroutine { continuation ->

            val largura = bitmap.width
            val altura = bitmap.height
            val imagem = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(imagem)
                .addOnSuccessListener { visionText ->
                    val blocos = visionText.textBlocks
                    val caixas = mutableListOf<CaixaNormalizada>()
                    var totalLinhas = 0

                    for (bloco in blocos) {
                        totalLinhas += bloco.lines.size

                        val rect: Rect? = bloco.boundingBox
                        if (rect != null) {
                            CaixaNormalizada.de(rect, largura, altura)?.let {
                                caixas.add(it)
                            }
                        }
                    }

                    continuation.resume(
                        ResultadoOcrDetalhado(
                            texto = visionText.text,
                            qtdBlocos = blocos.size,
                            qtdLinhas = totalLinhas,
                            caixas = caixas,
                            larguraImagem = largura,
                            alturaImagem = altura
                        )
                    )
                }
                .addOnFailureListener { e ->
                    continuation.resume(
                        ResultadoOcrDetalhado(
                            texto = "",
                            qtdBlocos = 0,
                            qtdLinhas = 0,
                            caixas = emptyList(),
                            larguraImagem = largura,
                            alturaImagem = altura,
                            erro = e.message ?: e.javaClass.simpleName
                        )
                    )
                }
        }

    /** Localiza onde está o texto na imagem e recorta apenas essa região. */
    suspend fun recortarRegiaoDeTexto(
        bitmap: Bitmap,
        margemPercentual: Double = 0.05
    ): Bitmap? = suspendCancellableCoroutine { continuation ->

        val imagem = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(imagem)
            .addOnSuccessListener { visionText ->
                val blocos = visionText.textBlocks

                if (blocos.isEmpty()) {
                    continuation.resume(null)
                    return@addOnSuccessListener
                }

                // Caixa que engloba todos os blocos de texto
                var esquerda = Int.MAX_VALUE
                var topo = Int.MAX_VALUE
                var direita = 0
                var base = 0

                for (bloco in blocos) {
                    val caixa: Rect = bloco.boundingBox ?: continue
                    if (caixa.left < esquerda) esquerda = caixa.left
                    if (caixa.top < topo) topo = caixa.top
                    if (caixa.right > direita) direita = caixa.right
                    if (caixa.bottom > base) base = caixa.bottom
                }

                if (esquerda == Int.MAX_VALUE || direita <= esquerda || base <= topo) {
                    continuation.resume(null)
                    return@addOnSuccessListener
                }

                // Margem de folga, sem sair dos limites da imagem
                val margemX = ((direita - esquerda) * margemPercentual).toInt()
                val margemY = ((base - topo) * margemPercentual).toInt()

                val x = (esquerda - margemX).coerceAtLeast(0)
                val y = (topo - margemY).coerceAtLeast(0)
                val larguraFinal = (direita + margemX).coerceAtMost(bitmap.width) - x
                val alturaFinal = (base + margemY).coerceAtMost(bitmap.height) - y

                if (larguraFinal <= 0 || alturaFinal <= 0) {
                    continuation.resume(null)
                    return@addOnSuccessListener
                }

                // Se o recorte cobre quase toda a imagem, não vale a pena
                val areaRecorte = larguraFinal.toLong() * alturaFinal.toLong()
                val areaTotal = bitmap.width.toLong() * bitmap.height.toLong()
                if (areaRecorte > areaTotal * 0.9) {
                    continuation.resume(null)
                    return@addOnSuccessListener
                }

                continuation.resume(
                    Bitmap.createBitmap(bitmap, x, y, larguraFinal, alturaFinal)
                )
            }
            .addOnFailureListener {
                continuation.resume(null)
            }
    }
}
