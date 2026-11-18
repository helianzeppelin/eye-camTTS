package com.eyecam.app

import java.time.LocalDateTime
import java.util.Locale

/** Texto de registro de um teste, em seções "[nome]" com linhas "chave: valor". */
class TextoRegistro {
    private val texto = StringBuilder()

    fun secao(nome: String) {
        if (texto.isNotEmpty()) texto.append('\n')
        texto.append('[').append(nome).append("]\n")
    }

    fun campo(chave: String, valor: Any?) {
        texto.append(chave).append(": ").append(valor?.toString()?.ifBlank { "—" } ?: "—").append('\n')
    }

    fun bloco(conteudo: String) {
        texto.append(conteudo.trimEnd()).append('\n')
    }

    override fun toString(): String = texto.toString()
}

/** Formatação dos registros do modo bancada: planilhas CSV, números, nomes de sessão. */
object FormatoTestes {

    private val BRASIL: Locale = Locale.forLanguageTag("pt-BR")

    /** Uma planilha por tipo de passo: cada uma tem colunas fixas */
    val COLUNAS_FOTOS = listOf(
        "teste", "plano", "resultado", "data_hora", "resolucao_pedida", "autofoco_final", "foco_resolucao",
        "largura", "altura", "bytes", "total_ms", "placa_ms", "ajuste_ms", "descarte_ms",
        "quadro_ms", "quadro_tentativas", "recuperacao", "fb_ovf", "avisos_camera", "captura_n",
        "xclk_mhz", "noturno", "temperatura_c",
        "foco_travou", "foco_ms", "foco_tentativas", "foco_zonas", "foco_total_app_ms", "nitidez_centro",
        "ocr_sem_ms", "ocr_total_ms", "fator_ampliacao", "altura_linha_px",
        "acerto_inteiro_sem", "acerto_inteiro_com", "corpo_por",
        "sem_14", "sem_12", "sem_11", "sem_10", "sem_9",
        "com_14", "com_12", "com_11", "com_10", "com_9",
        "fracao_10", "fracao_9", "palavra_esperada", "palavra_lida", "erro"
    )

    val COLUNAS_SEQUENCIAS = listOf(
        "teste", "repeticao", "resultado", "data_hora", "resolucao", "autofoco",
        "largura", "altura", "bytes", "total_ms", "placa_ms", "ajuste_ms", "descarte_ms",
        "quadro_ms", "quadro_tentativas", "recuperacao", "fb_ovf", "avisos_camera", "captura_n",
        "xclk_mhz", "noturno", "temperatura_c",
        "foco_travou", "foco_ms", "foco_tentativas", "foco_zonas", "erro"
    )

    val COLUNAS_CONEXOES = listOf("teste", "repeticao", "resultado", "data_hora", "tempo_ms", "forma")

    /** Página de tamanhos: acerto (%) de cada frase, de 32 a 10 pt */
    val COLUNAS_TAMANHOS = listOf(
        "teste", "data_hora", "resolucao_pedida", "distancia_cm",
        "t32", "t24", "t20", "t16", "t14", "t12", "t10",
        "menor_corpo_lido", "fator_ampliacao", "erro"
    )

    val COLUNAS_LEITURAS = listOf(
        "teste", "repeticao", "resultado", "data_hora", "resolucao_pedida", "largura", "altura",
        "tempo_total_ms", "caracteres", "fator_ampliacao", "observacao"
    )

    /** Corpos da página de teste, do maior para o menor */
    val CORPOS = listOf(14, 12, 11, 10, 9)

    /** Número com vírgula decimal; vazio quando não há valor */
    fun decimal(valor: Double?, casas: Int = 1): String =
        valor?.let { String.format(BRASIL, "%.${casas}f", it) } ?: ""

    /** Segundos a partir de milissegundos, para a tela: 6234 → "6,2 s" */
    fun segundos(ms: Long?): String = ms?.let { decimal(it / 1000.0) + " s" } ?: "—"

    /** Nome da pasta da sessão: "2026-10-21_14h30" */
    fun nomeSessao(agora: LocalDateTime): String =
        String.format(
            Locale.ROOT, "%04d-%02d-%02d_%02dh%02d",
            agora.year, agora.monthValue, agora.dayOfMonth, agora.hour, agora.minute
        )

    /** Valor de uma célula, entre aspas quando precisa */
    fun celula(valor: String): String =
        if (valor.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + valor.replace("\"", "\"\"") + "\""
        } else {
            valor
        }

    /** Linha na ordem das colunas; coluna sem valor fica vazia */
    fun linha(colunas: List<String>, valores: Map<String, String>): String =
        colunas.joinToString(";") { celula(valores[it] ?: "") }

    /** Acrescenta linhas a uma planilha. */
    fun acrescentar(planilha: String, colunas: List<String>, linhas: List<Map<String, String>>): String {
        val existentes = planilha.lines().filter { it.isNotBlank() }
        val antigas = existentes.firstOrNull()?.let(::celulas)
        if (antigas != null && antigas != colunas) {
            val todas = colunas + antigas.filter { it !in colunas }
            val texto = StringBuilder(todas.joinToString(";")).append('\n')
            existentes.drop(1).forEach { l -> texto.append(linha(todas, antigas.zip(celulas(l)).toMap())).append('\n') }
            linhas.forEach { texto.append(linha(todas, it)).append('\n') }
            return texto.toString()
        }

        val texto = StringBuilder()
        if (planilha.isBlank()) {
            texto.append(colunas.joinToString(";")).append('\n')
        } else {
            texto.append(planilha)
            if (!planilha.endsWith("\n")) texto.append('\n')
        }
        linhas.forEach { texto.append(linha(colunas, it)).append('\n') }
        return texto.toString()
    }

    /** Remove as linhas de um teste (primeira coluna igual a [teste]), para refazê-lo. */
    fun removerTeste(planilha: String, teste: String): String {
        val linhas = planilha.lines()
        if (linhas.isEmpty()) return planilha
        val mantidas = listOf(linhas.first()) + linhas.drop(1).filter { linha ->
            linha.isNotBlank() && celulas(linha).first() != teste
        }
        return mantidas.joinToString("\n") + "\n"
    }

    /** Células de uma linha da planilha: o inverso de [linha]. */
    fun celulas(linha: String): List<String> {
        val resultado = mutableListOf<String>()
        val atual = StringBuilder()
        var entreAspas = false
        var i = 0
        while (i < linha.length) {
            val c = linha[i]
            when {
                entreAspas && c == '"' && i + 1 < linha.length && linha[i + 1] == '"' -> { atual.append('"'); i++ }
                c == '"' && (entreAspas || atual.isEmpty()) -> entreAspas = !entreAspas
                c == ';' && !entreAspas -> { resultado += atual.toString(); atual.clear() }
                else -> atual.append(c)
            }
            i++
        }
        resultado += atual.toString()
        return resultado
    }

    /** "14 pt 100% · 10 pt 95%" para a tela; null se não achou nenhum corpo. */
    fun resumoAcertos(porCorpo: List<AcertoCorpo>): String? =
        porCorpo.takeIf { it.isNotEmpty() }?.joinToString(" · ") { a ->
            val cortado = if (a.fracaoDoTexto < 0.8) " (cortado)" else ""
            "${a.corpo} pt ${decimal(a.acerto, 0)}%$cortado"
        }?.let { if (porCorpo.any { it.pelaAltura }) "$it (corpo pela altura das linhas)" else it }

    /** Coluna corpo_por: de onde veio o corpo de cada parágrafo */
    fun origemDosCorpos(porCorpo: List<AcertoCorpo>): String = when {
        porCorpo.isEmpty() -> ""
        porCorpo.any { it.pelaAltura } -> "altura"
        else -> "titulo"
    }

    /** Colunas "sem_10", "com_9"… de uma leitura */
    fun colunasDeAcerto(prefixo: String, porCorpo: List<AcertoCorpo>): Map<String, String> =
        CORPOS.associate { corpo -> "${prefixo}_$corpo" to decimal(porCorpo.firstOrNull { it.corpo == corpo }?.acerto) }

    /** Média, mínimo e máximo, para o resumo de uma sequência */
    fun estatisticas(valores: List<Long>): String {
        if (valores.isEmpty()) return "—"
        val media = valores.average()
        return "média ${segundos(media.toLong())}, de ${segundos(valores.min())} a ${segundos(valores.max())}"
    }
}
