package com.constx.onvyra.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = RequestLogSanitizer.headers(request.headers)
        val requestBody = RequestLogSanitizer.body(request.body)
        val logUrl = RequestLogSanitizer.url(request.url)

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            // Exception text can contain credentials or signed URLs from upstream requests.
            error = e.javaClass.simpleName
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = logUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = RequestLogSanitizer.headers(response.headers)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = logUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

}
