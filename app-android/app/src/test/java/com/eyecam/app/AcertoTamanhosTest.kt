package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcertoTamanhosTest {

    private val frases = AcertoTamanhos.lerFrases(
        "32\tO barco azul chegou cedo ao porto da cidade.\n" +
            "24\tA professora abriu a janela e viu o jardim molhado pela chuva.\n" +
            "10\tNa biblioteca silenciosa, o estudante encontrou um livro raro.\n"
    )

    @Test
    fun leAsFrasesComOCorpo() {
        assertEquals(listOf(32, 24, 10), frases.map { it.corpo })
        assertTrue(frases[0].texto.startsWith("O barco"))
    }

    @Test
    fun fraseLidaInteiraDaCem() {
        val r = AcertoTamanhos.medir("O barco azul chegou cedo ao porto da\ncidade.", frases)
        assertEquals(100.0, r.first { it.corpo == 32 }.acerto, 0.01)
    }

    @Test
    fun fraseComErrosDeLetraFicaAlta() {
        val r = AcertoTamanhos.medir("A professora abriu a jancla e viu o jardim mollado pela chuva", frases)
        assertTrue(r.first { it.corpo == 24 }.acerto > 85)
    }

    @Test
    fun fraseAusenteFicaBaixa() {
        val r = AcertoTamanhos.medir("O barco azul chegou cedo ao porto da cidade.", frases)
        assertTrue(r.first { it.corpo == 10 }.acerto < 50)
    }

    @Test
    fun menorCorpoLidoUsaOLimiar() {
        val r = AcertoTamanhos.medir(
            "O barco azul chegou cedo ao porto da cidade. A professora abriu a janela e viu o jardim molhado pela chuva.",
            frases
        )
        assertEquals(24, AcertoTamanhos.menorCorpoLido(r))
        assertNull(AcertoTamanhos.menorCorpoLido(AcertoTamanhos.medir("", frases)))
    }
}
