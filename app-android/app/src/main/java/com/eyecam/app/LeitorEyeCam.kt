package com.eyecam.app

import android.content.Context
import android.graphics.Bitmap

/** Parâmetros do processo de leitura. estabilidade. */
data class ConfigLeitura(
    val resolucaoSondagem: String = "svga",
    val resolucaoFinal: String = "uxga",
    val maxSondagens: Int = 5,
    val sondagensSemTexto: Int = 2,
    val amostrasEstaveis: Int = 2,
    val limiarSimilaridade: Int = Estabilidade.LIMIAR_SIMILARIDADE_PADRAO,
    val toleranciaCaixa: Double = Estabilidade.TOLERANCIA_CAIXA_PADRAO,
    val exigirEstabilidadeTextual: Boolean = true,
    val usarEnquadramento: Boolean = false,
    val ampliacaoAdaptativa: Boolean = true
)

/** Aviso de progresso emitido durante a leitura. */
data class ProgressoLeitura(
    val statusVisual: String,
    val falar: String? = null,
    val detalhe: String? = null,
    /** A foto final chegou: a página já pode ser baixada. */
    val fotoTirada: Boolean = false
)

/** Resultado final de uma leitura. */
data class ResultadoLeitura(
    val sucesso: Boolean,
    val texto: String,
    val mensagem: String,
    val estabilizou: Boolean,
    val sondagensUsadas: Int,
    val tempoTotalMs: Long,
    val qtdBlocos: Int = 0,
    val qtdLinhas: Int = 0,
    val imagemFinal: Bitmap? = null,
    val fatorAmpliacao: Double = 1.0,
    val alturaLinhaPx: Double? = null
)

/** Orquestra a leitura completa com portão de estabilidade. */
object LeitorEyeCam {

    // Capturas seguidas sem resposta antes de desistir
    private const val MAX_FALHAS_SEGUIDAS = 2

    // Resolução de reserva da captura final.
    private const val RESOLUCAO_RESERVA = "uxga"
    private val RESOLUCOES_ACIMA_DE_UXGA = setOf("qxga", "qsxga", "5mp")

    /** Executa uma leitura completa. */
    suspend fun ler(
        context: Context,
        config: ConfigLeitura = ConfigLeitura(),
        onProgresso: (ProgressoLeitura) -> Unit = {}
    ): ResultadoLeitura {

        val inicio = System.currentTimeMillis()

        // --- Passo 1: rede ---
        // Avisa antes quando a conexão vai demorar (pedido ao Android) ou
        // depende do usuário (confirmação na primeira vez); se o celular já
        // está na rede do óculos, não diz nada
        val aviso = RegrasConexao.avisoAntesDeConectar(ConexaoOculos.formaPrevista(context))
        onProgresso(ProgressoLeitura("Conectando ao óculos...", aviso))

        // Confirma que o óculos responde ANTES das sondagens.
        if (!EyeCamClient.conectarNaRede(context) || !EyeCamClient.ping()) {
            return ResultadoLeitura(
                sucesso = false,
                texto = "",
                mensagem = RegrasConexao.orientacaoFalha(ConexaoOculos.conexaoAutomaticaDisponivel()),
                estabilizou = false,
                sondagensUsadas = 0,
                tempoTotalMs = System.currentTimeMillis() - inicio
            )
        }

        // --- Passo 2: sondagens ---
        val avaliador = AvaliadorEstabilidade(
            limiarSimilaridade = config.limiarSimilaridade,
            toleranciaCaixa = config.toleranciaCaixa,
            amostrasNecessarias = config.amostrasEstaveis,
            exigirTexto = config.exigirEstabilidadeTextual
        )

        var estabilizou = false
        var sondagens = 0
        var semTextoSeguidas = 0
        var falhasSeguidas = 0
        var ultimaFalaEmitida: String? = null

        // A primeira sondagem sempre foca.
        var focoPendente = true
        val modoDireto = config.maxSondagens <= 0

        if (modoDireto) {
            // Sem portão: esta captura serve só para o autofoco convergir em resolução
            // baixa (quadros rápidos).
            onProgresso(ProgressoLeitura("Focando...", "Lendo"))
            if (EyeCamClient.capturarFoto(config.resolucaoSondagem, autofoco = true) == null) {
                return ResultadoLeitura(
                    sucesso = false,
                    texto = "",
                    mensagem = "Falha de comunicação com o óculos",
                    estabilizou = false,
                    sondagensUsadas = 0,
                    tempoTotalMs = System.currentTimeMillis() - inicio
                )
            }
        } else {
            onProgresso(ProgressoLeitura("Procurando texto...", "Procurando texto"))
        }

        while (sondagens < config.maxSondagens) {
            sondagens++

            val comAutofoco = focoPendente

            val quadro = EyeCamClient.capturarFoto(
                resolucao = config.resolucaoSondagem,
                autofoco = comAutofoco
            )

            if (quadro == null) {
                falhasSeguidas++
                onProgresso(
                    ProgressoLeitura(
                        "Falha ao capturar (sondagem $sondagens)",
                        null,
                        "captura retornou nulo"
                    )
                )
                // O ping passou, mas o óculos parou de responder (saiu do alcance,
                // desligou, travou).
                if (falhasSeguidas >= MAX_FALHAS_SEGUIDAS) {
                    return ResultadoLeitura(
                        sucesso = false,
                        texto = "",
                        mensagem = "Falha de comunicação com o óculos",
                        estabilizou = false,
                        sondagensUsadas = sondagens,
                        tempoTotalMs = System.currentTimeMillis() - inicio
                    )
                }
                continue
            }
            falhasSeguidas = 0

            val leitura = OcrService.reconhecerComCaixas(quadro)
            val veredito = avaliador.avaliar(leitura.texto, leitura.caixas)

            // Nenhum texto no quadro: insistir não resolve
            if (leitura.caixas.isEmpty()) {
                focoPendente = true
                semTextoSeguidas++
                if (semTextoSeguidas >= config.sondagensSemTexto) {
                    return ResultadoLeitura(
                        sucesso = false,
                        texto = "",
                        mensagem = "Nenhum texto encontrado",
                        estabilizou = false,
                        sondagensUsadas = sondagens,
                        tempoTotalMs = System.currentTimeMillis() - inicio
                    )
                }
                onProgresso(
                    ProgressoLeitura(
                        "Nenhum texto no quadro",
                        falarSeNovo("Aponte para o texto", ultimaFalaEmitida)
                            ?.also { ultimaFalaEmitida = it },
                        veredito.resumo()
                    )
                )
                continue
            }
            semTextoSeguidas = 0
            focoPendente = false

            // Traduz o motivo técnico em orientação ao usuário
            val orientacao = orientacaoPara(veredito.motivo)
            onProgresso(
                ProgressoLeitura(
                    statusVisual = "Sondagem $sondagens: ${veredito.motivo}",
                    falar = falarSeNovo(orientacao, ultimaFalaEmitida)
                        ?.also { ultimaFalaEmitida = it },
                    detalhe = veredito.resumo()
                )
            )

            if (veredito.estavel) {
                estabilizou = true
                break
            }
        }

        // --- Passo 3: captura final ---
        // Mesmo sem estabilizar, segue adiante: entregar uma leitura
        // imperfeita é melhor do que não entregar nada.
        onProgresso(
            ProgressoLeitura(
                if (estabilizou || modoDireto) "Capturando em alta resolução..."
                else "Não estabilizou, capturando assim mesmo...",
                if (modoDireto) null else "Lendo"   // no modo direto já foi dito
            )
        )

        var imagemFinal = EyeCamClient.capturarFoto(
            resolucao = config.resolucaoFinal,
            autofoco = false          // a lente já está focada das sondagens
        )

        // Falhou numa resolução acima de UXGA: tenta uma vez em UXGA
        if (imagemFinal == null && config.resolucaoFinal != RESOLUCAO_RESERVA &&
            config.resolucaoFinal in RESOLUCOES_ACIMA_DE_UXGA
        ) {
            // O detalhe vai para o relatório do modo desenvolvedor: sem ele, um teste "em 5
            // MP" que caiu para UXGA seria registrado como 5 MP
            onProgresso(
                ProgressoLeitura(
                    "Tentando em resolução menor...",
                    null,
                    "captura final em ${config.resolucaoFinal} falhou; repetida em $RESOLUCAO_RESERVA"
                )
            )
            imagemFinal = EyeCamClient.capturarFoto(
                resolucao = RESOLUCAO_RESERVA,
                autofoco = false
            )
        }

        if (imagemFinal == null) {
            return ResultadoLeitura(
                sucesso = false,
                texto = "",
                mensagem = "Falha na captura final",
                estabilizou = estabilizou,
                sondagensUsadas = sondagens,
                tempoTotalMs = System.currentTimeMillis() - inicio
            )
        }

        onProgresso(ProgressoLeitura("Foto tirada. Reconhecendo o texto...", null, fotoTirada = true))

        // --- Passo 4: enquadramento (opcional) ---
        var imagemParaOcr = imagemFinal
        if (config.usarEnquadramento) {
            onProgresso(ProgressoLeitura("Enquadrando...", null))
            val enquadrada = DocumentProcessor.enquadrar(imagemFinal)
            imagemParaOcr = enquadrada.bitmap
            onProgresso(
                ProgressoLeitura(
                    "Enquadramento: ${enquadrada.metodo}",
                    null,
                    "método: ${enquadrada.metodo}"
                )
            )
        }

        // --- Passo 5: OCR definitivo (com ampliação adaptativa) ---
        onProgresso(ProgressoLeitura("Reconhecendo texto...", null))
        val adaptativo = if (config.ampliacaoAdaptativa) {
            OcrService.reconhecerAdaptativo(imagemParaOcr)
        } else {
            val r = OcrService.reconhecerTexto(imagemParaOcr)
            ResultadoOcrAdaptativo(r, 1.0, r.alturaMedianaLinhaPx)
        }
        val resultadoOcr = adaptativo.resultado
        onProgresso(
            ProgressoLeitura(
                "Texto reconhecido",
                null,
                "OCR: altura de linha %s px, ampliação %.2fx".format(
                    adaptativo.alturaLinhaOriginalPx?.let { "%.0f".format(it) } ?: "—",
                    adaptativo.fatorAmpliacao
                )
            )
        )

        val tempo = System.currentTimeMillis() - inicio

        if (resultadoOcr.erro != null) {
            return ResultadoLeitura(
                sucesso = false,
                texto = "",
                mensagem = "Erro no reconhecimento de texto",
                estabilizou = estabilizou,
                sondagensUsadas = sondagens,
                tempoTotalMs = tempo,
                imagemFinal = imagemParaOcr
            )
        }

        if (resultadoOcr.texto.isBlank()) {
            // Causa mais comum, pelos testes: letra com poucos pixels (longe demais) ou
            // pouca luz.
            return ResultadoLeitura(
                sucesso = false,
                texto = "",
                mensagem = "Não consegui ler o texto. Aproxime um pouco ou melhore a luz.",
                estabilizou = estabilizou,
                sondagensUsadas = sondagens,
                tempoTotalMs = tempo,
                imagemFinal = imagemParaOcr,
                fatorAmpliacao = adaptativo.fatorAmpliacao,
                alturaLinhaPx = adaptativo.alturaLinhaOriginalPx
            )
        }

        return ResultadoLeitura(
            sucesso = true,
            texto = resultadoOcr.texto,
            mensagem = "Leitura concluída",
            estabilizou = estabilizou,
            sondagensUsadas = sondagens,
            tempoTotalMs = tempo,
            qtdBlocos = resultadoOcr.qtdBlocos,
            qtdLinhas = resultadoOcr.qtdLinhas,
            imagemFinal = imagemParaOcr,
            fatorAmpliacao = adaptativo.fatorAmpliacao,
            alturaLinhaPx = adaptativo.alturaLinhaOriginalPx
        )
    }

    /** Converte o motivo técnico da instabilidade em uma orientação útil. */
    private fun orientacaoPara(motivo: String): String? = when (motivo) {
        "câmera em movimento" -> "Segure firme"
        "caixas deslocaram"   -> "Segure firme"
        "texto variou"        -> "Aproxime um pouco"
        "primeira amostra"    -> null
        "estabilizando"       -> null
        "estável"             -> null
        else                  -> null
    }

    /** Evita repetir a mesma frase em sondagens consecutivas. */
    private fun falarSeNovo(frase: String?, ultima: String?): String? {
        if (frase == null) return null
        if (frase == ultima) return null
        return frase
    }
}
