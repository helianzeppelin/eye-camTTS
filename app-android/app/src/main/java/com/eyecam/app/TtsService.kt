package com.eyecam.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Controla a síntese de voz (Text-To-Speech) nativa do Android.
 * Precisa ser inicializado antes do uso e desligado ao fechar o app.
 *
 * @param mensagemInicial frase dita assim que o motor fica pronto. Orienta o
 *        usuário cego ao abrir o app e evita que a primeira fala se perca: o
 *        motor leva um instante para inicializar, e falar() antes disso não
 *        tem efeito.
 */
class TtsService(context: Context, private val mensagemInicial: String? = null) {

    private var tts: TextToSpeech? = null
    private var pronto = false

    init {
        // Inicializa o motor de TTS. O callback avisa quando está pronto.
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Define o idioma como português do Brasil
                val resultado = tts?.setLanguage(Locale.forLanguageTag("pt-BR"))
                pronto = resultado != TextToSpeech.LANG_MISSING_DATA &&
                        resultado != TextToSpeech.LANG_NOT_SUPPORTED
                if (pronto) mensagemInicial?.let { falar(it) }
            }
        }
    }

    /**
     * Fala o texto em voz alta.
     * Se já estiver falando, interrompe e começa o novo texto.
     *
     * O Android RECUSA, sem falar nada, textos maiores que
     * getMaxSpeechInputLength() (4.000 caracteres). Uma página de livro pode
     * passar disso — a página de teste tem ~4.300. Por isso o texto é
     * dividido: a primeira parte interrompe a fala anterior (QUEUE_FLUSH) e
     * as demais entram na fila (QUEUE_ADD).
     */
    fun falar(texto: String) {
        if (!pronto || texto.isBlank()) return
        val limite = TextToSpeech.getMaxSpeechInputLength() - MARGEM_LIMITE
        dividirEmPartes(texto, limite).forEachIndexed { i, parte ->
            val modo = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(parte, modo, null, "eyecam_leitura_$i")
        }
    }

    /**
     * Para a fala atual (e descarta as partes que estavam na fila).
     */
    fun parar() {
        tts?.stop()
    }

    /**
     * Desliga o motor de TTS. Chamar quando o app for fechado.
     */
    fun desligar() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        // Folga abaixo do limite do Android, por segurança
        const val MARGEM_LIMITE = 100
    }
}

/**
 * Divide um texto em partes de no máximo [limite] caracteres, cortando de
 * preferência no fim de uma frase e, se não houver um perto, no último espaço.
 *
 * Fica fora da classe, sem dependência do Android, para poder ser testada no
 * computador (DivisaoTextoTest).
 */
internal fun dividirEmPartes(texto: String, limite: Int): List<String> {
    require(limite > 1) { "limite precisa ser maior que 1" }

    val partes = mutableListOf<String>()
    var resto = texto.trim()

    while (resto.length > limite) {
        // Último fim de frase dentro do limite. Se ele ficar na primeira
        // metade (frase enorme), corta no último espaço; se nem isso
        // existir (uma "palavra" gigante), corta seco no limite.
        var corte = resto.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), limite - 1)
        if (corte < limite / 2) corte = resto.lastIndexOf(' ', limite - 1)
        if (corte <= 0) corte = limite - 1

        val parte = resto.substring(0, corte + 1).trim()
        if (parte.isNotEmpty()) partes += parte
        resto = resto.substring(corte + 1).trim()
    }

    if (resto.isNotEmpty()) partes += resto
    return partes
}
