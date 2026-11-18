package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes dos planos do modo bancada: nomes de arquivo únicos e seguros, condições fixadas e
 * pedido de confirmação quando a montagem muda.
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
        assertEquals(PlanosTestes.FOCO, PlanosTestes.porId("foco"))
        assertEquals(null, PlanosTestes.porId("inexistente"))
    }

    @Test
    fun focoTemQuatroFormasCom3RepeticoesEUmaSequencia() {
        val fotos = PlanosTestes.FOCO.passos.filter { it.tipo == TipoPasso.FOTO }
        val porForma = fotos.groupBy { it.id.substringBeforeLast("-r") }
        assertEquals(setOf("FO-SVGA", "FO-XGA", "FO-5MPAF", "FO-SVGA20"), porForma.keys)
        porForma.values.forEach { assertEquals(3, it.size) }
        // Todas na mesma montagem: a nitidez só compara fotos da mesma cena
        assertEquals(1, PlanosTestes.FOCO.passos.map { it.montagem }.toSet().size)
        fotos.forEach { assertEquals("5mp", it.resolucao) }
        assertEquals(TipoPasso.SEQUENCIA_FOTOS, PlanosTestes.FOCO.passos.last().tipo)
    }

    @Test
    fun formasDeFocarDoPlanoFoco() {
        val passo = PlanosTestes.FOCO.passos.associateBy { it.id }
        assertEquals("xga", passo.getValue("FO-XGA-r1").resolucaoFoco)
        assertFalse(passo.getValue("FO-5MPAF-r1").focarAntes)
        assertTrue(passo.getValue("FO-5MPAF-r1").autofocoNaFinal)
        val d = PlanosTestes.descricaoAutomatica(passo.getValue("FO-XGA-r2"))
        assertTrue(d, d.contains("Foco em XGA"))
    }

    @Test
    fun clockDoPassoVaiParaATune() {
        val passo = PlanosTestes.FOCO.passos.associateBy { it.id }
        val svga20 = passo.getValue("FO-SVGA20-r1")
        assertEquals(mapOf("xclk" to "20", "xclk_alta" to "10"), PlanosTestes.ajusteDeClock(svga20))
        assertTrue(PlanosTestes.usaClockDeTeste(svga20))
        assertEquals(mapOf("xclk" to "10", "xclk_alta" to "16"), PlanosTestes.ajusteDeClock(passo.getValue("FO-5MP16-x5")))
        // Passo sem clock próprio devolve a placa ao padrão
        val comum = PlanosTestes.BANCADA.passos.first()
        assertEquals(mapOf("xclk" to "10", "xclk_alta" to "10"), PlanosTestes.ajusteDeClock(comum))
        assertFalse(PlanosTestes.usaClockDeTeste(comum))
    }

    @Test
    fun passosComLeituraDaPaginaTemDistancia() {
        val b30 = PlanosTestes.BANCADA.passos.first { it.id.startsWith("B30") }
        assertEquals(30, b30.distanciaCm)
        assertEquals(20, PlanosTestes.BANCADA.passos.first { it.id.startsWith("B20") }.distanciaCm)
        PlanosTestes.FOCO.passos.filter { it.tipo == TipoPasso.FOTO }.forEach { assertEquals(30, it.distanciaCm) }
    }

    @Test
    fun oculosTemSeteCondicoesCom3FotosDe5Mp() {
        val passos = PlanosTestes.OCULOS.passos
        assertEquals(21, passos.size)
        val porCondicao = passos.groupBy { it.id.substringBefore("-") }
        assertEquals(listOf("ON20", "ON25", "ON30", "ON35", "OL20", "OL25", "OL30"), porCondicao.keys.toList())
        porCondicao.forEach { (condicao, grupo) ->
            assertEquals(condicao, 3, grupo.size)
            assertEquals(condicao.drop(2).toInt(), grupo.first().distanciaCm)
            // Mesma montagem no grupo: só a primeira foto pede "Já ajustei"
            grupo.zipWithNext().forEach { (a, b) -> assertFalse(PlanosTestes.mudouMontagem(a, b)) }
        }
        passos.forEach {
            assertEquals("5mp", it.resolucao)
            assertTrue(it.focarAntes)
            assertTrue(it.lerTexto)
        }
    }

    @Test
    fun escalaEsperadaPelaDistancia() {
        // 121 px/mm·cm em 5 MP (medido em 22/10/2026); UXGA tem 1600 px de largura
        assertEquals(121.0 / 30, PlanosTestes.pxPorMmEsperado(30, 2560), 1e-9)
        assertEquals(121.0 / 20 * 1600 / 2560, PlanosTestes.pxPorMmEsperado(20, 1600), 1e-9)
    }
}
