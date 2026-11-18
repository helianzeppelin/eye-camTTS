# Eye-cam

Leitor de texto assistivo para pessoas com baixa visão. Uma câmera presa ao
óculos (ESP32-S3 + OV5640 com autofoco) fotografa a página, e um app Android
reconhece o texto (ML Kit) e o lê em voz alta, em português. Funciona sem
internet.

```
[óculos: ESP32-S3 + OV5640]  --Wi-Fi próprio (192.168.4.1)-->  [celular: ML Kit + voz]
```

## Estrutura

| Pasta | Conteúdo |
|---|---|
| `app-android/` | App Android (Kotlin, Jetpack Compose) |
| `firmware/eyecam_firmware/` | Firmware para a placa ligada ao USB (v5.6.4) |
| `firmware/eyecam_firmware_energia/` | Firmware para o aparelho com bateria (v5.7.4): trava de energia, botão e medição da bateria |

## Firmware

1. Arduino IDE 2 com o pacote **esp32** (3.0 ou superior) e a biblioteca
   **OV5640 Auto Focus for ESP32 Camera**.
2. Na pasta do sketch, copie `segredos.exemplo.h` para `segredos.h` e defina a
   senha do Wi-Fi (8 a 63 caracteres). Esse arquivo não vai para o Git.
3. Placa: **ESP32S3 Dev Module**, Flash 16MB, partição
   "16M Flash (3MB APP/9.9MB FATFS)", **PSRAM OPI**, USB CDC On Boot: Enabled.
4. Grave pela porta USB nativa.

O óculos cria a rede `EyeCam_01`. Rotas principais: `/capture` (foto JPEG),
`/info` (estado em JSON) e `/log` (mensagens do firmware).

## App

**Pelo APK:** baixe o [`apk/eyecam.apk`](apk/eyecam.apk) e instale no celular
(Android 8 ou superior). Conecte o celular à rede `EyeCam_01` nas
configurações de Wi-Fi antes de usar.

**Compilando:** abra `app-android/` no Android Studio e clique em Run. Se o
`segredos.h` existir, o app compilado conecta sozinho à rede do óculos
(Android 10 ou superior). Não distribua um APK compilado assim, porque a senha
vai dentro dele.

### Uso

| Gesto | Ação |
|---|---|
| Toque duplo | Fotografa e lê |
| Deslizar para a direita | Repete a leitura |
| Deslizar para a esquerda | Para a leitura |
| Tocar e segurar | Testa a conexão |

O app funciona com o TalkBack. O botão "Dev" abre o modo de testes.

## Licença

[GPL-3.0](LICENSE). O portão de estabilidade (`Estabilidade.kt`) adapta
funções do [GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner).
