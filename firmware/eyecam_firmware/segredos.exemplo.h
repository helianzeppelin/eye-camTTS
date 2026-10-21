// ============================================================================
// Eye-cam - Modelo do arquivo de segredos
// ============================================================================
//
// COMO USAR:
//   1. Copie este arquivo, na mesma pasta, com o nome  segredos.h
//   2. Troque a senha abaixo por uma sua (8 a 63 caracteres)
//   3. Grave o firmware
//
// O segredos.h está no .gitignore e nunca vai para o GitHub. Este modelo vai,
// por isso não coloque a senha real aqui.
//
// O app Android lê a MESMA senha deste arquivo ao compilar
// (app-android/app/build.gradle.kts), para conectar sozinho à rede do
// óculos. Ao trocar a senha, grave o firmware e recompile o app.
// ============================================================================

#pragma once

// Senha da rede Wi-Fi "EyeCam_01" criada pelo óculos
#define EYECAM_AP_SENHA "troque-esta-senha"
