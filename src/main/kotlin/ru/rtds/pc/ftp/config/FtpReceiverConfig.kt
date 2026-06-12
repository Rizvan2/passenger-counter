package ru.rtds.pc.ftp.config

import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.rtds.pc.ftp.UploadCompletionFtplet
import ru.rtds.pc.ftp.service.ReceivedVideoDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Configuration
class FtpReceiverConfig(
    private val properties: FtpProperties,
    private val dispatcher: ReceivedVideoDispatcher,
) {
    @Bean(initMethod = "start", destroyMethod = "stop")
    fun ftpServer(): FtpServer {
        val homeDir = prepareDir(properties.home)
        prepareDir(properties.processedDir)

        val serverFactory = FtpServerFactory()

        val listenerFactory = ListenerFactory().apply {
            port = properties.port
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                passivePorts = properties.passive.ports
                if (properties.passive.externalAddress.isNotBlank()) {
                    passiveExternalAddress = properties.passive.externalAddress
                }
            }.createDataConnectionConfiguration()
        }
        serverFactory.addListener("default", listenerFactory.createListener())

        val userManager = PropertiesUserManagerFactory().createUserManager()
        val ftpUser = BaseUser().apply {
            setName(properties.user)
            setPassword(properties.password)
            setHomeDirectory(homeDir.toString())
            setAuthorities(listOf(WritePermission()))
        }
        userManager.save(ftpUser)
        serverFactory.userManager = userManager

        serverFactory.ftplets = mapOf(
            "uploadCompletionFtplet" to UploadCompletionFtplet(dispatcher, homeDir),
        )

        return serverFactory.createServer()
    }

    private fun prepareDir(dir: String): Path {
        val path = Paths.get(dir).toAbsolutePath().normalize()
        Files.createDirectories(path)
        return path
    }
}
