package com.eyecam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppEyeCam()
            }
        }
    }

    override fun onDestroy() {
        // Ao fechar o app, desfaz o pedido da rede do óculos: o celular volta ao Wi-Fi
        // normal.
        if (isFinishing) EyeCamClient.desconectar(this)
        super.onDestroy()
    }
}

/** As telas do app: a do usuário final e as duas de testes */
enum class Tela { ACESSIVEL, DESENVOLVEDOR, BANCADA }

@Composable
fun AppEyeCam() {
    // Controla qual tela está visível. rememberSaveable mantém a tela quando o Android
    // recria a Activity (mudança de tema, de tamanho de fonte etc.).
    var tela by rememberSaveable { mutableStateOf(Tela.ACESSIVEL) }

    // O Voltar do sistema volta uma tela (bancada → desenvolvedor → acessível) em vez de
    // fechar o app no meio de um teste
    BackHandler(enabled = tela != Tela.ACESSIVEL) {
        tela = if (tela == Tela.BANCADA) Tela.DESENVOLVEDOR else Tela.ACESSIVEL
    }

    when (tela) {
        Tela.ACESSIVEL -> TelaAcessivel(onAbrirDev = { tela = Tela.DESENVOLVEDOR })
        Tela.DESENVOLVEDOR -> TelaDesenvolvedor(
            onVoltar = { tela = Tela.ACESSIVEL },
            onAbrirBancada = { tela = Tela.BANCADA }
        )
        Tela.BANCADA -> TelaTestes(onVoltar = { tela = Tela.DESENVOLVEDOR })
    }
}
