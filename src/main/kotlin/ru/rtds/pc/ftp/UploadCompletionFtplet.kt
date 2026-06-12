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
}
