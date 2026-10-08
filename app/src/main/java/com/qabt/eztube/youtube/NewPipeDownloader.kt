package com.qabt.eztube.youtube

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import java.util.concurrent.TimeUnit

class NewPipeDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val call = client.newCall(request.toOkHttpRequest())
        call.execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bytes.toString(Charsets.UTF_8),
                bytes,
                response.request.url.toString()
            )
        }
    }

    override fun executeAsync(
        request: Request,
        callback: AsyncCallback
    ): CancellableCall {
        val call = client.newCall(request.toOkHttpRequest())
        val cancellable = CancellableCall(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                try {
                    callback.onError(e)
                } finally {
                    cancellable.setFinished()
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val bytes = it.body?.bytes() ?: ByteArray(0)
                        callback.onSuccess(
                            Response(
                                it.code,
                                it.message,
                                it.headers.toMultimap(),
                                bytes.toString(Charsets.UTF_8),
                                bytes,
                                it.request.url.toString()
                            )
                        )
                    } catch (e: Exception) {
                        callback.onError(e)
                    } finally {
                        cancellable.setFinished()
                    }
                }
            }
        })
        return cancellable
    }

    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?
    ): StreamingResponse =
        openStreaming("GET", url, headers, null, null)

    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?,
        timeoutMs: Long
    ): StreamingResponse {
        val callClient = if (timeoutMs > 0L) {
            client.newBuilder()
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            client
        }
        return openStreaming("GET", url, headers, null, null, callClient)
    }

    override fun postStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        dataToSend: ByteArray?,
        localization: Localization?
    ): StreamingResponse =
        openStreaming(
            method = "POST",
            url = url,
            headers = headers,
            body = dataToSend ?: ByteArray(0),
            contentType = headers
                ?.entries
                ?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
        )

    private fun openStreaming(
        method: String,
        url: String,
        headers: Map<String, List<String>>?,
        body: ByteArray?,
        contentType: String?,
        callClient: OkHttpClient = client
    ): StreamingResponse {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)

        headers.orEmpty().forEach { (name, values) ->
            values.forEachIndexed { index, value ->
                if (index == 0) builder.header(name, value) else builder.addHeader(name, value)
            }
        }

        if (method == "POST") {
            builder.post(
                (body ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull())
            )
        } else {
            builder.get()
        }

        val response = callClient.newCall(builder.build()).execute()
        val responseBody = response.body
        if (responseBody == null) {
            val code = response.code
            val responseHeaders = response.headers.toMultimap()
            response.close()
            return StreamingResponse(
                code,
                responseHeaders,
                ByteArray(0).inputStream()
            )
        }

        val rawStream = responseBody.byteStream()
        val closingStream = object : java.io.FilterInputStream(rawStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }

        return StreamingResponse(
            response.code,
            response.headers.toMultimap(),
            closingStream
        )
    }

    private fun Request.toOkHttpRequest(): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
            .url(url())
            .header("User-Agent", USER_AGENT)

        headers().forEach { (name, values) ->
            values.forEachIndexed { index, value ->
                if (index == 0) builder.header(name, value) else builder.addHeader(name, value)
            }
        }

        val method = httpMethod()
        val data = dataToSend()
        return when (method.uppercase()) {
            "GET" -> builder.get().build()
            "HEAD" -> builder.head().build()
            "POST" -> builder.post(
                (data ?: ByteArray(0)).toRequestBody(
                    headers()
                        .entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value
                        ?.firstOrNull()
                        ?.toMediaTypeOrNull()
                )
            ).build()
            else -> builder.method(
                method,
                data?.toRequestBody(null)
            ).build()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
