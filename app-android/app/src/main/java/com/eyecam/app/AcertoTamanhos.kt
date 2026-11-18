package com.eyecam.app

/**
 * Acerto por tamanho de letra na página de tamanhos
 * (docs/testes/pagina_teste_tamanhos.pdf): uma frase diferente para cada corpo, de 32 a 10
 * pt.
 */
object AcertoTamanhos {

    /** Acerto mínimo para considerar um tamanho "lido" */
    const val LIMIAR_LIDO = 80.0

    data class Frase(val corpo: Int, val texto: String)

    data class AcertoTamanho(val corpo: Int, val acerto: Double)

    /** Lê o arquivo "corpo<TAB>frase", uma por linha */
    fun lerFrases(conteudo: String): List<Frase> =
        conteudo.lines().mapNotNull { linha ->
            val partes = linha.split('\t', limit = 2)
            val corpo = partes.getOrNull(0)?.trim()?.toIntOrNull()
            val texto = partes.getOrNull(1)?.trim()
            if (corpo != null && !texto.isNullOrEmpty()) Frase(corpo, texto) else null
        }

    fun medir(textoLido: String, frases: List<Frase>): List<AcertoTamanho> {
        val lido = Estabilidade.normalizar(textoLido.replace('\n', ' '))
        return frases.map { AcertoTamanho(it.corpo, melhorTrecho(Estabilidade.normalizar(it.texto), lido)) }
    }

    /** Menor corpo lido com pelo menos LIMIAR_LIDO, ou null se nenhum */
    fun menorCorpoLido(acertos: List<AcertoTamanho>, limiar: Double = LIMIAR_LIDO): Int? =
        acertos.filter { it.acerto >= limiar }.minOfOrNull { it.corpo }

    private fun melhorTrecho(frase: String, lido: String): Double {
        if (frase.isEmpty() || lido.isEmpty()) return 0.0
        val n = frase.length
        var melhor = 0.0
        val tamanhos = listOf(n - n / 5, n, n + n / 5).filter { it > 0 }.distinct()
        var inicio = 0
        val ultimoInicio = maxOf(0, lido.length - n / 2)
        while (inicio <= ultimoInicio) {
            for (t in tamanhos) {
                val fim = minOf(lido.length, inicio + t)
                if (fim <= inicio) continue
                val trecho = lido.substring(inicio, fim)
                val d = Estabilidade.distanciaLevenshtein(frase, trecho)
                val s = 100.0 * (1.0 - d.toDouble() / maxOf(frase.length, trecho.length))
                if (s > melhor) melhor = s
            }
            if (melhor >= 100.0) break
            inicio += PASSO
        }
        return melhor
    }

    private const val PASSO = 2
}
