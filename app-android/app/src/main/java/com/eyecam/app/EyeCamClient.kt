package com.eyecam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Uma foto recebida do óculos, com as medições da captura. */
class Captura(
    val bitmap: Bitmap,
    val bytes: Int,
    val tempoTotalMs: Long,
    val tempoFirmwareMs: Long?,
    val focoTravado: Boolean?,
    val jpeg: ByteArray = ByteArray(0),
    val cabecalhos: Map<String, String> = emptyMap()
)

/** Resultado de um pedido de foto, com ou sem sucesso. */
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

/** Comunicação HTTP com o ESP32 (Eye-cam). */
object EyeCamClient {

    private const val BASE_URL = "http://192.168.4.1"

    // Tempo para ESTABELECER a conexão.
    private const val TIMEOUT_CONEXAO = 8000

    // Tempo para RECEBER a resposta.
    private const val TIMEOUT_LEITURA = 45000

    // O ping só confirma que o óculos responde: na rede local ele volta em milissegundos.
    private const val TIMEOUT_PING = 3000

    private const val TENTATIVAS_PING = 2
    private const val PAUSA_ENTRE_PINGS_MS = 300L

    private const val PREFS_CAMERA = "eyecam_camera"
    private const val CHAVE_ROTACAO = "rotacao_graus"

    /** Rotação aplicada a cada foto, em graus no sentido horário (0, 90, 180 ou 270). */
    @Volatile
    var rotacaoGraus: Int = 0
        private set

    fun carregarRotacao(context: Context) {
        rotacaoGraus = context.getSharedPreferences(PREFS_CAMERA, Context.MODE_PRIVATE)
            .getInt(CHAVE_ROTACAO, 0)
    }

    fun definirRotacao(context: Context, graus: Int) {
        val normalizada = ((graus % 360) + 360) % 360 / 90 * 90
        rotacaoGraus = normalizada
        context.getSharedPreferences(PREFS_CAMERA, Context.MODE_PRIVATE)
            .edit().putInt(CHAVE_ROTACAO, normalizada).apply()
    }

    private fun girar(bitmap: Bitmap): Bitmap {
        val graus = rotacaoGraus
        if (graus == 0) return bitmap
        val matriz = Matrix().apply { postRotate(graus.toFloat()) }
        val girado = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matriz, true)
        if (girado !== bitmap) bitmap.recycle()
        return girado
    }

    // Rede Wi-Fi do ESP32, guardada depois de localizada
    private var redeEspCam: Network? = null

    /**
     * Leva o celular à rede Wi-Fi do óculos (ConexaoOculos) e amarra o tráfego do app a
     * ela.
     */
    suspend fun conectarNaRede(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val encontrada = ConexaoOculos.obterRede(app) ?: return@withContext false

            redeEspCam = encontrada.rede
            app.getSystemService(ConnectivityManager::class.java)
                .bindProcessToNetwork(encontrada.rede)

            if (RegrasConexao.deveAprenderBssid(encontrada.forma)) {
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
     * Solta a rede do óculos: o tráfego do app volta à rota normal e o pedido de rede é
     * desfeito (o celular volta ao Wi-Fi de casa).
     */
    fun desconectar(context: Context) {
        val app = context.applicationContext
        app.getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        redeEspCam = null
        ConexaoOculos.liberar(app)
    }

    /** Verifica se o Eye-cam está respondendo (com uma segunda tentativa). */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        repeat(TENTATIVAS_PING) { i ->
            if (i > 0) delay(PAUSA_ENTRE_PINGS_MS)
            try {
                val conexao = abrirConexao("$BASE_URL/ping", TIMEOUT_PING, TIMEOUT_PING)
                val codigo = conexao.responseCode
                val resposta = conexao.inputStream.bufferedReader().readText()
                conexao.disconnect()
                if (codigo == 200 && resposta.trim() == "OK") return@withContext true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // tenta de novo
            }
        }
        false
    }

    /** Captura uma foto do Eye-cam. */
    suspend fun capturarFoto(
        resolucao: String = "svga",
        autofoco: Boolean = true
    ): Bitmap? = tentarCaptura(resolucao, autofoco).captura?.bitmap

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
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { girar(it) }
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

    suspend fun ajustar(parametros: Map<String, String>): String? = withContext(Dispatchers.IO) {
        try {
            val consulta = parametros.entries.joinToString("&") { "${it.key}=${it.value}" }
            val conexao = abrirConexao("$BASE_URL/tune?$consulta", TIMEOUT_CONEXAO, TIMEOUT_CONEXAO)
            val codigo = conexao.responseCode
            val texto = if (codigo == 200) conexao.inputStream.bufferedReader().use { it.readText() } else null
            conexao.disconnect()
            texto
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

    /** Consulta as informações do dispositivo (JSON da rota /info). */
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

    /** Abre a conexão, usando a rede do ESP32 quando ela já foi localizada. */
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
