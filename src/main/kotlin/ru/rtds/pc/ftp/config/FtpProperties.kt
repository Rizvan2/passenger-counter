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
) {
    data class Passive(
        val ports: String = "30000-30009",
        val externalAddress: String = "",
    )
}
