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
        val relativePath = request.argument.trim().removePrefix("/")
        if (relativePath.isBlank()) {
            return FtpletResult.DEFAULT
        }

        val uploadedFile = homeDir.resolve(relativePath).normalize()
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

    private fun formatAddress(address: java.net.InetSocketAddress?): String {
        if (address == null) {
            return "unknown"
        }
        val host = address.address?.hostAddress ?: address.hostString
        return "$host:${address.port}"
    }
}
