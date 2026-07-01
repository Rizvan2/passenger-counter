package ru.rtds.pc.service

import ai.onnxruntime.OrtSession
import org.slf4j.LoggerFactory

object OrtSessionOptionsFactory {
    private val log = LoggerFactory.getLogger(OrtSessionOptionsFactory::class.java)

    fun create(provider: String, intraOpThreads: Int? = null): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        intraOpThreads?.let { options.setIntraOpNumThreads(it) }

        if (provider.equals("cuda", ignoreCase = true)) {
            val added = runCatching {
                val method = options.javaClass.methods.firstOrNull { it.name == "addCUDA" && it.parameterCount == 1 }
                    ?: return@runCatching false
                method.invoke(options, 0)
                true
            }.getOrElse {
                log.warn("CUDA ONNX provider is not available, falling back to CPU: {}", it.message)
                false
            }
            if (!added) {
                log.warn("CUDA ONNX provider requested but could not be enabled, falling back to CPU")
            }
        }

        return options
    }
}
