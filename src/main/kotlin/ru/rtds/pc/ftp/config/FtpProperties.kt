package ru.rtds.pc.ftp.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ftp")
data class FtpProperties(
    val port: Int = 2021,
    val user: String = "admin",
    val password: String = "admin",
    val home: String = "./videos/incoming",
    val processedDir: String = "./videos/processed",
    val passive: Passive = Passive(),
    val analysis: Analysis = Analysis(),
    // true  = тестовый режим: файл перемещается в processed/ после анализа
    //         (можно просмотреть через интерфейс)
    // false = продовый режим: файл сразу удаляется после анализа
    val keepAfterAnalysis: Boolean = true,
) {
    data class Passive(
        val ports: String = "30000-30010",
        val externalAddress: String = "",
    )

    data class Analysis(
        val lineYRatio: Float = 0.5f,
        // false = салон снизу (камера смотрит сверху/из глубины салона на дверь)
        val insideOnTop: Boolean = false,
        val autoInitialOnboard: Boolean = true,
        val initialOnboard: Int = 0,
    )
}
