package com.eyecam.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Resoluções oferecidas para os testes (Bloco 2 do protocolo).
private val RESOLUCOES = listOf("svga", "xga", "sxga", "uxga", "qxga", "5mp")

// Subpasta de Download/EyeCam onde o "Salvar original" põe a foto e o relatório
private const val PASTA_DEV = "modo_dev"

@Composable
fun TelaDesenvolvedor(onVoltar: () -> Unit, onAbrirBancada: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tts = remember { TtsService(context) }
    DisposableEffect(Unit) { onDispose { tts.desligar() } }

    // Parágrafo da página de teste (docs/testes), para o cálculo de acerto
    val textoReferencia = remember {
        context.assets.open("texto_referencia.txt").bufferedReader().use { it.readText() }
    }

    var fotoOriginal by remember { mutableStateOf<Bitmap?>(null) }
    var fotoProcessada by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember {
        mutableStateOf(
            if (ConexaoOculos.conexaoAutomaticaDisponivel()) "Ligue o óculos: o app conecta sozinho."
            else "Conecte-se ao ${RegrasConexao.SSID}."
        )
    }
    var carregando by remember { mutableStateOf(false) }
    var textoReconhecido by remember { mutableStateOf("") }
    var infoOcr by remember { mutableStateOf("") }
    var nitidez by remember { mutableStateOf("") }

    // Tratamento: tudo desligado por padrão (imagem crua para o ML Kit), que foi o que deu
    // o melhor acerto nos testes.
    var grayscale by remember { mutableStateOf(false) }
    var blur by remember { mutableStateOf(false) }
    var threshold by remember { mutableStateOf(false) }
    var engrossar by remember { mutableStateOf(false) }
    var resize by remember { mutableStateOf(false) }
    var removerPontos by remember { mutableStateOf(false) }

    // Leitura: ampliação adaptativa antes do OCR e uso do portão
    var ampliacaoAuto by remember { mutableStateOf(true) }
    var usarPortao by remember { mutableStateOf(false) }

    // Captura (Blocos 1 e 2 do protocolo)
    var resolucao by remember { mutableStateOf("svga") }
    var usarAutofoco by remember { mutableStateOf(true) }
    var rotacao by remember { mutableStateOf(EyeCamClient.rotacaoGraus) }
    var infoCaptura by remember { mutableStateOf("") }

    // Portão de estabilidade
    var logEstabilidade by remember { mutableStateOf<List<String>>(emptyList()) }
    var resumoLeitura by remember { mutableStateOf("") }
    var exigirTextoEstavel by remember { mutableStateOf(true) }

    // Enquadramento automático
    var corrigirPerspectiva by remember { mutableStateOf(true) }
    var corrigirInclinacao by remember { mutableStateOf(true) }
    var recortarTexto by remember { mutableStateOf(false) }
    var infoEnquadramento by remember { mutableStateOf("") }

    // Conexão com o óculos: diagnóstico e tempo da última conexão
    var infoConexao by remember { mutableStateOf(ConexaoOculos.diagnostico(context)) }
    var tempoConexao by remember { mutableStateOf("") }
    var infoOculos by remember { mutableStateOf("") }

    // Acerto do OCR contra o texto de referência
    var ultimoOcr by remember { mutableStateOf<ResultadoOcr?>(null) }
    var origemOcr by remember { mutableStateOf("") }
    var acerto by remember { mutableStateOf<ResultadoAcerto?>(null) }

    /** Executa uma ação da tela em segundo plano. */
    fun executar(mensagem: String, acao: suspend () -> Unit) {
        scope.launch {
            carregando = true
            status = mensagem
            try {
                acao()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Erro: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                carregando = false
            }
        }
    }

    // Abre uma imagem da galeria no lugar da captura do óculos: permite testar o programa
    // (tratamento + ML Kit) separado da câmera
    val seletorImagem = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            executar("Abrindo imagem...") {
                val imagem = ImagemGaleria.carregar(context, uri)
                if (imagem == null) {
                    status = "Não consegui abrir a imagem."
                    return@executar
                }
                fotoOriginal = imagem
                fotoProcessada = null
                textoReconhecido = ""
                infoOcr = ""
                infoEnquadramento = ""
                ultimoOcr = null
                acerto = null
                // O resumo de uma "4.
                resumoLeitura = ""
                logEstabilidade = emptyList()
                infoCaptura = "Imagem da galeria: ${imagem.width}×${imagem.height}"
                nitidez = "Nitidez (Laplaciano): %.1f".format(ImageProcessor.medirNitidez(imagem))
                status = "Imagem carregada."
            }
        }
    }

    /** Resumo da sessão em texto, para colar numa planilha ou anotação. */
    fun montarRelatorio(): String = buildString {
        val agora = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        appendLine("Eye-cam — modo desenvolvedor — $agora")
        appendLine("Resolução: ${resolucao.uppercase()} | Autofoco: ${if (usarAutofoco) "sim" else "não"}")
        val filtros = listOfNotNull(
            "grayscale".takeIf { grayscale }, "blur".takeIf { blur },
            "threshold".takeIf { threshold }, "engrossar".takeIf { engrossar },
            "resize".takeIf { resize }, "remover pontos".takeIf { removerPontos }
        ).ifEmpty { listOf("nenhum") }.joinToString(", ")
        appendLine("Filtros selecionados: $filtros")
        appendLine("Ampliação automática: ${if (ampliacaoAuto) "sim" else "não"} | Portão: ${if (usarPortao) "sim" else "não"}")
        appendLine(infoConexao)
        if (origemOcr.isNotBlank()) appendLine("OCR feito na imagem: $origemOcr")
        listOf(tempoConexao, infoOculos, infoCaptura, nitidez, infoEnquadramento, infoOcr, resumoLeitura)
            .filter { it.isNotBlank() }
            .forEach { appendLine(it) }
        acerto?.let { a ->
            appendLine("Acerto do texto inteiro: %.1f%%".format(a.acertoTextoInteiro))
            a.blocos.forEach { b ->
                appendLine("Acerto do bloco ${b.indice} (${b.caracteres} car.): %.1f%%".format(b.acerto))
            }
        }
        if (logEstabilidade.isNotEmpty()) {
            appendLine("Sondagens:")
            logEstabilidade.forEachIndexed { i, linha -> appendLine("  ${i + 1}. $linha") }
        }
        if (textoReconhecido.isNotBlank()) {
            appendLine()
            appendLine("Texto reconhecido:")
            appendLine(textoReconhecido)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Modo Desenvolvedor", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            TextButton(onClick = onVoltar) { Text("Voltar") }
        }

        // Testes de bancada com as condições automáticas: um botão por passo
        Button(
            onClick = onAbrirBancada,
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Modo bancada (testes automáticos)") }

        Text("Operações de tratamento (botão 2):", fontWeight = FontWeight.SemiBold)
        SwitchLinha("Grayscale", grayscale) { grayscale = it }
        SwitchLinha("Blur (redução de ruído)", blur) { blur = it }
        SwitchLinha("Threshold adaptativo", threshold) { threshold = it }
        SwitchLinha("Engrossar traços (erosão)", engrossar) { engrossar = it }
        SwitchLinha("Resize (ampliar 1.5x)", resize) { resize = it }
        SwitchLinha("Remover pontos (fechamento)", removerPontos) { removerPontos = it }

        HorizontalDivider()
        Text("Leitura:", fontWeight = FontWeight.SemiBold)
        SwitchLinha("Ampliação automática (texto pequeno)", ampliacaoAuto) { ampliacaoAuto = it }

        HorizontalDivider()
        Text("Captura:", fontWeight = FontWeight.SemiBold)
        RESOLUCOES.chunked(3).forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                linha.forEach { r ->
                    FilterChip(
                        selected = resolucao == r,
                        onClick = { resolucao = r },
                        label = { Text(r.uppercase()) }
                    )
                }
            }
        }
        SwitchLinha("Autofoco na captura", usarAutofoco) { usarAutofoco = it }
        Text("Rotação da foto (placa deitada no óculos: 270°):")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 90, 180, 270).forEach { g ->
                FilterChip(
                    selected = rotacao == g,
                    onClick = {
                        EyeCamClient.definirRotacao(context, g)
                        rotacao = g
                    },
                    label = { Text("$g°") }
                )
            }
        }

        HorizontalDivider()
        Text("Enquadramento automático:", fontWeight = FontWeight.SemiBold)
        SwitchLinha("Detectar página (perspectiva)", corrigirPerspectiva) {
            corrigirPerspectiva = it
        }
        SwitchLinha("Endireitar texto (inclinação)", corrigirInclinacao) {
            corrigirInclinacao = it
        }
        SwitchLinha("Recortar na região do texto", recortarTexto) {
            recortarTexto = it
        }

        HorizontalDivider()
        Text("Leitura completa (botão 4):", fontWeight = FontWeight.SemiBold)
        SwitchLinha("Usar portão de estabilidade", usarPortao) { usarPortao = it }
        SwitchLinha("Exigir estabilidade textual", exigirTextoEstavel) {
            exigirTextoEstavel = it
        }

        HorizontalDivider()
        Text("Conexão com o óculos:", fontWeight = FontWeight.SemiBold)
        Text(infoConexao, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mede o tempo até o óculos responder: com o celular já na rede, é quase só o
            // ping; com pedido ao Android, inclui procurar a rede
            OutlinedButton(
                onClick = {
                    executar("Conectando...") {
                        val forma = ConexaoOculos.formaPrevista(context)
                        val inicio = System.currentTimeMillis()
                        val ok = EyeCamClient.conectarNaRede(context) && EyeCamClient.ping()
                        val tempo = System.currentTimeMillis() - inicio
                        infoConexao = ConexaoOculos.diagnostico(context)
                        tempoConexao = "Conexão (${RegrasConexao.descrever(forma)}): " +
                                (if (ok) "ok" else "falhou") + " em $tempo ms"
                        status = if (ok) "Óculos conectado."
                                 else RegrasConexao.orientacaoFalha(ConexaoOculos.conexaoAutomaticaDisponivel())
                    }
                },
                enabled = !carregando,
                modifier = Modifier.weight(1f)
            ) { Text("Testar conexão") }

            // Para trocar de placa: o BSSID muda, e o pedido com o BSSID antigo não
            // encontraria a rede nova
            OutlinedButton(
                onClick = {
                    EyeCamClient.desconectar(context)
                    ConexaoOculos.esquecerBssid(context)
                    infoConexao = ConexaoOculos.diagnostico(context)
                    tempoConexao = ""
                    status = "Óculos esquecido: a próxima conexão pede confirmação."
                },
                enabled = !carregando,
                modifier = Modifier.weight(1f)
            ) { Text("Esquecer óculos") }
        }
        if (tempoConexao.isNotBlank()) Text(tempoConexao, style = MaterialTheme.typography.bodySmall)

        // Estado do óculos (rota /info) e as últimas mensagens dele (rota /log): versão do
        // firmware, energia (bateria ou USB), tensão da bateria, câmera em espera.
        OutlinedButton(
            onClick = {
                executar("Lendo o óculos...") {
                    val ok = EyeCamClient.conectarNaRede(context) && EyeCamClient.ping()
                    if (!ok) {
                        infoOculos = ""
                        status = RegrasConexao.orientacaoFalha(ConexaoOculos.conexaoAutomaticaDisponivel())
                    } else {
                        val info = EyeCamClient.obterInfo()
                            ?.replace(",\"", ",\n\"")?.trim('{', '}') ?: "(sem resposta do /info)"
                        val log = EyeCamClient.obterLog(0)?.texto?.trim()
                            ?.lines()?.takeLast(15)?.joinToString("\n") ?: "(sem resposta do /log)"
                        infoOculos = "INFO DO ÓCULOS\n$info\n\nÚLTIMAS MENSAGENS\n$log"
                        status = "Informações do óculos lidas."
                    }
                }
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Ver informações do óculos (/info e /log)") }
        if (infoOculos.isNotBlank()) Text(infoOculos, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()

        Button(
            onClick = {
                executar("Capturando...") {
                    EyeCamClient.conectarNaRede(context)
                    infoConexao = ConexaoOculos.diagnostico(context)
                    val captura = EyeCamClient.tentarCaptura(resolucao, usarAutofoco).captura
                    if (captura == null) {
                        status = "Falha na captura."
                        return@executar
                    }
                    fotoOriginal = captura.bitmap
                    fotoProcessada = null
                    textoReconhecido = ""
                    infoOcr = ""
                    infoEnquadramento = ""
                    ultimoOcr = null
                    acerto = null
                    // Idem: o resumo da leitura completa anterior não vale para esta foto
                    resumoLeitura = ""
                    logEstabilidade = emptyList()
                    infoCaptura = descreverCaptura(captura, resolucao, usarAutofoco)
                    val v = ImageProcessor.medirNitidez(captura.bitmap)
                    nitidez = "Nitidez (Laplaciano): %.1f".format(v)
                    status = "Foto capturada."
                }
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("1. Capturar foto") }

        OutlinedButton(
            onClick = {
                seletorImagem.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("1a. Abrir imagem da galeria (sem o óculos)") }

        // Opera sobre a foto original e a substitui pela versão enquadrada, para que as
        // etapas seguintes trabalhem sobre a imagem corrigida
        Button(
            onClick = {
                val atual = fotoOriginal
                if (atual == null) {
                    status = "Capture uma foto primeiro."
                    return@Button
                }
                executar("Enquadrando...") {
                    var trabalho: Bitmap = atual
                    val partes = mutableListOf<String>()

                    if (corrigirPerspectiva || corrigirInclinacao) {
                        val r = DocumentProcessor.enquadrar(
                            trabalho,
                            corrigirPerspectiva = corrigirPerspectiva,
                            corrigirInclinacao = corrigirInclinacao
                        )
                        trabalho = r.bitmap
                        partes += when (r.metodo) {
                            "perspectiva" -> "perspectiva corrigida"
                            "inclinacao"  -> "inclinação %.1f°".format(r.anguloGraus)
                            else          -> "sem correção geométrica"
                        }
                    }

                    if (recortarTexto) {
                        val recortado = OcrService.recortarRegiaoDeTexto(trabalho)
                        if (recortado != null) {
                            trabalho = recortado
                            partes += "recortado no texto"
                        } else {
                            partes += "recorte não aplicado"
                        }
                    }

                    fotoOriginal = trabalho
                    fotoProcessada = null

                    val v = ImageProcessor.medirNitidez(trabalho)
                    nitidez = "Nitidez (Laplaciano): %.1f".format(v)
                    infoEnquadramento = "Enquadramento: " + partes.joinToString(" · ")
                    status = "Enquadramento concluído."
                }
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("1b. Enquadrar automaticamente") }

        Button(
            onClick = {
                val original = fotoOriginal
                if (original == null) {
                    status = "Capture uma foto primeiro."
                    return@Button
                }
                executar("Processando com OpenCV...") {
                    val config = ConfigProcessamento(
                        grayscale = grayscale, blur = blur, threshold = threshold,
                        engrossarTracos = engrossar, resize = resize, removerPontos = removerPontos
                    )
                    val resultado = ImageProcessor.processar(original, config)
                    fotoProcessada = resultado.bitmap
                    status = "Processamento concluído."
                }
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("2. Processar imagem") }

        Button(
            onClick = {
                val alvo = fotoProcessada ?: fotoOriginal
                if (alvo == null) {
                    status = "Capture e processe primeiro."
                    return@Button
                }
                executar("Lendo texto...") {
                    val inicio = System.currentTimeMillis()
                    val adaptativo = if (ampliacaoAuto) {
                        OcrService.reconhecerAdaptativo(alvo)
                    } else {
                        val simples = OcrService.reconhecerTexto(alvo)
                        ResultadoOcrAdaptativo(simples, 1.0, simples.alturaMedianaLinhaPx)
                    }
                    val r = adaptativo.resultado
                    val tempoOcr = System.currentTimeMillis() - inicio

                    ultimoOcr = r
                    acerto = null
                    origemOcr = if (alvo === fotoProcessada) "processada" else "original"
                    textoReconhecido = r.erro?.let { "Erro no OCR: $it" }
                        ?: r.texto.ifBlank { "(nenhum texto detectado)" }
                    // A altura de linha é o "tamanho do texto" na imagem: abaixo de ~20 px
                    // o acerto cai, segundo os testes
                    val altura = adaptativo.alturaLinhaOriginalPx?.let { "%.0f px".format(it) } ?: "—"
                    infoOcr = "Blocos: ${r.qtdBlocos} | Linhas: ${r.qtdLinhas} | " +
                            "OCR: $tempoOcr ms (imagem $origemOcr) | altura de linha: $altura | " +
                            "ampliação: %.2fx".format(adaptativo.fatorAmpliacao)
                    if (r.texto.isNotBlank()) tts.falar(r.texto)
                    status = "Leitura concluída."
                }
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("3. Ler texto (OCR + voz)") }

        Button(
            onClick = {
                logEstabilidade = emptyList()
                resumoLeitura = ""
                executar(if (usarPortao) "Lendo com portão de estabilidade..." else "Focando e capturando...") {
                    // Sem portão = o fluxo do modo acessível: foca em SVGA e captura na
                    // resolução escolhida, sem refocar
                    val config = ConfigLeitura(
                        resolucaoSondagem = "svga",
                        resolucaoFinal = resolucao,
                        maxSondagens = if (usarPortao) 5 else 0,
                        amostrasEstaveis = 2,
                        exigirEstabilidadeTextual = exigirTextoEstavel,
                        usarEnquadramento = false,
                        ampliacaoAdaptativa = ampliacaoAuto
                    )

                    val resultado = LeitorEyeCam.ler(context, config) { progresso ->
                        status = progresso.statusVisual
                        // No modo dev não falamos as orientações: elas atrapalhariam a
                        // leitura dos dados na tela.
                        if (progresso.fotoTirada) avisarFotoTirada(context)
                        progresso.detalhe?.let { detalhe ->
                            logEstabilidade = logEstabilidade + detalhe
                        }
                    }

                    resumoLeitura = buildString {
                        append(if (resultado.sucesso) "OK" else "FALHOU")
                        append(" | sondagens: ${resultado.sondagensUsadas}")
                        append(" | estabilizou: ${if (resultado.estabilizou) "sim" else "não"}")
                        append(" | ${resultado.tempoTotalMs} ms")
                        append(" | blocos: ${resultado.qtdBlocos}")
                        append(" | linhas: ${resultado.qtdLinhas}")
                        append(" | ampliação: %.2fx".format(resultado.fatorAmpliacao))
                    }

                    textoReconhecido = resultado.texto.ifBlank { resultado.mensagem }
                    resultado.imagemFinal?.let { fotoOriginal = it }
                    // Tamanho real da foto final: confirma a resolução usada (a leitura cai
                    // para UXGA se o óculos recusar 5 MP) e substitui a linha de uma
                    // captura anterior, que iria errada para o relatório
                    infoCaptura = resultado.imagemFinal
                        ?.let { "Foto final da leitura completa: ${it.width}×${it.height}" } ?: ""
                    nitidez = ""   // era da foto anterior
                    fotoProcessada = null
                    // O portão não devolve os blocos: o acerto fica só no texto inteiro
                    ultimoOcr = ResultadoOcr(resultado.texto, resultado.qtdBlocos, resultado.qtdLinhas)
                    origemOcr = "captura final da leitura completa (${resolucao.uppercase()})"
                    acerto = null
                    status = resultado.mensagem
                }
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("4. Leitura completa (como o modo acessível)") }

        OutlinedButton(
            onClick = {
                val r = ultimoOcr
                if (r == null || r.texto.isBlank()) {
                    status = "Leia um texto primeiro."
                    return@OutlinedButton
                }
                executar("Calculando acerto...") {
                    acerto = withContext(Dispatchers.Default) {
                        MedidorAcerto.medir(r.texto, r.textosBlocos, textoReferencia)
                    }
                    status = "Acerto calculado."
                }
            },
            enabled = !carregando,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Calcular acerto (página de teste)") }

        Button(
            onClick = { tts.parar(); status = "Áudio interrompido." },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Parar áudio") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val o = fotoOriginal
                    if (o != null) {
                        scope.launch {
                            status = "Salvando..."
                            val ok = ImageSaver.salvarNaGaleria(context, o, "original")
                            // Cópia da foto + o relatório (OCR, tempos, conexão) com o
                            // mesmo nome, em Download/EyeCam/modo_dev, para casar foto e
                            // resultado sem depender da ordem das mensagens copiadas
                            val nome = "eyecam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            val png = withContext(Dispatchers.Default) {
                                java.io.ByteArrayOutputStream().also {
                                    o.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }.toByteArray()
                            }
                            val okPng = ArquivosTestes.salvar(context, PASTA_DEV, "$nome.png", "image/png", png) != null
                            val okTxt = ArquivosTestes.salvarTexto(context, PASTA_DEV, "$nome.txt", montarRelatorio()) != null
                            status = when {
                                okPng && okTxt -> "Salvo: Download/EyeCam/$PASTA_DEV/$nome (.png e .txt)" +
                                    (if (ok) " e na galeria." else ".")
                                ok -> "Original salva na galeria (falhou a cópia com o relatório)."
                                else -> "Falha ao salvar."
                            }
                        }
                    } else status = "Nenhuma foto original."
                },
                modifier = Modifier.weight(1f)
            ) { Text("Salvar original") }

            Button(
                onClick = {
                    val p = fotoProcessada
                    if (p != null) {
                        scope.launch {
                            status = "Salvando..."
                            val ok = ImageSaver.salvarNaGaleria(context, p, "processada")
                            status = if (ok) "Processada salva." else "Falha ao salvar."
                        }
                    } else status = "Nenhuma foto processada."
                },
                modifier = Modifier.weight(1f)
            ) { Text("Salvar processada") }
        }

        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Eye-cam", montarRelatorio()))
                    status = "Resultado copiado. Cole na planilha ou nas anotações."
                } else {
                    status = "Área de transferência indisponível."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Copiar resultado") }

        if (carregando) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Text(status, fontWeight = FontWeight.Medium)
        if (infoCaptura.isNotBlank()) Text(infoCaptura, style = MaterialTheme.typography.bodySmall)
        if (nitidez.isNotBlank()) Text(nitidez, style = MaterialTheme.typography.bodySmall)
        if (infoOcr.isNotBlank()) Text(infoOcr, style = MaterialTheme.typography.bodySmall)

        if (infoEnquadramento.isNotBlank()) {
            Text(infoEnquadramento, style = MaterialTheme.typography.bodySmall)
        }

        if (resumoLeitura.isNotBlank()) {
            Text(resumoLeitura, style = MaterialTheme.typography.bodySmall,
                 fontWeight = FontWeight.Medium)
        }

        if (logEstabilidade.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Sondagens:", fontWeight = FontWeight.SemiBold,
                         style = MaterialTheme.typography.bodySmall)
                    logEstabilidade.forEachIndexed { i, linha ->
                        Text("${i + 1}. $linha",
                             style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        acerto?.let { a ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Acerto contra o texto de referência:", fontWeight = FontWeight.SemiBold,
                         style = MaterialTheme.typography.bodySmall)
                    Text("Texto inteiro: %.1f%%".format(a.acertoTextoInteiro),
                         style = MaterialTheme.typography.bodySmall)
                    a.blocos.forEach { b ->
                        Text("Bloco ${b.indice} (${b.caracteres} car.): %.1f%% — \"%s…\""
                                 .format(b.acerto, b.inicio),
                             style = MaterialTheme.typography.bodySmall)
                    }
                    if (a.blocosIgnorados > 0) {
                        Text("${a.blocosIgnorados} bloco(s) curto(s) ignorado(s), como títulos",
                             style = MaterialTheme.typography.bodySmall)
                    }
                    Text("O acerto do texto inteiro só vale com um único parágrafo no quadro.",
                         style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (textoReconhecido.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(textoReconhecido, modifier = Modifier.padding(12.dp))
            }
        }
        fotoProcessada?.let {
            Text("Imagem processada:", fontWeight = FontWeight.SemiBold)
            Image(it.asImageBitmap(), "Imagem processada", Modifier.fillMaxWidth())
        }
        fotoOriginal?.let {
            Text("Imagem original:", fontWeight = FontWeight.SemiBold)
            Image(it.asImageBitmap(), "Imagem original", Modifier.fillMaxWidth())
        }
    }
}

/**
 * Linha de diagnóstico de uma captura, por exemplo: "UXGA 1600×1200 · 105,6 KB · total 6912
 * ms (firmware 6378 ms) · foco: travado"
 */
private fun descreverCaptura(captura: Captura, resolucao: String, autofoco: Boolean): String {
    val firmware = captura.tempoFirmwareMs?.let { " (firmware $it ms)" } ?: ""
    val zonas = captura.cabecalhos.entries
        .firstOrNull { it.key.equals("X-Eyecam-Foco-Zonas", ignoreCase = true) }?.value
    val foco = when {
        !autofoco -> "desligado"
        captura.focoTravado == true -> "travado"
        captura.focoTravado == false -> "não confirmado"
        else -> "?"
    } + (zonas?.let { " (zonas $it)" } ?: "")
    return "%s %d×%d · %.1f KB · total %d ms%s · foco: %s".format(
        resolucao.uppercase(), captura.bitmap.width, captura.bitmap.height,
        captura.bytes / 1024.0, captura.tempoTotalMs, firmware, foco
    )
}

@Composable
fun SwitchLinha(label: String, valor: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = valor, onCheckedChange = onChange)
    }
}
