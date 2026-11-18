package com.eyecam.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Controla a síntese de voz (Text-To-Speech) nativa do Android. */
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

    /** Fala o texto em voz alta. */
    fun falar(texto: String) {
        if (!pronto || texto.isBlank()) return
        val limite = TextToSpeech.getMaxSpeechInputLength() - MARGEM_LIMITE
        dividirEmPartes(texto, limite).forEachIndexed { i, parte ->
            val modo = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(parte, modo, null, "eyecam_leitura_$i")
        }
    }

    /** Para a fala atual (e descarta as partes que estavam na fila). */
    fun parar() {
        tts?.stop()
    }

    /** Desliga o motor de TTS. */
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
 * Divide um texto em partes de no máximo [limite] caracteres, cortando de preferência no
 * fim de uma frase e, se não houver um perto, no último espaço.
 */
internal fun dividirEmPartes(texto: String, limite: Int): List<String> {
    require(limite > 1) { "limite precisa ser maior que 1" }

    val partes = mutableListOf<String>()
    var resto = texto.trim()

    while (resto.length > limite) {
        // Último fim de frase dentro do limite.
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
