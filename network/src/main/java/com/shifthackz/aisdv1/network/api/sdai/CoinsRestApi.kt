package com.shifthackz.aisdv1.network.api.sdai

import com.shifthackz.aisdv1.network.response.CoinsResponse
import io.reactivex.rxjava3.core.Single
import retrofit2.http.GET

interface CoinsRestApi {

    @GET("/coins.json")
    fun fetchCoinsConfig(): Single<CoinsResponse>
}
