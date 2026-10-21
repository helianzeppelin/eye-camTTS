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

// Distância mínima para um deslize contar como gesto. Em dp, para valer o
// mesmo em qualquer densidade de tela (40 px eram ~15 dp num celular atual).
private val LIMIAR_DESLIZE = 60.dp

@Composable
fun TelaAcessivel(onAbrirDev: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A frase inicial é dita quando o motor de voz fica pronto: orienta o
    // usuário cego ao abrir o app
    val tts = remember { TtsService(context, "Eye-cam pronto. Toque duas vezes para ler.") }
    DisposableEffect(Unit) { onDispose { tts.desligar() } }

    var status by remember { mutableStateOf("Eye-cam pronto. Toque duas vezes para ler.") }
    var ultimoTexto by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }

    // Função de captura completa (toque duplo)
    // Leitura: foca em resolução baixa e captura em alta, sem o portão de
    // estabilidade (maxSondagens = 0), com ampliação adaptativa no OCR.
    //
    // Até 10/2026 o modo acessível capturava em SVGA: texto de 10 pt a 25 cm
    // ficava com ~10 px por em, ilegível com qualquer foco ou tratamento
    // (testes com a câmera virtual). A resolução final é o fator que mais
    // pesa no acerto: a 30 cm, 10 pt tem ~16 px por em em UXGA (no limite) e
    // ~26 em 5 MP, com folga mesmo com foco apenas bom. Por isso a captura
    // final é em 5 MP (decisão de 20/10/2026). Se o óculos recusar 5 MP, o
    // LeitorEyeCam tenta de novo em UXGA. O portão (maxSondagens = 5) fica
    // para depois da calibração no modo desenvolvedor.
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
            // try/finally: qualquer falha inesperada vira aviso falado, e o
            // carregando sempre volta a false (senão a tela travaria em
            // "ocupada" e ignoraria os próximos toques duplos)
            try {
                val resultado = LeitorEyeCam.ler(context, configLeitura) { progresso ->
                    status = progresso.statusVisual
                    progresso.falar?.let { tts.falar(it) }
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
                // Com o TalkBack ligado, os gestos abaixo não chegam ao
                // pointerInput: o toque duplo vira a AÇÃO DE CLIQUE do
                // elemento focado, e os deslizes servem para navegar. Por
                // isso as ações também são declaradas aqui. O TalkBack anuncia
                // "toque duas vezes para capturar e ler" e oferece Repetir e
                // Parar no menu de ações. Sem TalkBack, os gestos não mudam.
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
                // O callback recebe o deslocamento de CADA evento de toque, e
                // não o do gesto inteiro. Comparar cada evento com o limiar
                // fazia um deslize lento nunca disparar e um rápido disparar
                // várias vezes. Por isso o deslocamento é somado e a decisão
                // sai uma única vez, no fim do gesto.
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

/**
 * Faz o celular vibrar brevemente (feedback tátil).
 */
fun vibrar(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(50)
    }
}
