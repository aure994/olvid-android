package io.olvid.messenger.discussion.linkpreview

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class UserAgentInterceptor(private val userAgentForUrl: (HttpUrl) -> String) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", userAgentForUrl(chain.request().url))
                .build()
        )
    }
}