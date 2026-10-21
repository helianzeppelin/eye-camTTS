package com.eyecam.app

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Caixa delimitadora com coordenadas NORMALIZADAS entre 0.0 e 1.0.
 *
 * Por que normalizar: o Eye-cam compara quadros capturados em resoluções
 * diferentes (sondagem em QVGA, captura final em UXGA). Em pixels, a mesma
 * região teria números completamente diferentes em cada resolução. Em
 * coordenadas relativas ao tamanho da imagem, a comparação passa a ser
 * independente de resolução.
 */
data class CaixaNormalizada(
    val esquerda: Double,
    val topo: Double,
    val direita: Double,
    val base: Double
) {
    val largura: Double get() = direita - esquerda
    val altura: Double get() = base - topo
    val centroX: Double get() = (esquerda + direita) / 2.0
    val centroY: Double get() = (topo + base) / 2.0

    /** Fração da imagem ocupada por esta caixa (0.0 a 1.0). */
    val area: Double get() = largura * altura

    companion object {
        /**
         * Converte um Rect em pixels (como o ML Kit devolve) para coordenadas
         * normalizadas. Retorna null se o retângulo for degenerado.
         */
        fun de(rect: Rect, larguraImagem: Int, alturaImagem: Int): CaixaNormalizada? {
            if (larguraImagem <= 0 || alturaImagem <= 0) return null
            if (rect.right <= rect.left || rect.bottom <= rect.top) return null

            val l = larguraImagem.toDouble()
            val a = alturaImagem.toDouble()

            return CaixaNormalizada(
                esquerda = (rect.left / l).coerceIn(0.0, 1.0),
                topo = (rect.top / a).coerceIn(0.0, 1.0),
                direita = (rect.right / l).coerceIn(0.0, 1.0),
                base = (rect.bottom / a).coerceIn(0.0, 1.0)
            )
        }
    }
}

/**
 * Algoritmos de estabilidade entre quadros consecutivos.
 *
 * O problema que resolvem: numa foto tirada por um óculos, o usuário não
 * consegue enquadrar nem segurar firme. Antes de gastar uma captura em alta
 * resolução (lenta e caro em banda), vale confirmar que a cena está parada.
 *
 * Dois sinais independentes são usados:
 *   1. GEOMÉTRICO  - as caixas de texto continuam nas mesmas posições?
 *                    Detecta movimento da câmera.
 *   2. TEXTUAL     - o texto reconhecido é praticamente o mesmo?
 *                    Detecta variação do reconhecimento (foco oscilando,
 *                    ruído, iluminação mudando).
 *
 * Exigir os dois evita dois falsos positivos: caixas paradas com texto
 * variando (imagem instável) e texto igual com caixas deslocando (câmera
 * em movimento lento sobre texto repetitivo).
 *
 * Origem: os testes de estabilidade textual e geométrica são adaptados das
 * funções `_v2_texts_stable` e `_detection_boxes_stable` do projeto
 * GameSentenceMiner (https://github.com/bpwhelan/GameSentenceMiner, arquivo
 * GameSentenceMiner/ocr/gsm_ocr.py), distribuído sob a GPL-3.0. A
 * similaridade usa Levenshtein em vez do SequenceMatcher do original, que
 * não existe na biblioteca padrão do Kotlin.
 */
object Estabilidade {

    /** Similaridade mínima (0-100) para considerar dois textos estáveis. */
    const val LIMIAR_SIMILARIDADE_PADRAO = 90

    /**
     * Tolerância na comparação de caixas, em fração da imagem.
     * 0.02 = a caixa pode se deslocar até 2% da dimensão da imagem.
     */
    const val TOLERANCIA_CAIXA_PADRAO = 0.02

    // Rejeição rápida por diferença de comprimento, antes de calcular
    // similaridade (que é caro). Se os textos têm tamanhos muito diferentes,
    // não há chance de serem o mesmo conteúdo.
    private const val DELTA_ABSOLUTO_MINIMO = 2
    private const val DELTA_RELATIVO = 0.10

    // Acima deste tamanho, a comparação caractere a caractere fica lenta.
    // Textos longos são truncados: os primeiros caracteres já bastam para
    // decidir estabilidade.
    private const val LIMITE_COMPARACAO = 1200

    // ========================================================================
    // NORMALIZAÇÃO
    // ========================================================================
    /**
     * Prepara um texto para comparação: mantém apenas letras, dígitos e
     * espaços; colapsa espaços múltiplos; converte para minúsculas.
     *
     * Por que descartar a pontuação: lixo de OCR é majoritariamente
     * pontuação. Duas leituras ruins e sem relação podem compartilhar
     * muitos ".", ",", "-" e parecer artificialmente parecidas, inflando a
     * similaridade e fazendo o sistema aceitar um quadro ruim como estável.
     *
     * Por que minúsculas: o OCR troca maiúscula por minúscula com
     * frequência ("PREFÁCIo"), e isso não indica instabilidade da imagem.
     *
     * Acentos são preservados: em português eles carregam informação real,
     * e errar acento É sinal de leitura instável.
     */
    fun normalizar(texto: String): String {
        val construtor = StringBuilder(texto.length)
        var espacoPendente = false

        for (caractere in texto) {
            when {
                caractere.isLetterOrDigit() -> {
                    if (espacoPendente && construtor.isNotEmpty()) {
                        construtor.append(' ')
                    }
                    espacoPendente = false
                    construtor.append(caractere)
                }
                // Qualquer separador (espaço, quebra de linha, tabulação)
                // vira um único espaço pendente
                caractere.isWhitespace() -> espacoPendente = true
                // Pontuação e símbolos são descartados sem gerar espaço,
                // para que "co-lheita" e "colheita" fiquem iguais
                else -> { }
            }
        }

        return construtor.toString().lowercase()
    }

    // ========================================================================
    // SIMILARIDADE
    // ========================================================================
    /**
     * Distância de Levenshtein: número mínimo de inserções, remoções e
     * substituições de caracteres para transformar um texto no outro.
     *
     * Implementação com duas linhas em vez da matriz completa, para não
     * alocar memória proporcional ao produto dos comprimentos.
     */
    fun distanciaLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // A linha de trabalho tem o tamanho do MENOR texto, para economizar
        val curto: String
        val longo: String
        if (a.length <= b.length) {
            curto = a; longo = b
        } else {
            curto = b; longo = a
        }

        var anterior = IntArray(curto.length + 1) { it }
        var atual = IntArray(curto.length + 1)

        for (i in 1..longo.length) {
            atual[0] = i
            val caractereLongo = longo[i - 1]

            for (j in 1..curto.length) {
                val custoSubstituicao =
                    if (caractereLongo == curto[j - 1]) 0 else 1

                atual[j] = min(
                    min(
                        atual[j - 1] + 1,        // inserção
                        anterior[j] + 1          // remoção
                    ),
                    anterior[j - 1] + custoSubstituicao
                )
            }

            // Troca as linhas em vez de copiar
            val temporario = anterior
            anterior = atual
            atual = temporario
        }

        return anterior[curto.length]
    }

    /**
     * Similaridade entre dois textos, de 0 a 100.
     *
     * Os textos são normalizados antes da comparação. Serve tanto para o
     * portão de estabilidade quanto para medir a taxa de acerto do OCR
     * contra um texto de referência conhecido.
     */
    fun similaridade(a: String, b: String): Double {
        val normA = normalizar(a).take(LIMITE_COMPARACAO)
        val normB = normalizar(b).take(LIMITE_COMPARACAO)

        if (normA.isEmpty() && normB.isEmpty()) return 100.0
        if (normA.isEmpty() || normB.isEmpty()) return 0.0
        if (normA == normB) return 100.0

        val maiorComprimento = max(normA.length, normB.length)
        val distancia = distanciaLevenshtein(normA, normB)

        return (1.0 - distancia.toDouble() / maiorComprimento) * 100.0
    }

    // ========================================================================
    // ESTABILIDADE TEXTUAL
    // ========================================================================
    /**
     * Decide se dois textos consecutivos representam a mesma leitura.
     *
     * Sequência de três testes, do mais barato ao mais caro:
     *   1. Textos vazios nunca são considerados estáveis
     *   2. Textos idênticos (após normalizar) são estáveis
     *   3. Diferença grande de comprimento descarta sem calcular distância
     *   4. Só então calcula a similaridade e compara com o limiar
     */
    fun textosEstaveis(
        anterior: String,
        novo: String,
        limiar: Int = LIMIAR_SIMILARIDADE_PADRAO
    ): Boolean {
        val normAnterior = normalizar(anterior).take(LIMITE_COMPARACAO)
        val normNovo = normalizar(novo).take(LIMITE_COMPARACAO)

        // Teste 1: sem texto, não há o que estabilizar
        if (normAnterior.isEmpty() || normNovo.isEmpty()) return false

        // Teste 2: idênticos
        if (normAnterior == normNovo) return true

        // Teste 3: rejeição rápida por comprimento
        val maiorComprimento = max(normAnterior.length, normNovo.length)
        val deltaComprimento = abs(normAnterior.length - normNovo.length)
        val deltaTolerado = max(
            DELTA_ABSOLUTO_MINIMO,
            (maiorComprimento * DELTA_RELATIVO).toInt()
        )
        if (deltaComprimento > deltaTolerado) return false

        // Teste 4: similaridade caractere a caractere
        val distancia = distanciaLevenshtein(normAnterior, normNovo)
        val percentual = (1.0 - distancia.toDouble() / maiorComprimento) * 100.0

        return percentual >= limiar
    }

    // ========================================================================
    // ESTABILIDADE GEOMÉTRICA
    // ========================================================================
    /**
     * Ordena as caixas de forma determinística (de cima para baixo, da
     * esquerda para a direita), para que a comparação entre quadros
     * compare as mesmas caixas.
     *
     * Sem isso, a mesma cena poderia gerar as caixas em ordens diferentes
     * e a comparação falharia sem motivo.
     */
    fun ordenar(caixas: List<CaixaNormalizada>): List<CaixaNormalizada> {
        return caixas.sortedWith(
            compareBy({ it.topo }, { it.esquerda })
        )
    }

    /**
     * Compara duas caixas coordenada por coordenada.
     */
    fun caixasProximas(
        a: CaixaNormalizada,
        b: CaixaNormalizada,
        tolerancia: Double
    ): Boolean {
        return abs(a.esquerda - b.esquerda) <= tolerancia &&
               abs(a.topo - b.topo) <= tolerancia &&
               abs(a.direita - b.direita) <= tolerancia &&
               abs(a.base - b.base) <= tolerancia
    }

    /**
     * Decide se o conjunto de caixas está estável entre dois quadros.
     *
     * Exige que a QUANTIDADE de caixas seja igual e que CADA caixa esteja
     * dentro da tolerância. Comparar caixa por caixa, e não apenas a caixa
     * que engloba todas, é essencial: a caixa-união pode ficar estável
     * enquanto as caixas internas se deslocam — por exemplo quando a câmera
     * gira levemente sobre o próprio eixo, mantendo a área coberta mas
     * mudando a posição de cada linha de texto.
     *
     * As listas são ordenadas internamente, então a ordem de entrada não
     * importa.
     */
    fun caixasEstaveis(
        anteriores: List<CaixaNormalizada>,
        novas: List<CaixaNormalizada>,
        tolerancia: Double = TOLERANCIA_CAIXA_PADRAO
    ): Boolean {
        if (anteriores.isEmpty() || novas.isEmpty()) return false
        if (anteriores.size != novas.size) return false

        val ordenadasAnteriores = ordenar(anteriores)
        val ordenadasNovas = ordenar(novas)

        for (i in ordenadasAnteriores.indices) {
            if (!caixasProximas(ordenadasAnteriores[i], ordenadasNovas[i], tolerancia)) {
                return false
            }
        }

        return true
    }

    /**
     * Deslocamento máximo observado entre os dois conjuntos de caixas,
     * em fração da imagem. Útil para diagnóstico: mostra o quanto a câmera
     * se moveu, mesmo quando o resultado é considerado estável.
     *
     * Retorna null quando os conjuntos não são comparáveis.
     */
    fun deslocamentoMaximo(
        anteriores: List<CaixaNormalizada>,
        novas: List<CaixaNormalizada>
    ): Double? {
        if (anteriores.isEmpty() || novas.isEmpty()) return null
        if (anteriores.size != novas.size) return null

        val a = ordenar(anteriores)
        val b = ordenar(novas)
        var maior = 0.0

        for (i in a.indices) {
            maior = maxOf(
                maior,
                abs(a[i].esquerda - b[i].esquerda),
                abs(a[i].topo - b[i].topo),
                abs(a[i].direita - b[i].direita),
                abs(a[i].base - b[i].base)
            )
        }

        return maior
    }
}

/**
 * Resultado de uma avaliação de estabilidade.
 */
data class ResultadoEstabilidade(
    val estavel: Boolean,
    val motivo: String,
    val similaridadeTexto: Double,
    val deslocamentoCaixas: Double?,
    val amostrasConsecutivas: Int,
    val qtdCaixas: Int
) {
    /** Resumo legível, para exibir no modo desenvolvedor. */
    fun resumo(): String {
        val sim = "%.1f%%".format(similaridadeTexto)
        val desl = deslocamentoCaixas?.let { "%.3f".format(it) } ?: "—"
        return "$motivo | sim: $sim | desloc: $desl | caixas: $qtdCaixas | seq: $amostrasConsecutivas"
    }
}

/**
 * Avaliador com estado: recebe amostras sucessivas e informa quando a cena
 * está estável o suficiente para valer uma captura em alta resolução.
 *
 * Uso previsto:
 *   val avaliador = AvaliadorEstabilidade()
 *   repeat(maxTentativas) {
 *       val r = OcrService.reconhecerComCaixas(quadroBarato)
 *       val veredito = avaliador.avaliar(r.texto, r.caixas)
 *       if (veredito.estavel) { capturarEmAltaResolucao(); return }
 *   }
 *
 * @param limiarSimilaridade similaridade mínima entre textos (0-100)
 * @param toleranciaCaixa deslocamento tolerado das caixas (fração da imagem)
 * @param amostrasNecessarias quantas amostras consecutivas estáveis são
 *        exigidas. Duas é o mínimo útil; três é mais conservador e reduz
 *        falsos positivos ao custo de mais uma sondagem.
 */
class AvaliadorEstabilidade(
    private val limiarSimilaridade: Int = Estabilidade.LIMIAR_SIMILARIDADE_PADRAO,
    private val toleranciaCaixa: Double = Estabilidade.TOLERANCIA_CAIXA_PADRAO,
    private val amostrasNecessarias: Int = 2,
    // Quando a resolução de sondagem é baixa, o texto reconhecido vira ruído
    // aleatório e nunca se repete entre quadros — o portão jamais fecharia.
    // Nesses casos, desligar o sinal textual deixa a decisão apenas com a
    // geometria das caixas, que tolera resolução menor.
    private val exigirTexto: Boolean = true
) {
    private var textoAnterior: String? = null
    private var caixasAnteriores: List<CaixaNormalizada>? = null
    private var consecutivas = 0

    /** Descarta o histórico. Chamar antes de cada nova tentativa de leitura. */
    fun reiniciar() {
        textoAnterior = null
        caixasAnteriores = null
        consecutivas = 0
    }

    /**
     * Avalia uma nova amostra contra a anterior.
     */
    fun avaliar(texto: String, caixas: List<CaixaNormalizada>): ResultadoEstabilidade {
        // Nada detectado: não há como avaliar, e o histórico perde sentido
        if (caixas.isEmpty() || Estabilidade.normalizar(texto).isEmpty()) {
            textoAnterior = null
            caixasAnteriores = null
            consecutivas = 0
            return ResultadoEstabilidade(
                estavel = false,
                motivo = "nenhum texto detectado",
                similaridadeTexto = 0.0,
                deslocamentoCaixas = null,
                amostrasConsecutivas = 0,
                qtdCaixas = 0
            )
        }

        val anteriorTexto = textoAnterior
        val anteriorCaixas = caixasAnteriores

        // Primeira amostra: só guarda a referência
        if (anteriorTexto == null || anteriorCaixas == null) {
            textoAnterior = texto
            caixasAnteriores = caixas
            consecutivas = 1
            return ResultadoEstabilidade(
                estavel = false,
                motivo = "primeira amostra",
                similaridadeTexto = 0.0,
                deslocamentoCaixas = null,
                amostrasConsecutivas = 1,
                qtdCaixas = caixas.size
            )
        }

        val similaridade = Estabilidade.similaridade(anteriorTexto, texto)
        val deslocamento = Estabilidade.deslocamentoMaximo(anteriorCaixas, caixas)

        val geometricoOk = Estabilidade.caixasEstaveis(
            anteriorCaixas, caixas, toleranciaCaixa
        )
        val textualOk = if (exigirTexto) {
            Estabilidade.textosEstaveis(anteriorTexto, texto, limiarSimilaridade)
        } else {
            true
        }

        // A amostra atual sempre se torna a referência da próxima
        textoAnterior = texto
        caixasAnteriores = caixas

        if (geometricoOk && textualOk) {
            consecutivas++
            val estavel = consecutivas >= amostrasNecessarias
            return ResultadoEstabilidade(
                estavel = estavel,
                motivo = if (estavel) "estável" else "estabilizando",
                similaridadeTexto = similaridade,
                deslocamentoCaixas = deslocamento,
                amostrasConsecutivas = consecutivas,
                qtdCaixas = caixas.size
            )
        }

        // Instável: a contagem volta a 1, porque esta amostra passa a ser
        // a nova referência
        consecutivas = 1

        val motivo = when {
            !geometricoOk && !textualOk -> "câmera em movimento"
            !geometricoOk -> "caixas deslocaram"
            else -> "texto variou"
        }

        return ResultadoEstabilidade(
            estavel = false,
            motivo = motivo,
            similaridadeTexto = similaridade,
            deslocamentoCaixas = deslocamento,
            amostrasConsecutivas = 1,
            qtdCaixas = caixas.size
        )
    }
}
