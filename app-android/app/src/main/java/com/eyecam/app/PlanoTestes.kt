package com.eyecam.app

/**
 * Tipos de passo do modo bancada.
 */
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

/**
 * Um passo do plano de testes. Tudo o que depende do programa (resolução,
 * autofoco, leitura, repetições, nome dos arquivos) é fixado aqui, para que
 * o usuário na bancada só cuide da montagem física e aperte um botão.
 *
 * @param id nome do teste e dos arquivos, por exemplo "B30-5MP-r1"
 * @param montagem o que precisa estar montado na bancada. Quando muda de um
 *        passo para o seguinte, a tela pede confirmação antes de capturar
 * @param instrucao o que fazer neste passo
 * @param focarAntes tira antes uma foto SVGA com autofoco, só para focar (o
 *        autofoco não converge em alta resolução; a lente mantém a posição)
 * @param autofocoNaFinal autofoco na foto final (só faz sentido em SVGA)
 * @param lerTexto passa a foto final pelo OCR, com e sem ampliação
 * @param repeticoes quantas vezes a sequência repete (passos de sequência)
 * @param salvarFoto grava o JPEG recebido do óculos
 * @param palavraEsperada palavra que a foto tem de mostrar (teste da foto
 *        atrasada); null quando não se aplica
 * @param confirmarMontagem pede o "Já ajustei" quando a montagem muda. Falso
 *        quando a própria instrução é a mudança (trocar a folha no teste da
 *        foto atrasada)
 */
data class PassoTeste(
    val id: String,
    val tipo: TipoPasso,
    val montagem: String,
    val instrucao: String,
    val resolucao: String = "5mp",
    val focarAntes: Boolean = true,
    val autofocoNaFinal: Boolean = false,
    val lerTexto: Boolean = true,
    val repeticoes: Int = 1,
    val salvarFoto: Boolean = true,
    val palavraEsperada: String? = null,
    val confirmarMontagem: Boolean = true
)

data class PlanoTestes(
    val id: String,
    val nome: String,
    val descricao: String,
    val passos: List<PassoTeste>
)

/**
 * Os planos do roteiro de testes (tcc_local/guias/ROTEIRO_SEM_NOTEBOOK).
 * Kotlin puro: a sequência de testes é conferida em PlanoTestesTest.
 */
object PlanosTestes {

    private const val LUZ_BANCADA = "luz da bancada"

    val FIRMWARE = PlanoTestes(
        id = "firmware",
        nome = "Firmware",
        descricao = "Foco, fotos UXGA e 5 MP seguidas e foto atrasada. O app registra " +
            "o tempo do foco, as falhas e a contagem de FB-OVF da placa (firmware v5.6.2).",
        passos = passosFirmware()
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

    val TODOS = listOf(FIRMWARE, BANCADA, TEMPOS)

    fun porId(id: String?): PlanoTestes? = TODOS.firstOrNull { it.id == id }

    /** true quando é preciso mexer na bancada antes deste passo */
    fun mudouMontagem(anterior: PassoTeste?, atual: PassoTeste): Boolean =
        atual.confirmarMontagem && (anterior == null || anterior.montagem != atual.montagem)

    /** Rótulo da resolução para nomes e telas: "5mp" → "5MP" */
    fun rotulo(resolucao: String): String = resolucao.uppercase()

    /** O que o app faz sozinho neste passo, para a tela e para o registro */
    fun descricaoAutomatica(passo: PassoTeste): String {
        val rotulo = rotulo(passo.resolucao)
        val autofoco = if (passo.autofocoNaFinal) "com autofoco" else "sem autofoco"
        return when (passo.tipo) {
            TipoPasso.FOTO -> buildString {
                if (passo.focarAntes) append("Foco em SVGA, depois ")
                append("foto $rotulo $autofoco")
                when {
                    passo.palavraEsperada != null -> append(" · procura a palavra ${passo.palavraEsperada} na foto")
                    passo.lerTexto -> append(" · lê com e sem ampliação e mede o acerto de cada corpo")
                }
                append(" · salva foto e registro")
            }
            TipoPasso.SEQUENCIA_FOTOS -> buildString {
                append("${passo.repeticoes} fotos $rotulo $autofoco")
                if (passo.focarAntes) append(", depois de um foco em SVGA")
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

        // Foto atrasada: a foto tirada logo depois de trocar a folha tem de
        // mostrar a folha nova. O app procura a palavra na foto
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

    private fun passosBancada(): List<PassoTeste> =
        grupoBancada("B25", 25, LUZ_BANCADA, listOf("uxga", "qxga", "5mp")) +
        grupoBancada("B30", 30, LUZ_BANCADA, listOf("uxga", "qxga", "5mp")) +
        grupoBancada("B35", 35, LUZ_BANCADA, listOf("uxga", "5mp")) +
        grupoBancada("B40", 40, LUZ_BANCADA, listOf("uxga", "5mp")) +
        grupoBancada("L30", 30, "luz FRACA: a da bancada desligada, só a do ambiente", listOf("5mp")) +
        grupoBancada("S30", 30, "luz da bancada + SOMBRA na metade esquerda da folha (mão ou livro em pé)", listOf("5mp")) +
        grupoBancada("B20", 20, "$LUZ_BANCADA (opcional: pode pular)", listOf("uxga", "5mp"))

    /**
     * Um grupo da bancada: mesma montagem, várias resoluções, 3 repetições.
     * As resoluções se alternam dentro de cada repetição (UXGA r1, 5MP r1,
     * UXGA r2…): uma mudança lenta de luz ou de posição durante o grupo
     * afeta todas as resoluções por igual, em vez de só a última.
     */
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
                montagem = "Câmera a $distanciaCm cm da página (da lente ao papel) · $luz",
                instrucao = "Aperte CAPTURAR e não mexa na câmera até terminar.",
                resolucao = resolucao
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
