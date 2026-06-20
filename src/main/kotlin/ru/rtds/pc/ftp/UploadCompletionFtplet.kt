package ru.rtds.pc.ftp

import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.slf4j.LoggerFactory
import ru.rtds.pc.ftp.service.ReceivedVideoDispatcher
import java.nio.file.Files
import java.nio.file.Path

class UploadCompletionFtplet(
    private val dispatcher: ReceivedVideoDispatcher,
    private val homeDir: Path,
) : DefaultFtplet() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onConnect(session: FtpSession): FtpletResult {
        log.info(
            "FTP client connected: remote={}, local={}, session={}",
            formatAddress(session.clientAddress),
            formatAddress(session.serverAddress),
            session.sessionId,
        )
        return FtpletResult.DEFAULT
    }

    override fun onLogin(session: FtpSession, request: FtpRequest): FtpletResult {
        log.info(
            "FTP client logged in: user={}, remote={}, session={}",
            session.user?.name ?: session.userArgument ?: request.argument,
            formatAddress(session.clientAddress),
            session.sessionId,
        )
        return FtpletResult.DEFAULT
    }

    override fun onDisconnect(session: FtpSession): FtpletResult {
        log.info(
            "FTP client disconnected: remote={}, session={}",
            formatAddress(session.clientAddress),
            session.sessionId,
        )
        return FtpletResult.DEFAULT
    }

    override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
        val argument = request.argument.orEmpty()
        if (argument.isBlank()) {
            return FtpletResult.DEFAULT
        }

        val uploadedFile = runCatching { requestedUploadPath(session, argument) }.getOrElse {
            log.warn("Rejected FTP upload with invalid path: {}", argument, it)
            return FtpletResult.DEFAULT
        }
        if (!uploadedFile.startsWith(homeDir)) {
            log.warn("Rejected FTP upload outside home directory: {}", uploadedFile)
            return FtpletResult.DEFAULT
        }

        runCatching {
            if (Files.exists(uploadedFile)) {
                log.info("FTP upload finished: {} ({} bytes)", uploadedFile, Files.size(uploadedFile))
                dispatcher.onVideoReceived(uploadedFile)
            } else {
                log.warn("FTP upload finished but file is missing: {}", uploadedFile)
            }
        }.onFailure {
            log.error("Failed to dispatch uploaded file {}", uploadedFile, it)
        }

        return FtpletResult.DEFAULT
    }

    private fun requestedUploadPath(session: FtpSession, argument: String): Path {
        val physicalFile = runCatching { session.fileSystemView?.getFile(argument)?.physicalFile }.getOrNull()
        val targetFromFtpView = when (physicalFile) {
            is Path -> physicalFile
            is java.io.File -> physicalFile.toPath()
            is String -> Path.of(physicalFile)
            else -> null
        }
        if (targetFromFtpView != null) {
            val target = targetFromFtpView.toAbsolutePath().normalize()
            require(target.startsWith(homeDir)) {
                "Resolved upload path escapes FTP home: $argument -> $target"
            }
            return target
        }

        val ftpPath = argument.trim().trim('"').replace('\\', '/')
        val relative = if (ftpPath.startsWith("/")) {
            ftpPath.removePrefix("/")
        } else {
            val cwd = session.fileSystemView?.workingDirectory?.absolutePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                .orEmpty()
            listOf(cwd, ftpPath).filter { it.isNotBlank() }.joinToString("/")
        }
        val target = homeDir.resolve(relative).normalize()
        require(target.startsWith(homeDir)) {
            "Requested upload path escapes FTP home: $argument"
        }
        return target
    }

    private fun formatAddress(address: java.net.InetSocketAddress?): String {
        if (address == null) {
            return "unknown"
        }
        val host = address.address?.hostAddress ?: address.hostString
        return "$host:${address.port}"
    }
}
