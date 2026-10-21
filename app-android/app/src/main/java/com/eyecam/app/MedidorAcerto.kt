package com.eyecam.app

/**
 * Acerto de um bloco reconhecido pelo OCR.
 *
 * @param indice posição do bloco na lista do ML Kit (começando em 1)
 * @param caracteres tamanho do bloco depois de normalizado
 * @param acerto similaridade com o texto de referência, de 0 a 100
 * @param inicio primeiras palavras do bloco, para identificá-lo na tela
 */
data class AcertoBloco(
    val indice: Int,
    val caracteres: Int,
    val acerto: Double,
    val inicio: String
)

/**
 * Resultado da medição de acerto de uma leitura.
 *
 * @param acertoTextoInteiro acerto de todo o texto reconhecido. Só tem
 *        sentido quando há um único parágrafo da página de teste no quadro.
 * @param blocos acerto de cada bloco com tamanho de parágrafo
 * @param blocosIgnorados blocos curtos demais (títulos como "BLOCO 1 —
 *        CORPO 14 pt", ou ruído), deixados de fora da lista
 */
data class ResultadoAcerto(
    val acertoTextoInteiro: Double,
    val blocos: List<AcertoBloco>,
    val blocosIgnorados: Int
)

/**
 * Acerto de um corpo de letra da página de teste.
 *
 * @param corpo tamanho da fonte em pontos (14, 12, 11, 10 ou 9)
 * @param caracteres tamanho do texto atribuído a esse corpo, normalizado
 * @param fracaoDoTexto caracteres ÷ tamanho da referência: perto de 1 quando o
 *        parágrafo inteiro está na foto; bem abaixo de 1 quando ele foi
 *        cortado pela borda (o acerto baixo, então, não é erro de leitura)
 */
data class AcertoCorpo(
    val corpo: Int,
    val acerto: Double,
    val caracteres: Int,
    val fracaoDoTexto: Double
)

/**
 * Mede a taxa de acerto do OCR contra o texto de referência da página de
 * teste (docs/testes/pagina_teste_texto_referencia.txt).
 *
 * Usa a mesma métrica do relatório: Estabilidade.similaridade(), ou seja,
 * 1 − (distância de Levenshtein / maior comprimento), calculada sobre o
 * texto NORMALIZADO (sem pontuação, sem diferença entre maiúsculas e
 * minúsculas, acentos preservados).
 *
 * Por que bloco a bloco: a página de teste repete o mesmo parágrafo em 5
 * tamanhos de fonte. Comparar a página inteira com UM parágrafo de
 * referência daria um número sem sentido. O ML Kit costuma devolver cada
 * parágrafo como um bloco, então o acerto por bloco corresponde ao acerto
 * por tamanho de fonte. Se o ML Kit partir um parágrafo em dois blocos, os
 * dois aparecem com acerto baixo e poucos caracteres — sinal para cobrir os
 * outros parágrafos e usar o acerto do texto inteiro.
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

    // Títulos da página v2: "BLOCO n — CORPO X pt". Procurados no texto
    // normalizado (sem pontuação e em minúsculas), como "bloco 4 corpo 10 pt"
    private val TITULO_CORPO = Regex("""\bcorpo\s*(14|12|11|10|9)\b""")
    private val TITULO_BLOCO = Regex("""\bbloco\s*([1-5])\b""")
    private val CORPO_DO_BLOCO = mapOf(1 to 14, 2 to 12, 3 to 11, 4 to 10, 5 to 9)

    // Rodapé da página v2, logo depois do bloco 5: não faz parte do parágrafo.
    // Aceita a leitura sem acento, que o OCR às vezes faz
    private val RODAPE = Regex("""id[êe]ntico nos|cinco blocos|[úu]nica vari[áa]vel""")

    /**
     * Acerto de cada corpo de letra, para a página de teste v2.
     *
     * O ML Kit devolve os blocos de cima para baixo, e cada parágrafo vem
     * logo depois do seu título ("BLOCO 4 — CORPO 10 pt"), às vezes no mesmo
     * bloco que ele, às vezes num bloco à parte. Por isso o texto é lido linha
     * a linha: um título define o corpo atual, e as linhas seguintes, até o
     * próximo título, formam o parágrafo daquele corpo. Um parágrafo que o ML
     * Kit partiu em dois blocos é juntado de novo.
     *
     * O corpo vem do número após "CORPO"; se o OCR errou essa parte do
     * título, do número do bloco.
     *
     * @return um item por corpo encontrado, do maior para o menor; vazio se a
     *         foto não tiver nenhum título legível
     */
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

    /** Corpo indicado por um título, ou null se a linha não for um título. */
    private fun corpoDoTitulo(normalizada: String): Int? {
        // O título é curto; uma linha longa que cite "corpo" é texto comum
        if (normalizada.length > 40) return null
        TITULO_CORPO.find(normalizada)?.let { return it.groupValues[1].toInt() }
        if (!normalizada.startsWith("bloco")) return null
        return TITULO_BLOCO.find(normalizada)?.groupValues?.get(1)?.toInt()?.let { CORPO_DO_BLOCO[it] }
    }
}
