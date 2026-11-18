package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Testes da regra de ampliação adaptativa (AmpliacaoAdaptativa.fator). */
class AmpliacaoAdaptativaTest {

    private val delta = 1e-9

    @Test
    fun textoGrande_naoAmplia() {
        assertEquals(1.0, AmpliacaoAdaptativa.fator(30.0, 1600, 1200), delta)
    }

    @Test
    fun textoNoAlvo_naoAmplia() {
        // 26/25 = 1,04: abaixo do mínimo útil, não vale a segunda passada
        assertEquals(1.0, AmpliacaoAdaptativa.fator(25.0, 1600, 1200), delta)
    }

    @Test
    fun textoPequeno_ampliaAteOAlvo() {
        // 26/18 ≈ 1,44 (texto de ~19 px por em: simulação indicou 1,5x)
        assertEquals(26.0 / 18.0, AmpliacaoAdaptativa.fator(18.0, 1600, 1200), delta)
    }

    @Test
    fun textoMuitoPequeno_limitaEm2x() {
        assertEquals(2.0, AmpliacaoAdaptativa.fator(8.0, 1600, 1200), delta)
    }

    @Test
    fun semTexto_tentaCom2x() {
        assertEquals(2.0, AmpliacaoAdaptativa.fator(null, 1600, 1200), delta)
    }

    @Test
    fun imagemGrande_respeitaLimiteDeMemoria() {
        // 5 MP (2560 px): 4096/2560 = 1,6, mesmo pedindo 2x
        assertEquals(4096.0 / 2560.0, AmpliacaoAdaptativa.fator(8.0, 2560, 1920), delta)
    }

    @Test
    fun imagemNoLimite_naoAmplia() {
        assertEquals(1.0, AmpliacaoAdaptativa.fator(8.0, 4000, 3000), delta)
    }

    @Test
    fun dimensaoInvalida_naoAmplia() {
        assertEquals(1.0, AmpliacaoAdaptativa.fator(10.0, 0, 0), delta)
    }
}
