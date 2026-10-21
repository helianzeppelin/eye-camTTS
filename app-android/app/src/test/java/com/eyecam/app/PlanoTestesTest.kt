package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes dos planos do modo bancada: nomes de arquivo únicos e seguros,
 * condições fixadas e pedido de confirmação quando a montagem muda.
 */
class PlanoTestesTest {

    private val todosOsPassos = PlanosTestes.TODOS.flatMap { it.passos }

    @Test
    fun idsSaoUnicosEmTodosOsPlanos() {
        val ids = todosOsPassos.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun idsServemDeNomeDeArquivo() {
        val seguro = Regex("^[A-Za-z0-9-]+$")
        todosOsPassos.forEach { assertTrue("id inválido: ${it.id}", seguro.matches(it.id)) }
    }

    @Test
    fun bancadaTem3RepeticoesDeCadaCondicao() {
        val porCondicao = PlanosTestes.BANCADA.passos.groupBy { it.id.substringBeforeLast("-r") }
        porCondicao.forEach { (condicao, passos) -> assertEquals("condição $condicao", 3, passos.size) }
        // 25 e 30 cm: UXGA, QXGA e 5MP; 35, 40 e 20 cm: UXGA e 5MP; luz fraca e sombra: 5MP
        assertEquals(9 + 9 + 6 + 6 + 3 + 3 + 6, PlanosTestes.BANCADA.passos.size)
    }

    @Test
    fun bancadaFocaEmSvgaEFotografaSemAutofoco() {
        PlanosTestes.BANCADA.passos.forEach {
            assertTrue(it.focarAntes)
            assertFalse(it.autofocoNaFinal)
            assertTrue(it.lerTexto)
            assertTrue(it.salvarFoto)
        }
    }

    @Test
    fun resolucaoNoNomeBateComAResolucaoPedida() {
        PlanosTestes.BANCADA.passos.forEach {
            assertTrue(it.id, it.id.contains("-${PlanosTestes.rotulo(it.resolucao)}-"))
        }
    }

    @Test
    fun mudancaDeDistanciaPedeConfirmacao() {
        val passos = PlanosTestes.BANCADA.passos
        val ultimo25 = passos.last { it.id.startsWith("B25") }
        val primeiro30 = passos.first { it.id.startsWith("B30") }
        assertTrue(PlanosTestes.mudouMontagem(null, passos.first()))
        assertTrue(PlanosTestes.mudouMontagem(ultimo25, primeiro30))
        assertFalse(PlanosTestes.mudouMontagem(passos[0], passos[1]))
    }

    @Test
    fun fotoAtrasadaNaoPedeConfirmacaoATodoPasso() {
        val f6 = PlanosTestes.FIRMWARE.passos.filter { it.id.startsWith("F6") }
        assertEquals(20, f6.size)
        f6.zipWithNext().forEach { (a, b) -> assertFalse(PlanosTestes.mudouMontagem(a, b)) }
        // Alterna AZUL e VERDE: a segunda foto de cada par é a que testa o atraso
        assertEquals(listOf("AZUL", "VERDE"), f6.take(2).map { it.palavraEsperada })
    }

    @Test
    fun sequenciasTemRepeticoesEBotaoComContagem() {
        val f4 = PlanosTestes.FIRMWARE.passos.first { it.id.startsWith("F4") }
        assertEquals(10, f4.repeticoes)
        assertEquals("uxga", f4.resolucao)
        assertEquals("INICIAR (10×)", PlanosTestes.rotuloBotao(f4))
        assertEquals("CAPTURAR", PlanosTestes.rotuloBotao(PlanosTestes.BANCADA.passos.first()))
    }

    @Test
    fun descricaoDizOQueOAppFazSozinho() {
        val d = PlanosTestes.descricaoAutomatica(PlanosTestes.BANCADA.passos.first { it.resolucao == "5mp" })
        assertTrue(d, d.contains("Foco em SVGA") && d.contains("5MP sem autofoco"))
    }

    @Test
    fun planoPorId() {
        assertEquals(PlanosTestes.TEMPOS, PlanosTestes.porId("tempos"))
        assertEquals(null, PlanosTestes.porId("inexistente"))
    }
}
