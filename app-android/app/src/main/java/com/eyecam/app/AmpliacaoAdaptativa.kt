package com.eyecam.app

import kotlin.math.max
import kotlin.math.min

/**
 * Decide quanto ampliar a imagem antes do OCR, a partir do tamanho do texto.
 *
 * Por que existe: nos testes com a câmera virtual (simulação da OV5640 +
 * OCR neural), o acerto despenca quando a letra tem poucos pixels, e a
 * ampliação bicúbica foi a ÚNICA operação de tratamento que ajudou:
 *
 *   texto com ~12 px por em, foco bom:     crua 67%  ->  ampliada 2x 94%
 *   texto com ~14 px por em, com sombra:   crua 54%  ->  ampliada 2x 90%
 *   texto com ~22 px por em, foco médio:   crua 98%  ->  ampliada 1,5x 99,5%
 *
 * Acima de ~26 px por linha a ampliação não traz ganho, e 2,5x não foi melhor
 * que 2x. A limiarização e a morfologia, ao contrário, pioraram o acerto em
 * imagens boas e médias (ver ImageProcessor).
 *
 * A orientação do ML Kit vai na mesma linha: ao menos 16x16 px por
 * caractere, sem ganho acima de 24x24.
 *
 * O tamanho do texto vem da altura mediana das linhas reconhecidas numa
 * primeira passada do OCR (≈ 0,95 em nas medições). Fica em Kotlin puro para
 * ser testado no computador (AmpliacaoAdaptativaTest).
 */
object AmpliacaoAdaptativa {

    /** Altura de linha (px) a partir da qual não vale ampliar (≈ 27 px por em). */
    const val ALTURA_LINHA_ALVO = 26.0

    /** Ampliação máxima: 2,5x não foi melhor que 2x e custa memória. */
    const val FATOR_MAXIMO = 2.0

    /** Abaixo disto a ampliação não compensa a segunda passada do OCR. */
    const val FATOR_MINIMO_UTIL = 1.15

    /**
     * Quando a primeira passada não acha texto nenhum, a letra pode estar
     * pequena demais até para ser DETECTADA: tenta-se de novo com 2x.
     */
    const val FATOR_SEM_TEXTO = 2.0

    /** Maior lado da imagem ampliada, para não estourar a memória do celular. */
    const val DIMENSAO_MAXIMA = 4096

    /**
     * @param alturaLinhaPx altura mediana das linhas na primeira passada, ou
     *        null se não houver linhas
     * @param largura largura da imagem original (px)
     * @param altura altura da imagem original (px)
     * @return fator de ampliação; 1.0 significa "não ampliar"
     */
    fun fator(alturaLinhaPx: Double?, largura: Int, altura: Int): Double {
        val maiorLado = max(largura, altura)
        if (maiorLado <= 0) return 1.0

        val limitePorMemoria = DIMENSAO_MAXIMA.toDouble() / maiorLado
        val desejado = when {
            alturaLinhaPx == null -> FATOR_SEM_TEXTO
            alturaLinhaPx <= 0.0 -> FATOR_SEM_TEXTO
            else -> ALTURA_LINHA_ALVO / alturaLinhaPx
        }

        val fator = min(min(desejado, FATOR_MAXIMO), limitePorMemoria)
        return if (fator < FATOR_MINIMO_UTIL) 1.0 else fator
    }
}
