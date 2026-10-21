/*
 * ============================================================================
 * Eye-cam - Óculos Assistivo para Leitura de Texto
 * ============================================================================
 *
 * Firmware do módulo ESP32-S3-CAM N16R8 + OV5640 AF (autofoco)
 * VERSÃO 5.6.2 - Configuração da câmera zerada (correção do FB-OVF)
 *
 * ----------------------------------------------------------------------------
 * MUDANÇAS DA v5.6.1 PARA A v5.6.2
 * ----------------------------------------------------------------------------
 *
 *   Com a v5.6.1 o boot ficou limpo e o foco travou, mas TODA foto falhava,
 *   com dezenas de FB-OVF, mesmo a 10 MHz e em SVGA, em que os dados chegam
 *   devagar. A causa não era o clock:
 *
 *   1. A ESTRUTURA camera_config_t NÃO ERA ZERADA. Os campos que o código não
 *      preenchia ficavam com lixo da memória. O driver do núcleo 3.3.x tem o
 *      campo jpeg_buffer_size (tamanho do buffer da foto; 0 = padrão): com
 *      lixo nele, o buffer saía de tamanho aleatório e toda foto transbordava.
 *      Agora "camera_config_t config = {}" zera tudo.
 *
 *   2. BUFFER INFORMADO DIRETAMENTE (jpeg_buffer_size = 1,2 MB, para 5 MP).
 *      A câmera inicia em XGA, a resolução de trabalho, como na v5.2. Iniciar
 *      em QSXGA só para dimensionar o buffer (v5.4) deixou de ser preciso.
 *
 *   3. FOCO: 4 s por tentativa (eram 2,5 s). A 10 MHz o foco levou 2,0–2,4 s,
 *      e a primeira tentativa às vezes esgotava o tempo.
 *
 *   O XCLK continua em 10 MHz, por precaução: a hipótese da v5.6.1 (20 MHz
 *   rápido demais para a cópia do driver) não foi confirmada, porque o
 *   buffer errado causava FB-OVF em qualquer clock. Pode voltar a ser
 *   testado com o buffer certo (foco mais rápido).
 *
 * ----------------------------------------------------------------------------
 * MUDANÇAS DA v5.6 PARA A v5.6.1
 * ----------------------------------------------------------------------------
 *
 *   No primeiro teste da v5.6 na placa (núcleo ESP32 3.3.12), o log encheu de
 *   "cam_hal: FB-OVF" a partir de "Ajustando XCLK: 10 -> 20 MHz" e não parou
 *   mais, nem com a câmera parada; o Wi-Fi ficou lento e nenhuma foto saía.
 *
 *   1. XCLK DE 10 MHz EM TODAS AS RESOLUÇÕES. No driver deste núcleo o modo
 *      PSRAM DMA vem desligado: o processador copia cada pedaço do quadro
 *      para a PSRAM. A 20 MHz o sensor manda o dobro de dados por segundo
 *      (no driver da OV5640 o clock de pixel é proporcional ao XCLK) e a
 *      cópia não acompanha: o fim do quadro se perde, os quadros se somam e
 *      o buffer transborda sem parar. 10 MHz já funcionava em UXGA (v5.2).
 *      O custo é um quadro mais lento em resolução baixa, o que pode alongar
 *      o foco: medir no teste F3.
 *
 *   2. A CONTAGEM DE FB-OVF SÓ CONTA. Na v5.6 ela repassava cada caractere à
 *      UART (115200 baud, ~87 µs por caractere) dentro do driver da câmera;
 *      se o canal 1 estava desligado antes, isso atrasava o driver a cada
 *      aviso. Agora o canal 1 só conta; a saída USB (canal 2) não muda.
 *
 * ----------------------------------------------------------------------------
 * MUDANÇAS DA v5.5 PARA A v5.6
 * ----------------------------------------------------------------------------
 *
 *   Os testes de bancada são feitos sem o computador, e o Serial Monitor
 *   era o único lugar onde apareciam o tempo do foco, as falhas e o FB-OVF.
 *   Agora o firmware manda esses dados ao app:
 *
 *   1. CABEÇALHOS DE MEDIÇÃO em cada resposta de /capture (também nas de
 *      erro): tempo e tentativas do foco, tempo da troca de resolução, do
 *      descarte e da captura do quadro, resolução real, clock, qualidade,
 *      número da captura desde o boot e avisos do driver da câmera.
 *
 *   2. ROTA /log: as últimas 64 mensagens do firmware, numeradas. O app pede
 *      só as novas (/log?desde=N) e as guarda junto de cada foto. A senha do
 *      Wi-Fi continua só no Serial.
 *
 *   3. CONTAGEM DO FB-OVF (experimental). O driver da câmera imprime o aviso
 *      direto na saída de console, pelo printf da ROM, sem passar pelo
 *      firmware. O firmware se instala no canal 1 dessa saída (a UART) e
 *      conta as mensagens "FB-OVF" e "cam_hal:" (na v5.6.1, sem repassá-las
 *      à UART). A saída USB usa outro canal e não muda. Validar uma vez: a
 *      contagem precisa bater com o Serial Monitor.
 *
 *   4. MOTIVO DO ÚLTIMO RESET em /info e /log: mostra se a placa reiniciou
 *      por queda de tensão (brownout), o que importa na bateria.
 *
 * ----------------------------------------------------------------------------
 * MUDANÇAS DA v5.4 PARA A v5.5
 * ----------------------------------------------------------------------------
 *
 *   1. CAMPO ap_bssid EM /info: o endereço MAC do Access Point (BSSID). O
 *      app passou a pedir a rede do óculos ao próprio Android
 *      (WifiNetworkSpecifier), em vez de exigir que o usuário a escolha nas
 *      configurações de Wi-Fi. Na primeira vez o Android pede uma
 *      confirmação; depois, se o pedido trouxer o nome da rede E o BSSID
 *      exatos, ele conecta sem perguntar. O app lê o BSSID aqui, na primeira
 *      conexão, e o guarda. Nada muda no Access Point em si.
 *
 * ----------------------------------------------------------------------------
 * MUDANÇAS DA v5.3 PARA A v5.4
 * ----------------------------------------------------------------------------
 *
 *   1. RESOLUÇÕES QXGA (2048x1536) E QSXGA (2560x1920, "5mp"). Testes com
 *      uma câmera virtual mostraram que o acerto do OCR depende sobretudo de
 *      quantos pixels a letra tem: texto de 10 pt a 30 cm fica com ~16 px
 *      por em em UXGA (no limite) e ~26 px em QSXGA (com folga). O sensor é
 *      de 5 MP e o driver aceita essas resoluções; a v5.3 parava em UXGA.
 *      O buffer JPEG agora é reservado para QSXGA (~983 KB, em PSRAM). Se
 *      o init em 5 MP falhar nesta placa, o firmware volta sozinho ao buffer
 *      UXGA e recusa capturas maiores (campo resolucao_maxima em /info).
 *
 *   2. ROTA /tune PARA A BANCADA DE TESTES. Ajusta nitidez (sharpness),
 *      redução de ruído (denoise), contraste e qualidade do JPEG sem
 *      regravar o firmware, e devolve os valores em uso. Os mesmos valores
 *      aparecem em /info, para registro nos testes.
 *
 * ----------------------------------------------------------------------------
 * MUDANÇAS DA v5.2 PARA A v5.3
 * ----------------------------------------------------------------------------
 *
 *   1. BUFFER JPEG PARA UXGA. O driver da câmera reserva o buffer JPEG uma
 *      única vez, no esp_camera_init, com largura x altura / 5 bytes da
 *      resolução de init (CONFIG_CAMERA_JPEG_MODE_FRAME_SIZE_AUTO). A v5.2
 *      iniciava em XGA, deixando ~157 KB: uma foto UXGA de página cheia de
 *      texto (muito detalhe = JPEG maior) podia não caber, e o driver também
 *      acusa FB-OVF nesse caso. Isso explicava o FB-OVF residual em UXGA
 *      mesmo com o XCLK a 10 MHz. Agora o init é feito em UXGA (~384 KB) e a
 *      resolução cai para a padrão logo em seguida. É o mesmo procedimento
 *      do exemplo oficial CameraWebServer ("init with UXGA resolution ...
 *      for larger pre-allocated frame buffer").
 *
 *   2. CAPTURA SOB DEMANDA (GRAB_WHEN_EMPTY) COM UM ÚNICO BUFFER. Com
 *      GRAB_LATEST a câmera captura sem parar, mantendo a PSRAM ocupada e
 *      gerando FB-OVF em segundo plano. Com GRAB_WHEN_EMPTY ela só captura
 *      quando um buffer é liberado; porém, segundo o esp_camera.h, "the
 *      first 'fb_count' frames might be old". Com 2 buffers e 1 descarte
 *      (como a v5.2 fazia em alta resolução), a foto sairia ANTERIOR ao
 *      autofoco. Com 1 buffer, descartar 1 quadro sempre basta.
 *
 *   3. AP_MAX_CONN = 1: só o celular do usuário se conecta ao óculos.
 *
 *   4. Removido o cabeçalho Content-Type duplicado na resposta de /capture.
 *
 * ----------------------------------------------------------------------------
 * HISTÓRICO: O PROBLEMA RESOLVIDO NA v5.2
 * ----------------------------------------------------------------------------
 *
 * Em UXGA (1600x1200), o log era inundado por "cam_hal: FB-OVF" e a captura
 * falhava. FB-OVF significa Frame Buffer Overflow: o sensor entrega pixels
 * mais rápido do que o ESP32 consegue gravar na PSRAM, e o buffer transborda.
 *
 * Causa: o clock da câmera (XCLK) estava fixo em 20 MHz. Esse valor é
 * adequado para a OV2640 (2MP), mas a OV5640 em UXGA gera cerca de 4x mais
 * dados por frame. Somado à disputa por banda de PSRAM com o Wi-Fi em modo
 * Access Point, o resultado é transbordo constante.
 *
 * Solução: XCLK DINÂMICO. O driver permite alterar o clock em tempo de
 * execução, então usamos 20 MHz nas resoluções baixas (rápido) e caímos
 * para 10 MHz apenas quando a resolução exige (SXGA e acima). O frame
 * demora mais, mas chega íntegro.
 *
 * Mudanças da v5.1 para a v5.2:
 *   1. XCLK dinâmico conforme a resolução (correção principal do FB-OVF).
 *   2. Qualidade JPEG adaptativa: arquivos menores em alta resolução,
 *      reduzindo tempo de transferência.
 *   3. Menos frames descartados em alta resolução (cada frame custa caro).
 *   4. Removido o relatório de "posição da lente": os registros lidos não
 *      correspondem à posição real do foco neste módulo, e os valores
 *      exibidos não tinham significado.
 *   5. Modo de captura configurável (GRAB_LATEST x GRAB_WHEN_EMPTY) para
 *      o caso de o FB-OVF persistir.
 *
 * ----------------------------------------------------------------------------
 * PRÉ-REQUISITOS
 * ----------------------------------------------------------------------------
 *
 * BIBLIOTECA: "OV5640 Auto Focus for ESP32 Camera" (Library Manager)
 *
 * SENHA DO WI-FI: copie segredos.exemplo.h para segredos.h, nesta mesma
 *   pasta, e defina EYECAM_AP_SENHA.
 *
 * PLACA (ESP32-S3 N16R8):
 *   Board: ESP32S3 Dev Module | Flash Size: 16MB (128Mb)
 *   Partition: 16M Flash (3MB APP/9.9MB FATFS) | PSRAM: OPI PSRAM
 *   USB CDC On Boot: Enabled | USB Mode: Hardware CDC and JTAG
 *
 * ----------------------------------------------------------------------------
 * COMANDOS HTTP
 * ----------------------------------------------------------------------------
 *   /ping     -> "OK"
 *   /capture  -> foca e retorna a foto JPEG
 *   /focus    -> diagnóstico do autofoco
 *   /info     -> informações do dispositivo (JSON)
 *   /tune     -> ajusta e informa nitidez, ruído, contraste e qualidade (JSON)
 *   /log      -> últimas mensagens do firmware (?desde=N: só as novas)
 *
 * Parâmetros de /capture:
 *   ?res=qvga|vga|svga|xga|sxga|uxga|qxga|qsxga  (5mp = qsxga)
 *   ?af=0     -> captura sem focar (controle experimental)
 *
 * Parâmetros de /tune (todos opcionais; sem parâmetros, só informa):
 *   ?sharpness=-3..3   nitidez do ISP do sensor
 *   ?denoise=0..8      redução de ruído (0 = desligada)
 *   ?contrast=-2..2
 *   ?quality=4..63     qualidade do JPEG (menor = melhor); 0 = automática
 *   ?reset=1           volta aos valores do firmware
 *
 * ============================================================================
 */

#include "esp_camera.h"
#include <WiFi.h>
#include <WebServer.h>
#include <stdarg.h>
#include "esp_system.h"      // esp_reset_reason
#include "esp_rom_sys.h"     // esp_rom_install_channel_putc
#include "ESP32_OV5640_AF.h"

// ============================================================================
// CONFIGURAÇÕES DA REDE WI-FI (modo AP)
// ============================================================================
// A senha da rede fica em segredos.h, que NÃO vai para o GitHub (está no
// .gitignore). Motivo: o repositório é público, e quem conhece a senha pode
// se conectar ao óculos e capturar fotos. Na primeira instalação, copie
// segredos.exemplo.h para segredos.h e defina uma senha própria.

#if __has_include("segredos.h")
#include "segredos.h"
#else
#error "Falta segredos.h: copie segredos.exemplo.h para segredos.h e defina a senha (docs/INSTALACAO.md)"
#endif

// O WPA2 exige senha de 8 a 63 caracteres; fora disso o softAP() falha
// em tempo de execução. Melhor descobrir na compilação.
static_assert(sizeof(EYECAM_AP_SENHA) - 1 >= 8 && sizeof(EYECAM_AP_SENHA) - 1 <= 63,
              "EYECAM_AP_SENHA precisa ter de 8 a 63 caracteres");

const char* AP_SSID     = "EyeCam_01";
const char* AP_PASSWORD = EYECAM_AP_SENHA;
const int   AP_CHANNEL  = 1;

// Apenas o celular do usuário. Com mais vagas, outra pessoa que soubesse a
// senha poderia se conectar e capturar fotos enquanto o óculos está em uso.
const int   AP_MAX_CONN = 1;

// ============================================================================
// CONFIGURAÇÕES DA CÂMERA
// ============================================================================

const framesize_t RESOLUCAO_PADRAO = FRAMESIZE_XGA;

// Tamanho do buffer JPEG, em bytes, informado ao driver (jpeg_buffer_size).
// O driver o reserva uma única vez, no esp_camera_init, e um JPEG maior que
// ele é perdido (FB-OVF). Precisa caber a MAIOR foto que o app pede: QSXGA
// ("5mp", 2560x1920, o sensor inteiro). A regra padrão do driver daria
// largura x altura / 5 = 983 KB; uma página cheia de texto tem muito detalhe
// e gera JPEG grande, por isso a folga. Fica na PSRAM (8 MB).
//
// Até a v5.6.1 o tamanho vinha da resolução de init (a câmera iniciava em
// QSXGA só para isso). Com este campo, ela inicia direto na resolução de
// trabalho, como na v5.2.
const size_t BUFFER_JPEG_5MP  = 1200 * 1024;
// Reserva, se a PSRAM não comportar o de 5 MP: só até UXGA
const size_t BUFFER_JPEG_UXGA = 1600 * 1200 / 5;

// ---------------------------------------------------------------------------
// CLOCK DA CÂMERA (XCLK) - correção do FB-OVF
// ---------------------------------------------------------------------------
// Clock alto = frames rápidos, mas mais dados por segundo para a PSRAM.
// Acima do que a cópia do driver acompanha, o buffer transborda (FB-OVF).
//
// v5.6.1: 10 MHz também em resolução baixa, por precaução (ver o cabeçalho:
// o FB-OVF da v5.6 vinha do buffer de tamanho errado, não do clock). A 10 MHz
// o foco em SVGA leva ~2 s; com 20 MHz (v5.2) levava ~1 s. Para testar de
// novo, volte XCLK_PADRAO_MHZ a 20, ou 16 (o driver da OV5640 tem um ajuste
// próprio para 16 MHz). Se o FB-OVF aparecer em 5 MP, reduza
// XCLK_ALTA_RES_MHZ para 8.
// ---------------------------------------------------------------------------
const int XCLK_PADRAO_MHZ   = 10;   // resoluções até XGA
const int XCLK_ALTA_RES_MHZ = 10;   // SXGA e acima

// A partir desta resolução, o firmware considera "alta resolução" e aplica
// o clock reduzido, a qualidade adaptativa e menos descartes de frame.
const framesize_t LIMITE_ALTA_RES = FRAMESIZE_SXGA;

// ---------------------------------------------------------------------------
// QUALIDADE JPEG (0-63; MENOR número = melhor qualidade, arquivo maior)
// ---------------------------------------------------------------------------
const int QUALIDADE_PADRAO   = 12;
const int QUALIDADE_ALTA_RES = 15;  // arquivo menor, transferência mais rápida

// ---------------------------------------------------------------------------
// AJUSTES DE IMAGEM (alteráveis em tempo de execução pela rota /tune)
// ---------------------------------------------------------------------------
// No boot nada é alterado: o sensor fica com os valores de fábrica do driver
// (a nitidez de fábrica da OV5640 é AUTOMÁTICA; chamar set_sharpness(0) a
// trocaria por manual). A bancada de testes usa /tune para comparar, por
// exemplo, nitidez de fábrica x +2, sem regravar o firmware.
const int AJUSTE_FABRICA = -99;   // marcador de "não alterado"

// ---------------------------------------------------------------------------
// MODO DE CAPTURA
// ---------------------------------------------------------------------------
// true  = CAMERA_GRAB_LATEST: a câmera captura continuamente e entrega o
//         frame mais recente. Melhor latência, mas carga constante na PSRAM.
//         Usa 2 buffers.
// false = CAMERA_GRAB_WHEN_EMPTY: a câmera só captura quando um buffer é
//         liberado. Muito menos carga de memória. Usa 1 buffer, para que
//         descartar 1 quadro garanta uma foto do instante atual.
//
// Se a foto voltar a mostrar a cena de instantes antes (teste do
// cronômetro), aumente os descartes em handler_capture.
// ---------------------------------------------------------------------------
const bool USAR_GRAB_LATEST = false;

// ---------------------------------------------------------------------------
// ORIENTAÇÃO DA IMAGEM (valores confirmados experimentalmente)
// ---------------------------------------------------------------------------
const int FLIP_VERTICAL      = 1;
const int ESPELHO_HORIZONTAL = 0;

// ---------------------------------------------------------------------------
// AUTOFOCO
// ---------------------------------------------------------------------------
const bool MODO_AF_CONTINUO = false;   // false = foco único por captura
// 4 s por tentativa: com XCLK de 10 MHz os quadros chegam na metade da
// velocidade e o foco em SVGA levou ~2,0–2,4 s no primeiro teste da v5.6.1;
// com 2,5 s a primeira tentativa esgotou o tempo e o foco recomeçou do zero
const unsigned long TIMEOUT_FOCO_MS = 4000;
const int TENTATIVAS_FOCO = 2;

// Registros do firmware de autofoco da OV5640
const uint16_t REG_AF_COMANDO = 0x3022;
const uint16_t REG_AF_STATUS  = 0x3029;

const uint8_t CMD_AF_UNICO      = 0x03;
const uint8_t AF_STATUS_FOCADO  = 0x10;
const uint8_t AF_STATUS_PRONTO  = 0x70;

// ============================================================================
// PINOUT - ESP32-S3-CAM N16R8
// ============================================================================

#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     15
#define SIOD_GPIO_NUM      4
#define SIOC_GPIO_NUM      5
#define Y9_GPIO_NUM       16
#define Y8_GPIO_NUM       17
#define Y7_GPIO_NUM       18
#define Y6_GPIO_NUM       12
#define Y5_GPIO_NUM       10
#define Y4_GPIO_NUM        8
#define Y3_GPIO_NUM        9
#define Y2_GPIO_NUM       11
#define VSYNC_GPIO_NUM     6
#define HREF_GPIO_NUM      7
#define PCLK_GPIO_NUM     13

// ============================================================================
// VARIÁVEIS GLOBAIS
// ============================================================================

WebServer servidor(80);
OV5640 ov5640 = OV5640();

bool autofoco_ativo = false;
String sensor_detectado = "desconhecido";

// Estado atual, para não reconfigurar o que já está correto
framesize_t resolucao_atual = RESOLUCAO_PADRAO;
int xclk_atual_mhz = XCLK_PADRAO_MHZ;

// Maior resolução que o buffer reservado comporta (cai para UXGA se a PSRAM
// não comportar o buffer de 5 MP) e o tamanho efetivamente reservado
framesize_t resolucao_maxima = FRAMESIZE_QSXGA;
size_t tamanho_buffer_jpeg = BUFFER_JPEG_5MP;

// Ajustes feitos por /tune. AJUSTE_FABRICA = não alterado desde o boot;
// qualidade_manual 0 = qualidade automática por resolução.
int nitidez_atual    = AJUSTE_FABRICA;
int denoise_atual    = AJUSTE_FABRICA;
int contraste_atual  = AJUSTE_FABRICA;
int qualidade_manual = 0;

// Medições do último foco (para os cabeçalhos de /capture)
unsigned long foco_ultimo_ms = 0;
int foco_ultimas_tentativas = 0;

// Capturas atendidas desde o boot. Se o número voltar a 1 no meio de um
// teste, a placa reiniciou (por exemplo, queda de tensão)
uint32_t capturas_desde_boot = 0;

// ============================================================================
// REGISTRO PARA O APP (rota /log)
// ============================================================================
// Nos testes de bancada não há computador com o Serial Monitor. Cada mensagem
// do firmware vai para o Serial E para esta memória circular com as últimas
// LOG_LINHAS linhas, numeradas. O app pede só as novas (/log?desde=N) e as
// guarda junto de cada foto. Linhas fixas em vez de String: nada de alocar e
// fragmentar a memória a cada mensagem.

const int LOG_LINHAS  = 64;
const int LOG_TAMANHO = 112;

char     log_texto[LOG_LINHAS][LOG_TAMANHO];
uint32_t log_tempo_ms[LOG_LINHAS];
uint32_t log_ultima = 0;   // número da última linha registrada (começa em 1)

void registra(const char* formato, ...) {
    char linha[LOG_TAMANHO];
    va_list argumentos;
    va_start(argumentos, formato);
    vsnprintf(linha, sizeof(linha), formato, argumentos);
    va_end(argumentos);

    Serial.println(linha);

    log_ultima++;
    int i = log_ultima % LOG_LINHAS;
    memcpy(log_texto[i], linha, LOG_TAMANHO);
    log_tempo_ms[i] = millis();
}

// ============================================================================
// CONTAGEM DOS AVISOS DO DRIVER DA CÂMERA (experimental)
// ============================================================================
// O driver imprime "cam_hal: FB-OVF" (e outros avisos "cam_hal: ...") direto
// pelo printf da ROM, sem passar pelo firmware. Esse printf entrega cada
// caractere a dois canais: o 1 é a UART e o 2, a USB do Serial Monitor.
// O firmware troca a função do canal 1 por espia_console(), que só procura
// os textos abaixo e conta. O canal 2 não é tocado: o Serial Monitor continua
// igual.
//
// Não repassa nada à UART (a v5.6 repassava): escrever na UART a 115200 baud
// leva ~87 µs por caractere, e isso acontecia dentro do driver da câmera, a
// cada aviso, atrasando justamente a cópia que transbordava. Ninguém lê a
// UART neste projeto (o Serial Monitor usa a USB nativa).
//
// Roda dentro do driver, às vezes em interrupção: fica na IRAM, usa só
// variáveis simples e não chama nada do Arduino.

volatile uint32_t ovf_total = 0;          // "FB-OVF" desde o boot
volatile uint32_t avisos_cam_total = 0;   // linhas "cam_hal:" desde o boot

DRAM_ATTR static const char PADRAO_OVF[] = "FB-OVF";
DRAM_ATTR static const char PADRAO_CAM[] = "cam_hal:";
static uint8_t casa_ovf = 0;
static uint8_t casa_cam = 0;

// Avança a comparação com o padrão; devolve quantos caracteres já batem
static uint8_t IRAM_ATTR avanca_padrao(const char* padrao, uint8_t casa, char c) {
    if (c == padrao[casa]) return casa + 1;
    return (c == padrao[0]) ? 1 : 0;
}

static void IRAM_ATTR espia_console(char c) {
    casa_ovf = avanca_padrao(PADRAO_OVF, casa_ovf, c);
    if (PADRAO_OVF[casa_ovf] == '\0') { ovf_total = ovf_total + 1; casa_ovf = 0; }

    casa_cam = avanca_padrao(PADRAO_CAM, casa_cam, c);
    if (PADRAO_CAM[casa_cam] == '\0') { avisos_cam_total = avisos_cam_total + 1; casa_cam = 0; }
}

// Motivo do último reset, em texto curto para /info e /log
const char* motivo_reset() {
    switch (esp_reset_reason()) {
        // No ESP32-S3 o botão RESET (pino EN) também conta como "ligada"
        case ESP_RST_POWERON:   return "ligada_ou_botao_reset";
        case ESP_RST_SW:        return "reinicio_pelo_firmware";
        case ESP_RST_PANIC:     return "erro_fatal";
        case ESP_RST_INT_WDT:
        case ESP_RST_TASK_WDT:
        case ESP_RST_WDT:       return "watchdog";
        case ESP_RST_BROWNOUT:  return "queda_de_tensao";
        case ESP_RST_EXT:       return "pino_externo";
        case ESP_RST_USB:       return "usb";
        case ESP_RST_DEEPSLEEP: return "deep_sleep";
        default:                return "outro";
    }
}

// ============================================================================
// SETUP
// ============================================================================

void setup() {
    Serial.begin(115200);
    delay(1000);

    // Antes da câmera, para contar desde o primeiro aviso do driver
    esp_rom_install_channel_putc(1, espia_console);

    Serial.println("\n========================================");
    registra("Eye-cam - Iniciando firmware v5.6.2");
    registra("ESP32-S3-CAM N16R8 + OV5640 AF");
    registra("Modo de foco: %s", MODO_AF_CONTINUO ? "CONTINUO" : "UNICO (por captura)");
    registra("Modo de captura: %s", USAR_GRAB_LATEST ? "GRAB_LATEST" : "GRAB_WHEN_EMPTY");
    registra("Motivo do reset: %s", motivo_reset());
    Serial.println("========================================\n");

    if (!inicializa_camera()) {
        registra("[ERRO] Falha ao inicializar camera.");
        registra("       Verifique o cabo flat e a PSRAM (OPI).");
        delay(5000);
        ESP.restart();
    }
    registra("[OK] Camera inicializada");

    inicializa_autofoco();

    if (!inicia_access_point()) {
        registra("[ERRO] Falha ao iniciar Access Point.");
        delay(10000);
        ESP.restart();
    }

    servidor.on("/ping",    HTTP_GET, handler_ping);
    servidor.on("/capture", HTTP_GET, handler_capture);
    servidor.on("/focus",   HTTP_GET, handler_focus);
    servidor.on("/info",    HTTP_GET, handler_info);
    servidor.on("/tune",    HTTP_GET, handler_tune);
    servidor.on("/log",     HTTP_GET, handler_log);
    servidor.onNotFound(handler_404);

    servidor.begin();
    registra("[OK] Servidor HTTP iniciado");

    Serial.println("========================================");
    Serial.println("PARA CONECTAR:");
    Serial.printf("  Rede:  %s\n", AP_SSID);
    Serial.printf("  Senha: %s\n", AP_PASSWORD);
    Serial.print("  IP:    ");
    Serial.println(WiFi.softAPIP());
    Serial.println("========================================\n");
}

// ============================================================================
// LOOP
// ============================================================================

void loop() {
    servidor.handleClient();
}

// ============================================================================
// INICIALIZAÇÃO DA CÂMERA
// ============================================================================

bool inicializa_camera() {
    // "= {}" zera TODOS os campos. Sem ele, os campos que este código não
    // preenche ficavam com lixo da memória. O driver do núcleo ESP32 3.3.x
    // tem o campo jpeg_buffer_size (tamanho do buffer da foto; 0 = padrão):
    // com lixo nele, o buffer saía de tamanho aleatório e TODA foto
    // transbordava (FB-OVF), a qualquer clock e resolução. Foi a causa da
    // falha das v5.6 e v5.6.1 na placa. Na v5.2 não aparecia, provavelmente
    // porque o núcleo da época ainda não tinha esse campo.
    camera_config_t config = {};

    config.pin_d0       = Y2_GPIO_NUM;
    config.pin_d1       = Y3_GPIO_NUM;
    config.pin_d2       = Y4_GPIO_NUM;
    config.pin_d3       = Y5_GPIO_NUM;
    config.pin_d4       = Y6_GPIO_NUM;
    config.pin_d5       = Y7_GPIO_NUM;
    config.pin_d6       = Y8_GPIO_NUM;
    config.pin_d7       = Y9_GPIO_NUM;
    config.pin_xclk     = XCLK_GPIO_NUM;
    config.pin_pclk     = PCLK_GPIO_NUM;
    config.pin_vsync    = VSYNC_GPIO_NUM;
    config.pin_href     = HREF_GPIO_NUM;
    config.pin_sccb_sda = SIOD_GPIO_NUM;
    config.pin_sccb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn     = PWDN_GPIO_NUM;
    config.pin_reset    = RESET_GPIO_NUM;

    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer   = LEDC_TIMER_0;

    // Inicia direto na resolução de trabalho, com o clock e a qualidade
    // dela. O buffer é dimensionado à parte, para a maior foto (5 MP)
    bool alta = (RESOLUCAO_PADRAO >= LIMITE_ALTA_RES);
    xclk_atual_mhz = alta ? XCLK_ALTA_RES_MHZ : XCLK_PADRAO_MHZ;
    config.xclk_freq_hz = xclk_atual_mhz * 1000000;

    config.frame_size       = RESOLUCAO_PADRAO;
    config.pixel_format     = PIXFORMAT_JPEG;
    config.jpeg_quality     = alta ? QUALIDADE_ALTA_RES : QUALIDADE_PADRAO;
    config.jpeg_buffer_size = BUFFER_JPEG_5MP;

    config.fb_location  = CAMERA_FB_IN_PSRAM;
    // GRAB_WHEN_EMPTY usa 1 buffer: "the first 'fb_count' frames might be
    // old" (esp_camera.h), então com 1 buffer basta descartar 1 quadro.
    config.fb_count     = USAR_GRAB_LATEST ? 2 : 1;
    config.grab_mode    = USAR_GRAB_LATEST
                          ? CAMERA_GRAB_LATEST
                          : CAMERA_GRAB_WHEN_EMPTY;

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        // Rede de segurança: se a PSRAM não comportar o buffer de 5 MP, tenta
        // com o de UXGA em vez de reiniciar em loop. As capturas acima de
        // UXGA passam a ser recusadas (resolucao_maxima).
        registra("[AVISO] init com buffer de 5 MP falhou (0x%x); tentando com buffer UXGA", err);
        esp_camera_deinit();
        delay(100);
        config.jpeg_buffer_size = BUFFER_JPEG_UXGA;
        err = esp_camera_init(&config);
        if (err == ESP_OK) resolucao_maxima = FRAMESIZE_UXGA;
    }
    if (err != ESP_OK) {
        registra("[ERRO] esp_camera_init falhou: 0x%x", err);
        return false;
    }
    tamanho_buffer_jpeg = config.jpeg_buffer_size;

    registra("[INFO] XCLK: %d MHz | buffers: %d de %u KB | maior resolucao: %s",
                  xclk_atual_mhz, (int)config.fb_count, (unsigned)(tamanho_buffer_jpeg / 1024),
                  resolucao_maxima == FRAMESIZE_QSXGA ? "QSXGA 2560x1920" : "UXGA 1600x1200");

    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor != NULL) {
        registra("[INFO] Sensor PID = 0x%04X", sensor->id.PID);

        if (sensor->id.PID == 0x5640) {
            sensor_detectado = "OV5640";
            registra("[INFO] OV5640 detectado (5MP, com autofoco)");
        } else if (sensor->id.PID == 0x26) {
            sensor_detectado = "OV2640";
            registra("[INFO] OV2640 detectado (2MP, foco fixo)");
        }

        sensor->set_vflip(sensor, FLIP_VERTICAL);
        sensor->set_hmirror(sensor, ESPELHO_HORIZONTAL);
        registra("[INFO] Orientacao: vflip=%d hmirror=%d",
                      FLIP_VERTICAL, ESPELHO_HORIZONTAL);
    }

    // A câmera já está na resolução de trabalho, com o clock dela
    resolucao_atual = RESOLUCAO_PADRAO;
    return true;
}

// ============================================================================
// AJUSTE DE RESOLUÇÃO (com XCLK e qualidade adaptativos)
// ============================================================================
// Esta é a correção central do FB-OVF: ao subir a resolução, reduzimos o
// clock da câmera para que a taxa de dados caiba na banda disponível de
// PSRAM (que também está sendo usada pelo Wi-Fi).

bool ajusta_resolucao(framesize_t nova) {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL) return false;

    // Já está na resolução pedida: nada a fazer
    if (nova == resolucao_atual) return true;

    bool alta = (nova >= LIMITE_ALTA_RES);
    int xclk_desejado = alta ? XCLK_ALTA_RES_MHZ : XCLK_PADRAO_MHZ;

    // --- Ajusta o clock ANTES de trocar a resolução ---
    if (xclk_desejado != xclk_atual_mhz) {
        registra("[INFO] Ajustando XCLK: %d -> %d MHz",
                      xclk_atual_mhz, xclk_desejado);
        sensor->set_xclk(sensor, LEDC_TIMER_0, xclk_desejado);
        xclk_atual_mhz = xclk_desejado;
        delay(100);
    }

    // --- Qualidade JPEG adaptativa (ou a manual, se definida por /tune) ---
    sensor->set_quality(sensor, qualidade_em_uso(alta));

    // --- Troca a resolução ---
    sensor->set_framesize(sensor, nova);
    resolucao_atual = nova;

    // Tempo para o sensor reajustar exposição e balanço de branco. Sem esta
    // pausa, as primeiras fotos após a troca saem escuras ou esverdeadas.
    delay(alta ? 500 : 300);

    // Descarta frames de transição. Em alta resolução cada frame custa
    // muito tempo, então descartamos menos.
    int descartes = alta ? 1 : 3;
    for (int i = 0; i < descartes; i++) {
        camera_fb_t* fb_lixo = esp_camera_fb_get();
        if (fb_lixo) esp_camera_fb_return(fb_lixo);
    }

    return true;
}

// ============================================================================
// AUXILIARES DO AUTOFOCO
// ============================================================================

uint8_t le_reg_af(uint16_t reg) {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL) return 0xFF;
    int valor = sensor->get_reg(sensor, reg, 0xFF);
    if (valor < 0) return 0xFF;
    return (uint8_t)valor;
}

void escreve_reg_af(uint16_t reg, uint8_t valor) {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL) return;
    sensor->set_reg(sensor, reg, 0xFF, valor);
}

// ============================================================================
// INICIALIZAÇÃO DO AUTOFOCO
// ============================================================================

void inicializa_autofoco() {
    if (sensor_detectado != "OV5640") {
        registra("[INFO] Autofoco indisponivel neste sensor");
        autofoco_ativo = false;
        return;
    }

    registra("[INFO] Carregando firmware de autofoco...");

    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL) {
        autofoco_ativo = false;
        return;
    }

    ov5640.start(sensor);

    if (ov5640.focusInit() != 0) {
        registra("[ERRO] Falha ao carregar o firmware de autofoco");
        autofoco_ativo = false;
        return;
    }

    registra("[OK] Firmware de autofoco carregado");
    autofoco_ativo = true;

    if (MODO_AF_CONTINUO) {
        if (ov5640.autoFocusMode() == 0) {
            registra("[OK] Modo de autofoco CONTINUO ativado");
        }
    } else {
        unsigned long inicio = millis();
        while (millis() - inicio < 2000) {
            if (le_reg_af(REG_AF_STATUS) == AF_STATUS_PRONTO) {
                registra("[OK] Firmware de AF pronto (modo foco unico)");
                break;
            }
            delay(50);
        }
    }
}

// ============================================================================
// EXECUTA O FOCO
// ============================================================================

bool executa_foco() {
    foco_ultimo_ms = 0;
    foco_ultimas_tentativas = 0;
    if (!autofoco_ativo) return false;

    unsigned long inicio_foco = millis();

    for (int tentativa = 1; tentativa <= TENTATIVAS_FOCO; tentativa++) {
        foco_ultimas_tentativas = tentativa;

        if (!MODO_AF_CONTINUO) {
            escreve_reg_af(REG_AF_COMANDO, CMD_AF_UNICO);
            delay(50);
        }

        unsigned long inicio = millis();

        while (millis() - inicio < TIMEOUT_FOCO_MS) {
            uint8_t status = le_reg_af(REG_AF_STATUS);

            if (status == AF_STATUS_FOCADO) {
                foco_ultimo_ms = millis() - inicio_foco;
                registra("[INFO] Foco travado em %lu ms (tentativa %d)",
                              millis() - inicio, tentativa);
                return true;
            }

            if (status == 0xFF) {
                foco_ultimo_ms = millis() - inicio_foco;
                registra("[AVISO] Erro de comunicacao com o sensor");
                return false;
            }

            delay(25);
        }

        registra("[AVISO] Tentativa %d de foco esgotou o tempo", tentativa);
    }

    foco_ultimo_ms = millis() - inicio_foco;
    registra("[AVISO] Foco nao travou. Causa provavel: pouca luz.");
    return false;
}

// ============================================================================
// ACCESS POINT
// ============================================================================

bool inicia_access_point() {
    registra("[INFO] Iniciando Access Point...");

    WiFi.mode(WIFI_AP);
    if (!WiFi.softAP(AP_SSID, AP_PASSWORD, AP_CHANNEL, 0, AP_MAX_CONN)) {
        registra("[ERRO] WiFi.softAP() falhou");
        return false;
    }

    delay(500);
    registra("[OK] Access Point ativo");
    return true;
}

// ============================================================================
// HANDLER: /ping
// ============================================================================

void handler_ping() {
    servidor.send(200, "text/plain", "OK");
}

// ============================================================================
// HANDLER: /capture
// ============================================================================

// Cabeçalhos de medição de uma captura. Vão também na resposta de erro: o app
// registra o que a placa fez mesmo quando a foto falha.
void envia_diagnostico(bool foco_pedido, bool foco_ok, unsigned long ajuste_ms,
                       unsigned long descarte_ms, unsigned long captura_ms,
                       unsigned long total_ms, uint32_t ovf_antes,
                       uint32_t avisos_antes, const camera_fb_t* fb) {
    servidor.sendHeader("X-Eyecam-Foco", foco_ok ? "travado" : "nao-travado");
    servidor.sendHeader("X-Eyecam-Foco-Ms", String(foco_pedido ? foco_ultimo_ms : 0));
    servidor.sendHeader("X-Eyecam-Foco-Tentativas", String(foco_pedido ? foco_ultimas_tentativas : 0));
    servidor.sendHeader("X-Eyecam-Ajuste-Ms", String(ajuste_ms));
    servidor.sendHeader("X-Eyecam-Descarte-Ms", String(descarte_ms));
    servidor.sendHeader("X-Eyecam-Captura-Ms", String(captura_ms));
    servidor.sendHeader("X-Eyecam-Tempo-Ms", String(total_ms));
    if (fb != NULL) {
        servidor.sendHeader("X-Eyecam-Resolucao", String(fb->width) + "x" + String(fb->height));
    }
    servidor.sendHeader("X-Eyecam-Xclk-Mhz", String(xclk_atual_mhz));
    servidor.sendHeader("X-Eyecam-Qualidade", String(qualidade_em_uso(resolucao_atual >= LIMITE_ALTA_RES)));
    servidor.sendHeader("X-Eyecam-Ovf", String((unsigned long)(ovf_total - ovf_antes)));
    servidor.sendHeader("X-Eyecam-Avisos-Cam", String((unsigned long)(avisos_cam_total - avisos_antes)));
    servidor.sendHeader("X-Eyecam-Captura-N", String((unsigned long)capturas_desde_boot));
    servidor.sendHeader("X-Eyecam-Uptime-S", String(millis() / 1000));
    servidor.sendHeader("X-Eyecam-Log-Seq", String((unsigned long)log_ultima));
    servidor.sendHeader("Access-Control-Allow-Origin", "*");
}

void handler_capture() {
    unsigned long inicio_total = millis();
    capturas_desde_boot++;
    uint32_t ovf_antes = ovf_total;
    uint32_t avisos_antes = avisos_cam_total;
    unsigned long ajuste_ms = 0;

    // --- Resolução ---
    if (servidor.hasArg("res")) {
        String res = servidor.arg("res");
        res.toLowerCase();
        framesize_t nova = traduz_resolucao(res);

        if (nova == FRAMESIZE_INVALID) {
            servidor.send(400, "text/plain",
                "Resolucao invalida. Use: qvga, vga, svga, xga, sxga, uxga, qxga, qsxga (ou 5mp)");
            return;
        }
        if (nova > resolucao_maxima) {
            servidor.send(400, "text/plain",
                "Resolucao acima do buffer reservado no boot (veja /info). Maxima: uxga");
            return;
        }

        unsigned long inicio_ajuste = millis();
        ajusta_resolucao(nova);
        ajuste_ms = millis() - inicio_ajuste;
    }

    // --- Foco ---
    bool usar_af = !(servidor.hasArg("af") && servidor.arg("af") == "0");
    bool foco_pedido = usar_af && autofoco_ativo;
    bool foco_ok = false;

    if (foco_pedido) {
        foco_ok = executa_foco();
    }

    // --- Descarta frames antigos ---
    // GRAB_WHEN_EMPTY (1 buffer): o buffer guarda um quadro capturado logo
    // depois da foto ANTERIOR, ou seja, antes deste pedido e antes do foco.
    // Descartá-lo faz a câmera capturar um quadro novo. Um descarte basta.
    //
    // GRAB_LATEST (2 buffers): o driver já entrega o quadro mais recente; os
    // descartes garantem um quadro posterior ao foco. Em alta resolução,
    // descartar é caro (cada quadro leva centenas de milissegundos).
    bool alta = (resolucao_atual >= LIMITE_ALTA_RES);
    int descartes = !USAR_GRAB_LATEST ? 1 : (alta ? 1 : 2);

    unsigned long inicio_descarte = millis();
    for (int i = 0; i < descartes; i++) {
        camera_fb_t* fb_descarte = esp_camera_fb_get();
        if (fb_descarte) esp_camera_fb_return(fb_descarte);
    }
    unsigned long descarte_ms = millis() - inicio_descarte;

    // --- Captura ---
    // O tempo de esp_camera_fb_get é o de um quadro: se um quadro se perder
    // (FB-OVF), ele dobra. É um sinal indireto, que não depende da contagem
    // experimental dos avisos do driver.
    unsigned long inicio_captura = millis();
    camera_fb_t* fb = esp_camera_fb_get();
    unsigned long captura_ms = millis() - inicio_captura;
    unsigned long tempo_total = millis() - inicio_total;

    if (!fb) {
        registra("[ERRO] Falha ao capturar frame (captura %lu, %lu ms, FB-OVF %lu)",
                 (unsigned long)capturas_desde_boot, tempo_total,
                 (unsigned long)(ovf_total - ovf_antes));
        envia_diagnostico(foco_pedido, foco_ok, ajuste_ms, descarte_ms, captura_ms,
                          tempo_total, ovf_antes, avisos_antes, NULL);
        servidor.send(500, "text/plain",
            "Falha na captura. Se o log mostrar FB-OVF, reduza a resolucao "
            "ou o valor de XCLK_ALTA_RES_MHZ no firmware.");
        return;
    }

    envia_diagnostico(foco_pedido, foco_ok, ajuste_ms, descarte_ms, captura_ms,
                      tempo_total, ovf_antes, avisos_antes, fb);
    // O Content-Type vai no próprio send_P; enviá-lo também por sendHeader
    // duplicava o cabeçalho na resposta
    servidor.send_P(200, "image/jpeg", (const char*)fb->buf, fb->len);

    registra("[INFO] Foto %lu: %ux%u, %u bytes | foco: %s | %lu ms | XCLK %d MHz | FB-OVF %lu",
             (unsigned long)capturas_desde_boot, fb->width, fb->height, fb->len,
             foco_ok ? "travado" : (foco_pedido ? "nao-travado" : "nao-pedido"),
             tempo_total, xclk_atual_mhz, (unsigned long)(ovf_total - ovf_antes));

    esp_camera_fb_return(fb);
}

// ============================================================================
// HANDLER: /log
// ============================================================================
// Texto, uma linha por mensagem: número, milissegundos desde o boot e texto,
// separados por TAB. ?desde=N devolve só as linhas depois da N. O cabeçalho
// X-Eyecam-Log-Seq traz o número da última linha. Se ele for MENOR que o N
// pedido, a placa reiniciou e a numeração recomeçou.

void handler_log() {
    uint32_t desde = 0;
    if (servidor.hasArg("desde")) {
        desde = strtoul(servidor.arg("desde").c_str(), NULL, 10);
    }

    // Só as linhas que ainda estão na memória circular
    uint32_t primeira = (log_ultima > (uint32_t)LOG_LINHAS) ? log_ultima - LOG_LINHAS + 1 : 1;
    if (desde + 1 > primeira) primeira = desde + 1;

    String texto;
    texto.reserve(2048);
    for (uint32_t n = primeira; n <= log_ultima; n++) {
        int i = n % LOG_LINHAS;
        texto += String((unsigned long)n) + "\t" + String((unsigned long)log_tempo_ms[i]) +
                 "\t" + log_texto[i] + "\n";
    }

    servidor.sendHeader("X-Eyecam-Log-Seq", String((unsigned long)log_ultima));
    servidor.sendHeader("X-Eyecam-Ovf-Total", String((unsigned long)ovf_total));
    servidor.sendHeader("Access-Control-Allow-Origin", "*");
    servidor.send(200, "text/plain; charset=utf-8", texto);
}

// ============================================================================
// HANDLER: /focus
// ============================================================================

void handler_focus() {
    if (!autofoco_ativo) {
        servidor.send(200, "text/plain",
            "Autofoco indisponivel.\n\n"
            "Possiveis causas:\n"
            "  - sensor nao e OV5640\n"
            "  - biblioteca de AF nao instalada\n"
            "  - pino AF-VCC do cabo flat sem 3,3V\n");
        return;
    }

    unsigned long inicio = millis();
    bool ok = executa_foco();
    unsigned long tempo = millis() - inicio;
    uint8_t status = le_reg_af(REG_AF_STATUS);

    String msg = "=== DIAGNOSTICO DO AUTOFOCO ===\n\n";
    msg += "Resultado: ";
    msg += ok ? "FOCO TRAVADO" : "NAO TRAVOU";
    msg += "\nTempo: " + String(tempo) + " ms";
    msg += "\nStatus (0x3029): 0x" + String(status, HEX);
    msg += "\n\nO autofoco precisa de CONTRASTE. Aponte para um texto\n";
    msg += "com boa iluminacao, a uns 20-30 cm de distancia.\n";

    servidor.sendHeader("Access-Control-Allow-Origin", "*");
    servidor.send(200, "text/plain", msg);
}

// ============================================================================
// HANDLER: /info
// ============================================================================

void handler_info() {
    uint8_t af_status = autofoco_ativo ? le_reg_af(REG_AF_STATUS) : 0;

    String json = "{";
    json += "\"dispositivo\":\"Eye-cam\",";
    json += "\"versao_firmware\":\"5.6.2\",";
    json += "\"motivo_reset\":\"" + String(motivo_reset()) + "\",";
    json += "\"capturas_desde_boot\":" + String((unsigned long)capturas_desde_boot) + ",";
    json += "\"fb_ovf_total\":" + String((unsigned long)ovf_total) + ",";
    json += "\"avisos_camera_total\":" + String((unsigned long)avisos_cam_total) + ",";
    json += "\"log_seq\":" + String((unsigned long)log_ultima) + ",";
    json += "\"placa\":\"ESP32-S3-CAM N16R8\",";
    json += "\"sensor\":\"" + sensor_detectado + "\",";
    json += "\"autofoco_disponivel\":" + String(autofoco_ativo ? "true" : "false") + ",";
    json += "\"autofoco_modo\":\"" + String(MODO_AF_CONTINUO ? "continuo" : "unico") + "\",";
    json += "\"autofoco_status\":" + String(af_status) + ",";
    json += "\"xclk_mhz\":" + String(xclk_atual_mhz) + ",";
    json += "\"resolucao_atual\":" + String((int)resolucao_atual) + ",";
    json += "\"resolucao_maxima\":\"" + String(resolucao_maxima == FRAMESIZE_QSXGA ? "qsxga" : "uxga") + "\",";
    json += "\"buffer_jpeg_kb\":" + String((unsigned long)(tamanho_buffer_jpeg / 1024)) + ",";
    json += "\"modo_captura\":\"" + String(USAR_GRAB_LATEST ? "grab_latest" : "grab_when_empty") + "\",";
    json += json_ajustes() + ",";
    json += "\"modo_wifi\":\"AP\",";
    json += "\"ap_ssid\":\"" + String(AP_SSID) + "\",";
    json += "\"ap_ip\":\"" + WiFi.softAPIP().toString() + "\",";
    // BSSID: o app o guarda para reconectar sem pedir confirmação (v5.5)
    json += "\"ap_bssid\":\"" + WiFi.softAPmacAddress() + "\",";
    json += "\"clientes_conectados\":" + String(WiFi.softAPgetStationNum()) + ",";
    json += "\"heap_livre\":" + String(ESP.getFreeHeap()) + ",";
    json += "\"psram_livre\":" + String(ESP.getFreePsram()) + ",";
    json += "\"uptime_segundos\":" + String(millis() / 1000);
    json += "}";

    servidor.sendHeader("Access-Control-Allow-Origin", "*");
    servidor.send(200, "application/json", json);
}

// ============================================================================
// HANDLER: /tune  (ajustes de imagem para a bancada de testes)
// ============================================================================
// Exemplo: /tune?sharpness=2&denoise=0 aplica os dois e devolve os valores
// em uso. Sem parâmetros, só informa. Valores fora da faixa são recusados
// (400) sem aplicar nada. Os ajustes valem até a placa reiniciar; nada é
// gravado. reset=1 reinicia a placa, o único jeito confiável de voltar aos
// registros de fábrica do sensor (o reset do próprio sensor apagaria o
// firmware de autofoco carregado nele).

void handler_tune() {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL) {
        servidor.send(500, "text/plain", "Sensor indisponivel");
        return;
    }

    if (servidor.hasArg("reset") && servidor.arg("reset") == "1") {
        servidor.send(200, "text/plain", "Reiniciando a placa para voltar aos valores de fabrica (~5 s).");
        delay(200);
        ESP.restart();
    }

    // --- Validação de tudo antes de aplicar qualquer coisa ---
    struct Ajuste { const char* nome; int minimo; int maximo; };
    const Ajuste faixas[] = {
        {"sharpness", -3, 3}, {"denoise", 0, 8}, {"contrast", -2, 2}, {"quality", 0, 63}
    };
    for (const Ajuste& f : faixas) {
        if (!servidor.hasArg(f.nome)) continue;
        int v = servidor.arg(f.nome).toInt();
        bool numero = servidor.arg(f.nome).length() > 0 &&
                      (servidor.arg(f.nome) == "0" || v != 0);
        bool qualidade_invalida = (String(f.nome) == "quality" && v > 0 && v < 4);
        if (!numero || v < f.minimo || v > f.maximo || qualidade_invalida) {
            servidor.send(400, "text/plain",
                "Valor invalido para " + String(f.nome) +
                ". Faixas: sharpness -3..3, denoise 0..8, contrast -2..2, "
                "quality 4..63 (0 = automatica)");
            return;
        }
    }

    // --- Aplicação ---
    int falhas = 0;
    if (servidor.hasArg("sharpness") && sensor->set_sharpness) {
        int v = servidor.arg("sharpness").toInt();
        if (sensor->set_sharpness(sensor, v) == 0) nitidez_atual = v; else falhas++;
    }
    if (servidor.hasArg("denoise") && sensor->set_denoise) {
        int v = servidor.arg("denoise").toInt();
        if (sensor->set_denoise(sensor, v) == 0) denoise_atual = v; else falhas++;
    }
    if (servidor.hasArg("contrast") && sensor->set_contrast) {
        int v = servidor.arg("contrast").toInt();
        if (sensor->set_contrast(sensor, v) == 0) contraste_atual = v; else falhas++;
    }
    if (servidor.hasArg("quality")) {
        qualidade_manual = servidor.arg("quality").toInt();
        bool alta = (resolucao_atual >= LIMITE_ALTA_RES);
        if (sensor->set_quality(sensor, qualidade_em_uso(alta)) != 0) falhas++;
    }

    registra("[INFO] /tune: nitidez=%d denoise=%d contraste=%d qualidade=%d (falhas: %d)",
                  nitidez_atual, denoise_atual, contraste_atual,
                  qualidade_em_uso(resolucao_atual >= LIMITE_ALTA_RES), falhas);

    String json = "{" + json_ajustes() + ",\"falhas\":" + String(falhas) + "}";
    servidor.sendHeader("Access-Control-Allow-Origin", "*");
    servidor.send(falhas == 0 ? 200 : 500, "application/json", json);
}

// Qualidade JPEG efetiva: a manual (definida por /tune), se houver; senão a
// automática por resolução
int qualidade_em_uso(bool alta) {
    if (qualidade_manual > 0) return qualidade_manual;
    return alta ? QUALIDADE_ALTA_RES : QUALIDADE_PADRAO;
}

// Trecho JSON com os ajustes de imagem em uso (null = valor de fábrica)
String json_ajustes() {
    auto valor = [](int v) { return v == AJUSTE_FABRICA ? String("null") : String(v); };
    bool alta = (resolucao_atual >= LIMITE_ALTA_RES);
    String j = "\"nitidez\":" + valor(nitidez_atual);
    j += ",\"denoise\":" + valor(denoise_atual);
    j += ",\"contraste\":" + valor(contraste_atual);
    j += ",\"qualidade_jpeg\":" + String(qualidade_em_uso(alta));
    j += ",\"qualidade_modo\":\"" + String(qualidade_manual > 0 ? "manual" : "automatica") + "\"";
    return j;
}

// ============================================================================
// HANDLER: 404
// ============================================================================

void handler_404() {
    String msg = "Rota nao encontrada.\n\n";
    msg += "Comandos disponiveis:\n";
    msg += "  GET /ping     - testa conexao\n";
    msg += "  GET /capture  - foca e tira foto JPEG\n";
    msg += "  GET /focus    - diagnostico do autofoco\n";
    msg += "  GET /info     - info do dispositivo (JSON)\n";
    msg += "  GET /tune     - ajustes de imagem (JSON)\n";
    msg += "  GET /log      - ultimas mensagens do firmware (?desde=N)\n";
    msg += "\nParametros de /capture:\n";
    msg += "  ?res=qvga|vga|svga|xga|sxga|uxga|qxga|qsxga (5mp = qsxga)\n";
    msg += "  ?af=0  (captura sem focar)\n";
    msg += "\nParametros de /tune:\n";
    msg += "  ?sharpness=-3..3 &denoise=0..8 &contrast=-2..2 &quality=4..63 (0 = auto)\n";
    msg += "  ?reset=1  (reinicia a placa)\n";

    servidor.send(404, "text/plain", msg);
}

// ============================================================================
// AUXILIAR: converte string em framesize_t
// ============================================================================

framesize_t traduz_resolucao(String res) {
    if (res == "qvga")  return FRAMESIZE_QVGA;
    if (res == "vga")   return FRAMESIZE_VGA;
    if (res == "svga")  return FRAMESIZE_SVGA;
    if (res == "xga")   return FRAMESIZE_XGA;
    if (res == "sxga")  return FRAMESIZE_SXGA;
    if (res == "uxga")  return FRAMESIZE_UXGA;
    if (res == "qxga")  return FRAMESIZE_QXGA;     // 2048x1536
    if (res == "qsxga" || res == "5mp") return FRAMESIZE_QSXGA;  // 2560x1920
    return FRAMESIZE_INVALID;
}
