package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do MedidorAcerto: a métrica de acerto usada na coleta de dados.
 */
class MedidorAcertoTest {

    private val referencia =
        "A manhã chegou devagar sobre a cidade adormecida, e o zelador percebeu " +
        "que a chuva da noite havia deixado poças rasas no pátio de pedra."

    @Test
    fun leituraPerfeita_100PorCento() {
        val r = MedidorAcerto.medir(referencia, listOf(referencia), referencia)
        assertEquals(100.0, r.acertoTextoInteiro, 0.001)
        assertEquals(1, r.blocos.size)
        assertEquals(100.0, r.blocos[0].acerto, 0.001)
    }

    @Test
    fun tituloCurtoEIgnorado() {
        val titulo = "BLOCO 4 — CORPO 10 pt"
        val r = MedidorAcerto.medir("$titulo\n$referencia", listOf(titulo, referencia), referencia)
        assertEquals(1, r.blocosIgnorados)
        assertEquals(1, r.blocos.size)
        assertEquals(2, r.blocos[0].indice)   // mantém a posição original na lista
    }

    @Test
    fun cadaBlocoTemSeuProprioAcerto() {
        val comErros = referencia.replace("manhã", "manha").replace("pátio", "patio")
        val r = MedidorAcerto.medir("", listOf(referencia, comErros), referencia)
        assertEquals(100.0, r.blocos[0].acerto, 0.001)
        assertTrue(r.blocos[1].acerto < 100.0 && r.blocos[1].acerto > 95.0)
    }

    @Test
    fun inicioIdentificaOBloco() {
        val r = MedidorAcerto.medir(referencia, listOf(referencia), referencia)
        assertEquals("a manhã chegou devagar", r.blocos[0].inicio)
    }

    // --- Acerto por corpo (página de teste v2) ---

    @Test
    fun porCorpo_tituloEmBlocoSeparado() {
        val blocos = listOf(
            "EYE-CAM — PÁGINA DE TESTE PADRONIZADA · AMOSTRA A",
            "BLOCO 4 — CORPO 10 pt", referencia,
            "BLOCO 5 — CORPO 9 pt", referencia.replace("manhã", "manha")
        )
        val r = MedidorAcerto.medirPorCorpo(blocos, referencia)
        assertEquals(listOf(10, 9), r.map { it.corpo })
        assertEquals(100.0, r[0].acerto, 0.001)
        assertTrue(r[1].acerto < 100.0)
    }

    @Test
    fun porCorpo_tituloNoMesmoBlocoDoParagrafo() {
        val r = MedidorAcerto.medirPorCorpo(listOf("BLOCO 1 — CORPO 14 pt\n$referencia"), referencia)
        assertEquals(1, r.size)
        assertEquals(14, r[0].corpo)
        assertEquals(100.0, r[0].acerto, 0.001)
        assertEquals(1.0, r[0].fracaoDoTexto, 0.001)
    }

    @Test
    fun porCorpo_paragrafoPartidoEmDoisBlocosEhJuntado() {
        val metade = referencia.length / 2
        val corte = referencia.lastIndexOf(' ', metade)
        val blocos = listOf("BLOCO 4 — CORPO 10 pt", referencia.substring(0, corte), referencia.substring(corte + 1))
        val r = MedidorAcerto.medirPorCorpo(blocos, referencia)
        assertEquals(100.0, r.single().acerto, 0.001)
    }

    @Test
    fun porCorpo_numeroDoCorpoIlegivelUsaONumeroDoBloco() {
        // OCR leu "CORP0" com zero: vale o "BLOCO 3" = 11 pt
        val r = MedidorAcerto.medirPorCorpo(listOf("BLOCO 3 — CORP0 11 pt", referencia), referencia)
        assertEquals(11, r.single().corpo)
    }

    @Test
    fun porCorpo_rodapeNaoEntraNoUltimoParagrafo() {
        val blocos = listOf(
            "BLOCO 5 — CORPO 9 pt", referencia,
            "O texto e identico nos cinco blocos: a unica variavel e o corpo da fonte (serifada, entrelinha 1,3)."
        )
        val r = MedidorAcerto.medirPorCorpo(blocos, referencia)
        assertEquals(100.0, r.single().acerto, 0.001)
    }

    @Test
    fun porCorpo_paragrafoCortadoTemFracaoBaixa() {
        val r = MedidorAcerto.medirPorCorpo(
            listOf("BLOCO 4 — CORPO 10 pt", referencia.substring(0, referencia.length / 3)),
            referencia
        )
        assertTrue(r.single().fracaoDoTexto < 0.5)
    }

    @Test
    fun porCorpo_semTitulosNaoDevolveNada() {
        assertTrue(MedidorAcerto.medirPorCorpo(listOf(referencia), referencia).isEmpty())
    }
}
