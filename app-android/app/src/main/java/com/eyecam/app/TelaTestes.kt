package com.eyecam.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Modo bancada: roda os planos de teste (PlanoTestes) com um botão por passo.
 *
 * Resolução, autofoco, leitura, repetições e nome dos arquivos são fixados
 * pelo plano; a pessoa na bancada só monta a condição pedida e aperta. Cada
 * passo grava foto, registro e planilha em Download/EyeCam/<sessão>. O
 * progresso fica guardado: fechar o app no meio não perde o ponto.
 *
 * Para evitar rótulo errado, quando a montagem física muda (distância, luz)
 * o botão de captura só aparece depois do "Já ajustei a montagem".
 */
@Composable
fun TelaTestes(onVoltar: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val estado = remember { EstadoTestes(context) }
    val tts = remember { TtsService(context) }
    DisposableEffect(Unit) { onDispose { tts.desligar() } }

    // Tela sempre acesa durante os testes: na bancada as mãos estão ocupadas
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var sessao by remember { mutableStateOf(estado.sessaoAtual()) }
    var plano by remember { mutableStateOf(PlanosTestes.porId(estado.planoAtual) ?: PlanosTestes.BANCADA) }
    var indice by remember { mutableIntStateOf(estado.indice(plano.id)) }
    var podeDesfazer by remember { mutableStateOf(estado.ultimo(plano.id) != null) }
    var montagemConfirmada by remember { mutableStateOf(false) }
    var rodando by remember { mutableStateOf(false) }
    var progresso by remember { mutableStateOf("") }
    var ultimo by remember { mutableStateOf<ResultadoPasso?>(null) }
    var pedirNovaSessao by remember { mutableStateOf(false) }

    val passo = plano.passos.getOrNull(indice)
    val anterior = plano.passos.getOrNull(indice - 1)
    val precisaConfirmar = passo != null && PlanosTestes.mudouMontagem(anterior, passo) && !montagemConfirmada

    fun irPara(novo: Int) {
        indice = novo
        estado.salvarIndice(plano.id, novo)
        montagemConfirmada = false
    }

    fun trocarPlano(novo: PlanoTestes) {
        plano = novo
        estado.planoAtual = novo.id
        indice = estado.indice(novo.id)
        podeDesfazer = estado.ultimo(novo.id) != null
        montagemConfirmada = false
        ultimo = null
    }

    fun executarPasso() {
        val atual = passo ?: return
        if (rodando) return
        rodando = true
        ultimo = null
        scope.launch {
            try {
                val resultado = ExecutorTestes.executar(context, estado, plano, atual) { progresso = it }
                ultimo = resultado
                vibrar(context)
                if (resultado.concluido) {
                    estado.salvarUltimo(plano.id, resultado.desfazer)
                    podeDesfazer = resultado.desfazer != null
                    irPara(indice + 1)
                    val proximo = plano.passos.getOrNull(indice)
                    tts.falar(
                        when {
                            proximo == null -> "Plano concluído"
                            PlanosTestes.mudouMontagem(atual, proximo) -> "Pronto. Mude a montagem"
                            else -> "Pronto"
                        }
                    )
                } else {
                    tts.falar("Falhou")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ultimo = ResultadoPasso(
                    concluido = false,
                    titulo = "Erro em ${atual.id}",
                    linhas = listOf(e.message ?: e.javaClass.simpleName, "Aperte de novo para repetir o passo.")
                )
                tts.falar("Erro")
            } finally {
                rodando = false
                progresso = ""
            }
        }
    }

    fun desfazerUltimo() {
        val ref = estado.ultimo(plano.id) ?: return
        if (rodando) return
        rodando = true
        scope.launch {
            try {
                ExecutorTestes.desfazer(context, sessao, ref)
                estado.salvarUltimo(plano.id, null)
                podeDesfazer = false
                irPara((indice - 1).coerceAtLeast(0))
                // A bancada continua montada como estava nesse passo
                montagemConfirmada = true
                ultimo = ResultadoPasso(
                    concluido = false,
                    titulo = "${ref.teste} apagado",
                    linhas = listOf("Os arquivos e a linha da planilha foram apagados. Refaça o teste.")
                )
            } finally {
                rodando = false
            }
        }
    }

    fun pular() {
        estado.salvarUltimo(plano.id, null)
        podeDesfazer = false
        ultimo = null
        irPara(indice + 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Modo bancada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onVoltar, enabled = !rodando) { Text("Voltar") }
        }
        Text(
            "Arquivos em ${ArquivosTestes.descreverPasta(sessao)}",
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanosTestes.TODOS.forEach { p ->
                FilterChip(
                    selected = p.id == plano.id,
                    onClick = { trocarPlano(p) },
                    enabled = !rodando,
                    label = { Text(p.nome) }
                )
            }
        }
        Text(plano.descricao, style = MaterialTheme.typography.bodySmall)

        val total = plano.passos.size
        LinearProgressIndicator(
            progress = { indice.toFloat() / total },
            modifier = Modifier.fillMaxWidth()
        )
        Text("Passo ${minOf(indice + 1, total)} de $total", style = MaterialTheme.typography.bodySmall)

        if (passo == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Plano concluído ✓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Os arquivos estão na pasta acima. Para repetir o plano, comece uma nova sessão.")
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (precisaConfirmar) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(passo.id, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(passo.montagem, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(PlanosTestes.descricaoAutomatica(passo), style = MaterialTheme.typography.bodyMedium)
                    Text(passo.instrucao, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (precisaConfirmar) {
                Text(
                    "A montagem mudou. Ajuste a bancada antes de capturar.",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { montagemConfirmada = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("Já ajustei a montagem") }
            } else {
                Button(
                    onClick = { executarPasso() },
                    enabled = !rodando,
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) {
                    Text(PlanosTestes.rotuloBotao(passo), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { desfazerUltimo() },
                    enabled = !rodando && podeDesfazer,
                    modifier = Modifier.weight(1f)
                ) { Text("Refazer o último") }
                OutlinedButton(
                    onClick = { pular() },
                    enabled = !rodando,
                    modifier = Modifier.weight(1f)
                ) { Text("Pular este") }
            }
        }

        if (rodando) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(progresso.ifBlank { "Trabalhando..." })
            }
        }

        ultimo?.let { r ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    r.linhas.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    r.miniatura?.let {
                        Image(it.asImageBitmap(), contentDescription = "Última foto", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        HorizontalDivider()
        OutlinedButton(
            onClick = { pedirNovaSessao = true },
            enabled = !rodando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Nova sessão (pasta nova, planos do início)") }
    }

    if (pedirNovaSessao) {
        AlertDialog(
            onDismissRequest = { pedirNovaSessao = false },
            title = { Text("Começar uma nova sessão?") },
            text = {
                Text(
                    "Os arquivos da sessão atual continuam na pasta dela. Os três planos " +
                        "voltam ao primeiro passo, gravando numa pasta nova."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sessao = estado.novaSessao()
                    indice = 0
                    podeDesfazer = false
                    montagemConfirmada = false
                    ultimo = null
                    pedirNovaSessao = false
                }) { Text("Começar") }
            },
            dismissButton = {
                TextButton(onClick = { pedirNovaSessao = false }) { Text("Cancelar") }
            }
        )
    }
}
