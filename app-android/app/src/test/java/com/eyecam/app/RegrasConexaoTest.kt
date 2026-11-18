package com.eyecam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes das regras da conexão automática com o óculos (RegrasConexao). */
class RegrasConexaoTest {

    private val bssid = "aa:bb:cc:dd:ee:ff"

    @Test
    fun jaNaRede_naoPedeNada() {
        // Mesmo sem senha ou em Android antigo: se já está na rede, usa
        val forma = RegrasConexao.escolherForma(sdk = 26, temSenha = false, bssid = null, jaNaRede = true)
        assertEquals(FormaConexao.REDE_JA_CONECTADA, forma)
    }

    @Test
    fun androidAnteriorAo10_soManual() {
        val forma = RegrasConexao.escolherForma(sdk = 28, temSenha = true, bssid = bssid, jaNaRede = false)
        assertEquals(FormaConexao.SO_MANUAL, forma)
    }

    @Test
    fun appSemSenha_soManual() {
        val forma = RegrasConexao.escolherForma(sdk = 34, temSenha = false, bssid = bssid, jaNaRede = false)
        assertEquals(FormaConexao.SO_MANUAL, forma)
    }

    @Test
    fun primeiraVez_pedeComConfirmacao() {
        val forma = RegrasConexao.escolherForma(sdk = 29, temSenha = true, bssid = null, jaNaRede = false)
        assertEquals(FormaConexao.PEDIDO_SEM_BSSID, forma)
    }

    @Test
    fun oculosConhecido_pedeSemConfirmacao() {
        val forma = RegrasConexao.escolherForma(sdk = 34, temSenha = true, bssid = bssid, jaNaRede = false)
        assertEquals(FormaConexao.PEDIDO_COM_BSSID, forma)
    }

    @Test
    fun primeiraVez_temPrazoMaiorParaOUsuarioConfirmar() {
        assertTrue(
            RegrasConexao.tempoLimiteMs(FormaConexao.PEDIDO_SEM_BSSID) >
                RegrasConexao.tempoLimiteMs(FormaConexao.PEDIDO_COM_BSSID)
        )
    }

    @Test
    fun bssid_maiusculoDoFirmwareViraMinusculo() {
        // WiFi.softAPmacAddress() devolve "AA:BB:CC:DD:EE:FF"
        assertEquals(bssid, RegrasConexao.normalizarBssid(" AA:BB:CC:DD:EE:FF "))
    }

    @Test
    fun bssid_invalidoEhRecusado() {
        assertNull(RegrasConexao.normalizarBssid(null))
        assertNull(RegrasConexao.normalizarBssid(""))
        assertNull(RegrasConexao.normalizarBssid("aa:bb:cc:dd:ee"))
        assertNull(RegrasConexao.normalizarBssid("aa-bb-cc-dd-ee-ff"))
        assertNull(RegrasConexao.normalizarBssid("gg:bb:cc:dd:ee:ff"))
        // O Android recusa estes dois num pedido de rede
        assertNull(RegrasConexao.normalizarBssid("00:00:00:00:00:00"))
        assertNull(RegrasConexao.normalizarBssid("FF:FF:FF:FF:FF:FF"))
    }

    @Test
    fun extraiBssidDoJsonDoFirmware55() {
        val json = """{"dispositivo":"Eye-cam","versao_firmware":"5.5.0",""" +
            """"ap_ssid":"EyeCam_01","ap_ip":"192.168.4.1","ap_bssid":"30:30:F9:12:AB:CD",""" +
            """"clientes_conectados":1}"""
        assertEquals("30:30:f9:12:ab:cd", RegrasConexao.extrairBssid(json))
    }

    @Test
    fun firmwareAntigoSemBssid_naoAprendeNada() {
        val json = """{"dispositivo":"Eye-cam","versao_firmware":"5.4.0","ap_ip":"192.168.4.1"}"""
        assertNull(RegrasConexao.extrairBssid(json))
        assertNull(RegrasConexao.extrairBssid(null))
    }

    @Test
    fun bssid_soEhAprendidoNoPedidoConfirmadoPeloUsuario() {
        assertTrue(RegrasConexao.deveAprenderBssid(FormaConexao.PEDIDO_SEM_BSSID))
        // Conexão manual não é aprovação para o Android: guardar o BSSID ali fazia o pedido
        // seguinte cancelar a caixa de confirmação aos 12 s
        assertFalse(RegrasConexao.deveAprenderBssid(FormaConexao.REDE_JA_CONECTADA))
        assertFalse(RegrasConexao.deveAprenderBssid(FormaConexao.SO_MANUAL))
        // Já aprendido: nada a mudar
        assertFalse(RegrasConexao.deveAprenderBssid(FormaConexao.PEDIDO_COM_BSSID))
    }

    @Test
    fun avisos_soQuandoAConexaoDemoraOuPedeConfirmacao() {
        assertNotNull(RegrasConexao.avisoAntesDeConectar(FormaConexao.PEDIDO_SEM_BSSID))
        assertNotNull(RegrasConexao.avisoAntesDeConectar(FormaConexao.PEDIDO_COM_BSSID))
        assertNull(RegrasConexao.avisoAntesDeConectar(FormaConexao.REDE_JA_CONECTADA))
        assertNull(RegrasConexao.avisoAntesDeConectar(FormaConexao.SO_MANUAL))
    }

    @Test
    fun orientacaoDeFalha_mandaConectarAMaoSoSemConexaoAutomatica() {
        assertTrue(RegrasConexao.orientacaoFalha(conexaoAutomatica = false).contains(RegrasConexao.SSID))
        assertTrue(RegrasConexao.orientacaoFalha(conexaoAutomatica = true).contains("ligado"))
    }
}
