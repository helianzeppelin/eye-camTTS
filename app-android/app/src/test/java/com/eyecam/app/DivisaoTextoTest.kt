package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de dividirEmPartes() (TtsService.kt).
 *
 * O TextToSpeech do Android recusa textos acima de 4.000 caracteres. Estes
 * testes garantem que a divisão respeita o limite e não perde palavras.
 */
class DivisaoTextoTest {

    private val paragrafo =
        "A manhã chegou devagar sobre a cidade adormecida, e o zelador percebeu " +
        "que a chuva da noite havia deixado poças rasas no pátio de pedra. Ele " +
        "anotou no caderno, com letra miúda, que faltavam três lâmpadas no " +
        "corredor oeste e que a bomba d'água precisava de reparo urgente antes " +
        "do fim do mês. "

    private fun palavras(t: String) = t.trim().split(Regex("\\s+"))

    @Test
    fun textoCurto_umaParteIgual() {
        assertEquals(listOf("Olá, mundo."), dividirEmPartes("  Olá, mundo.  ", 100))
    }

    @Test
    fun paginaDeTesteInteira_respeitaLimiteDoAndroid() {
        // Texto acima do limite do Android, como a página de teste inteira
        val pagina = paragrafo.repeat(18)
        assertTrue(pagina.length > 4000)

        val partes = dividirEmPartes(pagina, 3900)

        assertTrue(partes.size >= 2)
        partes.forEach { assertTrue("parte com ${it.length} caracteres", it.length <= 3900) }
    }

    @Test
    fun naoPerdeNemDuplicaPalavras() {
        val texto = paragrafo.repeat(7)
        val partes = dividirEmPartes(texto, 300)
        assertEquals(palavras(texto), palavras(partes.joinToString(" ")))
    }

    @Test
    fun cortaNoFimDeFraseQuandoPossivel() {
        val texto = "Primeira frase curta de teste. Segunda frase, bem mais longa do que a primeira."
        val partes = dividirEmPartes(texto, 50)
        assertEquals("Primeira frase curta de teste.", partes[0])
    }

    @Test
    fun palavraGigante_corteSecoNoLimite() {
        val texto = "x".repeat(25)
        val partes = dividirEmPartes(texto, 10)
        assertEquals(listOf("x".repeat(10), "x".repeat(10), "x".repeat(5)), partes)
    }
}
