package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes unitários de Estabilidade.kt. */
class EstabilidadeTest {

    // ---------------------------------------------------------------- normalizar

    @Test
    fun normalizar_descartaPontuacaoEMinusculas() {
        assertEquals("prefácio", Estabilidade.normalizar("PREFÁCIo."))
    }

    @Test
    fun normalizar_juntaPalavraHifenizada() {
        // Pontuação some sem gerar espaço: "co-lheita" == "colheita"
        assertEquals("colheita", Estabilidade.normalizar("co-lheita"))
    }

    @Test
    fun normalizar_colapsaEspacosEQuebrasDeLinha() {
        assertEquals("a manhã chegou", Estabilidade.normalizar("  A   manhã\n\tchegou  "))
    }

    @Test
    fun normalizar_preservaAcentos() {
        assertEquals("ônibus térreo", Estabilidade.normalizar("Ônibus, térreo!"))
    }

    // ---------------------------------------------------------------- Levenshtein

    @Test
    fun levenshtein_exemploClassico() {
        assertEquals(3, Estabilidade.distanciaLevenshtein("kitten", "sitting"))
    }

    @Test
    fun levenshtein_simetrica() {
        assertEquals(
            Estabilidade.distanciaLevenshtein("casa", "asas"),
            Estabilidade.distanciaLevenshtein("asas", "casa")
        )
    }

    @Test
    fun levenshtein_casosVazios() {
        assertEquals(0, Estabilidade.distanciaLevenshtein("", ""))
        assertEquals(4, Estabilidade.distanciaLevenshtein("", "abcd"))
        assertEquals(4, Estabilidade.distanciaLevenshtein("abcd", ""))
    }

    // ---------------------------------------------------------------- similaridade

    @Test
    fun similaridade_identicos100() {
        assertEquals(100.0, Estabilidade.similaridade("Dona Efigênia", "dona efigênia!"), 0.001)
    }

    @Test
    fun similaridade_umVazio0() {
        assertEquals(0.0, Estabilidade.similaridade("texto", ""), 0.001)
    }

    @Test
    fun similaridade_umErroEmDezCaracteres() {
        // "abcdefghij" x "abcdefghiX": 1 substituição em 10 -> 90%
        assertEquals(90.0, Estabilidade.similaridade("abcdefghij", "abcdefghix"), 0.001)
    }

    @Test
    fun similaridade_acentoErradoContaComoErro() {
        // "manhã" x "manha": 1 erro em 5 -> 80%
        assertEquals(80.0, Estabilidade.similaridade("manhã", "manha"), 0.001)
    }

    // ---------------------------------------------------------------- textosEstaveis

    @Test
    fun textosEstaveis_vazioNuncaEstavel() {
        assertFalse(Estabilidade.textosEstaveis("", ""))
        assertFalse(Estabilidade.textosEstaveis("...", "---"))
    }

    @Test
    fun textosEstaveis_comprimentoMuitoDiferenteRejeita() {
        assertFalse(Estabilidade.textosEstaveis("a manhã chegou", "a manhã chegou devagar sobre a cidade"))
    }

    @Test
    fun textosEstaveis_pequenaVariacaoAceita() {
        val a = "A manhã chegou devagar sobre a cidade adormecida"
        val b = "A manhã chegou devagar sobre a cidade adormecída"  // 1 erro
        assertTrue(Estabilidade.textosEstaveis(a, b, limiar = 90))
    }

    // ---------------------------------------------------------------- caixas

    private fun caixa(e: Double, t: Double, d: Double, b: Double) = CaixaNormalizada(e, t, d, b)

    @Test
    fun caixasEstaveis_ordemNaoImporta() {
        val c1 = caixa(0.1, 0.1, 0.9, 0.2)
        val c2 = caixa(0.1, 0.3, 0.9, 0.5)
        assertTrue(Estabilidade.caixasEstaveis(listOf(c1, c2), listOf(c2, c1), 0.02))
    }

    @Test
    fun caixasEstaveis_deslocamentoAcimaDaTolerancia() {
        val antes = listOf(caixa(0.10, 0.10, 0.90, 0.20))
        val depois = listOf(caixa(0.15, 0.10, 0.95, 0.20))   // moveu 5%
        assertFalse(Estabilidade.caixasEstaveis(antes, depois, 0.02))
        assertEquals(0.05, Estabilidade.deslocamentoMaximo(antes, depois)!!, 1e-9)
    }

    @Test
    fun caixasEstaveis_quantidadeDiferente() {
        val c = caixa(0.1, 0.1, 0.9, 0.2)
        assertFalse(Estabilidade.caixasEstaveis(listOf(c), listOf(c, c), 0.02))
        assertNull(Estabilidade.deslocamentoMaximo(listOf(c), listOf(c, c)))
    }

    // ---------------------------------------------------------------- avaliador

    private val caixasParadas = listOf(caixa(0.1, 0.1, 0.9, 0.4), caixa(0.1, 0.5, 0.9, 0.8))
    private val texto = "A manhã chegou devagar sobre a cidade adormecida"

    @Test
    fun avaliador_fechaNaSegundaAmostraIgual() {
        val av = AvaliadorEstabilidade(amostrasNecessarias = 2)
        val r1 = av.avaliar(texto, caixasParadas)
        assertFalse(r1.estavel)
        assertEquals("primeira amostra", r1.motivo)

        val r2 = av.avaliar(texto, caixasParadas)
        assertTrue(r2.estavel)
        assertEquals("estável", r2.motivo)
    }

    @Test
    fun avaliador_detectaCaixasDeslocadas() {
        val av = AvaliadorEstabilidade()
        av.avaliar(texto, caixasParadas)
        val movidas = caixasParadas.map { it.copy(esquerda = it.esquerda + 0.1, direita = it.direita + 0.1) }
        val r = av.avaliar(texto, movidas)
        assertFalse(r.estavel)
        assertEquals("caixas deslocaram", r.motivo)
    }

    @Test
    fun avaliador_detectaTextoVariando() {
        val av = AvaliadorEstabilidade()
        av.avaliar(texto, caixasParadas)
        val r = av.avaliar("xyz qwe rty uio pas dfg hjk lzx cvb nmq wer tyu", caixasParadas)
        assertFalse(r.estavel)
        assertEquals("texto variou", r.motivo)
    }

    @Test
    fun avaliador_semExigirTextoUsaSoGeometria() {
        val av = AvaliadorEstabilidade(exigirTexto = false)
        av.avaliar(texto, caixasParadas)
        val r = av.avaliar("xyz qwe rty uio pas dfg hjk lzx cvb nmq wer tyu", caixasParadas)
        assertTrue(r.estavel)
    }

    @Test
    fun avaliador_semTextoReiniciaHistorico() {
        val av = AvaliadorEstabilidade()
        av.avaliar(texto, caixasParadas)
        val vazio = av.avaliar("", emptyList())
        assertEquals("nenhum texto detectado", vazio.motivo)
        // Depois de um quadro vazio, a próxima amostra volta a ser a primeira
        assertEquals("primeira amostra", av.avaliar(texto, caixasParadas).motivo)
    }
}
