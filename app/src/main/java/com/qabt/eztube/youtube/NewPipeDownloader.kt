package com.qabt.eztube.youtube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL

class NewPipeDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                values.forEachIndexed { index, value ->
                    if (index == 0) setRequestProperty(name, value) else addRequestProperty(name, value)
                }
            }

            request.dataToSend()?.let { body ->
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }

        return try {
            val code = connection.responseCode
            val bodyStream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = bodyStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }

            Response(
                code,
                connection.responseMessage.orEmpty(),
                headers,
                body,
                connection.url.toString()
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
