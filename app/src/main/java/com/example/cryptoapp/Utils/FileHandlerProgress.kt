package com.example.cryptoapp.Utils

class FileHandlerProgress(_fileSize:Long) {
    private val fileSize = _fileSize
    private var progressSize:Long = 0

    fun init():Unit {
        progressSize = 0
    }

    fun update(addSize:Long):Unit {
        progressSize += addSize
    }

    fun getStatus():Double {
        println(progressSize.toDouble() / fileSize.toDouble())
        return progressSize.toDouble() / fileSize.toDouble()
    }
}