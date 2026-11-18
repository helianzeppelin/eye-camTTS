package com.eyecam.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Arquivos do modo bancada: fotos, registros e planilhas de cada sessão, numa pasta só. */
object ArquivosTestes {

    private const val PASTA = "EyeCam"

    /** Onde os arquivos ficam, para mostrar na tela */
    fun descreverPasta(sessao: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Download/$PASTA/$sessao"
        } else {
            "Android/data/com.eyecam.app/files/$PASTA/$sessao"
        }

    /** Grava um arquivo novo; devolve a referência, ou null se falhar */
    suspend fun salvar(
        context: Context,
        sessao: String,
        nome: String,
        tipo: String,
        conteudo: ByteArray
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                salvarMediaStore(context, sessao, nome, tipo, conteudo)
            } else {
                val arquivo = File(pastaAntiga(context, sessao), nome)
                arquivo.writeBytes(conteudo)
                arquivo.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun salvarTexto(context: Context, sessao: String, nome: String, texto: String): String? =
        salvar(context, sessao, nome, "text/plain", texto.toByteArray(Charsets.UTF_8))

    /**
     * Acrescenta linhas a uma planilha da sessão, criando-a com o cabeçalho se ainda não
     * existir.
     */
    suspend fun acrescentarPlanilha(
        context: Context,
        sessao: String,
        nome: String,
        colunas: List<String>,
        linhas: List<Map<String, String>>
    ): Boolean = reescreverPlanilha(context, sessao, nome) { atual ->
        FormatoTestes.acrescentar(atual, colunas, linhas)
    }

    /** Tira da planilha as linhas de um teste (para refazê-lo) */
    suspend fun removerDaPlanilha(context: Context, sessao: String, nome: String, teste: String): Boolean =
        reescreverPlanilha(context, sessao, nome, criarSeFaltar = false) { atual ->
            FormatoTestes.removerTeste(atual, teste)
        }

    /** Apaga um arquivo salvo por este app */
    suspend fun apagar(context: Context, referencia: String) = withContext(Dispatchers.IO) {
        try {
            if (referencia.startsWith("content://")) {
                context.contentResolver.delete(referencia.toUri(), null, null)
            } else {
                File(referencia).delete()
            }
        } catch (e: Exception) {
            // Já apagado à mão, por exemplo: nada a fazer
        }
    }

    // ------------------------------------------------------------------

    private suspend fun reescreverPlanilha(
        context: Context,
        sessao: String,
        nome: String,
        criarSeFaltar: Boolean = true,
        transformar: (String) -> String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolvedor = context.contentResolver
                val existente = procurarMediaStore(context, sessao, nome)
                if (existente == null && !criarSeFaltar) return@withContext false
                val atual = existente?.let { uri ->
                    resolvedor.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.orEmpty()
                val novo = transformar(atual).toByteArray(Charsets.UTF_8)
                if (existente != null) {
                    // "wt": substitui o conteúdo inteiro
                    resolvedor.openOutputStream(existente, "wt")?.use { it.write(novo) } != null
                } else {
                    salvarMediaStore(context, sessao, nome, "text/csv", novo) != null
                }
            } else {
                val arquivo = File(pastaAntiga(context, sessao), nome)
                if (!arquivo.exists() && !criarSeFaltar) return@withContext false
                val atual = if (arquivo.exists()) arquivo.readText(Charsets.UTF_8) else ""
                arquivo.writeText(transformar(atual), Charsets.UTF_8)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun caminhoRelativo(sessao: String) = "${Environment.DIRECTORY_DOWNLOADS}/$PASTA/$sessao/"

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun salvarMediaStore(
        context: Context,
        sessao: String,
        nome: String,
        tipo: String,
        conteudo: ByteArray
    ): String? {
        val resolvedor = context.contentResolver
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, tipo)
            put(MediaStore.Downloads.RELATIVE_PATH, caminhoRelativo(sessao))
            // Invisível para os outros apps até terminar de gravar
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolvedor.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores) ?: return null
        resolvedor.openOutputStream(uri)?.use { it.write(conteudo) } ?: run {
            resolvedor.delete(uri, null, null)
            return null
        }
        valores.clear()
        valores.put(MediaStore.Downloads.IS_PENDING, 0)
        resolvedor.update(uri, valores, null, null)
        return uri.toString()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun procurarMediaStore(context: Context, sessao: String, nome: String): Uri? {
        val colecao = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            colecao,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(caminhoRelativo(sessao), nome),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return ContentUris.withAppendedId(colecao, cursor.getLong(0))
        }
        return null
    }

    private fun pastaAntiga(context: Context, sessao: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "$PASTA/$sessao").apply { mkdirs() }
}
