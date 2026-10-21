package com.eyecam.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Resultado simples do reconhecimento de texto.
 *
 * @param textosBlocos texto de cada bloco, na ordem devolvida pelo ML Kit.
 *        Usado no modo desenvolvedor para medir o acerto bloco a bloco (a
 *        página de teste tem um parágrafo por tamanho de fonte).
 * @param erro mensagem técnica quando o OCR falha; nesse caso o texto vem
 *        vazio. Fica separada do texto para que a falha nunca seja lida em
 *        voz alta nem contada como texto reconhecido.
 * @param alturaMedianaLinhaPx altura mediana das caixas das linhas, em
 *        pixels: uma medida do tamanho do texto na imagem, usada pela
 *        ampliação adaptativa. null quando não há linhas.
 */
data class ResultadoOcr(
    val texto: String,
    val qtdBlocos: Int,
    val qtdLinhas: Int,
    val textosBlocos: List<String> = emptyList(),
    val erro: String? = null,
    val alturaMedianaLinhaPx: Double? = null
)

/**
 * Resultado do OCR adaptativo.
 *
 * @param fatorAmpliacao ampliação aplicada antes da passada que gerou o
 *        resultado (1.0 = imagem original)
 * @param alturaLinhaOriginalPx tamanho do texto medido na imagem original
 * @param semAmpliacao a primeira passada, na imagem original: o modo bancada
 *        registra as duas leituras e mede o ganho da ampliação
 * @param fatorTentado ampliação da segunda passada, mesmo quando ela foi
 *        descartada (1.0 = não houve segunda passada)
 * @param tempoSemAmpliacaoMs duração da primeira passada
 * @param tempoTotalMs duração das duas passadas, com a ampliação
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
 * Resultado do OCR incluindo as caixas dos blocos de texto em coordenadas
 * normalizadas (0.0 a 1.0).
 *
 * As caixas servem a dois propósitos:
 *   - detectar movimento da câmera entre quadros (ver Estabilidade)
 *   - recortar a região do texto antes da leitura definitiva
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

/**
 * Reconhecimento de texto com o Google ML Kit.
 *
 * O modelo roda inteiramente no aparelho, sem necessidade de internet — o que
 * é essencial aqui, já que o celular fica conectado ao Access Point do ESP32,
 * que não tem saída para a internet.
 */
object OcrService {

    // Reconhecedor para alfabeto latino (cobre português)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Reconhece o texto de uma imagem.
     */
    suspend fun reconhecerTexto(bitmap: Bitmap): ResultadoOcr =
        suspendCancellableCoroutine { continuation ->

            // rotationDegrees = 0: a imagem já vem na orientação correta,
            // corrigida no firmware pelo vflip
            val imagem = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(imagem)
                .addOnSuccessListener { visionText ->
                    val blocos = visionText.textBlocks
                    var totalLinhas = 0
                    val alturas = mutableListOf<Int>()
                    for (bloco in blocos) {
                        totalLinhas += bloco.lines.size
                        for (linha in bloco.lines) {
                            linha.boundingBox?.let { alturas += it.height() }
                        }
                    }

                    continuation.resume(
                        ResultadoOcr(
                            texto = visionText.text,
                            qtdBlocos = blocos.size,
                            qtdLinhas = totalLinhas,
                            textosBlocos = blocos.map { it.text },
                            alturaMedianaLinhaPx = alturas.sorted()
                                .getOrNull(alturas.size / 2)?.toDouble()
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
     * OCR com ampliação adaptativa (pipeline adaptativo).
     *
     * 1. Lê a imagem original e mede o tamanho do texto (altura das linhas)
     * 2. Se o texto estiver pequeno, ou se nada foi achado, amplia a imagem
     *    (AmpliacaoAdaptativa decide quanto) e lê de novo
     * 3. Fica com a passada que reconheceu mais texto
     *
     * A segunda passada só acontece quando pode ajudar; texto grande é lido
     * uma vez só. Critério "mais texto": sem o campo de confiança do OCR, a
     * quantidade de letras e dígitos reconhecidos é o sinal disponível, e nos
     * testes a passada ampliada só perdia por diferenças mínimas.
     */
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
     * Reconhece o texto e devolve também as caixas dos blocos, normalizadas
     * pelo tamanho da imagem.
     *
     * Por que normalizar aqui: a sondagem de estabilidade roda em quadros de
     * baixa resolução e a captura final em alta resolução. Devolvendo as
     * caixas em fração da imagem, o restante do código compara quadros de
     * resoluções diferentes sem converter escala em lugar nenhum.
     *
     * Usa BLOCOS (e não linhas) como unidade: blocos são mais estáveis entre
     * quadros, porque pequenas variações do reconhecimento quebram e
     * reagrupam linhas com muito mais frequência.
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

    /**
     * Localiza onde está o texto na imagem e recorta apenas essa região.
     *
     * Motivação: numa foto tirada por um óculos, o texto pode ocupar só uma
     * parte do quadro (o resto é mesa, parede, a outra página). Recortar a
     * região de texto concentra os pixels onde eles importam, melhorando o
     * reconhecimento na segunda passada.
     *
     * @param margemPercentual folga ao redor do texto (0.05 = 5% de cada lado)
     * @return o bitmap recortado, ou null se não valer o recorte
     */
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
