/*
 * Eye-cam: firmware do óculos (ESP32-S3 N16R8 + câmera OV5640 com autofoco)
 * Versão 5.7.4
 *
 * Cria a rede Wi-Fi do óculos e entrega fotos JPEG ao aplicativo sob demanda.
 * Controla a energia: mantém a trava ligada (HOLD), desliga com toque longo no
 * botão e corta com a bateria baixa.
 *
 * Biblioteca: "OV5640 Auto Focus for ESP32 Camera" (Library Manager)
 * Senha do Wi-Fi: copie segredos.exemplo.h para segredos.h e defina EYECAM_AP_SENHA
 * Placa: ESP32S3 Dev Module, Flash 16MB, partição "16M Flash (3MB APP/9.9MB FATFS)",
 *        PSRAM OPI, USB CDC On Boot: Enabled
 *
 * Rotas HTTP:
 *   /ping     -> "OK"
 *   /capture  -> foto JPEG  (?res=svga|xga|sxga|uxga|qxga|5mp, ?af=0 = sem focar)
 *   /focus    -> diagnóstico do autofoco
 *   /info     -> estado do dispositivo (JSON), inclusive bateria
 *   /tune     -> ajustes de imagem e de clock (JSON; ?sharpness ?denoise ?contrast
 *                ?quality ?xclk ?xclk_alta ?noite ?reset=1)
 *   /log      -> últimas mensagens do firmware (?desde=N)
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

#if __has_include("segredos.h")
#include "segredos.h"
#else
#error "Falta segredos.h: copie segredos.exemplo.h para segredos.h e defina a senha (docs/INSTALACAO.md)"
#endif

// O WPA2 exige senha de 8 a 63 caracteres; fora disso o softAP() falha em tempo de
// execução.
static_assert(sizeof(EYECAM_AP_SENHA) - 1 >= 8 && sizeof(EYECAM_AP_SENHA) - 1 <= 63,
              "EYECAM_AP_SENHA precisa ter de 8 a 63 caracteres");

const char* AP_SSID     = "EyeCam_01";
const char* AP_PASSWORD = EYECAM_AP_SENHA;
const int   AP_CHANNEL  = 1;

// Apenas o celular do usuário.
const int   AP_MAX_CONN = 1;

// ============================================================================
// CONFIGURAÇÕES DA CÂMERA
// ============================================================================

const framesize_t RESOLUCAO_PADRAO = FRAMESIZE_XGA;

// Tamanho do buffer JPEG, em bytes, informado ao driver (jpeg_buffer_size).
const size_t BUFFER_JPEG_5MP  = 1200 * 1024;
// Reserva, se a PSRAM não comportar o de 5 MP: só até UXGA
const size_t BUFFER_JPEG_UXGA = 1600 * 1200 / 5;

// ---------------------------------------------------------------------------
// CLOCK DA CÂMERA (XCLK)
// ---------------------------------------------------------------------------
const int XCLK_PADRAO_MHZ = 10;
const int XCLK_MINIMO_MHZ = 6;    // faixa de entrada da OV5640: 6 a 27 MHz
const int XCLK_MAXIMO_MHZ = 24;

// A partir desta resolução, o firmware considera "alta resolução" e aplica o clock de alta
// resolução, a qualidade adaptativa e menos descartes de quadro.
const framesize_t LIMITE_ALTA_RES = FRAMESIZE_SXGA;

// ---------------------------------------------------------------------------
// QUALIDADE JPEG (0-63; MENOR número = melhor qualidade, arquivo maior)
// ---------------------------------------------------------------------------
const int QUALIDADE_PADRAO   = 12;
const int QUALIDADE_ALTA_RES = 15;  // arquivo menor, transferência mais rápida

// ---------------------------------------------------------------------------
// AJUSTES DE IMAGEM (alteráveis em tempo de execução pela rota /tune)
// ---------------------------------------------------------------------------
const int AJUSTE_FABRICA = -99;   // marcador de "não alterado"

// ---------------------------------------------------------------------------
// MODO NOTURNO DO SENSOR (registro 0x3A00, bit 2, da OV5640)
// ---------------------------------------------------------------------------
const bool MODO_NOTURNO_PADRAO = false;
const uint16_t REG_AEC_CTRL00 = 0x3A00;
const uint8_t  BIT_MODO_NOTURNO = 0x04;

// ---------------------------------------------------------------------------
// CAPTURA
// ---------------------------------------------------------------------------
const int PASSO_COMPRESSAO = 5;            // pontos a mais na qualidade (maior = menor arquivo)
const int QUALIDADE_MAXIMA_RECUPERACAO = 30;
const framesize_t RESOLUCAO_FOCO_RECUPERACAO = FRAMESIZE_SVGA;   // a do app

// ---------------------------------------------------------------------------
// ORIENTAÇÃO DA IMAGEM (valores confirmados experimentalmente)
// ---------------------------------------------------------------------------
const int FLIP_VERTICAL      = 1;
const int ESPELHO_HORIZONTAL = 0;

// ---------------------------------------------------------------------------
// AUTOFOCO (firmware interno da OV5640, carregado pela biblioteca de AF)
// ---------------------------------------------------------------------------
const unsigned long TIMEOUT_FOCO_MS = 4000;
const int TENTATIVAS_FOCO = 2;
// Comandos de configuração e de consulta, que não movem a lente
const unsigned long TIMEOUT_COMANDO_AF_MS = 500;

const uint16_t REG_AF_COMANDO = 0x3022;   // CMD_MAIN
const uint16_t REG_AF_ACK     = 0x3023;   // CMD_ACK
const uint16_t REG_AF_PARAM0  = 0x3024;   // CMD_PARA0..CMD_PARA4 = 0x3024..0x3028
const uint16_t REG_AF_STATUS  = 0x3029;   // FW_STATUS

const uint8_t CMD_AF_UNICO         = 0x03;   // dispara um foco
const uint8_t CMD_AF_RESULTADO     = 0x07;   // zonas focadas em CMD_PARA0..4 (0 = focou)
const uint8_t CMD_AF_RELANCA_ZONAS = 0x12;   // refaz as zonas para a resolução atual

// FW_STATUS: 0x10 = foco concluído (NÃO quer dizer nítido); 0x70 = ocioso, lente no
// infinito; 0x00–0x0F e 0x80–0x8F = focando
const uint8_t AF_STATUS_FOCADO = 0x10;

// ============================================================================
// CONFIGURAÇÕES DE ENERGIA (trava, botão e bateria; v5.7)
// ============================================================================

// Toque longo que desliga. 2 s: longo o bastante para não desligar por um esbarrão, curto o
// bastante para não parecer que o botão não responde.
const unsigned long TOQUE_LONGO_MS = 2000;
// O botão só passa a valer depois de ficar solto este tempo: o toque que ligou o aparelho
// ainda está acontecendo quando o setup() começa
const unsigned long BOTAO_SOLTO_PARA_ARMAR_MS = 300;
// Menor toque contado como toque curto (abaixo disso, ruído ou trepidação)
const unsigned long TOQUE_MINIMO_MS = 60;

// Tensão da bateria, medida depois da chave (Q1), em mV
const uint32_t CORTE_BATERIA_MV = 3400;   // abaixo disso, desliga
const uint32_t AVISO_BATERIA_MV = 3550;   // abaixo disso, "fraca"
// Histerese: para sair de "baixa" ou "fraca" a tensão tem de subir esta margem acima do
// limite, para o estado não ficar oscilando no limite
const uint32_t HISTERESE_BATERIA_MV = 50;
// Abaixo disso não há bateria medida (placa no USB, cabo solto, divisor ausente): uma Li-Po
// ligada nunca fica tão baixa, o DW01A corta em 2,4 V
const uint32_t SEM_BATERIA_MV = 2000;
// Tempo seguido abaixo do corte antes de desligar.
const unsigned long CORTE_PERSISTENCIA_MS = 10000;
// Divisor R7 = R8 = 10 k: a bateria é o dobro da tensão no pino.
const float FATOR_DIVISOR_BATERIA = 2.0f;

// Fica aqui, antes de qualquer função, porque o Arduino gera os protótipos das funções no
// começo do arquivo, e um deles usa este tipo
enum EstadoBateria { BATERIA_SEM_MEDICAO, BATERIA_OK, BATERIA_FRACA, BATERIA_BAIXA };

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

// Energia (cabo da caixa do bolso).
#define PINO_BATERIA       1   // ADC1: divisor R7/R8 de 10 k, com C4 de 100 nF
#define PINO_BOTAO        47   // BTN: 0,83 x bateria com o S1 apertado
#define PINO_BOTAO_ALT     2   // fio antigo; o LED "IO2" da placa fica neste pino
#define PINO_HOLD         21   // HOLD: alto mantém a trava ligada

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

// Clock por faixa de resolução (alterável por /tune)
int xclk_baixa_mhz = XCLK_PADRAO_MHZ;   // até XGA
int xclk_alta_mhz  = XCLK_PADRAO_MHZ;   // SXGA para cima

// Maior resolução que o buffer reservado comporta (cai para UXGA se a PSRAM não comportar o
// buffer de 5 MP) e o tamanho efetivamente reservado
framesize_t resolucao_maxima = FRAMESIZE_QSXGA;
size_t tamanho_buffer_jpeg = BUFFER_JPEG_5MP;

// Ajustes feitos por /tune.
int nitidez_atual    = AJUSTE_FABRICA;
int denoise_atual    = AJUSTE_FABRICA;
int contraste_atual  = AJUSTE_FABRICA;
int qualidade_manual = 0;
bool modo_noturno    = MODO_NOTURNO_PADRAO;

// A resolução mudou desde a última configuração das zonas de foco: o próximo foco relança
// as zonas antes (comando 0x12)
bool zonas_af_desatualizadas = false;

int compressao_extra = 0;
bool ajuste_sem_quadro = false;
uint32_t recuperacoes_compressao = 0;
uint32_t recuperacoes_reinicio = 0;

// Medições do último foco (para os cabeçalhos de /capture)
unsigned long foco_ultimo_ms = 0;
int foco_ultimas_tentativas = 0;
char foco_zonas[24] = "";   // resposta do comando 0x07, ex. "0,1,1,1,1"

// Capturas atendidas desde o boot.
uint32_t capturas_desde_boot = 0;

// ============================================================================
// REGISTRO PARA O APP (rota /log)
// ============================================================================

const int LOG_LINHAS  = 64;
const int LOG_TAMANHO = 112;

char     log_texto[LOG_LINHAS][LOG_TAMANHO];
uint32_t log_tempo_ms[LOG_LINHAS];
volatile uint32_t log_ultima = 0;   // número da última linha registrada (começa em 1)

SemaphoreHandle_t mutex_log = NULL;

void registra(const char* formato, ...) {
    char linha[LOG_TAMANHO];
    va_list argumentos;
    va_start(argumentos, formato);
    vsnprintf(linha, sizeof(linha), formato, argumentos);
    va_end(argumentos);

    if (mutex_log) xSemaphoreTake(mutex_log, portMAX_DELAY);
    Serial.println(linha);

    uint32_t n = log_ultima + 1;
    int i = n % LOG_LINHAS;
    memcpy(log_texto[i], linha, LOG_TAMANHO);
    log_tempo_ms[i] = millis();
    log_ultima = n;
    if (mutex_log) xSemaphoreGive(mutex_log);
}

// ============================================================================
// CONTAGEM DOS AVISOS DO DRIVER DA CÂMERA
// ============================================================================

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
// ENERGIA: TRAVA (HOLD), BOTÃO (BTN) E BATERIA (v5.7)
// ============================================================================

// true = ligada pela bateria (o botão estava apertado no boot). false = alimentada pelo USB
// (bancada): sem corte por bateria e sem desligar pelo botão, porque o HOLD não controla a
// energia da placa nesse caso
bool energia_pela_bateria = false;
unsigned long hold_alto_ms = 0;          // quando o HOLD subiu (millis)
volatile bool hold_alto = false;         // nível que o firmware põe no HOLD

// Escritas pela tarefa de energia, lidas pelos handlers (32 bits: leitura atômica, sem
// trava)
volatile uint32_t bateria_mv = 0;        // filtrada; 0 = sem medição
volatile uint32_t bateria_lida_mv = 0;   // última média, sem filtro
volatile EstadoBateria bateria_estado = BATERIA_SEM_MEDICAO;
volatile bool botao_apertado = false;
volatile uint32_t toques_curtos = 0;

// Marcada durante /capture: sob a corrente da foto a tensão da bateria cai, e essa medida
// não entra no filtro nem no corte
volatile bool captura_em_andamento = false;

// Câmera em espera (v5.7.4; funções junto de MarcaCaptura)
const uint16_t REG_SYSTEM_CTRL0 = 0x3008;   // bit 6 = software power down
const uint8_t  SENSOR_ATIVO     = 0x02;
const uint8_t  SENSOR_EM_ESPERA = 0x42;
const unsigned long ESPERA_CAMERA_MS = 15000;  // sem pedidos -> espera
const unsigned long ACORDAR_CAMERA_MS = 300;   // exposição se acomodar

bool camera_em_espera = false;
unsigned long ultima_atividade_camera = 0;
uint32_t entradas_em_espera = 0;


// Pedido de desligar ainda sem efeito (millis; 0 = nenhum)
volatile unsigned long desligamento_pedido_ms = 0;

const char* nome_estado_bateria(EstadoBateria e) {
    switch (e) {
        case BATERIA_OK:    return "ok";
        case BATERIA_FRACA: return "fraca";
        case BATERIA_BAIXA: return "baixa";
        default:            return "sem_medicao";
    }
}

// Primeira coisa do setup(): com a bateria, o aparelho só continua ligado depois que o
// botão é solto se o HOLD já estiver alto.
bool botao_lido() {
    return digitalRead(PINO_BOTAO) == HIGH || digitalRead(PINO_BOTAO_ALT) == HIGH;
}

void inicia_energia() {
    pinMode(PINO_HOLD, OUTPUT);
    digitalWrite(PINO_HOLD, HIGH);
    hold_alto = true;
    hold_alto_ms = millis();

    // Pull-down interno (~45 k): com o cabo desligado o pino ficaria solto e poderia ler
    // toques falsos.
    pinMode(PINO_BOTAO, INPUT_PULLDOWN);
    pinMode(PINO_BOTAO_ALT, INPUT_PULLDOWN);
    delayMicroseconds(100);

    // Ligada pela bateria = alguém está segurando o botão agora.
    energia_pela_bateria = botao_lido();
    if (!energia_pela_bateria) {
        digitalWrite(PINO_HOLD, LOW);
        hold_alto = false;
    }

    analogReadResolution(12);
    analogSetPinAttenuation(PINO_BATERIA, ADC_11db);   // até ~3,1 V no pino
}

void desliga(const char* motivo) {
    registra("[ENERGIA] Desligando: %s (bateria %lu mV)", motivo, (unsigned long)bateria_mv);
    desligamento_pedido_ms = millis();
    // Com a bateria, a energia acaba aqui; se o botão ainda estiver apertado, acaba quando
    // ele for solto
    digitalWrite(PINO_HOLD, LOW);
    hold_alto = false;
}

// Média de 16 leituras, convertida para a tensão da bateria
uint32_t le_bateria_mv() {
    uint32_t soma = 0;
    for (int i = 0; i < 16; i++) soma += analogReadMilliVolts(PINO_BATERIA);
    return (uint32_t)((soma / 16) * FATOR_DIVISOR_BATERIA);
}

void atualiza_bateria(unsigned long agora) {
    static unsigned long abaixo_do_corte_desde = 0;   // 0 = acima do corte
    static bool corte_feito = false;

    if (captura_em_andamento) return;

    uint32_t mv = le_bateria_mv();
    bateria_lida_mv = mv;

    if (mv < SEM_BATERIA_MV) {
        if (bateria_estado != BATERIA_SEM_MEDICAO) registra("[ENERGIA] Bateria sem medicao (%lu mV no divisor)", (unsigned long)mv);
        bateria_mv = 0;
        bateria_estado = BATERIA_SEM_MEDICAO;
        abaixo_do_corte_desde = 0;
        return;
    }

    // Filtro: cada leitura nova pesa 1/4.
    uint32_t filtrada = (bateria_mv == 0) ? mv : (bateria_mv * 3 + mv) / 4;
    bateria_mv = filtrada;

    // Estado com histerese: piora assim que passa do limite, melhora só com folga acima
    // dele
    EstadoBateria anterior = bateria_estado;
    EstadoBateria novo;
    if (filtrada < CORTE_BATERIA_MV) {
        novo = BATERIA_BAIXA;
    } else if (filtrada < AVISO_BATERIA_MV) {
        novo = (anterior == BATERIA_BAIXA && filtrada < CORTE_BATERIA_MV + HISTERESE_BATERIA_MV)
               ? BATERIA_BAIXA : BATERIA_FRACA;
    } else {
        novo = ((anterior == BATERIA_FRACA || anterior == BATERIA_BAIXA) &&
                filtrada < AVISO_BATERIA_MV + HISTERESE_BATERIA_MV)
               ? BATERIA_FRACA : BATERIA_OK;
    }
    if (novo != anterior) {
        registra("[ENERGIA] Bateria %s: %lu mV", nome_estado_bateria(novo), (unsigned long)filtrada);
    }
    bateria_estado = novo;

    // Corte em 3,4 V: só abaixo do limite (não pela histerese) e por CORTE_PERSISTENCIA_MS
    // seguidos
    if (filtrada < CORTE_BATERIA_MV) {
        if (abaixo_do_corte_desde == 0) abaixo_do_corte_desde = agora;
        if (energia_pela_bateria && !corte_feito &&
            agora - abaixo_do_corte_desde >= CORTE_PERSISTENCIA_MS) {
            corte_feito = true;
            desliga("bateria abaixo de 3,4 V");
        }
    } else {
        abaixo_do_corte_desde = 0;
    }
}

// Tarefa própria, a cada 20 ms: o botão e a bateria continuam vigiados enquanto o loop()
// está preso numa foto ou num foco
void tarefa_energia(void* parametro) {
    bool armado = false;              // o botão já foi solto depois do boot
    bool apertado = false;
    bool longo_tratado = false;
    unsigned long mudou_em = millis();   // última troca de nível do botão
    unsigned long ultima_bateria = 0;
    bool aviso_sem_efeito = false;

    for (;;) {
        unsigned long agora = millis();
        bool nivel = botao_lido();

        if (nivel != apertado) {
            unsigned long duracao = agora - mudou_em;
            if (!nivel && armado && !longo_tratado && duracao >= TOQUE_MINIMO_MS) {
                toques_curtos = toques_curtos + 1;
                registra("[ENERGIA] Toque curto no botao (%lu ms)", duracao);
            }
            apertado = nivel;
            botao_apertado = nivel;
            mudou_em = agora;
            longo_tratado = false;
        }

        if (!apertado && !armado && agora - mudou_em >= BOTAO_SOLTO_PARA_ARMAR_MS) {
            armado = true;
            registra("[ENERGIA] Botao pronto: toque longo (%lu s) desliga", TOQUE_LONGO_MS / 1000);
        }

        if (apertado && armado && !longo_tratado && agora - mudou_em >= TOQUE_LONGO_MS) {
            longo_tratado = true;
            if (energia_pela_bateria) {
                desliga("toque longo no botao");
            } else {
                registra("[ENERGIA] Toque longo no botao (sem efeito: placa alimentada pelo USB)");
            }
        }

        if (agora - ultima_bateria >= 1000) {
            ultima_bateria = agora;
            atualiza_bateria(agora);
        }

        // Pedido de desligar e a placa continua ligada, com o botão solto: a energia vem de
        // outro lugar (USB conectado).
        if (desligamento_pedido_ms != 0 && !apertado && !aviso_sem_efeito &&
            agora - desligamento_pedido_ms >= 3000 && agora - mudou_em >= 3000) {
            aviso_sem_efeito = true;
            registra("[AVISO] Pedido de desligar sem efeito: placa alimentada por outra fonte (USB?)");
        }

        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

// Trecho JSON com o estado da energia, para /info
String json_energia() {
    uint32_t mv = bateria_mv;
    String j = "\"energia\":\"" + String(energia_pela_bateria ? "bateria" : "usb") + "\"";
    j += ",\"bateria_mv\":" + (mv ? String((unsigned long)mv) : String("null"));
    j += ",\"bateria_lida_mv\":" + String((unsigned long)bateria_lida_mv);
    j += ",\"bateria_estado\":\"" + String(nome_estado_bateria(bateria_estado)) + "\"";
    j += ",\"bateria_corte_mv\":" + String((unsigned long)CORTE_BATERIA_MV);
    j += ",\"botao\":\"" + String(botao_apertado ? "apertado" : "solto") + "\"";
    j += ",\"toques_curtos\":" + String((unsigned long)toques_curtos);
    j += ",\"hold\":\"" + String(hold_alto ? "alto" : "baixo") + "\"";
    return j;
}

// ============================================================================
// SETUP
// ============================================================================

void setup() {
    // ANTES DE TUDO, até da pausa do Serial: na bateria, o aparelho só fica ligado depois
    // que o botão é solto se o HOLD já estiver alto
    inicia_energia();

    Serial.begin(115200);
    delay(1000);

    mutex_log = xSemaphoreCreateMutex();

    // Antes da câmera, para contar desde o primeiro aviso do driver
    esp_rom_install_channel_putc(1, espia_console);

    Serial.println("\n========================================");
    registra("Eye-cam - Iniciando firmware v5.7.4");
    registra("ESP32-S3-CAM N16R8 + OV5640 AF");
    registra("Motivo do reset: %s | chip a %.1f C", motivo_reset(), temperatureRead());
    if (energia_pela_bateria) {
        registra("[ENERGIA] Ligada pela bateria: HOLD alto aos %lu ms do programa", hold_alto_ms);
    } else {
        registra("[ENERGIA] Botao solto no boot: placa no USB. HOLD baixo, sem corte por bateria");
    }
    Serial.println("========================================\n");

    // Antes da câmera: se o init dela travar, o botão ainda desliga
    xTaskCreatePinnedToCore(tarefa_energia, "energia", 4096, NULL, 2, NULL, 1);

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
    if (!camera_em_espera && !captura_em_andamento &&
        millis() - ultima_atividade_camera >= ESPERA_CAMERA_MS) {
        camera_dormir();
    }
}

// ============================================================================
// INICIALIZAÇÃO DA CÂMERA
// ============================================================================

bool inicializa_camera() {
    // "= {}" zera TODOS os campos.
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

    // Inicia direto na resolução de trabalho, com o clock e a qualidade dela.
    bool alta = (RESOLUCAO_PADRAO >= LIMITE_ALTA_RES);
    xclk_atual_mhz = alta ? xclk_alta_mhz : xclk_baixa_mhz;
    config.xclk_freq_hz = xclk_atual_mhz * 1000000;

    config.frame_size       = RESOLUCAO_PADRAO;
    config.pixel_format     = PIXFORMAT_JPEG;
    config.jpeg_quality     = alta ? QUALIDADE_ALTA_RES : QUALIDADE_PADRAO;
    config.jpeg_buffer_size = BUFFER_JPEG_5MP;

    config.fb_location  = CAMERA_FB_IN_PSRAM;
    config.fb_count     = 1;
    config.grab_mode    = CAMERA_GRAB_WHEN_EMPTY;

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        // Rede de segurança: se a PSRAM não comportar o buffer de 5 MP, tenta com o de UXGA
        // em vez de reiniciar em loop.
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

    registra("[INFO] XCLK: %d MHz | buffer: %u KB | maior resolucao: %s",
                  xclk_atual_mhz, (unsigned)(tamanho_buffer_jpeg / 1024),
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

        // Registra o que o driver deixou no sensor antes de mudar
        if (sensor_detectado == "OV5640") {
            int aec = sensor->get_reg(sensor, REG_AEC_CTRL00, 0xFF);
            registra("[INFO] Modo noturno do driver: %s (0x3A00 = 0x%02X); em uso: %s",
                     (aec >= 0 && (aec & BIT_MODO_NOTURNO)) ? "ligado" : "desligado",
                     aec, modo_noturno ? "ligado" : "desligado");
            aplica_modo_noturno();
        }
    }

    // A câmera já está na resolução de trabalho, com o clock dela
    resolucao_atual = RESOLUCAO_PADRAO;
    return true;
}

// Liga ou desliga o modo noturno da OV5640 (bit 2 do 0x3A00), conforme modo_noturno.
void aplica_modo_noturno() {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL || sensor_detectado != "OV5640") return;
    sensor->set_reg(sensor, REG_AEC_CTRL00, BIT_MODO_NOTURNO,
                    modo_noturno ? BIT_MODO_NOTURNO : 0);
}

// ============================================================================
// AJUSTE DE RESOLUÇÃO (com XCLK e qualidade por faixa de resolução)
// ============================================================================

// Troca o clock da câmera.
void troca_xclk(sensor_t* sensor, int mhz) {
    if (mhz == xclk_atual_mhz) return;
    registra("[INFO] Ajustando XCLK: %d -> %d MHz", xclk_atual_mhz, mhz);
    sensor->set_xclk(sensor, LEDC_TIMER_0, mhz);
    xclk_atual_mhz = mhz;
    delay(100);
}

bool ajusta_resolucao(framesize_t nova) {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL) return false;

    // Já está na resolução pedida: nada a fazer
    if (nova == resolucao_atual) return true;

    bool alta = (nova >= LIMITE_ALTA_RES);
    troca_xclk(sensor, alta ? xclk_alta_mhz : xclk_baixa_mhz);

    // --- Qualidade JPEG adaptativa (ou a manual, se definida por /tune) ---
    sensor->set_quality(sensor, qualidade_em_uso(alta));

    // --- Troca a resolução ---
    sensor->set_framesize(sensor, nova);
    resolucao_atual = nova;
    aplica_modo_noturno();

    // A janela de foco foi calculada para a resolução anterior.
    zonas_af_desatualizadas = true;

    // Tempo para o sensor reajustar exposição e balanço de branco.
    delay(alta ? 500 : 300);

    // Descarta quadros de transição.
    int descartes = alta ? 1 : 3;
    for (int i = 0; i < descartes; i++) {
        camera_fb_t* fb_lixo = esp_camera_fb_get();
        ajuste_sem_quadro = (fb_lixo == NULL);
        if (fb_lixo) esp_camera_fb_return(fb_lixo);
    }

    return true;
}

// ============================================================================
// RECUPERAÇÃO DA CÂMERA (v5.6.4)
// ============================================================================

// Exposição, ganho e tamanho do quadro no momento de uma falha.
void registra_diagnostico_sensor() {
    sensor_t* s = esp_camera_sensor_get();
    if (s == NULL || sensor_detectado != "OV5640") return;
    auto reg = [s](int r) { int v = s->get_reg(s, r, 0xFF); return v < 0 ? 0 : v; };
    unsigned long exposicao = ((unsigned long)(reg(0x3500) & 0x0F) << 16) |
                              ((unsigned long)reg(0x3501) << 8) | reg(0x3502);
    unsigned vts = (reg(0x380E) << 8) | reg(0x380F);
    unsigned extra = (reg(0x350C) << 8) | reg(0x350D);
    unsigned ganho = ((reg(0x350A) & 0x03) << 8) | reg(0x350B);
    registra("[DIAG] exposicao %lu linhas | VTS %u + %u | ganho %u | AEC 0x%02X | %.1f C",
             exposicao / 16, vts, extra, ganho, reg(0x3503), temperatureRead());
}

// Reaplica no sensor os ajustes feitos por /tune: o reinício da câmera os apaga, mas as
// variáveis continuam dizendo que estão em uso
void reaplica_ajustes(sensor_t* s) {
    if (nitidez_atual != AJUSTE_FABRICA)   s->set_sharpness(s, nitidez_atual);
    if (denoise_atual != AJUSTE_FABRICA)   s->set_denoise(s, denoise_atual);
    if (contraste_atual != AJUSTE_FABRICA) s->set_contrast(s, contraste_atual);
}

// Reinicia a câmera e o firmware de foco, foca de novo e volta a [alvo].
bool reinicia_camera(framesize_t alvo) {
    registra("[AVISO] Sem quadro: reiniciando a camera");
    esp_camera_deinit();
    delay(100);
    if (!inicializa_camera()) {
        registra("[ERRO] A camera nao voltou depois do reinicio");
        return false;
    }
    // Pouca PSRAM livre no novo init: o firmware cai para o buffer de UXGA
    if (alvo > resolucao_maxima) {
        registra("[ERRO] Depois do reinicio o buffer so comporta UXGA");
        return false;
    }
    sensor_t* s = esp_camera_sensor_get();
    if (s) reaplica_ajustes(s);
    inicializa_autofoco();

    // O reinício solta a lente (foco no infinito): foca de novo onde o app foca, antes de
    // voltar à resolução pedida
    if (autofoco_ativo) {
        ajusta_resolucao(RESOLUCAO_FOCO_RECUPERACAO);
        executa_foco();
    }
    ajusta_resolucao(alvo);
    registra("[INFO] Camera reiniciada; recuperacoes: %lu", (unsigned long)(recuperacoes_reinicio + 1));
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

// Envia um comando que não move a lente e espera o firmware de foco confirmar (CMD_ACK de
// volta a 0).
bool comando_af(uint8_t comando) {
    escreve_reg_af(REG_AF_ACK, 0x01);
    escreve_reg_af(REG_AF_COMANDO, comando);
    unsigned long inicio = millis();
    while (millis() - inicio < TIMEOUT_COMANDO_AF_MS) {
        uint8_t ack = le_reg_af(REG_AF_ACK);
        if (ack == 0x00) return true;
        if (ack == 0xFF) return false;   // erro de comunicação com o sensor
        delay(5);
    }
    return false;
}

// Pergunta ao firmware de foco quais zonas focaram (comando 0x07): cada um dos CMD_PARA0..4
// vale 0 se a zona correspondente focou.
bool foco_confirmado() {
    if (!comando_af(CMD_AF_RESULTADO)) {
        snprintf(foco_zonas, sizeof(foco_zonas), "sem-resposta");
        return true;
    }
    uint8_t z[5];
    bool alguma = false;
    for (int i = 0; i < 5; i++) {
        z[i] = le_reg_af(REG_AF_PARAM0 + i);
        if (z[i] == 0) alguma = true;
    }
    snprintf(foco_zonas, sizeof(foco_zonas), "%u,%u,%u,%u,%u",
             (unsigned)z[0], (unsigned)z[1], (unsigned)z[2], (unsigned)z[3], (unsigned)z[4]);
    return alguma;
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

    // focusInit() grava o firmware de foco no sensor e espera o status "ocioso" (0x70).
    if (ov5640.focusInit() != 0) {
        registra("[ERRO] Falha ao carregar o firmware de autofoco");
        autofoco_ativo = false;
        return;
    }

    autofoco_ativo = true;
    zonas_af_desatualizadas = false;
    registra("[OK] Firmware de autofoco carregado (status 0x%02X, foco unico)",
             le_reg_af(REG_AF_STATUS));
}

// ============================================================================
// EXECUTA O FOCO
// ============================================================================

bool executa_foco() {
    foco_ultimo_ms = 0;
    foco_ultimas_tentativas = 0;
    foco_zonas[0] = '\0';
    if (!autofoco_ativo) return false;

    unsigned long inicio_foco = millis();

    // O manual manda relançar as zonas depois de mudar a resolução: a janela de foco (o
    // centro da imagem) é calculada para a imagem do momento
    if (zonas_af_desatualizadas) {
        if (!comando_af(CMD_AF_RELANCA_ZONAS)) {
            registra("[AVISO] Firmware de foco nao confirmou o relancamento das zonas");
        }
        zonas_af_desatualizadas = false;
    }

    for (int tentativa = 1; tentativa <= TENTATIVAS_FOCO; tentativa++) {
        foco_ultimas_tentativas = tentativa;

        escreve_reg_af(REG_AF_ACK, 0x01);
        escreve_reg_af(REG_AF_COMANDO, CMD_AF_UNICO);
        unsigned long inicio = millis();

        // Concluído = CMD_ACK zerado e status 0x10.
        bool concluiu = false;
        bool viu_focando = false;
        while (millis() - inicio < TIMEOUT_FOCO_MS) {
            delay(25);
            uint8_t ack = le_reg_af(REG_AF_ACK);
            uint8_t status = le_reg_af(REG_AF_STATUS);

            if (status == 0xFF || ack == 0xFF) {
                foco_ultimo_ms = millis() - inicio_foco;
                registra("[AVISO] Erro de comunicacao com o sensor");
                return false;
            }
            if (status < 0x10 || (status >= 0x80 && status <= 0x8F)) viu_focando = true;
            if (status == AF_STATUS_FOCADO && (ack == 0x00 || viu_focando)) {
                concluiu = true;
                break;
            }
        }

        if (!concluiu) {
            registra("[AVISO] Tentativa %d de foco esgotou o tempo", tentativa);
            continue;
        }

        // Concluído não quer dizer nítido: o sensor diz quais zonas focaram
        bool focou = foco_confirmado();
        registra("[INFO] Foco concluido em %lu ms (tentativa %d), zonas %s: %s",
                 millis() - inicio, tentativa, foco_zonas,
                 focou ? "focou" : "NENHUMA focou");
        if (focou) {
            foco_ultimo_ms = millis() - inicio_foco;
            return true;
        }
    }

    foco_ultimo_ms = millis() - inicio_foco;
    registra("[AVISO] Foco nao confirmado: pouco contraste no centro da imagem, pouca luz ou objeto perto demais");
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

// Cabeçalhos de medição de uma captura.
void envia_diagnostico(bool foco_pedido, bool foco_ok, unsigned long ajuste_ms,
                       unsigned long descarte_ms, unsigned long captura_ms,
                       int tentativas_quadro, const char* recuperacao, unsigned long total_ms,
                       uint32_t ovf_antes, uint32_t avisos_antes, const camera_fb_t* fb) {
    servidor.sendHeader("X-Eyecam-Foco", foco_ok ? "travado" : (foco_pedido ? "nao-confirmado" : "nao-pedido"));
    servidor.sendHeader("X-Eyecam-Foco-Ms", String(foco_pedido ? foco_ultimo_ms : 0));
    servidor.sendHeader("X-Eyecam-Foco-Tentativas", String(foco_pedido ? foco_ultimas_tentativas : 0));
    if (foco_pedido && foco_zonas[0] != '\0') servidor.sendHeader("X-Eyecam-Foco-Zonas", foco_zonas);
    servidor.sendHeader("X-Eyecam-Ajuste-Ms", String(ajuste_ms));
    servidor.sendHeader("X-Eyecam-Descarte-Ms", String(descarte_ms));
    servidor.sendHeader("X-Eyecam-Captura-Ms", String(captura_ms));
    servidor.sendHeader("X-Eyecam-Quadro-Tentativas", String(tentativas_quadro));
    servidor.sendHeader("X-Eyecam-Recuperacao", recuperacao);
    servidor.sendHeader("X-Eyecam-Tempo-Ms", String(total_ms));
    if (fb != NULL) {
        servidor.sendHeader("X-Eyecam-Resolucao", String(fb->width) + "x" + String(fb->height));
    }
    servidor.sendHeader("X-Eyecam-Xclk-Mhz", String(xclk_atual_mhz));
    servidor.sendHeader("X-Eyecam-Qualidade", String(qualidade_em_uso(resolucao_atual >= LIMITE_ALTA_RES)));
    servidor.sendHeader("X-Eyecam-Noturno", modo_noturno ? "1" : "0");
    servidor.sendHeader("X-Eyecam-Ovf", String((unsigned long)(ovf_total - ovf_antes)));
    servidor.sendHeader("X-Eyecam-Avisos-Cam", String((unsigned long)(avisos_cam_total - avisos_antes)));
    servidor.sendHeader("X-Eyecam-Captura-N", String((unsigned long)capturas_desde_boot));
    servidor.sendHeader("X-Eyecam-Uptime-S", String(millis() / 1000));
    servidor.sendHeader("X-Eyecam-Temp-C", String(temperatureRead(), 1));
    servidor.sendHeader("X-Eyecam-Log-Seq", String((unsigned long)log_ultima));
    // Bateria medida antes da foto (0 = sem medição, placa no USB)
    servidor.sendHeader("X-Eyecam-Bateria-Mv", String((unsigned long)bateria_mv));
    servidor.sendHeader("X-Eyecam-Energia", energia_pela_bateria ? "bateria" : "usb");
    servidor.sendHeader("Access-Control-Allow-Origin", "*");
}

// ----------------------------------------------------------------------------
// Marca a captura em andamento enquanto existir: a medição da bateria pula
// ----------------------------------------------------------------------------

void camera_dormir() {
    sensor_t* s = esp_camera_sensor_get();
    if (s == NULL || camera_em_espera) return;
    s->set_reg(s, REG_SYSTEM_CTRL0, 0xFF, SENSOR_EM_ESPERA);
    camera_em_espera = true;
    entradas_em_espera++;
    registra("[CAMERA] Em espera (sem pedidos ha %lu s)", ESPERA_CAMERA_MS / 1000);
}

void camera_acordar() {
    if (!camera_em_espera) return;
    sensor_t* s = esp_camera_sensor_get();
    if (s != NULL) s->set_reg(s, REG_SYSTEM_CTRL0, 0xFF, SENSOR_ATIVO);
    camera_em_espera = false;
    delay(ACORDAR_CAMERA_MS);
    // O sensor parou de gerar quadros: por segurança, o próximo foco relança as zonas
    // (0x12), como depois de trocar a resolução
    zonas_af_desatualizadas = true;
    registra("[CAMERA] Acordada");
}

struct MarcaCaptura {
    MarcaCaptura()  { captura_em_andamento = true; camera_acordar(); ultima_atividade_camera = millis(); }
    ~MarcaCaptura() { captura_em_andamento = false; ultima_atividade_camera = millis(); }
};

void handler_capture() {
    MarcaCaptura marca;
    unsigned long inicio_total = millis();
    capturas_desde_boot++;
    uint32_t ovf_antes = ovf_total;
    uint32_t avisos_antes = avisos_cam_total;
    unsigned long ajuste_ms = 0;
    ajuste_sem_quadro = false;

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

    // --- Descarta o quadro antigo ---
    // GRAB_WHEN_EMPTY (1 buffer): o buffer guarda um quadro capturado logo
    // depois da foto ANTERIOR, ou seja, antes deste pedido e antes do foco.
    // Descartá-lo faz a câmera capturar um quadro novo. Um descarte basta.
    // Se a troca de resolução não recebeu quadro nenhum, pula: seriam mais
    // 4 s esperando antes da recuperação.
    unsigned long inicio_descarte = millis();
    if (!ajuste_sem_quadro) {
        camera_fb_t* fb_descarte = esp_camera_fb_get();
        if (fb_descarte) esp_camera_fb_return(fb_descarte);
    }
    unsigned long descarte_ms = millis() - inicio_descarte;

    // --- Captura ---
    // O tempo de esp_camera_fb_get é o de um quadro: se um quadro se perder
    // (FB-OVF), ele dobra. Sem quadro em 4 s e sem FB-OVF, a câmera parou de
    // entregar quadros nesta resolução: recuperação em dois passos (v5.6.4)
    unsigned long inicio_captura = millis();
    int tentativas_quadro = 1;
    const char* recuperacao = "nenhuma";
    camera_fb_t* fb = esp_camera_fb_get();
    bool alta = (resolucao_atual >= LIMITE_ALTA_RES);

    // 1) JPEG grande demais?
    if (!fb && ovf_total == ovf_antes && alta && qualidade_manual == 0 &&
        QUALIDADE_ALTA_RES + compressao_extra + PASSO_COMPRESSAO <= QUALIDADE_MAXIMA_RECUPERACAO) {
        compressao_extra += PASSO_COMPRESSAO;
        sensor_t* s = esp_camera_sensor_get();
        if (s) s->set_quality(s, qualidade_em_uso(true));
        registra("[AVISO] Sem quadro; JPEG mais comprimido: qualidade %d", qualidade_em_uso(true));
        registra_diagnostico_sensor();
        tentativas_quadro++;
        fb = esp_camera_fb_get();
        if (fb) {
            recuperacao = "compressao";
            recuperacoes_compressao++;
        }
    }

    // 2) Reinicia a câmera, foca de novo e tenta outra vez
    if (!fb && ovf_total == ovf_antes) {
        registra_diagnostico_sensor();
        framesize_t alvo = resolucao_atual;
        if (reinicia_camera(alvo)) {
            camera_fb_t* fb_descarte = esp_camera_fb_get();
            if (fb_descarte) esp_camera_fb_return(fb_descarte);
            tentativas_quadro++;
            fb = esp_camera_fb_get();
            if (fb) {
                recuperacao = "reinicio";
                recuperacoes_reinicio++;
            }
        }
    }
    if (!fb) recuperacao = "falhou";
    unsigned long captura_ms = millis() - inicio_captura;
    unsigned long tempo_total = millis() - inicio_total;

    if (!fb) {
        registra("[ERRO] Falha ao capturar quadro (captura %lu, %lu ms, FB-OVF %lu, %.1f C)",
                 (unsigned long)capturas_desde_boot, tempo_total,
                 (unsigned long)(ovf_total - ovf_antes), temperatureRead());
        envia_diagnostico(foco_pedido, foco_ok, ajuste_ms, descarte_ms, captura_ms,
                          tentativas_quadro, recuperacao, tempo_total, ovf_antes, avisos_antes, NULL);
        servidor.send(500, "text/plain",
            "Falha na captura. Com FB-OVF: reduza a resolucao ou o XCLK (/tune). "
            "Sem FB-OVF: a camera nao entregou quadros nem depois de reiniciada.");
        return;
    }

    envia_diagnostico(foco_pedido, foco_ok, ajuste_ms, descarte_ms, captura_ms,
                      tentativas_quadro, recuperacao, tempo_total, ovf_antes, avisos_antes, fb);
    // O Content-Type vai no próprio send_P; enviá-lo também por sendHeader duplicava o
    // cabeçalho na resposta
    servidor.send_P(200, "image/jpeg", (const char*)fb->buf, fb->len);

    bool recuperada = strcmp(recuperacao, "nenhuma") != 0;
    registra("[INFO] Foto %lu: %ux%u, %u bytes | foco: %s | %lu ms | q%d | OVF %lu | %.1f C%s%s",
             (unsigned long)capturas_desde_boot, fb->width, fb->height, fb->len,
             foco_ok ? "travado" : (foco_pedido ? "nao-confirmado" : "nao-pedido"),
             tempo_total, qualidade_em_uso(alta), (unsigned long)(ovf_total - ovf_antes),
             temperatureRead(), recuperada ? " | recuperada: " : "", recuperada ? recuperacao : "");

    esp_camera_fb_return(fb);
}

// ============================================================================
// HANDLER: /log
// ============================================================================

void handler_log() {
    uint32_t desde = 0;
    if (servidor.hasArg("desde")) {
        desde = strtoul(servidor.arg("desde").c_str(), NULL, 10);
    }

    String texto;
    texto.reserve(2048);

    // A tarefa de energia também registra: a memória é lida com a trava
    xSemaphoreTake(mutex_log, portMAX_DELAY);
    uint32_t ultima = log_ultima;

    // Só as linhas que ainda estão na memória circular
    uint32_t primeira = (ultima > (uint32_t)LOG_LINHAS) ? ultima - LOG_LINHAS + 1 : 1;
    if (desde + 1 > primeira) primeira = desde + 1;

    for (uint32_t n = primeira; n <= ultima; n++) {
        int i = n % LOG_LINHAS;
        texto += String((unsigned long)n) + "\t" + String((unsigned long)log_tempo_ms[i]) +
                 "\t" + log_texto[i] + "\n";
    }
    xSemaphoreGive(mutex_log);

    servidor.sendHeader("X-Eyecam-Log-Seq", String((unsigned long)ultima));
    servidor.sendHeader("X-Eyecam-Ovf-Total", String((unsigned long)ovf_total));
    servidor.sendHeader("Access-Control-Allow-Origin", "*");
    servidor.send(200, "text/plain; charset=utf-8", texto);
}

// ============================================================================
// HANDLER: /focus
// ============================================================================

void handler_focus() {
    MarcaCaptura marca;
    if (!autofoco_ativo) {
        servidor.send(200, "text/plain",
            "Autofoco indisponivel.\n\n"
            "Possiveis causas:\n"
            "  - sensor nao e OV5640\n"
            "  - biblioteca de AF nao instalada\n"
            "  - pino AF-VCC do cabo flat sem 3,3V\n");
        return;
    }

    bool ok = executa_foco();

    String msg = "=== DIAGNOSTICO DO AUTOFOCO ===\n\n";
    msg += "Resultado: ";
    msg += ok ? "FOCO CONFIRMADO" : "FOCO NAO CONFIRMADO";
    msg += "\nTempo: " + String(foco_ultimo_ms) + " ms em " + String(foco_ultimas_tentativas) + " tentativa(s)";
    msg += "\nZonas (0 = focou): " + String(foco_zonas[0] ? foco_zonas : "-");
    msg += "\nStatus (0x3029): 0x" + String(le_reg_af(REG_AF_STATUS), HEX);
    msg += "\n\nO autofoco mede o CONTRASTE no centro da imagem. Aponte o\n";
    msg += "centro para o texto, com boa iluminacao, a uns 20-35 cm.\n";

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
    json += "\"versao_firmware\":\"5.7.4\",";
    json += "\"motivo_reset\":\"" + String(motivo_reset()) + "\",";
    json += "\"capturas_desde_boot\":" + String((unsigned long)capturas_desde_boot) + ",";
    json += "\"fb_ovf_total\":" + String((unsigned long)ovf_total) + ",";
    json += "\"avisos_camera_total\":" + String((unsigned long)avisos_cam_total) + ",";
    json += "\"log_seq\":" + String((unsigned long)log_ultima) + ",";
    json += "\"temperatura_c\":" + String(temperatureRead(), 1) + ",";
    json += "\"compressao_extra\":" + String(compressao_extra) + ",";
    json += "\"recuperacoes_compressao\":" + String((unsigned long)recuperacoes_compressao) + ",";
    json += "\"recuperacoes_reinicio\":" + String((unsigned long)recuperacoes_reinicio) + ",";
    json += "\"placa\":\"ESP32-S3-CAM N16R8\",";
    json += "\"sensor\":\"" + sensor_detectado + "\",";
    json += "\"autofoco_disponivel\":" + String(autofoco_ativo ? "true" : "false") + ",";
    json += "\"autofoco_status\":" + String(af_status) + ",";
    json += "\"xclk_mhz\":" + String(xclk_atual_mhz) + ",";
    json += "\"resolucao_atual\":" + String((int)resolucao_atual) + ",";
    json += "\"resolucao_maxima\":\"" + String(resolucao_maxima == FRAMESIZE_QSXGA ? "qsxga" : "uxga") + "\",";
    json += "\"buffer_jpeg_kb\":" + String((unsigned long)(tamanho_buffer_jpeg / 1024)) + ",";
    json += json_ajustes() + ",";
    json += json_energia() + ",";
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
// HANDLER: /tune  (ajustes de imagem e do clock para a bancada de testes)
// ============================================================================

void handler_tune() {
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == NULL) {
        servidor.send(500, "text/plain", "Sensor indisponivel");
        return;
    }

    if (servidor.hasArg("reset") && servidor.arg("reset") == "1") {
        // Na bateria o reinício solta o HOLD e desliga o aparelho (v5.7)
        servidor.send(200, "text/plain", energia_pela_bateria
            ? "Reiniciando a placa. Na bateria, o reinicio DESLIGA o aparelho: ligue de novo pelo botao."
            : "Reiniciando a placa para voltar aos valores de fabrica (~5 s).");
        delay(200);
        ESP.restart();
    }

    // --- Validação de tudo antes de aplicar qualquer coisa ---
    struct Ajuste { const char* nome; int minimo; int maximo; };
    const Ajuste faixas[] = {
        {"sharpness", -3, 3}, {"denoise", 0, 8}, {"contrast", -2, 2}, {"quality", 0, 63},
        {"xclk", XCLK_MINIMO_MHZ, XCLK_MAXIMO_MHZ}, {"xclk_alta", XCLK_MINIMO_MHZ, XCLK_MAXIMO_MHZ},
        {"noite", 0, 1}
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
                "quality 4..63 (0 = automatica), xclk e xclk_alta 6..24 (MHz), noite 0..1");
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
    if (servidor.hasArg("noite")) {
        modo_noturno = servidor.arg("noite") == "1";
        aplica_modo_noturno();
    }
    if (servidor.hasArg("xclk") || servidor.hasArg("xclk_alta")) {
        if (servidor.hasArg("xclk")) xclk_baixa_mhz = servidor.arg("xclk").toInt();
        if (servidor.hasArg("xclk_alta")) xclk_alta_mhz = servidor.arg("xclk_alta").toInt();
        // Se o clock da resolução atual mudou, aplica já e refaz a resolução (o PLL do
        // sensor depende do XCLK)
        bool alta = (resolucao_atual >= LIMITE_ALTA_RES);
        int desejado = alta ? xclk_alta_mhz : xclk_baixa_mhz;
        if (desejado != xclk_atual_mhz) {
            troca_xclk(sensor, desejado);
            sensor->set_framesize(sensor, resolucao_atual);
            aplica_modo_noturno();
            zonas_af_desatualizadas = true;
        }
    }

    registra("[INFO] /tune: nitidez=%d denoise=%d contraste=%d qualidade=%d xclk=%d/%d noturno=%d (falhas: %d)",
                  nitidez_atual, denoise_atual, contraste_atual,
                  qualidade_em_uso(resolucao_atual >= LIMITE_ALTA_RES),
                  xclk_baixa_mhz, xclk_alta_mhz, modo_noturno ? 1 : 0, falhas);

    String json = "{" + json_ajustes() + ",\"falhas\":" + String(falhas) + "}";
    servidor.sendHeader("Access-Control-Allow-Origin", "*");
    servidor.send(falhas == 0 ? 200 : 500, "application/json", json);
}

int qualidade_em_uso(bool alta) {
    if (qualidade_manual > 0) return qualidade_manual;
    return alta ? QUALIDADE_ALTA_RES + compressao_extra : QUALIDADE_PADRAO;
}

// Trecho JSON com os ajustes em uso (null = valor de fábrica)
String json_ajustes() {
    auto valor = [](int v) { return v == AJUSTE_FABRICA ? String("null") : String(v); };
    bool alta = (resolucao_atual >= LIMITE_ALTA_RES);
    String j = "\"nitidez\":" + valor(nitidez_atual);
    j += ",\"denoise\":" + valor(denoise_atual);
    j += ",\"contraste\":" + valor(contraste_atual);
    j += ",\"qualidade_jpeg\":" + String(qualidade_em_uso(alta));
    j += ",\"camera_em_espera\":" + String(camera_em_espera ? "true" : "false");
    j += ",\"entradas_em_espera\":" + String(entradas_em_espera);
    j += ",\"qualidade_modo\":\"" + String(qualidade_manual > 0 ? "manual" : "automatica") + "\"";
    j += ",\"xclk_ate_xga_mhz\":" + String(xclk_baixa_mhz);
    j += ",\"xclk_alta_mhz\":" + String(xclk_alta_mhz);
    j += ",\"modo_noturno\":" + String(modo_noturno ? "true" : "false");
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
    msg += "  GET /tune     - ajustes de imagem e do clock (JSON)\n";
    msg += "  GET /log      - ultimas mensagens do firmware (?desde=N)\n";
    msg += "\nParametros de /capture:\n";
    msg += "  ?res=qvga|vga|svga|xga|sxga|uxga|qxga|qsxga (5mp = qsxga)\n";
    msg += "  ?af=0  (captura sem focar)\n";
    msg += "\nParametros de /tune:\n";
    msg += "  ?sharpness=-3..3 &denoise=0..8 &contrast=-2..2 &quality=4..63 (0 = auto)\n";
    msg += "  ?xclk=6..24 &xclk_alta=6..24 (MHz) &noite=0|1\n";
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
