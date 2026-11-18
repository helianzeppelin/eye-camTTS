package com.eyecam.app

/** Tipos de passo do modo bancada. */
enum class TipoPasso {
    /** Uma foto (com ou sem foco antes), leitura e acerto: um aperto, um registro */
    FOTO,

    /** Várias fotos seguidas, com um único aperto: falhas, FB-OVF e tempos */
    SEQUENCIA_FOTOS,

    /** Desconecta e reconecta várias vezes: tempo da conexão automática */
    SEQUENCIA_CONEXAO,

    /** Várias leituras completas, como o toque duplo do modo acessível */
    SEQUENCIA_LEITURA
}

/** Um passo do plano de testes. */
data class PassoTeste(
    val id: String,
    val tipo: TipoPasso,
    val montagem: String,
    val instrucao: String,
    val resolucao: String = "5mp",
    val focarAntes: Boolean = true,
    val resolucaoFoco: String = "svga",
    val autofocoNaFinal: Boolean = false,
    val lerTexto: Boolean = true,
    val repeticoes: Int = 1,
    val salvarFoto: Boolean = true,
    val palavraEsperada: String? = null,
    val confirmarMontagem: Boolean = true,
    val xclkMhz: Int? = null,
    val xclkAltaMhz: Int? = null,
    val distanciaCm: Int? = null,
    /** Foto da página de tamanhos: o acerto é medido por frase (AcertoTamanhos) */
    val paginaTamanhos: Boolean = false
)

data class PlanoTestes(
    val id: String,
    val nome: String,
    val descricao: String,
    val passos: List<PassoTeste>
)

/** Os planos do roteiro de testes (tcc_local/guias/ROTEIRO_SEM_NOTEBOOK). */
object PlanosTestes {

    private const val LUZ_BANCADA = "luz da bancada"

    /** Clock da câmera do firmware (XCLK_PADRAO_MHZ), nos dois casos */
    const val XCLK_PADRAO_MHZ = 10

    /** Escala medida da câmera: px/mm × distância em cm, numa foto de 2560 px de largura. */
    const val ESCALA_PX_MM_CM_5MP = 121.0

    /** Escala esperada de uma foto (px/mm) a [distanciaCm] cm da página */
    fun pxPorMmEsperado(distanciaCm: Int, larguraFoto: Int): Double =
        ESCALA_PX_MM_CM_5MP * larguraFoto / 2560.0 / distanciaCm

    val FIRMWARE = PlanoTestes(
        id = "firmware",
        nome = "Firmware",
        descricao = "Foco, fotos UXGA e 5 MP seguidas e foto atrasada. O app registra " +
            "o tempo e o resultado do foco, as falhas e a contagem de FB-OVF da placa " +
            "(firmware v5.6.3 ou posterior).",
        passos = passosFirmware()
    )

    val FOCO = PlanoTestes(
        id = "foco",
        nome = "Foco",
        descricao = "Mesma página, 3 repetições de cada forma de focar: em SVGA (a do " +
            "app), em XGA, na própria foto de 5 MP e em SVGA com o clock a 20 MHz; " +
            "depois, 5 fotos de 5 MP com o clock a 16 MHz. O app registra a nitidez " +
            "do centro da foto, as zonas que o sensor diz ter focado e o acerto. " +
            "Exige o firmware v5.6.3 ou posterior.",
        passos = passosFoco()
    )

    val BANCADA = PlanoTestes(
        id = "bancada",
        nome = "Bancada",
        descricao = "Distância × resolução × luz, 3 repetições. Enquadre os títulos " +
            "\"BLOCO n — CORPO X pt\" e, a partir de 30 cm, a barra de 50 mm. O app foca " +
            "em SVGA, fotografa sem refocar, lê com e sem ampliação e calcula o acerto de " +
            "cada corpo de letra.",
        passos = passosBancada()
    )

    val TEMPOS = PlanoTestes(
        id = "tempos",
        nome = "Tempos",
        descricao = "Conexão automática e leitura completa em 5 MP e em UXGA, 10 vezes " +
            "cada. O tempo de cada etapa da foto já sai dos planos Firmware e Bancada.",
        passos = passosTempos()
    )

    val OCULOS = PlanoTestes(
        id = "oculos",
        nome = "Óculos",
        descricao = "Protótipo de mesa: a câmera na armação, com os LEDs. Só 5 MP, 3 " +
            "repetições: luz natural de janela a 20, 25, 30 e 35 cm e quarto escuro só com " +
            "os LEDs a 20, 25 e 30 cm. 21 fotos. O app foca em SVGA, lê e calcula o acerto " +
            "de cada corpo (pela altura das linhas se o título não for lido).",
        passos = passosOculos()
    )

    val TAMANHOS = PlanoTestes(
        id = "tamanhos",
        nome = "Tamanhos",
        descricao = "Página de tamanhos (frases de 32 a 10 pt), segurada a 20, 25 e 30 cm do óculos: " +
            "3 fotos em 5 MP e 1 em SVGA por distância, 12 fotos. O app foca em SVGA, lê e calcula " +
            "o acerto de cada tamanho e o menor tamanho lido (acerto de 80% ou mais).",
        passos = passosTamanhos()
    )

    val TODOS = listOf(FIRMWARE, FOCO, BANCADA, TEMPOS, OCULOS, TAMANHOS)

    fun porId(id: String?): PlanoTestes? = TODOS.firstOrNull { it.id == id }

    /** true quando é preciso mexer na bancada antes deste passo */
    fun mudouMontagem(anterior: PassoTeste?, atual: PassoTeste): Boolean =
        atual.confirmarMontagem && (anterior == null || anterior.montagem != atual.montagem)

    /** Rótulo da resolução para nomes e telas: "5mp" → "5MP" */
    fun rotulo(resolucao: String): String = resolucao.uppercase()

    /** O que o app faz sozinho neste passo, para a tela e para o registro */
    fun descricaoAutomatica(passo: PassoTeste): String {
        val rotulo = rotulo(passo.resolucao)
        val foco = rotulo(passo.resolucaoFoco)
        val autofoco = if (passo.autofocoNaFinal) "com autofoco" else "sem autofoco"
        val clock = descreverClock(passo)
        return when (passo.tipo) {
            TipoPasso.FOTO -> buildString {
                append(clock)
                if (passo.focarAntes) append("Foco em $foco, depois ")
                append("foto $rotulo $autofoco")
                when {
                    passo.palavraEsperada != null -> append(" · procura a palavra ${passo.palavraEsperada} na foto")
                    passo.lerTexto -> append(" · lê com e sem ampliação e mede o acerto de cada corpo")
                }
                append(" · salva foto e registro")
            }
            TipoPasso.SEQUENCIA_FOTOS -> buildString {
                append(clock)
                append("${passo.repeticoes} fotos $rotulo $autofoco")
                if (passo.focarAntes) append(", depois de um foco em $foco")
                append(" · registra tempos, foco, falhas e FB-OVF")
            }
            TipoPasso.SEQUENCIA_CONEXAO ->
                "${passo.repeticoes} conexões automáticas, com 3 s de pausa entre elas · registra o tempo de cada uma"
            TipoPasso.SEQUENCIA_LEITURA ->
                "${passo.repeticoes} leituras completas (foco em SVGA, foto $rotulo, leitura com ampliação) · registra o tempo de cada uma"
        }
    }

    /** Texto do botão principal da tela */
    fun rotuloBotao(passo: PassoTeste): String =
        if (passo.tipo == TipoPasso.FOTO) "CAPTURAR" else "INICIAR (${passo.repeticoes}×)"

    /** Parâmetros da rota /tune que deixam o clock como o passo pede */
    fun ajusteDeClock(passo: PassoTeste): Map<String, String> = mapOf(
        "xclk" to (passo.xclkMhz ?: XCLK_PADRAO_MHZ).toString(),
        "xclk_alta" to (passo.xclkAltaMhz ?: XCLK_PADRAO_MHZ).toString()
    )

    /** true quando o passo pede um clock diferente do padrão do firmware */
    fun usaClockDeTeste(passo: PassoTeste): Boolean =
        (passo.xclkMhz ?: XCLK_PADRAO_MHZ) != XCLK_PADRAO_MHZ ||
            (passo.xclkAltaMhz ?: XCLK_PADRAO_MHZ) != XCLK_PADRAO_MHZ

    private fun descreverClock(passo: PassoTeste): String = buildString {
        passo.xclkMhz?.let { append("XCLK $it MHz até XGA · ") }
        passo.xclkAltaMhz?.let { append("XCLK $it MHz acima de XGA · ") }
    }

    // ------------------------------------------------------------------

    private fun passosFirmware(): List<PassoTeste> {
        val montagem = "Câmera a uns 25–30 cm da página v2, boa luz"
        val passos = mutableListOf(
            PassoTeste(
                id = "F3-foco-SVGA", tipo = TipoPasso.SEQUENCIA_FOTOS, montagem = montagem,
                instrucao = "5 fotos em SVGA, cada uma com foco: mede o tempo do foco e se ele trava.",
                resolucao = "svga", focarAntes = false, autofocoNaFinal = true,
                lerTexto = false, repeticoes = 5, salvarFoto = false
            ),
            PassoTeste(
                id = "F4-UXGA-x10", tipo = TipoPasso.SEQUENCIA_FOTOS, montagem = montagem,
                instrucao = "Um foco em SVGA e 10 fotos UXGA seguidas: falhas, FB-OVF e tempos.",
                resolucao = "uxga", lerTexto = false, repeticoes = 10
            ),
            PassoTeste(
                id = "F5-5MP-x5", tipo = TipoPasso.SEQUENCIA_FOTOS, montagem = montagem,
                instrucao = "Um foco em SVGA e 5 fotos de 5 MP seguidas: falhas, FB-OVF e tempos.",
                resolucao = "5mp", lerTexto = false, repeticoes = 5
            )
        )

        // Foto atrasada: a foto tirada logo depois de trocar a folha tem de mostrar a folha
        // nova.
        for ((resolucao, autofoco) in listOf("svga" to true, "uxga" to false)) {
            for (r in 1..5) {
                val base = "F6-${rotulo(resolucao)}-r$r"
                passos += PassoTeste(
                    id = "$base-AZUL", tipo = TipoPasso.FOTO,
                    montagem = "Folha AZUL na frente da câmera",
                    instrucao = "Mostre a folha AZUL e aperte CAPTURAR.",
                    resolucao = resolucao, focarAntes = false, autofocoNaFinal = autofoco,
                    palavraEsperada = "AZUL", confirmarMontagem = false
                )
                passos += PassoTeste(
                    id = "$base-VERDE", tipo = TipoPasso.FOTO,
                    montagem = "Folha VERDE na frente da câmera",
                    instrucao = "Troque para a folha VERDE, espere 1 segundo e aperte CAPTURAR.",
                    resolucao = resolucao, focarAntes = false, autofocoNaFinal = autofoco,
                    palavraEsperada = "VERDE", confirmarMontagem = false
                )
            }
        }
        return passos
    }

    /** Formas de focar, na mesma montagem. */
    private fun passosFoco(): List<PassoTeste> {
        val montagem = "Página v2 PLANA e presa, com o CENTRO da foto sobre o texto (o foco " +
            "mede o centro) · câmera a 30 cm da página (da lente ao papel) · $LUZ_BANCADA"
        val instrucao = "Aperte CAPTURAR e não mexa na câmera até terminar."
        val formas = (1..3).flatMap { r ->
            listOf(
                PassoTeste(
                    id = "FO-SVGA-r$r", tipo = TipoPasso.FOTO, montagem = montagem, instrucao = instrucao,
                    distanciaCm = 30
                ),
                PassoTeste(
                    id = "FO-XGA-r$r", tipo = TipoPasso.FOTO, montagem = montagem, instrucao = instrucao,
                    resolucaoFoco = "xga", distanciaCm = 30
                ),
                PassoTeste(
                    id = "FO-5MPAF-r$r", tipo = TipoPasso.FOTO, montagem = montagem, instrucao = instrucao,
                    focarAntes = false, autofocoNaFinal = true, distanciaCm = 30
                ),
                PassoTeste(
                    id = "FO-SVGA20-r$r", tipo = TipoPasso.FOTO, montagem = montagem, instrucao = instrucao,
                    xclkMhz = 20, distanciaCm = 30
                )
            )
        }
        return formas + PassoTeste(
            id = "FO-5MP16-x5", tipo = TipoPasso.SEQUENCIA_FOTOS, montagem = montagem,
            instrucao = "Um foco em SVGA e 5 fotos de 5 MP com o clock a 16 MHz: falhas, FB-OVF e tempo do quadro.",
            lerTexto = false, repeticoes = 5, xclkAltaMhz = 16
        )
    }

    private fun passosOculos(): List<PassoTeste> {
        val natural = "luz NATURAL de janela, indireta (sem sol direto na folha)"
        val leds = "quarto ESCURO: só os LEDs do óculos acesos"
        return listOf(20, 25, 30, 35).flatMap { d -> grupoOculos("ON$d", d, natural) } +
            listOf(20, 25, 30).flatMap { d -> grupoOculos("OL$d", d, leds) }
    }

    /**
     * Página de tamanhos a 20, 25 e 30 cm: 3 fotos de 5 MP e 1 de SVGA (para mostrar o
     * limite da resolução baixa) por distância.
     */
    private fun passosTamanhos(): List<PassoTeste> =
        listOf(20, 25, 30).flatMap { d ->
            val montagem = "Vestindo o óculos, segure a página de TAMANHOS de frente para a câmera, o " +
                "mais reta possível, texto no centro da foto · $d cm da lente ao papel (meça com " +
                "régua ou um barbante preso ao óculos) · luz do ambiente, sem lâmpada na foto"
            (1..3).map { r ->
                PassoTeste(
                    id = "TA$d-5MP-r$r", tipo = TipoPasso.FOTO, montagem = montagem,
                    instrucao = "Aperte CAPTURAR e não mexa no óculos nem na folha até terminar.",
                    resolucao = "5mp", distanciaCm = d, paginaTamanhos = true
                )
            } + PassoTeste(
                id = "TA$d-SVGA", tipo = TipoPasso.FOTO, montagem = montagem,
                instrucao = "Aperte CAPTURAR (foto em SVGA, para comparar).",
                resolucao = "svga", distanciaCm = d, paginaTamanhos = true
            )
        }

    /** Um grupo do protótipo: mesma montagem, 3 fotos de 5 MP */
    private fun grupoOculos(grupo: String, distanciaCm: Int, luz: String): List<PassoTeste> =
        (1..3).map { r ->
            PassoTeste(
                id = "$grupo-5MP-r$r",
                tipo = TipoPasso.FOTO,
                montagem = "Óculos no suporte, página v2 plana e de frente para a câmera, centro da foto " +
                    "sobre o texto · $distanciaCm cm da lente ao papel · $luz",
                instrucao = "Aperte CAPTURAR e não mexa no óculos até terminar.",
                resolucao = "5mp",
                distanciaCm = distanciaCm
            )
        }

    private fun passosBancada(): List<PassoTeste> =
        grupoBancada("B25", 25, LUZ_BANCADA, listOf("uxga", "qxga", "5mp")) +
        grupoBancada("B30", 30, LUZ_BANCADA, listOf("uxga", "qxga", "5mp")) +
        grupoBancada("B35", 35, LUZ_BANCADA, listOf("uxga", "5mp")) +
        grupoBancada("B40", 40, "$LUZ_BANCADA (opcional: pode pular)", listOf("uxga", "5mp")) +
        grupoBancada("L30", 30, "luz FRACA: a da bancada desligada, só a do ambiente", listOf("5mp")) +
        grupoBancada("S30", 30, "luz da bancada + SOMBRA na metade esquerda da folha (mão ou livro em pé)", listOf("5mp")) +
        grupoBancada("B20", 20, "$LUZ_BANCADA (opcional: pode pular)", listOf("uxga", "5mp"))

    /** Um grupo da bancada: mesma montagem, várias resoluções, 3 repetições. */
    private fun grupoBancada(
        grupo: String,
        distanciaCm: Int,
        luz: String,
        resolucoes: List<String>
    ): List<PassoTeste> = (1..3).flatMap { r ->
        resolucoes.map { resolucao ->
            PassoTeste(
                id = "$grupo-${rotulo(resolucao)}-r$r",
                tipo = TipoPasso.FOTO,
                montagem = "Página v2 plana, centro da foto sobre o texto · câmera a $distanciaCm cm " +
                    "da página (da lente ao papel) · $luz",
                instrucao = "Aperte CAPTURAR e não mexa na câmera até terminar.",
                resolucao = resolucao,
                distanciaCm = distanciaCm
            )
        }
    }

    private fun passosTempos(): List<PassoTeste> = listOf(
        PassoTeste(
            id = "T-conexao-x10", tipo = TipoPasso.SEQUENCIA_CONEXAO,
            montagem = "Óculos ligado, celular perto dele",
            instrucao = "O app solta a rede do óculos e conecta de novo, 10 vezes (cerca de 1 minuto).",
            lerTexto = false, repeticoes = 10, salvarFoto = false
        ),
        PassoTeste(
            id = "T-leitura-5MP-x10", tipo = TipoPasso.SEQUENCIA_LEITURA,
            montagem = "Câmera a 30 cm da página v2 · $LUZ_BANCADA",
            instrucao = "10 leituras completas em 5 MP, como o toque duplo do modo acessível.",
            resolucao = "5mp", repeticoes = 10, salvarFoto = false
        ),
        PassoTeste(
            id = "T-leitura-UXGA-x10", tipo = TipoPasso.SEQUENCIA_LEITURA,
            montagem = "Câmera a 30 cm da página v2 · $LUZ_BANCADA",
            instrucao = "10 leituras completas em UXGA.",
            resolucao = "uxga", repeticoes = 10, salvarFoto = false
        )
    )
}
