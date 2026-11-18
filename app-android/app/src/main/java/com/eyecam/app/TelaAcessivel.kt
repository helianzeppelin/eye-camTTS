package com.eyecam.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Distância mínima para um deslize contar como gesto.
private val LIMIAR_DESLIZE = 60.dp

@Composable
fun TelaAcessivel(onAbrirDev: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A frase inicial é dita quando o motor de voz fica pronto: orienta o usuário cego ao
    // abrir o app
    val tts = remember { TtsService(context, "Eye-cam pronto. Toque duas vezes para ler.") }
    DisposableEffect(Unit) { onDispose { tts.desligar() } }

    var status by remember { mutableStateOf("Eye-cam pronto. Toque duas vezes para ler.") }
    var ultimoTexto by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }

    // Função de captura completa (toque duplo) Leitura: foca em resolução baixa e captura
    // em alta, sem o portão de estabilidade (maxSondagens = 0), com ampliação adaptativa no
    // OCR.
    val configLeitura = remember {
        ConfigLeitura(
            resolucaoSondagem = "svga",
            resolucaoFinal = "5mp",
            maxSondagens = 0,
            usarEnquadramento = false,
            ampliacaoAdaptativa = true
        )
    }

    fun capturarELer() {
        if (carregando) return
        scope.launch {
            carregando = true
            vibrar(context)
            // try/finally: qualquer falha inesperada vira aviso falado, e o carregando
            // sempre volta a false (senão a tela travaria em "ocupada" e ignoraria os
            // próximos toques duplos)
            try {
                val resultado = LeitorEyeCam.ler(context, configLeitura) { progresso ->
                    status = progresso.statusVisual
                    progresso.falar?.let { tts.falar(it) }
                    // Vibração dupla e bipe: "pode baixar a folha".
                    if (progresso.fotoTirada) avisarFotoTirada(context)
                }

                if (resultado.sucesso) {
                    ultimoTexto = resultado.texto
                    status = "Lendo"
                    tts.falar(resultado.texto)
                } else {
                    status = resultado.mensagem
                    tts.falar(resultado.mensagem)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Erro: ${e.message ?: e.javaClass.simpleName}"
                tts.falar("Ocorreu um erro. Tente de novo.")
            } finally {
                carregando = false
            }
        }
    }

    fun repetir() {
        if (ultimoTexto.isNotBlank()) {
            vibrar(context)
            status = "Lendo novamente"
            tts.falar(ultimoTexto)
        } else {
            tts.falar("Nenhum texto para repetir.")
        }
    }

    fun parar() {
        vibrar(context)
        tts.parar()
        status = "Leitura parada"
    }

    fun testarConexao() {
        scope.launch {
            vibrar(context)
            // Na primeira vez, avisa que o sistema vai pedir confirmação
            val aviso = RegrasConexao.avisoAntesDeConectar(ConexaoOculos.formaPrevista(context))
            tts.falar(aviso ?: "Testando conexão")
            val ok = EyeCamClient.conectarNaRede(context) && EyeCamClient.ping()
            if (ok) {
                status = "Óculos conectado"
                tts.falar("Óculos conectado")
            } else {
                status = "Óculos não encontrado"
                tts.falar(RegrasConexao.orientacaoFalha(ConexaoOculos.conexaoAutomaticaDisponivel()))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .semantics {
                // Com o TalkBack ligado, os gestos abaixo não chegam ao pointerInput: o
                // toque duplo vira a AÇÃO DE CLIQUE do elemento focado, e os deslizes
                // servem para navegar.
                contentDescription = "Área de leitura do Eye-cam"
                onClick(label = "capturar e ler") { capturarELer(); true }
                onLongClick(label = "testar a conexão") { testarConexao(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Repetir leitura") { repetir(); true },
                    CustomAccessibilityAction("Parar leitura") { parar(); true }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { capturarELer() },
                    onLongPress = { testarConexao() }
                )
            }
            .pointerInput(Unit) {
                // O callback recebe o deslocamento de CADA evento de toque, e não o do
                // gesto inteiro.
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val limiar = LIMIAR_DESLIZE.toPx()
                        if (total > limiar) repetir()        // deslize à direita
                        else if (total < -limiar) parar()    // deslize à esquerda
                    }
                ) { _, arrasto -> total += arrasto }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                "Eye-cam",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (carregando) {
                CircularProgressIndicator(modifier = Modifier.size(64.dp))
            }
            Text(
                status,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
            Text(
                "Toque 2x: ler\nDeslize →: repetir\nDeslize ←: parar\nSegure: testar conexão",
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Botão discreto pra abrir o modo desenvolvedor (canto)
        TextButton(
            onClick = onAbrirDev,
            modifier = Modifier.align(Alignment.BottomEnd)
        ) { Text("Dev") }
    }
}

/** Faz o celular vibrar brevemente (feedback tátil). */
fun vibrar(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    // O app exige o Android 8 (minSdk 26), que já tem o VibrationEffect
    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
}

/**
 * Aviso de "foto tirada": duas vibrações curtas e um bipe, diferente da vibração simples do
 * toque, para o usuário saber que já pode baixar a folha.
 */
fun avisarFotoTirada(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
    try {
        val tom = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
        tom.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 200)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tom.release() }, 400)
    } catch (e: RuntimeException) {
        // sem áudio disponível: fica só a vibração
    }
}
