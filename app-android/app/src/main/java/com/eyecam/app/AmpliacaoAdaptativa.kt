package com.eyecam.app

import kotlin.math.max
import kotlin.math.min

/** Decide quanto ampliar a imagem antes do OCR, a partir do tamanho do texto. */
object AmpliacaoAdaptativa {

    /** Altura de linha (px) a partir da qual não vale ampliar (≈ 27 px por em). */
    const val ALTURA_LINHA_ALVO = 26.0

    /** Ampliação máxima: 2,5x não foi melhor que 2x e custa memória. */
    const val FATOR_MAXIMO = 2.0

    /** Abaixo disto a ampliação não compensa a segunda passada do OCR. */
    const val FATOR_MINIMO_UTIL = 1.15

    /**
     * Quando a primeira passada não acha texto nenhum, a letra pode estar pequena demais
     * até para ser DETECTADA: tenta-se de novo com 2x.
     */
    const val FATOR_SEM_TEXTO = 2.0

    /** Maior lado da imagem ampliada, para não estourar a memória do celular. */
    const val DIMENSAO_MAXIMA = 4096

    /** null se não houver linhas */
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
