package com.eyecam.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Como o celular chegou (ou vai chegar) à rede Wi-Fi do óculos. */
enum class FormaConexao {
    /** O celular já está na rede do óculos: conexão manual ou pedido anterior ainda ativo */
    REDE_JA_CONECTADA,

    /** Pedido ao Android com o nome da rede e o BSSID: conecta sem perguntar */
    PEDIDO_COM_BSSID,

    /** Primeiro pedido, só com o nome da rede: o Android pede confirmação ao usuário */
    PEDIDO_SEM_BSSID,

    /** Android 8 ou 9, ou app compilado sem a senha: só a conexão manual */
    SO_MANUAL
}

/** Rede localizada e a forma como se chegou a ela. */
data class RedeOculos(val rede: Network, val forma: FormaConexao)

/** Regras da conexão, sem dependência do Android (testadas em RegrasConexaoTest). */
object RegrasConexao {

    /** Nome da rede criada pelo óculos. Igual a AP_SSID no firmware. */
    const val SSID = "EyeCam_01"

    /** O WifiNetworkSpecifier existe a partir do Android 10 (API 29). */
    const val SDK_MINIMO_PEDIDO = 29

    // Com o óculos já aprovado, o Android só procura a rede e conecta, o que leva alguns
    // segundos.
    const val TEMPO_PEDIDO_COM_BSSID_MS = 12_000

    // Na primeira vez o usuário precisa ouvir e confirmar a caixa do sistema (com o
    // TalkBack, isso leva mais tempo).
    const val TEMPO_PEDIDO_SEM_BSSID_MS = 60_000

    private val FORMATO_MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    private val CAMPO_BSSID = Regex("\"ap_bssid\"\\s*:\\s*\"([^\"]*)\"")

    /** Decide como conectar. */
    fun escolherForma(
        sdk: Int,
        temSenha: Boolean,
        bssid: String?,
        jaNaRede: Boolean
    ): FormaConexao = when {
        jaNaRede -> FormaConexao.REDE_JA_CONECTADA
        sdk < SDK_MINIMO_PEDIDO || !temSenha -> FormaConexao.SO_MANUAL
        bssid != null -> FormaConexao.PEDIDO_COM_BSSID
        else -> FormaConexao.PEDIDO_SEM_BSSID
    }

    /** Prazo do pedido de rede, conforme o usuário precise confirmar ou não. */
    fun tempoLimiteMs(forma: FormaConexao): Int =
        if (forma == FormaConexao.PEDIDO_SEM_BSSID) TEMPO_PEDIDO_SEM_BSSID_MS
        else TEMPO_PEDIDO_COM_BSSID_MS

    /** Se a conexão que acabou de dar certo autoriza guardar o BSSID do óculos. */
    fun deveAprenderBssid(forma: FormaConexao): Boolean = forma == FormaConexao.PEDIDO_SEM_BSSID

    /** Padroniza um endereço MAC ("AA:BB:CC:DD:EE:FF" → minúsculas). */
    fun normalizarBssid(texto: String?): String? {
        val mac = texto?.trim()?.lowercase() ?: return null
        if (!FORMATO_MAC.matches(mac)) return null
        if (mac == "00:00:00:00:00:00" || mac == "ff:ff:ff:ff:ff:ff") return null
        return mac
    }

    fun extrairBssid(jsonInfo: String?): String? =
        jsonInfo?.let { CAMPO_BSSID.find(it)?.groupValues?.get(1) }?.let(::normalizarBssid)

    /** Frase dita antes de conectar, ou null quando a conexão é imediata. */
    fun avisoAntesDeConectar(forma: FormaConexao): String? = when (forma) {
        FormaConexao.PEDIDO_SEM_BSSID -> "Primeira conexão com o óculos. Confirme o pedido na tela."
        FormaConexao.PEDIDO_COM_BSSID -> "Conectando ao óculos."
        else -> null
    }

    /** O que dizer ao usuário quando o óculos não foi encontrado. */
    fun orientacaoFalha(conexaoAutomatica: Boolean): String =
        if (conexaoAutomatica) {
            "Óculos não encontrado. Verifique se ele está ligado e perto do celular."
        } else {
            "Óculos não encontrado. Conecte o celular à rede $SSID nas configurações de Wi-Fi."
        }

    /** Descrição curta da forma de conexão, para o modo desenvolvedor. */
    fun descrever(forma: FormaConexao?): String = when (forma) {
        FormaConexao.REDE_JA_CONECTADA -> "o celular já estava na rede do óculos"
        FormaConexao.PEDIDO_COM_BSSID -> "pedido automático, sem confirmação"
        FormaConexao.PEDIDO_SEM_BSSID -> "pedido com confirmação (primeira vez)"
        FormaConexao.SO_MANUAL -> "conexão manual"
        null -> "nenhuma ainda"
    }
}

/** Leva o celular até a rede Wi-Fi do óculos. */
object ConexaoOculos {

    // Faixa de endereços que o Access Point do ESP32 distribui
    private const val PREFIXO_REDE_OCULOS = "192.168.4."

    private const val ARQUIVO_PREFERENCIAS = "eyecam_conexao"
    // BSSID de um óculos aprovado na caixa do Android.
    private const val CHAVE_BSSID = "bssid_aprovado"
    private const val CHAVE_BSSID_ANTIGA = "bssid_oculos"

    // Margem sobre o prazo do próprio Android: se o aviso de "não encontrado" nunca chegar,
    // o app não fica preso esperando
    private const val MARGEM_TEMPO_MS = 5_000L

    // Um pedido por vez: dois toques seguidos não podem abrir dois pedidos
    private val mutex = Mutex()

    // Pedido em andamento.
    @Volatile private var callbackPedido: ConnectivityManager.NetworkCallback? = null
    @Volatile private var redePedida: Network? = null

    /** Forma usada na última conexão bem-sucedida (diagnóstico) */
    @Volatile var ultimaForma: FormaConexao? = null
        private set

    /**
     * Erro técnico da última tentativa de conexão que falhou por exceção (uma permissão
     * faltando, por exemplo).
     */
    @Volatile var ultimoErro: String? = null

    fun registrarErro(e: Throwable) {
        ultimoErro = "${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}"
    }

    /** true se este aparelho e esta compilação permitem conectar sozinho */
    fun conexaoAutomaticaDisponivel(): Boolean =
        Build.VERSION.SDK_INT >= RegrasConexao.SDK_MINIMO_PEDIDO &&
            BuildConfig.OCULOS_SENHA.isNotEmpty()

    /** BSSID do óculos guardado na primeira conexão, ou null */
    fun bssidSalvo(context: Context): String? =
        RegrasConexao.normalizarBssid(preferencias(context).getString(CHAVE_BSSID, null))

    /** Guarda o BSSID informado em /info (ignora se o firmware não informar) */
    fun lembrarBssid(context: Context, jsonInfo: String?) {
        val bssid = RegrasConexao.extrairBssid(jsonInfo) ?: return
        preferencias(context).edit {
            putString(CHAVE_BSSID, bssid)
            remove(CHAVE_BSSID_ANTIGA)
        }
    }

    /** Esquece o óculos: a próxima conexão volta a pedir confirmação. */
    fun esquecerBssid(context: Context) {
        preferencias(context).edit {
            remove(CHAVE_BSSID)
            remove(CHAVE_BSSID_ANTIGA)
        }
    }

    /** Como a próxima conexão vai acontecer (para avisar o usuário antes) */
    fun formaPrevista(context: Context): FormaConexao {
        val cm = gerenciador(context)
        return RegrasConexao.escolherForma(
            sdk = Build.VERSION.SDK_INT,
            temSenha = BuildConfig.OCULOS_SENHA.isNotEmpty(),
            bssid = bssidSalvo(context),
            jaNaRede = redeDoOculosAtual(cm) != null
        )
    }

    /** Localiza a rede do óculos ou a pede ao Android. */
    suspend fun obterRede(context: Context): RedeOculos? = mutex.withLock {
        val app = context.applicationContext
        val cm = gerenciador(app)

        val atual = redeDoOculosAtual(cm)
        if (atual != null) {
            return@withLock registrar(RedeOculos(atual, FormaConexao.REDE_JA_CONECTADA))
        }

        val bssid = bssidSalvo(app)
        val forma = RegrasConexao.escolherForma(
            sdk = Build.VERSION.SDK_INT,
            temSenha = BuildConfig.OCULOS_SENHA.isNotEmpty(),
            bssid = bssid,
            jaNaRede = false
        )

        val rede = if (forma == FormaConexao.SO_MANUAL) {
            // Caminho antigo: usa a primeira Wi-Fi e deixa o ping decidir
            primeiraRedeWifi(cm)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pedirRede(cm, bssid, RegrasConexao.tempoLimiteMs(forma))
        } else {
            null
        }
        rede?.let { registrar(RedeOculos(it, forma)) }
    }

    /** Desfaz o pedido de rede: o celular volta ao Wi-Fi normal. */
    fun liberar(context: Context) = liberarPedido(gerenciador(context))

    /** Resumo para o modo desenvolvedor. */
    fun diagnostico(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        val automatica = when {
            sdk < RegrasConexao.SDK_MINIMO_PEDIDO -> "não (exige Android 10)"
            BuildConfig.OCULOS_SENHA.isEmpty() -> "não (app compilado sem o segredos.h)"
            else -> "sim"
        }
        // O diagnóstico roda ao abrir a tela: uma consulta que falhe vira texto, nunca
        // derruba o app (foi o que a falta de ACCESS_WIFI_STATE fez na primeira versão)
        val duasRedes = try {
            when (suportaDuasRedes(context)) {
                true -> "sim"
                false -> "não (o celular sai do Wi-Fi de casa)"
                null -> "não se aplica (exige Android 12)"
            }
        } catch (e: Exception) {
            "não verificado (${e.javaClass.simpleName})"
        }
        return "Android ${Build.VERSION.RELEASE} (API $sdk) · conexão automática: $automatica\n" +
            "BSSID do óculos: ${bssidSalvo(context) ?: "não aprendido"} · " +
            "duas redes Wi-Fi ao mesmo tempo: $duasRedes\n" +
            "Última conexão: ${RegrasConexao.descrever(ultimaForma)}" +
            (ultimoErro?.let { "\nÚltimo erro de conexão: $it" } ?: "")
    }

    // ------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun pedirRede(
        cm: ConnectivityManager,
        bssid: String?,
        tempoMs: Int
    ): Network? {
        // Um pedido antigo cuja rede caiu ainda pode estar registrado
        liberarPedido(cm)

        val especificador = WifiNetworkSpecifier.Builder()
            .setSsid(RegrasConexao.SSID)
            .setWpa2Passphrase(BuildConfig.OCULOS_SENHA)   // o AP do ESP32 usa WPA2-PSK
            .apply { if (bssid != null) setBssid(MacAddress.fromString(bssid)) }
            .build()

        val pedido = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(especificador)
            .build()

        return withTimeoutOrNull(tempoMs + MARGEM_TEMPO_MS) {
            suspendCancellableCoroutine { continuacao ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        redePedida = network
                        if (continuacao.isActive) continuacao.resume(network)
                    }

                    override fun onUnavailable() {
                        // Não achou no prazo, ou o usuário recusou
                        callbackPedido = null
                        if (continuacao.isActive) continuacao.resume(null)
                    }

                    override fun onLost(network: Network) {
                        // Óculos desligado ou fora de alcance: desfaz o pedido para que a
                        // próxima leitura faça um novo
                        if (network == redePedida) liberarPedido(cm)
                    }
                }
                callbackPedido = callback
                continuacao.invokeOnCancellation { liberarPedido(cm) }
                cm.requestNetwork(pedido, callback, tempoMs)
            }
        }
    }

    private fun liberarPedido(cm: ConnectivityManager) {
        // runCatching: depois de onUnavailable o Android já removeu o pedido, e cancelar de
        // novo lança exceção
        callbackPedido?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callbackPedido = null
        redePedida = null
    }

    /**
     * Rede do óculos à qual o celular já está conectado: o pedido anterior, se ainda
     * estiver de pé, ou uma Wi-Fi com endereço 192.168.4.x (conexão manual).
     */
    private fun redeDoOculosAtual(cm: ConnectivityManager): Network? {
        redePedida?.let { if (cm.getNetworkCapabilities(it) != null) return it }
        return redesWifi(cm).firstOrNull { rede ->
            cm.getLinkProperties(rede)?.linkAddresses?.any { endereco ->
                endereco.address.hostAddress?.startsWith(PREFIXO_REDE_OCULOS) == true
            } == true
        }
    }

    private fun primeiraRedeWifi(cm: ConnectivityManager): Network? = redesWifi(cm).firstOrNull()

    // allNetworks está obsoleto desde o Android 12; a alternativa (NetworkCallback) é
    // assíncrona e não traz ganho aqui, onde só interessa o retrato das redes no instante
    // do toque.
    @Suppress("DEPRECATION")
    private fun redesWifi(cm: ConnectivityManager): List<Network> =
        cm.allNetworks.filter { rede ->
            cm.getNetworkCapabilities(rede)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    /** null quando o Android é anterior ao 12 (o recurso não existe) */
    private fun suportaDuasRedes(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.isStaConcurrencyForLocalOnlyConnectionsSupported
        } else {
            null
        }

    private fun registrar(rede: RedeOculos): RedeOculos {
        ultimaForma = rede.forma
        ultimoErro = null
        return rede
    }

    private fun gerenciador(context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private fun preferencias(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO_PREFERENCIAS, Context.MODE_PRIVATE)
}
