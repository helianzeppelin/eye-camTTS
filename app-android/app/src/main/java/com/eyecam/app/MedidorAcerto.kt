package com.eyecam.app

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** Acerto de um bloco reconhecido pelo OCR. */
data class AcertoBloco(
    val indice: Int,
    val caracteres: Int,
    val acerto: Double,
    val inicio: String
)

/**
 * Resultado da medição de acerto de uma leitura. sentido quando há um único parágrafo da
 * página de teste no quadro.
 */
data class ResultadoAcerto(
    val acertoTextoInteiro: Double,
    val blocos: List<AcertoBloco>,
    val blocosIgnorados: Int
)

/**
 * Acerto de um corpo de letra da página de teste. parágrafo inteiro está na foto; bem
 * abaixo de 1 quando ele foi cortado pela borda (o acerto baixo, então, não é erro de
 * leitura) foi deduzido por ela (medirPorAltura); null quando veio do título
 */
data class AcertoCorpo(
    val corpo: Int,
    val acerto: Double,
    val caracteres: Int,
    val fracaoDoTexto: Double,
    val alturaLinhaPx: Double? = null
) {
    /** true quando o corpo foi deduzido pela altura das linhas, sem título */
    val pelaAltura: Boolean get() = alturaLinhaPx != null
}

/**
 * Uma linha reconhecida pelo OCR, com a altura medida na imagem. alinhada aos eixos, que
 * cresce quando a página está inclinada) na ordem da página
 */
data class LinhaOcr(
    val texto: String,
    val alturaPx: Double?,
    val centroY: Double
)

/**
 * Mede a taxa de acerto do OCR contra o texto de referência da página de teste
 * (docs/testes/pagina_teste_texto_referencia.txt).
 */
object MedidorAcerto {

    // Blocos menores que isto (após normalizar) não são um parágrafo
    private const val MINIMO_CARACTERES_BLOCO = 40

    // Palavras mostradas para identificar cada bloco
    private const val PALAVRAS_INICIO = 4

    fun medir(
        textoInteiro: String,
        textosBlocos: List<String>,
        referencia: String
    ): ResultadoAcerto {
        val blocos = mutableListOf<AcertoBloco>()
        var ignorados = 0

        textosBlocos.forEachIndexed { i, texto ->
            val normalizado = Estabilidade.normalizar(texto)
            if (normalizado.length < MINIMO_CARACTERES_BLOCO) {
                ignorados++
            } else {
                blocos += AcertoBloco(
                    indice = i + 1,
                    caracteres = normalizado.length,
                    acerto = Estabilidade.similaridade(texto, referencia),
                    inicio = normalizado.split(' ').take(PALAVRAS_INICIO).joinToString(" ")
                )
            }
        }

        return ResultadoAcerto(
            acertoTextoInteiro = Estabilidade.similaridade(textoInteiro, referencia),
            blocos = blocos,
            blocosIgnorados = ignorados
        )
    }

    // Títulos da página v2: "BLOCO n — CORPO X pt".
    private val TITULO_CORPO = Regex("""\bcorpo\s*(14|12|11|10|9)\b""")
    private val TITULO_BLOCO = Regex("""\bbloco\s*([1-5])\b""")
    private val CORPO_DO_BLOCO = mapOf(1 to 14, 2 to 12, 3 to 11, 4 to 10, 5 to 9)

    // Rodapé da página v2, logo depois do bloco 5: não faz parte do parágrafo.
    private val RODAPE = Regex("""id[êe]ntico nos|cinco blocos|[úu]nica vari[áa]vel""")

    /** Acerto de cada corpo de letra, para a página de teste v2. */
    fun medirPorCorpo(textosBlocos: List<String>, referencia: String): List<AcertoCorpo> {
        val paragrafos = linkedMapOf<Int, StringBuilder>()
        var corpoAtual: Int? = null

        for (linha in textosBlocos.flatMap { it.lines() }) {
            val normalizada = Estabilidade.normalizar(linha)
            if (normalizada.isEmpty()) continue

            val corpoTitulo = corpoDoTitulo(normalizada)
            if (corpoTitulo != null) {
                corpoAtual = corpoTitulo
                paragrafos.getOrPut(corpoTitulo) { StringBuilder() }
                continue
            }
            if (RODAPE.containsMatchIn(normalizada)) {
                corpoAtual = null
                continue
            }
            corpoAtual?.let { paragrafos.getValue(it).append(linha).append(' ') }
        }

        val tamanhoReferencia = Estabilidade.normalizar(referencia).length.coerceAtLeast(1)
        return paragrafos
            .filterValues { Estabilidade.normalizar(it.toString()).isNotEmpty() }
            .map { (corpo, texto) ->
                val caracteres = Estabilidade.normalizar(texto.toString()).length
                AcertoCorpo(
                    corpo = corpo,
                    acerto = Estabilidade.similaridade(texto.toString(), referencia),
                    caracteres = caracteres,
                    fracaoDoTexto = caracteres.toDouble() / tamanhoReferencia
                )
            }
            .sortedByDescending { it.corpo }
    }

    // --- Corpo pela altura das linhas ---------------------------------------

    // Altura da caixa de uma linha ≈ 0,94 em (medido na câmera virtual); 1 pt = 25,4/72 mm.
    private const val ALTURA_LINHA_POR_EM = 0.94
    private const val MM_POR_PONTO = 25.4 / 72
    private val CORPOS_DA_PAGINA = listOf(14, 12, 11, 10, 9)

    // Linhas mais curtas que isto (títulos, pedaços) não entram na conta
    private const val MINIMO_CARACTERES_LINHA = 15
    // Linhas seguidas são do mesmo parágrafo se a altura difere menos que ~6%
    private const val TOLERANCIA_PARAGRAFO = 0.06
    // Incertezas (em logaritmo): da altura de uma linha e da escala esperada (distância e
    // calibração da câmera)
    private const val DESVIO_ALTURA_LINHA = 0.05
    private const val DESVIO_ESCALA = 0.10
    // Parágrafo cuja altura não bate com nenhum corpo (mais de ~8% fora) fica de fora:
    // provavelmente não é texto do parágrafo
    private const val RESIDUO_MAXIMO = 0.08
    private const val MAXIMO_PARAGRAFOS = 12

    /**
     * Acerto de cada corpo de letra quando os títulos não foram lidos: o corpo de cada
     * parágrafo é deduzido pela altura das linhas.
     */
    fun medirPorAltura(linhas: List<LinhaOcr>, referencia: String, pxPorMm: Double): List<AcertoCorpo> {
        if (pxPorMm <= 0.0) return emptyList()
        val uteis = linhas.filter { l ->
            val n = Estabilidade.normalizar(l.texto)
            val altura = l.alturaPx
            altura != null && altura > 0.0 && n.length >= MINIMO_CARACTERES_LINHA &&
                corpoDoTitulo(n) == null && !RODAPE.containsMatchIn(n)
        }.sortedBy { it.centroY }
        if (uteis.isEmpty()) return emptyList()

        // 1. Parágrafos
        val paragrafos = mutableListOf<MutableList<LinhaOcr>>()
        for (linha in uteis) {
            val atual = paragrafos.lastOrNull()
            if (atual != null && abs(ln(linha.alturaPx!!) - ln(medianaAltura(atual))) < TOLERANCIA_PARAGRAFO) {
                atual += linha
            } else {
                paragrafos += mutableListOf(linha)
            }
        }

        // Mais grupos que isto = alturas sem padrão (texto ilegível ou foto muito
        // inclinada): a dedução não seria confiável
        if (paragrafos.size > MAXIMO_PARAGRAFOS) return emptyList()

        // 2. Corpos: a melhor sequência não crescente de corpos
        val lnAltura = paragrafos.map { ln(medianaAltura(it)) }
        // Peso de cada parágrafo: a mediana de mais linhas é mais confiável
        val pesos = paragrafos.map { min(it.size, 6) / DESVIO_ALTURA_LINHA.pow(2) }
        val pesoEscala = 1.0 / DESVIO_ESCALA.pow(2)
        val lnEscalaEsperada = ln(pxPorMm * MM_POR_PONTO * ALTURA_LINHA_POR_EM)

        var melhor: List<Int>? = null
        var melhorCusto = Double.MAX_VALUE
        var melhorLnEscala = lnEscalaEsperada
        for (indices in sequenciasNaoDecrescentes(paragrafos.size, CORPOS_DA_PAGINA.size)) {
            val d = lnAltura.indices.map { lnAltura[it] - ln(CORPOS_DA_PAGINA[indices[it]].toDouble()) }
            val lnEscala = (d.indices.sumOf { pesos[it] * d[it] } + pesoEscala * lnEscalaEsperada) /
                (pesos.sum() + pesoEscala)
            val custo = d.indices.sumOf { pesos[it] * (d[it] - lnEscala).pow(2) } +
                pesoEscala * (lnEscala - lnEscalaEsperada).pow(2)
            if (custo < melhorCusto) {
                melhorCusto = custo
                melhor = indices
                melhorLnEscala = lnEscala
            }
        }
        val atribuicao = melhor ?: return emptyList()

        // 3. Texto de cada corpo
        val porCorpo = linkedMapOf<Int, MutableList<LinhaOcr>>()
        paragrafos.forEachIndexed { i, paragrafo ->
            val corpo = CORPOS_DA_PAGINA[atribuicao[i]]
            val residuo = lnAltura[i] - ln(corpo.toDouble()) - melhorLnEscala
            if (abs(residuo) <= RESIDUO_MAXIMO) porCorpo.getOrPut(corpo) { mutableListOf() } += paragrafo
        }
        val tamanhoReferencia = Estabilidade.normalizar(referencia).length.coerceAtLeast(1)
        return porCorpo.map { (corpo, linhasDoCorpo) ->
            val texto = linhasDoCorpo.joinToString(" ") { it.texto }
            val caracteres = Estabilidade.normalizar(texto).length
            AcertoCorpo(
                corpo = corpo,
                acerto = Estabilidade.similaridade(texto, referencia),
                caracteres = caracteres,
                fracaoDoTexto = caracteres.toDouble() / tamanhoReferencia,
                alturaLinhaPx = medianaAltura(linhasDoCorpo)
            )
        }.sortedByDescending { it.corpo }
    }

    private fun medianaAltura(linhas: List<LinhaOcr>): Double {
        val alturas = linhas.mapNotNull { it.alturaPx }.sorted()
        return alturas[alturas.size / 2]
    }

    /**
     * Todas as sequências não decrescentes de [n] índices entre 0 e [opcoes] − 1 (índice
     * maior = corpo menor).
     */
    private fun sequenciasNaoDecrescentes(n: Int, opcoes: Int): List<List<Int>> {
        val todas = mutableListOf<List<Int>>()
        val atual = IntArray(n)
        fun gera(posicao: Int, minimo: Int) {
            if (posicao == n) {
                todas += atual.toList()
                return
            }
            for (i in minimo until opcoes) {
                atual[posicao] = i
                gera(posicao + 1, i)
            }
        }
        gera(0, 0)
        return todas
    }

    /** Corpo indicado por um título, ou null se a linha não for um título. */
    private fun corpoDoTitulo(normalizada: String): Int? {
        // O título é curto; uma linha longa que cite "corpo" é texto comum
        if (normalizada.length > 40) return null
        TITULO_CORPO.find(normalizada)?.let { return it.groupValues[1].toInt() }
        if (!normalizada.startsWith("bloco")) return null
        return TITULO_BLOCO.find(normalizada)?.groupValues?.get(1)?.toInt()?.let { CORPO_DO_BLOCO[it] }
    }
}
