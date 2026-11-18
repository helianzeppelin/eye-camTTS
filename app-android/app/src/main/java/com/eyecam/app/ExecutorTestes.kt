package com.eyecam.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** O que apagar ao refazer o último passo */
data class RefUltimo(val teste: String, val planilha: String, val arquivos: List<String>)

/**
 * Resultado de um passo, para a tela. foto que falhou fica registrada, mas o passo se
 * repete
 */
class ResultadoPasso(
    val concluido: Boolean,
    val titulo: String,
    val linhas: List<String>,
    val miniatura: Bitmap? = null,
    val desfazer: RefUltimo? = null
)

/**
 * Onde cada plano parou, entre aberturas do app: se o celular desligar no meio da bancada,
 * os testes continuam do mesmo ponto, na mesma pasta.
 */
class EstadoTestes(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("eyecam_testes", Context.MODE_PRIVATE)

    /** Pasta da sessão atual; cria uma se ainda não houver */
    fun sessaoAtual(): String = prefs.getString(CHAVE_SESSAO, null) ?: novaSessao()

    /** Nova pasta, todos os planos do início. Os arquivos antigos ficam */
    fun novaSessao(): String {
        val nome = FormatoTestes.nomeSessao(LocalDateTime.now())
        val plano = planoAtual
        prefs.edit {
            clear()
            putString(CHAVE_SESSAO, nome)
            plano?.let { putString(CHAVE_PLANO, it) }
        }
        return nome
    }

    var planoAtual: String?
        get() = prefs.getString(CHAVE_PLANO, null)
        set(valor) = prefs.edit { putString(CHAVE_PLANO, valor) }

    fun indice(plano: String): Int = prefs.getInt("indice_$plano", 0)

    fun salvarIndice(plano: String, indice: Int) = prefs.edit { putInt("indice_$plano", indice) }

    fun ultimo(plano: String): RefUltimo? {
        val partes = prefs.getString("ultimo_$plano", null)?.split('\n') ?: return null
        if (partes.size < 2) return null
        return RefUltimo(partes[0], partes[1], partes.drop(2).filter { it.isNotBlank() })
    }

    fun salvarUltimo(plano: String, ref: RefUltimo?) = prefs.edit {
        if (ref == null) remove("ultimo_$plano")
        else putString("ultimo_$plano", (listOf(ref.teste, ref.planilha) + ref.arquivos).joinToString("\n"))
    }

    /** Última linha do registro da placa já guardada nesta sessão */
    var linhaLog: Long
        get() = prefs.getLong(CHAVE_LOG, 0)
        set(valor) = prefs.edit { putLong(CHAVE_LOG, valor) }

    /** O arquivo sessao_info.txt já foi gravado nesta sessão */
    var infoSalva: Boolean
        get() = prefs.getBoolean(CHAVE_INFO, false)
        set(valor) = prefs.edit { putBoolean(CHAVE_INFO, valor) }

    private companion object {
        const val CHAVE_SESSAO = "sessao"
        const val CHAVE_PLANO = "plano_atual"
        const val CHAVE_LOG = "linha_log"
        const val CHAVE_INFO = "info_salva"
    }
}

/** Executa os passos do modo bancada. */
object ExecutorTestes {

    private const val PLANILHA_FOTOS = "fotos.csv"
    private const val PLANILHA_TAMANHOS = "tamanhos.csv"
    private const val PLANILHA_SEQUENCIAS = "sequencias.csv"
    private const val PLANILHA_CONEXOES = "conexoes.csv"
    private const val PLANILHA_LEITURAS = "leituras.csv"

    // Entre soltar a rede do óculos e pedi-la de novo: tempo para o celular voltar ao Wi-Fi
    // de casa, como acontece ao fechar e abrir o app
    private const val PAUSA_ENTRE_CONEXOES_MS = 3000L

    private val FORMATO_DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FORMATO_HORA = DateTimeFormatter.ofPattern("HHmmss")

    private var referencia: String? = null

    suspend fun executar(
        context: Context,
        estado: EstadoTestes,
        plano: PlanoTestes,
        passo: PassoTeste,
        onProgresso: (String) -> Unit
    ): ResultadoPasso {
        val app = context.applicationContext
        val sessao = estado.sessaoAtual()

        onProgresso("Conectando ao óculos...")
        if (!EyeCamClient.conectarNaRede(app) || !EyeCamClient.ping()) {
            return ResultadoPasso(
                concluido = false,
                titulo = "${passo.id}: óculos não encontrado",
                linhas = listOf(
                    RegrasConexao.orientacaoFalha(ConexaoOculos.conexaoAutomaticaDisponivel()),
                    "Nada foi salvo. Aperte de novo quando o óculos estiver ligado."
                )
            )
        }
        salvarInfoDaSessao(app, estado, sessao)

        // Clock da câmera como o passo pede, ou o padrão: um passo anterior (plano Foco)
        // pode ter deixado outro valor na placa.
        if (passo.tipo != TipoPasso.SEQUENCIA_CONEXAO) {
            val aceito = EyeCamClient.ajustar(PlanosTestes.ajusteDeClock(passo)) != null
            if (!aceito && PlanosTestes.usaClockDeTeste(passo)) {
                return ResultadoPasso(
                    concluido = false,
                    titulo = "${passo.id}: o óculos não aceitou o clock pedido",
                    linhas = listOf(
                        "Este passo exige o firmware v5.6.3 ou posterior (rota /tune com xclk).",
                        "Nada foi salvo."
                    )
                )
            }
        }

        return when (passo.tipo) {
            TipoPasso.FOTO -> foto(app, estado, sessao, plano, passo, onProgresso)
            TipoPasso.SEQUENCIA_FOTOS -> sequenciaFotos(app, estado, sessao, plano, passo, onProgresso)
            TipoPasso.SEQUENCIA_CONEXAO -> sequenciaConexao(app, estado, sessao, plano, passo, onProgresso)
            TipoPasso.SEQUENCIA_LEITURA -> sequenciaLeitura(app, estado, sessao, plano, passo, onProgresso)
        }
    }

    /** Apaga os arquivos e as linhas da planilha do último passo */
    suspend fun desfazer(context: Context, sessao: String, ref: RefUltimo) {
        ref.arquivos.forEach { ArquivosTestes.apagar(context, it) }
        ArquivosTestes.removerDaPlanilha(context, sessao, ref.planilha, ref.teste)
    }

    // ------------------------------------------------------------------
    // FOTO: um aperto, uma foto, um registro
    // ------------------------------------------------------------------

    private suspend fun foto(
        app: Context,
        estado: EstadoTestes,
        sessao: String,
        plano: PlanoTestes,
        passo: PassoTeste,
        onProgresso: (String) -> Unit
    ): ResultadoPasso {
        val dataHora = agora()
        val rotulo = PlanosTestes.rotulo(passo.resolucao)
        val rotuloFoco = PlanosTestes.rotulo(passo.resolucaoFoco)

        var foco: TentativaCaptura? = null
        if (passo.focarAntes) {
            onProgresso("Focando (foto $rotuloFoco com autofoco)...")
            foco = EyeCamClient.tentarCaptura(passo.resolucaoFoco, autofoco = true)
        }
        onProgresso("Fotografando em $rotulo...")
        val final = EyeCamClient.tentarCaptura(passo.resolucao, passo.autofocoNaFinal)
        val captura = final.captura
        val nitidezCentro = captura?.let { ImageProcessor.medirNitidezCentro(it.bitmap) }

        var ocr: ResultadoOcrAdaptativo? = null
        if (captura != null && passo.lerTexto) {
            onProgresso("Lendo o texto (com e sem ampliação)...")
            ocr = OcrService.reconhecerAdaptativo(captura.bitmap)
        }
        val ref = textoReferencia(app)
        // Escala esperada pela distância da montagem: se os títulos não forem lidos, o
        // corpo de cada parágrafo é deduzido pela altura das linhas
        val escala = passo.distanciaCm?.let { d -> captura?.let { PlanosTestes.pxPorMmEsperado(d, maxOf(it.bitmap.width, it.bitmap.height)) } }
        // Na página de tamanhos não há os blocos da página v2: o acerto é por frase
        val porCorpoSem = if (passo.paginaTamanhos) emptyList()
            else ocr?.let { o -> acertoPorCorpo(o.semAmpliacao, ref, escala) }.orEmpty()
        val porCorpoCom = if (passo.paginaTamanhos) emptyList()
            else ocr?.let { o -> acertoPorCorpo(o.resultado, ref, escala?.times(o.fatorAmpliacao)) }.orEmpty()
        val tamanhos = if (passo.paginaTamanhos && ocr != null)
            AcertoTamanhos.medir(ocr.resultado.texto, frasesTamanhos(app)) else emptyList()
        val menorLido = AcertoTamanhos.menorCorpoLido(tamanhos)
        val palavraLida = passo.palavraEsperada?.let { palavraNaFoto(ocr?.resultado?.texto.orEmpty()) }

        onProgresso("Salvando...")
        val (log, reiniciou) = lerLogDaPlaca(estado)
        val sucesso = captura != null
        val nome = if (sucesso) passo.id else "${passo.id}-falha-${LocalDateTime.now().format(FORMATO_HORA)}"
        val arquivos = mutableListOf<String>()

        if (captura != null && passo.salvarFoto) {
            ArquivosTestes.salvar(app, sessao, "$nome.jpg", "image/jpeg", captura.jpeg)?.let { arquivos += it }
        }

        val registro = TextoRegistro().apply {
            secao("teste")
            campo("id", passo.id)
            campo("plano", plano.nome)
            campo("data_hora", dataHora)
            campo("montagem", passo.montagem)
            campo("condicoes", PlanosTestes.descricaoAutomatica(passo))
            campo("resultado", if (sucesso) "ok" else "falha")
            if (reiniciou) campo("aviso", "a placa reiniciou desde o teste anterior (veja motivo_reset no log)")
            foco?.let { secao("foto_de_foco"); campo("resolucao_foco", passo.resolucaoFoco); camposCaptura(this, it) }
            secao("foto_final")
            camposCaptura(this, final)
            campo("nitidez_centro", FormatoTestes.decimal(nitidezCentro, 0))
            ocr?.let { o ->
                secao("ocr")
                campo("altura_linha_px", FormatoTestes.decimal(o.alturaLinhaOriginalPx, 0))
                campo("fator_tentado", FormatoTestes.decimal(o.fatorTentado, 2))
                campo("fator_escolhido", FormatoTestes.decimal(o.fatorAmpliacao, 2))
                campo("tempo_sem_ampliacao_ms", o.tempoSemAmpliacaoMs)
                campo("tempo_total_ms", o.tempoTotalMs)
                escala?.let { campo("escala_esperada_px_mm", FormatoTestes.decimal(it, 2)) }
                campo("corpo_por", FormatoTestes.origemDosCorpos(porCorpoCom).ifBlank { "nenhum corpo encontrado" })
                if (passo.paginaTamanhos) {
                    secao("acerto_por_tamanho")
                    tamanhos.forEach { campo("${it.corpo}_pt", FormatoTestes.decimal(it.acerto, 0)) }
                    campo("menor_corpo_lido", menorLido?.let { "$it pt" } ?: "nenhum")
                } else {
                    secao("acerto_sem_ampliacao")
                    camposAcerto(this, porCorpoSem)
                    secao("acerto_com_ampliacao")
                    camposAcerto(this, porCorpoCom)
                }
            }
            passo.palavraEsperada?.let {
                secao("foto_atrasada")
                campo("palavra_esperada", it)
                campo("palavra_lida", palavraLida)
            }
            secao("log_da_placa")
            bloco(log)
            ocr?.let { o ->
                secao("texto_sem_ampliacao")
                bloco(o.semAmpliacao.texto.ifBlank { "(nenhum texto)" })
                if (o.fatorTentado > 1.0) {
                    secao("texto_com_ampliacao")
                    bloco(o.resultado.texto.ifBlank { "(nenhum texto)" })
                }
            }
        }
        ArquivosTestes.salvarTexto(app, sessao, "$nome.txt", registro.toString())?.let { arquivos += it }

        val focoDaLinha = foco ?: final
        val linha = mutableMapOf(
            "teste" to nome,
            "plano" to plano.nome,
            "resultado" to if (sucesso) "ok" else "falha",
            "data_hora" to dataHora,
            "resolucao_pedida" to passo.resolucao,
            "autofoco_final" to simNao(passo.autofocoNaFinal),
            "foco_resolucao" to when {
                passo.focarAntes -> passo.resolucaoFoco
                passo.autofocoNaFinal -> passo.resolucao
                else -> ""
            },
            "foco_total_app_ms" to (foco?.tempoTotalMs?.toString() ?: ""),
            "nitidez_centro" to FormatoTestes.decimal(nitidezCentro, 0),
            "ocr_sem_ms" to (ocr?.tempoSemAmpliacaoMs?.toString() ?: ""),
            "ocr_total_ms" to (ocr?.tempoTotalMs?.toString() ?: ""),
            "fator_ampliacao" to FormatoTestes.decimal(ocr?.fatorAmpliacao, 2),
            "altura_linha_px" to FormatoTestes.decimal(ocr?.alturaLinhaOriginalPx, 0),
            "acerto_inteiro_sem" to FormatoTestes.decimal(ocr?.let { Estabilidade.similaridade(it.semAmpliacao.texto, ref) }),
            "acerto_inteiro_com" to FormatoTestes.decimal(ocr?.let { Estabilidade.similaridade(it.resultado.texto, ref) }),
            "corpo_por" to FormatoTestes.origemDosCorpos(porCorpoCom),
            "fracao_10" to FormatoTestes.decimal(porCorpoCom.firstOrNull { it.corpo == 10 }?.fracaoDoTexto, 2),
            "fracao_9" to FormatoTestes.decimal(porCorpoCom.firstOrNull { it.corpo == 9 }?.fracaoDoTexto, 2),
            "palavra_esperada" to (passo.palavraEsperada ?: ""),
            "palavra_lida" to (palavraLida ?: ""),
            "erro" to (final.erro ?: "")
        )
        linha += colunasCaptura(final)
        linha += colunasFoco(focoDaLinha)
        linha += FormatoTestes.colunasDeAcerto("sem", porCorpoSem)
        linha += FormatoTestes.colunasDeAcerto("com", porCorpoCom)
        ArquivosTestes.acrescentarPlanilha(app, sessao, PLANILHA_FOTOS, FormatoTestes.COLUNAS_FOTOS, listOf(linha))
        if (passo.paginaTamanhos) {
            val linhaTamanhos = mutableMapOf(
                "teste" to nome,
                "data_hora" to dataHora,
                "resolucao_pedida" to passo.resolucao,
                "distancia_cm" to (passo.distanciaCm?.toString() ?: ""),
                "menor_corpo_lido" to (menorLido?.toString() ?: ""),
                "fator_ampliacao" to FormatoTestes.decimal(ocr?.fatorAmpliacao, 2),
                "erro" to (final.erro ?: "")
            )
            tamanhos.forEach { linhaTamanhos["t${it.corpo}"] = FormatoTestes.decimal(it.acerto, 0) }
            ArquivosTestes.acrescentarPlanilha(
                app, sessao, PLANILHA_TAMANHOS, FormatoTestes.COLUNAS_TAMANHOS, listOf(linhaTamanhos)
            )
        }

        // --- Resumo para a tela ---
        val linhasTela = mutableListOf<String>()
        if (reiniciou) linhasTela += "⚠ A placa reiniciou desde o teste anterior"
        if (captura == null) {
            linhasTela += "Falha: ${final.erro ?: "sem resposta"}"
            linhasTela += "Registrada como $nome. Aperte de novo para repetir o passo."
        } else {
            linhasTela += "Foto ${captura.bitmap.width}×${captura.bitmap.height} · ${captura.bytes / 1024} KB · " +
                "${FormatoTestes.segundos(final.tempoTotalMs)} (placa ${FormatoTestes.segundos(captura.tempoFirmwareMs)})"
            foco?.let { linhasTela += "Foco em $rotuloFoco: ${descreverFoco(it)}" }
            if (!passo.focarAntes && passo.autofocoNaFinal) linhasTela += "Foco: ${descreverFoco(final)}"
            linhasTela += "Quadro: ${final.cabecalho("Captura-Ms") ?: "—"} ms · FB-OVF: ${final.cabecalho("Ovf") ?: "—"}" +
                (final.cabecalho("Temp-C")?.let { " · chip $it °C" } ?: "")
            descreverRecuperacao(final)?.let { linhasTela += it }
            nitidezCentro?.let { linhasTela += "Nitidez do centro: ${FormatoTestes.decimal(it, 0)} (só compara fotos da mesma cena)" }
            if (passo.palavraEsperada != null) {
                linhasTela += descreverPalavra(passo.palavraEsperada, palavraLida)
            } else if (passo.paginaTamanhos) {
                linhasTela += "Acerto por tamanho: " +
                    tamanhos.joinToString(" · ") { "${it.corpo} pt ${FormatoTestes.decimal(it.acerto, 0)}%" }
                linhasTela += "Menor tamanho lido (≥ 80%): " + (menorLido?.let { "$it pt" } ?: "nenhum")
            } else if (ocr != null) {
                val com = FormatoTestes.resumoAcertos(porCorpoCom)
                if (com == null) {
                    linhasTela += "Nenhum corpo de letra identificado (nem pelos títulos, nem pela altura das linhas): " +
                        "confira o enquadramento"
                } else if (ocr.fatorTentado > 1.0) {
                    linhasTela += "Com ampliação (${FormatoTestes.decimal(ocr.fatorAmpliacao, 2)}×): $com"
                    FormatoTestes.resumoAcertos(porCorpoSem)?.let { linhasTela += "Sem ampliação: $it" }
                } else {
                    linhasTela += "Acerto: $com"
                }
            }
        }

        return ResultadoPasso(
            concluido = sucesso,
            titulo = if (sucesso) "${passo.id} ✓ salvo" else "${passo.id}: a foto falhou",
            linhas = linhasTela,
            miniatura = captura?.bitmap,
            desfazer = if (sucesso) RefUltimo(passo.id, PLANILHA_FOTOS, arquivos) else null
        )
    }

    // ------------------------------------------------------------------
    // SEQUÊNCIA DE FOTOS: falhas, FB-OVF e tempos com um só aperto
    // ------------------------------------------------------------------

    private suspend fun sequenciaFotos(
        app: Context,
        estado: EstadoTestes,
        sessao: String,
        plano: PlanoTestes,
        passo: PassoTeste,
        onProgresso: (String) -> Unit
    ): ResultadoPasso {
        val rotulo = PlanosTestes.rotulo(passo.resolucao)
        val linhas = mutableListOf<Map<String, String>>()
        val registro = TextoRegistro().apply {
            secao("teste")
            campo("id", passo.id)
            campo("plano", plano.nome)
            campo("data_hora", agora())
            campo("montagem", passo.montagem)
            campo("condicoes", PlanosTestes.descricaoAutomatica(passo))
        }

        if (passo.focarAntes) {
            onProgresso("Focando (foto ${PlanosTestes.rotulo(passo.resolucaoFoco)} com autofoco)...")
            val foco = EyeCamClient.tentarCaptura(passo.resolucaoFoco, autofoco = true)
            registro.secao("foto_de_foco")
            camposCaptura(registro, foco)
            linhas += linhaSequencia(passo, "foco", passo.resolucaoFoco, true, foco)
        }

        val tentativas = mutableListOf<TentativaCaptura>()
        var ultimaFoto: Captura? = null
        for (i in 1..passo.repeticoes) {
            onProgresso("Foto $i de ${passo.repeticoes} ($rotulo)...")
            val t = EyeCamClient.tentarCaptura(passo.resolucao, passo.autofocoNaFinal)
            tentativas += t
            t.captura?.let { ultimaFoto = it }
            registro.secao("foto_$i")
            camposCaptura(registro, t)
            linhas += linhaSequencia(passo, i.toString(), passo.resolucao, passo.autofocoNaFinal, t)
        }

        onProgresso("Salvando...")
        val (log, reiniciou) = lerLogDaPlaca(estado)
        val boas = tentativas.filter { it.captura != null }
        val ovf = tentativas.sumOf { it.cabecalho("Ovf")?.toIntOrNull() ?: 0 }
        val ovfInformado = tentativas.any { it.cabecalho("Ovf") != null }
        val resumo = mutableListOf(
            "${boas.size} de ${tentativas.size} fotos ok · FB-OVF: ${if (ovfInformado) ovf.toString() else "— (firmware sem contagem)"}",
            "Tempo total: ${FormatoTestes.estatisticas(boas.map { it.tempoTotalMs })}",
            "Na placa: ${FormatoTestes.estatisticas(boas.mapNotNull { it.captura?.tempoFirmwareMs })}",
            "Quadro: ${FormatoTestes.estatisticas(tentativas.mapNotNull { it.cabecalho("Captura-Ms")?.toLongOrNull() })}"
        )
        if (passo.autofocoNaFinal) {
            val travou = tentativas.count { it.cabecalho("Foco") == "travado" }
            val tempos = tentativas.mapNotNull { it.cabecalho("Foco-Ms")?.toLongOrNull() }
            resumo += "Foco confirmado em $travou de ${tentativas.size} · ${FormatoTestes.estatisticas(tempos)}"
        }
        val recuperadas = tentativas.mapNotNull { it.cabecalho("Recuperacao") }.filter { it != "nenhuma" }
        if (recuperadas.isNotEmpty()) {
            resumo += "Recuperações da câmera: " + recuperadas.groupingBy { it }.eachCount()
                .entries.joinToString(", ") { "${it.key} ${it.value}×" }
        }
        val temperaturas = tentativas.mapNotNull { it.cabecalho("Temp-C")?.replace(',', '.')?.toDoubleOrNull() }
        if (temperaturas.isNotEmpty()) {
            resumo += "Chip: de ${FormatoTestes.decimal(temperaturas.min())} a ${FormatoTestes.decimal(temperaturas.max())} °C"
        }
        if (reiniciou) resumo.add(0, "⚠ A placa reiniciou durante ou antes da sequência")

        registro.secao("resumo")
        resumo.forEach { registro.bloco(it) }
        registro.secao("log_da_placa")
        registro.bloco(log)

        val arquivos = mutableListOf<String>()
        val foto = ultimaFoto
        if (foto != null && passo.salvarFoto) {
            ArquivosTestes.salvar(app, sessao, "${passo.id}-ultima.jpg", "image/jpeg", foto.jpeg)?.let { arquivos += it }
        }
        ArquivosTestes.salvarTexto(app, sessao, "${passo.id}.txt", registro.toString())?.let { arquivos += it }
        ArquivosTestes.acrescentarPlanilha(app, sessao, PLANILHA_SEQUENCIAS, FormatoTestes.COLUNAS_SEQUENCIAS, linhas)

        return ResultadoPasso(
            concluido = true,
            titulo = "${passo.id} ✓ salvo",
            linhas = resumo,
            miniatura = foto?.bitmap,
            desfazer = RefUltimo(passo.id, PLANILHA_SEQUENCIAS, arquivos)
        )
    }

    // ------------------------------------------------------------------
    // SEQUÊNCIA DE CONEXÕES: tempo da conexão automática
    // ------------------------------------------------------------------

    private suspend fun sequenciaConexao(
        app: Context,
        estado: EstadoTestes,
        sessao: String,
        plano: PlanoTestes,
        passo: PassoTeste,
        onProgresso: (String) -> Unit
    ): ResultadoPasso {
        val linhas = mutableListOf<Map<String, String>>()
        val tempos = mutableListOf<Long>()
        val formas = mutableSetOf<String>()
        val registro = TextoRegistro().apply {
            secao("teste")
            campo("id", passo.id)
            campo("plano", plano.nome)
            campo("data_hora", agora())
            campo("condicoes", PlanosTestes.descricaoAutomatica(passo))
            secao("conexoes")
        }

        for (i in 1..passo.repeticoes) {
            onProgresso("Conexão $i de ${passo.repeticoes}: soltando a rede...")
            EyeCamClient.desconectar(app)
            delay(PAUSA_ENTRE_CONEXOES_MS)
            onProgresso("Conexão $i de ${passo.repeticoes}: conectando...")
            val inicio = System.currentTimeMillis()
            val ok = EyeCamClient.conectarNaRede(app) && EyeCamClient.ping()
            val tempo = System.currentTimeMillis() - inicio
            val forma = RegrasConexao.descrever(ConexaoOculos.ultimaForma)
            if (ok) { tempos += tempo; formas += forma }
            registro.bloco("$i: ${if (ok) "ok" else "falhou"} em $tempo ms ($forma)")
            linhas += mapOf(
                "teste" to passo.id, "repeticao" to i.toString(), "resultado" to if (ok) "ok" else "falha",
                "data_hora" to agora(), "tempo_ms" to tempo.toString(), "forma" to forma
            )
        }

        // A última conexão pode ter falhado: tenta uma vez mais para ler o log
        if (!EyeCamClient.ping()) EyeCamClient.conectarNaRede(app)
        val (log, _) = lerLogDaPlaca(estado)
        val resumo = mutableListOf(
            "${tempos.size} de ${passo.repeticoes} conexões ok",
            "Tempo: ${FormatoTestes.estatisticas(tempos)}",
            "Forma: ${formas.joinToString(", ").ifBlank { "—" }}"
        )
        if (formas.any { it == RegrasConexao.descrever(FormaConexao.REDE_JA_CONECTADA) }) {
            resumo += "⚠ O celular já estava na rede do óculos (conexão manual?): o tempo medido é só o do ping"
        }
        registro.secao("resumo")
        resumo.forEach { registro.bloco(it) }
        registro.secao("log_da_placa")
        registro.bloco(log)

        val arquivos = listOfNotNull(ArquivosTestes.salvarTexto(app, sessao, "${passo.id}.txt", registro.toString()))
        ArquivosTestes.acrescentarPlanilha(app, sessao, PLANILHA_CONEXOES, FormatoTestes.COLUNAS_CONEXOES, linhas)

        return ResultadoPasso(
            concluido = true,
            titulo = "${passo.id} ✓ salvo",
            linhas = resumo,
            desfazer = RefUltimo(passo.id, PLANILHA_CONEXOES, arquivos)
        )
    }

    // ------------------------------------------------------------------
    // SEQUÊNCIA DE LEITURAS: tempo da leitura completa
    // ------------------------------------------------------------------

    private suspend fun sequenciaLeitura(
        app: Context,
        estado: EstadoTestes,
        sessao: String,
        plano: PlanoTestes,
        passo: PassoTeste,
        onProgresso: (String) -> Unit
    ): ResultadoPasso {
        // O mesmo fluxo do toque duplo do modo acessível
        val config = ConfigLeitura(
            resolucaoSondagem = "svga",
            resolucaoFinal = passo.resolucao,
            maxSondagens = 0,
            usarEnquadramento = false,
            ampliacaoAdaptativa = true
        )
        val linhas = mutableListOf<Map<String, String>>()
        val tempos = mutableListOf<Long>()
        var quedas = 0
        var ultimaImagem: Bitmap? = null
        val registro = TextoRegistro().apply {
            secao("teste")
            campo("id", passo.id)
            campo("plano", plano.nome)
            campo("data_hora", agora())
            campo("montagem", passo.montagem)
            campo("condicoes", PlanosTestes.descricaoAutomatica(passo))
            secao("leituras")
        }

        for (i in 1..passo.repeticoes) {
            onProgresso("Leitura $i de ${passo.repeticoes} (${PlanosTestes.rotulo(passo.resolucao)})...")
            val detalhes = mutableListOf<String>()
            val r = LeitorEyeCam.ler(app, config) { p -> p.detalhe?.let { detalhes += it } }
            if (r.sucesso) tempos += r.tempoTotalMs
            if (detalhes.any { it.contains("repetida em") }) quedas++
            val imagem = r.imagemFinal
            if (imagem != null) ultimaImagem = imagem
            val tamanho = imagem?.let { "${it.width}×${it.height}" } ?: "—"
            val caracteres = Estabilidade.normalizar(r.texto).length
            val observacao = (detalhes.filter { it.contains("repetida em") } +
                if (r.sucesso) emptyList() else listOf(r.mensagem)).joinToString(" | ")
            registro.bloco(
                "$i: ${if (r.sucesso) "ok" else "falhou"} em ${r.tempoTotalMs} ms · foto $tamanho · " +
                    "$caracteres caracteres · ampliação ${FormatoTestes.decimal(r.fatorAmpliacao, 2)}" +
                    observacao.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            )
            linhas += mapOf(
                "teste" to passo.id, "repeticao" to i.toString(), "resultado" to if (r.sucesso) "ok" else "falha",
                "data_hora" to agora(), "resolucao_pedida" to passo.resolucao,
                "largura" to (imagem?.width?.toString() ?: ""), "altura" to (imagem?.height?.toString() ?: ""),
                "tempo_total_ms" to r.tempoTotalMs.toString(), "caracteres" to caracteres.toString(),
                "fator_ampliacao" to FormatoTestes.decimal(r.fatorAmpliacao, 2), "observacao" to observacao
            )
        }

        onProgresso("Salvando...")
        val (log, reiniciou) = lerLogDaPlaca(estado)
        val resumo = mutableListOf(
            "${tempos.size} de ${passo.repeticoes} leituras ok",
            "Tempo da leitura completa: ${FormatoTestes.estatisticas(tempos)}"
        )
        if (quedas > 0) resumo += "⚠ Em $quedas leitura(s) a foto em ${PlanosTestes.rotulo(passo.resolucao)} falhou e foi repetida em UXGA"
        if (reiniciou) resumo.add(0, "⚠ A placa reiniciou durante ou antes da sequência")
        registro.secao("resumo")
        resumo.forEach { registro.bloco(it) }
        registro.secao("log_da_placa")
        registro.bloco(log)

        val arquivos = listOfNotNull(ArquivosTestes.salvarTexto(app, sessao, "${passo.id}.txt", registro.toString()))
        ArquivosTestes.acrescentarPlanilha(app, sessao, PLANILHA_LEITURAS, FormatoTestes.COLUNAS_LEITURAS, linhas)

        return ResultadoPasso(
            concluido = true,
            titulo = "${passo.id} ✓ salvo",
            linhas = resumo,
            miniatura = ultimaImagem,
            desfazer = RefUltimo(passo.id, PLANILHA_LEITURAS, arquivos)
        )
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    /** Registro da placa desde o último teste. */
    private suspend fun lerLogDaPlaca(estado: EstadoTestes): Pair<String, Boolean> {
        val desde = estado.linhaLog
        var resposta = EyeCamClient.obterLog(desde)
            ?: return "(sem o registro da placa: firmware anterior à v5.6, ou o óculos não respondeu)" to false
        var reiniciou = false
        if ((resposta.ultimaLinha ?: 0) < desde) {
            reiniciou = true
            resposta = EyeCamClient.obterLog(0) ?: resposta
        }
        resposta.ultimaLinha?.let { estado.linhaLog = it }
        return resposta.texto.ifBlank { "(nenhuma mensagem nova)" } to reiniciou
    }

    /** Uma vez por sessão: aparelho, app, conexão e o /info da placa */
    private suspend fun salvarInfoDaSessao(app: Context, estado: EstadoTestes, sessao: String) {
        if (estado.infoSalva) return
        val info = EyeCamClient.obterInfo()
        val registro = TextoRegistro().apply {
            secao("sessao")
            campo("pasta", ArquivosTestes.descreverPasta(sessao))
            campo("data_hora", agora())
            campo("app", BuildConfig.VERSION_NAME)
            campo("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            campo("aparelho", "${Build.MANUFACTURER} ${Build.MODEL}")
            secao("conexao")
            bloco(ConexaoOculos.diagnostico(app))
            secao("info_da_placa")
            bloco(info ?: "(o óculos não respondeu ao /info)")
        }
        if (ArquivosTestes.salvarTexto(app, sessao, "sessao_info.txt", registro.toString()) != null) {
            estado.infoSalva = true
        }
    }

    private fun camposCaptura(registro: TextoRegistro, t: TentativaCaptura) {
        registro.campo("resultado", if (t.captura != null) "ok" else "falha")
        registro.campo("http", t.codigoHttp)
        t.erro?.let { registro.campo("erro", it) }
        registro.campo("tempo_total_app_ms", t.tempoTotalMs)
        t.captura?.let {
            registro.campo("bytes", it.bytes)
            registro.campo("imagem", "${it.bitmap.width}x${it.bitmap.height}")
        }
        t.cabecalhos.toSortedMap().forEach { (chave, valor) -> registro.campo(chave, valor) }
    }

    private fun camposAcerto(registro: TextoRegistro, porCorpo: List<AcertoCorpo>) {
        if (porCorpo.isEmpty()) {
            registro.campo("corpos", "nenhum corpo identificado (nem pelos títulos, nem pela altura das linhas)")
            return
        }
        porCorpo.forEach { a ->
            registro.campo(
                "${a.corpo}_pt",
                "${FormatoTestes.decimal(a.acerto)}% (${a.caracteres} caracteres, " +
                    "${FormatoTestes.decimal(a.fracaoDoTexto * 100, 0)}% do texto" +
                    (a.alturaLinhaPx?.let { ", pela altura das linhas: ${FormatoTestes.decimal(it, 0)} px" } ?: "") + ")"
            )
        }
    }

    /**
     * Acerto por corpo pelos títulos da página; sem título legível e com a escala da
     * montagem conhecida, pela altura das linhas
     */
    private fun acertoPorCorpo(ocr: ResultadoOcr, ref: String, escalaPxMm: Double?): List<AcertoCorpo> =
        MedidorAcerto.medirPorCorpo(ocr.textosBlocos, ref).ifEmpty {
            escalaPxMm?.let { MedidorAcerto.medirPorAltura(ocr.linhas, ref, it) }.orEmpty()
        }

    /** Linha da tela quando o firmware precisou recuperar a câmera (v5.6.4) */
    private fun descreverRecuperacao(t: TentativaCaptura): String? = when (t.cabecalho("Recuperacao")) {
        "compressao" -> "⚠ A câmera não entregou o quadro; saiu com o JPEG mais comprimido (qualidade ${t.cabecalho("Qualidade") ?: "?"})"
        "reinicio" -> "⚠ A câmera não entregou o quadro; saiu depois de reiniciar a câmera e focar de novo"
        "falhou" -> "A câmera não entregou o quadro nem depois de reiniciada"
        else -> null
    }

    /** Colunas da planilha que vêm da foto final */
    private fun colunasCaptura(t: TentativaCaptura): Map<String, String> = mapOf(
        "largura" to (t.captura?.bitmap?.width?.toString() ?: ""),
        "altura" to (t.captura?.bitmap?.height?.toString() ?: ""),
        "bytes" to (t.captura?.bytes?.toString() ?: ""),
        "total_ms" to t.tempoTotalMs.toString(),
        "placa_ms" to (t.cabecalho("Tempo-Ms") ?: ""),
        "ajuste_ms" to (t.cabecalho("Ajuste-Ms") ?: ""),
        "descarte_ms" to (t.cabecalho("Descarte-Ms") ?: ""),
        "quadro_ms" to (t.cabecalho("Captura-Ms") ?: ""),
        "quadro_tentativas" to (t.cabecalho("Quadro-Tentativas") ?: ""),
        "recuperacao" to (t.cabecalho("Recuperacao") ?: ""),
        "fb_ovf" to (t.cabecalho("Ovf") ?: ""),
        "avisos_camera" to (t.cabecalho("Avisos-Cam") ?: ""),
        "captura_n" to (t.cabecalho("Captura-N") ?: ""),
        "xclk_mhz" to (t.cabecalho("Xclk-Mhz") ?: ""),
        "noturno" to (t.cabecalho("Noturno") ?: ""),
        // Cabeçalho com ponto decimal ("41.3"); a planilha usa vírgula
        "temperatura_c" to (t.cabecalho("Temp-C")?.replace('.', ',') ?: "")
    )

    /** Colunas do foco; vazias quando o foco não foi pedido nessa foto */
    private fun colunasFoco(t: TentativaCaptura): Map<String, String> {
        val tentativas = t.cabecalho("Foco-Tentativas")
        val pedido = tentativas != null && tentativas != "0"
        return mapOf(
            "foco_travou" to if (pedido) simNao(t.cabecalho("Foco") == "travado") else "",
            "foco_ms" to if (pedido) t.cabecalho("Foco-Ms").orEmpty() else "",
            "foco_tentativas" to if (pedido) tentativas.orEmpty() else "",
            "foco_zonas" to if (pedido) t.cabecalho("Foco-Zonas").orEmpty() else ""
        )
    }

    private fun linhaSequencia(
        passo: PassoTeste,
        repeticao: String,
        resolucao: String,
        autofoco: Boolean,
        t: TentativaCaptura
    ): Map<String, String> = mapOf(
        "teste" to passo.id,
        "repeticao" to repeticao,
        "resultado" to if (t.captura != null) "ok" else "falha",
        "data_hora" to agora(),
        "resolucao" to resolucao,
        "autofoco" to simNao(autofoco),
        "erro" to (t.erro ?: "")
    ) + colunasCaptura(t) + colunasFoco(t)

    private fun descreverFoco(t: TentativaCaptura): String {
        val estado = when (t.cabecalho("Foco")) {
            "travado" -> "travou"
            // v5.6.3: o foco terminou, mas o sensor disse que nenhuma zona focou
            "nao-confirmado" -> "NÃO confirmado pelo sensor"
            else -> "NÃO travou"
        }
        val ms = t.cabecalho("Foco-Ms")
        val tentativas = t.cabecalho("Foco-Tentativas")
        return estado +
            (ms?.let { " em $it ms" } ?: "") +
            (tentativas?.let { " ($it tentativa${if (it == "1") "" else "s"})" } ?: "") +
            (t.cabecalho("Foco-Zonas")?.let { " · zonas $it" } ?: "")
    }

    private fun descreverPalavra(esperada: String, lida: String?): String = when {
        lida == esperada -> "Palavra lida: $esperada ✓"
        lida == "nenhuma" -> "Nenhuma das duas palavras lida: confira a foto"
        lida == "as duas" -> "As duas palavras na foto: confira"
        else -> "Palavra lida: $lida ✗ — a foto mostra a folha anterior"
    }

    /** Qual das duas folhas do teste da foto atrasada aparece no texto */
    private fun palavraNaFoto(texto: String): String {
        val palavras = Estabilidade.normalizar(texto).split(' ').toSet()
        val azul = "azul" in palavras
        val verde = "verde" in palavras
        return when {
            azul && verde -> "as duas"
            azul -> "AZUL"
            verde -> "VERDE"
            else -> "nenhuma"
        }
    }

    private fun textoReferencia(context: Context): String =
        referencia ?: context.assets.open("texto_referencia.txt").bufferedReader()
            .use { it.readText() }.also { referencia = it }

    private var frasesTamanhos: List<AcertoTamanhos.Frase>? = null

    /** Frases da página de tamanhos (asset copiado de docs/testes/pagina_teste_tamanhos.txt) */
    private fun frasesTamanhos(context: Context): List<AcertoTamanhos.Frase> =
        frasesTamanhos ?: AcertoTamanhos.lerFrases(
            context.assets.open("pagina_tamanhos.txt").bufferedReader().use { it.readText() }
        ).also { frasesTamanhos = it }

    private fun simNao(valor: Boolean) = if (valor) "sim" else "não"

    private fun agora(): String = LocalDateTime.now().format(FORMATO_DATA)
}
