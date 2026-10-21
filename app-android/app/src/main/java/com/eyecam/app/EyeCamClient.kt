package com.eyecam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uma foto recebida do óculos, com as medições da captura.
 *
 * @param bytes tamanho do JPEG recebido
 * @param tempoTotalMs do pedido HTTP até o JPEG chegar inteiro no celular
 *        (foco + captura + transferência pelo Wi-Fi)
 * @param tempoFirmwareMs tempo medido pelo próprio ESP32 (cabeçalho
 *        X-Eyecam-Tempo-Ms): ajuste de resolução + foco + captura, sem a
 *        transferência. A diferença entre os dois é o custo do Wi-Fi.
 * @param focoTravado cabeçalho X-Eyecam-Foco; null se o firmware não enviar
 * @param jpeg o arquivo exatamente como veio do óculos (o modo bancada o
 *        grava sem recomprimir)
 * @param cabecalhos todos os cabeçalhos X-Eyecam-* da resposta
 */
class Captura(
    val bitmap: Bitmap,
    val bytes: Int,
    val tempoTotalMs: Long,
    val tempoFirmwareMs: Long?,
    val focoTravado: Boolean?,
    val jpeg: ByteArray = ByteArray(0),
    val cabecalhos: Map<String, String> = emptyMap()
)

/**
 * Resultado de um pedido de foto, com ou sem sucesso. O modo bancada
 * registra também as falhas, com o que a placa informou nos cabeçalhos.
 *
 * @param codigoHttp null quando nem houve resposta (rede)
 * @param tempoTotalMs do pedido até a resposta inteira chegar
 */
class TentativaCaptura(
    val captura: Captura?,
    val codigoHttp: Int?,
    val erro: String?,
    val tempoTotalMs: Long,
    val cabecalhos: Map<String, String>
) {
    /** Cabeçalho de medição do firmware v5.6, sem o prefixo: "Foco-Ms" */
    fun cabecalho(nome: String): String? =
        cabecalhos.entries.firstOrNull { it.key.equals("X-Eyecam-$nome", ignoreCase = true) }?.value
}

/** Trecho do registro da placa (rota /log do firmware v5.6) */
class RespostaLog(val texto: String, val ultimaLinha: Long?)

/**
 * Comunicação HTTP com o ESP32 (Eye-cam).
 *
 * Três particularidades do Android tratadas aqui:
 *
 *   1. CHEGAR À REDE. O óculos cria a própria rede Wi-Fi; ConexaoOculos
 *      localiza essa rede ou a pede ao Android sozinho.
 *
 *   2. ROTEAMENTO. Quando o celular se conecta a uma rede Wi-Fi sem acesso à
 *      internet (como a do ESP32), o Android mantém os dados móveis como rota
 *      principal e pode mandar o tráfego do app pela interface errada. O
 *      bindProcessToNetwork amarra as requisições à rede do óculos.
 *
 *   3. CLEARTEXT HTTP. Desde o Android 9, requisições HTTP sem criptografia
 *      são bloqueadas por padrão. A liberação está em
 *      res/xml/network_security_config.xml, restrita ao IP 192.168.4.1.
 */
object EyeCamClient {

    private const val BASE_URL = "http://192.168.4.1"

    // Tempo para ESTABELECER a conexão. Curto de propósito: se o óculos está
    // desligado ou fora de alcance, é melhor descobrir rápido.
    private const val TIMEOUT_CONEXAO = 8000

    // Tempo para RECEBER a resposta. Precisa ser generoso: em UXGA o ESP32
    // gasta segundos focando, capturando e transferindo centenas de KB por
    // Wi-Fi. Com 10 s, capturas que o firmware concluía com sucesso eram
    // descartadas pelo app por tempo esgotado.
    private const val TIMEOUT_LEITURA = 45000

    // O ping só confirma que o óculos responde: na rede local ele volta em
    // milissegundos. Um limite curto faz o "óculos desligado" ser avisado
    // ao usuário em poucos segundos, em vez de esperar o timeout de captura.
    private const val TIMEOUT_PING = 3000

    // Rede Wi-Fi do ESP32, guardada depois de localizada
    private var redeEspCam: Network? = null

    /**
     * Leva o celular à rede Wi-Fi do óculos (ConexaoOculos) e amarra o
     * tráfego do app a ela. Deve ser chamado antes de ping ou captura.
     *
     * Na primeira conexão com um óculos, lê o BSSID dele em /info: é o que
     * permite às conexões seguintes dispensar a confirmação do usuário.
     */
    suspend fun conectarNaRede(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val encontrada = ConexaoOculos.obterRede(app) ?: return@withContext false

            redeEspCam = encontrada.rede
            app.getSystemService(ConnectivityManager::class.java)
                .bindProcessToNetwork(encontrada.rede)

            // Na conexão manual em último caso (SO_MANUAL) a rede pode nem ser
            // a do óculos; consultar /info ali só atrasaria o aviso de falha
            if (encontrada.forma != FormaConexao.SO_MANUAL && ConexaoOculos.bssidSalvo(app) == null) {
                ConexaoOculos.lembrarBssid(app, obterInfo(TIMEOUT_PING, TIMEOUT_PING))
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Aparece no diagnóstico do modo desenvolvedor
            ConexaoOculos.registrarErro(e)
            false
        }
    }

    /**
     * Solta a rede do óculos: o tráfego do app volta à rota normal e o
     * pedido de rede é desfeito (o celular volta ao Wi-Fi de casa).
     */
    fun desconectar(context: Context) {
        val app = context.applicationContext
        app.getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        redeEspCam = null
        ConexaoOculos.liberar(app)
    }

    /**
     * Verifica se o Eye-cam está respondendo.
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conexao = abrirConexao("$BASE_URL/ping", TIMEOUT_PING, TIMEOUT_PING)
            val codigo = conexao.responseCode
            val resposta = conexao.inputStream.bufferedReader().readText()
            conexao.disconnect()
            codigo == 200 && resposta.trim() == "OK"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Captura uma foto do Eye-cam.
     *
     * @param resolucao qvga, vga, svga, xga, sxga, uxga, qxga ou 5mp
     *        (as duas últimas exigem o firmware v5.4 ou posterior)
     * @param autofoco se false, acrescenta ?af=0 e o firmware captura sem
     *        esperar o foco.
     *
     * Sobre o autofoco: focar custa cerca de um segundo. Nas capturas
     * seguintes à primeira a lente já está travada, então refocar é
     * desperdício. E em alta resolução o autofoco do sensor não converge —
     * a 10 MHz de clock, os quadros são lentos demais para o algoritmo de
     * foco avaliar nitidez dentro do tempo limite. Por isso o padrão do
     * fluxo é focar em resolução baixa e capturar em alta com af=0.
     */
    suspend fun capturarFoto(
        resolucao: String = "svga",
        autofoco: Boolean = true
    ): Bitmap? = capturarFotoDetalhada(resolucao, autofoco)?.bitmap

    /**
     * Captura uma foto e devolve também as medições da captura, usadas no
     * modo desenvolvedor para os testes de resolução e desempenho (Blocos 2
     * e 7 do protocolo).
     */
    suspend fun capturarFotoDetalhada(
        resolucao: String = "svga",
        autofoco: Boolean = true
    ): Captura? = tentarCaptura(resolucao, autofoco).captura

    /**
     * Pede uma foto e devolve o resultado mesmo quando falha: código HTTP,
     * mensagem e os cabeçalhos de medição que o firmware v5.6 manda também
     * nas respostas de erro.
     */
    suspend fun tentarCaptura(
        resolucao: String = "svga",
        autofoco: Boolean = true
    ): TentativaCaptura = withContext(Dispatchers.IO) {
        val inicio = System.currentTimeMillis()
        try {
            val parametroAf = if (autofoco) "" else "&af=0"
            val conexao = abrirConexao("$BASE_URL/capture?res=$resolucao$parametroAf")
            val codigo = conexao.responseCode
            val cabecalhos = cabecalhosEyecam(conexao)

            if (codigo == 200) {
                val bytes = conexao.inputStream.use { it.readBytes() }
                conexao.disconnect()
                val tempoTotal = System.currentTimeMillis() - inicio
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext TentativaCaptura(
                        null, codigo, "JPEG inválido (${bytes.size} bytes)", tempoTotal, cabecalhos
                    )

                // Cabeçalhos enviados pelo firmware (v5.2 em diante)
                val captura = Captura(
                    bitmap = bitmap,
                    bytes = bytes.size,
                    tempoTotalMs = tempoTotal,
                    tempoFirmwareMs = valor(cabecalhos, "Tempo-Ms")?.toLongOrNull(),
                    focoTravado = valor(cabecalhos, "Foco")?.let { it == "travado" },
                    jpeg = bytes,
                    cabecalhos = cabecalhos
                )
                TentativaCaptura(captura, codigo, null, tempoTotal, cabecalhos)
            } else {
                val mensagem = conexao.errorStream?.use { it.bufferedReader().readText() }?.trim()
                conexao.disconnect()
                TentativaCaptura(
                    null, codigo, "HTTP $codigo ${mensagem.orEmpty()}".trim(),
                    System.currentTimeMillis() - inicio, cabecalhos
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TentativaCaptura(
                null, null, e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis() - inicio, emptyMap()
            )
        }
    }

    /**
     * Mensagens do firmware desde a linha [desde] (rota /log, firmware v5.6).
     * null se o óculos não respondeu ou se o firmware não tem a rota.
     */
    suspend fun obterLog(desde: Long): RespostaLog? = withContext(Dispatchers.IO) {
        try {
            val conexao = abrirConexao("$BASE_URL/log?desde=$desde", TIMEOUT_PING, TIMEOUT_CONEXAO)
            if (conexao.responseCode == 200) {
                val ultima = conexao.getHeaderField("X-Eyecam-Log-Seq")?.toLongOrNull()
                val texto = conexao.inputStream.bufferedReader().use { it.readText() }
                conexao.disconnect()
                RespostaLog(texto, ultima)
            } else {
                conexao.disconnect()
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun cabecalhosEyecam(conexao: HttpURLConnection): Map<String, String> =
        conexao.headerFields
            .filterKeys { it != null && it.startsWith("X-Eyecam-", ignoreCase = true) }
            .mapKeys { it.key!! }
            .mapValues { it.value.joinToString(",") }

    private fun valor(cabecalhos: Map<String, String>, nome: String): String? =
        cabecalhos.entries.firstOrNull { it.key.equals("X-Eyecam-$nome", ignoreCase = true) }?.value

    /**
     * Consulta as informações do dispositivo (JSON da rota /info).
     * Retorna o texto bruto, ou null em caso de falha.
     */
    suspend fun obterInfo(
        timeoutConexao: Int = TIMEOUT_CONEXAO,
        timeoutLeitura: Int = TIMEOUT_LEITURA
    ): String? = withContext(Dispatchers.IO) {
        try {
            val conexao = abrirConexao("$BASE_URL/info", timeoutConexao, timeoutLeitura)
            if (conexao.responseCode == 200) {
                val texto = conexao.inputStream.bufferedReader().readText()
                conexao.disconnect()
                texto
            } else {
                conexao.disconnect()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Abre a conexão, usando a rede do ESP32 quando ela já foi localizada.
     */
    private fun abrirConexao(
        urlStr: String,
        timeoutConexao: Int = TIMEOUT_CONEXAO,
        timeoutLeitura: Int = TIMEOUT_LEITURA
    ): HttpURLConnection {
        val url = URL(urlStr)

        val conexao = if (redeEspCam != null) {
            redeEspCam!!.openConnection(url) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }

        conexao.connectTimeout = timeoutConexao
        conexao.readTimeout = timeoutLeitura
        conexao.requestMethod = "GET"
        return conexao
    }
}
