package com.eyecam.app

import android.app.Application
import org.opencv.android.OpenCVLoader
import android.util.Log

/** Classe Application: roda uma vez quando o app inicia. */
class EyeCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EyeCamClient.carregarRotacao(this)
        if (OpenCVLoader.initLocal()) {
            Log.d("EyeCam", "OpenCV inicializado com sucesso")
        } else {
            Log.e("EyeCam", "Falha ao inicializar OpenCV")
        }
    }
}