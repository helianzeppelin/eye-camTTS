package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Testes da formatação dos registros do modo bancada (FormatoTestes,
 * TextoRegistro).
 */
class RegistroTestesTest {

    private val colunas = listOf("teste", "valor", "erro")

    @Test
    fun planilhaNovaGanhaCabecalho() {
        val csv = FormatoTestes.acrescentar("", colunas, listOf(mapOf("teste" to "B30-5MP-r1", "valor" to "97,5")))
        assertEquals("teste;valor;erro\nB30-5MP-r1;97,5;\n", csv)
    }

    @Test
    fun acrescentaSemRepetirCabecalho() {
        val um = FormatoTestes.acrescentar("", colunas, listOf(mapOf("teste" to "a")))
        val dois = FormatoTestes.acrescentar(um, colunas, listOf(mapOf("teste" to "b")))
        assertEquals("teste;valor;erro\na;;\nb;;\n", dois)
    }

    @Test
    fun celulaComPontoEVirgulaOuAspasVaiEntreAspas() {
        assertEquals("\"HTTP 500; falha\"", FormatoTestes.celula("HTTP 500; falha"))
        assertEquals("\"diz \"\"oi\"\"\"", FormatoTestes.celula("diz \"oi\""))
        assertEquals("simples", FormatoTestes.celula("simples"))
    }

    @Test
    fun removerTesteTiraSoAsLinhasDele() {
        val csv = "teste;valor\nB30-5MP-r1;1\nB30-5MP-r2;2\nB30-5MP-r1-falha-101010;3\n"
        assertEquals(
            "teste;valor\nB30-5MP-r2;2\nB30-5MP-r1-falha-101010;3\n",
            FormatoTestes.removerTeste(csv, "B30-5MP-r1")
        )
    }

    @Test
    fun removerTesteEntendeCelulaEntreAspas() {
        val csv = "teste;valor\n\"F4;x\";1\nF5;2\n"
        assertEquals("teste;valor\nF5;2\n", FormatoTestes.removerTeste(csv, "F4;x"))
    }

    @Test
    fun numerosComVirgulaDecimal() {
        assertEquals("97,5", FormatoTestes.decimal(97.46))
        assertEquals("", FormatoTestes.decimal(null))
        assertEquals("6,2 s", FormatoTestes.segundos(6234))
    }

    @Test
    fun nomeDaSessaoServeDePasta() {
        assertEquals("2026-10-21_09h05", FormatoTestes.nomeSessao(LocalDateTime.of(2026, 10, 21, 9, 5, 30)))
    }

    @Test
    fun resumoMarcaParagrafoCortado() {
        val r = FormatoTestes.resumoAcertos(
            listOf(AcertoCorpo(10, 95.4, 800, 0.98), AcertoCorpo(9, 40.0, 200, 0.25))
        )
        assertEquals("10 pt 95% · 9 pt 40% (cortado)", r)
        assertEquals(null, FormatoTestes.resumoAcertos(emptyList()))
    }

    @Test
    fun colunasDeAcertoTemOsCincoCorpos() {
        val c = FormatoTestes.colunasDeAcerto("com", listOf(AcertoCorpo(10, 95.0, 800, 1.0)))
        assertEquals(setOf("com_14", "com_12", "com_11", "com_10", "com_9"), c.keys)
        assertEquals("95,0", c["com_10"])
        assertEquals("", c["com_9"])
    }

    @Test
    fun textoDeRegistroEmSecoes() {
        val t = TextoRegistro().apply {
            secao("teste")
            campo("id", "B30-5MP-r1")
            campo("vazio", null)
        }.toString()
        assertTrue(t.startsWith("[teste]\nid: B30-5MP-r1\nvazio: —\n"))
    }
}
