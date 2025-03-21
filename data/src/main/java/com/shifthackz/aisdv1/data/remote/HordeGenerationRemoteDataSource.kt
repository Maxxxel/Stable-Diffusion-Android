package com.shifthackz.aisdv1.data.remote

import android.graphics.BitmapFactory
import com.shifthackz.aisdv1.core.common.log.debugLog
import com.shifthackz.aisdv1.core.imageprocessing.BitmapToBase64Converter
import com.shifthackz.aisdv1.data.mappers.mapHordeToAiGenResult
import com.shifthackz.aisdv1.data.mappers.mapToHordeRequest
import com.shifthackz.aisdv1.domain.datasource.HordeGenerationDataSource
import com.shifthackz.aisdv1.domain.entity.HordeProcessStatus
import com.shifthackz.aisdv1.domain.entity.ImageToImagePayload
import com.shifthackz.aisdv1.domain.entity.TextToImagePayload
import com.shifthackz.aisdv1.network.api.horde.HordeRestApi
import com.shifthackz.aisdv1.network.request.HordeGenerationAsyncRequest
import com.shifthackz.aisdv1.network.response.HordeGenerationCheckResponse
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.PublishSubject
import java.net.URL
import java.util.concurrent.TimeUnit

internal class HordeGenerationRemoteDataSource(
    private val hordeApi: HordeRestApi,
    private val converter: BitmapToBase64Converter,
    private val statusSource: HordeGenerationDataSource.StatusSource,
) : HordeGenerationDataSource.Remote {

    override fun validateApiKey() = hordeApi
        .checkHordeApiKey()
        .map { user -> user.id != null }
        .onErrorReturn { false }

    override fun textToImage(payload: TextToImagePayload) = Single
        .just(payload.mapToHordeRequest())
        .flatMap(::executeRequestChain)
        .map { base64 -> payload to base64 }
        .map(Pair<TextToImagePayload, String>::mapHordeToAiGenResult)

    override fun imageToImage(payload: ImageToImagePayload) = Single
        .just(payload.mapToHordeRequest())
        .flatMap(::executeRequestChain)
        .map { base64 -> payload to base64 }
        .map(Pair<ImageToImagePayload, String>::mapHordeToAiGenResult)

    private fun executeRequestChain(request: HordeGenerationAsyncRequest): Single<String> {
        val observableChain = hordeApi
            .generateAsync(request)
            .flatMapObservable { asyncStartResponse ->
                asyncStartResponse.id?.let { id ->
                    val pingObs = Observable
                        .fromSingle(hordeApi.checkGeneration(id))
                        .flatMap { pingResponse ->
                            if (pingResponse.isPossible == false) {
                                return@flatMap Observable.error(Throwable("Response is not possible"))
                            }
                            if (pingResponse.done == true) {
                                return@flatMap Observable.fromSingle(hordeApi.checkStatus(id))
                            }
                            statusSource.update(
                                HordeProcessStatus(
                                    waitTimeSeconds = pingResponse.waitTime ?: 0,
                                    queuePosition = pingResponse.queuePosition,
                                )
                            )
                            return@flatMap Observable.error(RetryException(pingResponse))
                        }
                        .retryWhen { obs ->
                            obs.flatMap { t ->
                                if (t is RetryException) {
                                    return@flatMap Observable
                                        .timer(HORDE_SOCKET_PING_TIME_SECONDS, TimeUnit.SECONDS)
                                        .doOnNext {
                                            debugLog("Retrying HORDE status check...")
                                        }
                                }
                                return@flatMap Observable.error(t)
                            }
                        }

                    pingObs
                } ?: Observable.error(Throwable("Horde returned null generation id"))
            }
            .flatMapSingle {
                it.generations?.firstOrNull()?.let { generation ->
                    val bytes = URL(generation.img).readBytes()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    Single.just(bitmap)
                } ?: Single.error(Throwable("Error extracting image"))
            }
            .flatMapSingle { converter(BitmapToBase64Converter.Input(it)) }
            .map { it.base64ImageString }

        return Single.fromObservable(observableChain)
    }

    private class RetryException(val response: HordeGenerationCheckResponse): Throwable()

    companion object {
        private const val HORDE_SOCKET_PING_TIME_SECONDS = 10L
    }
}

internal class HordeStatusSource : HordeGenerationDataSource.StatusSource {
    private val processStatusSubject: PublishSubject<HordeProcessStatus> = PublishSubject.create()

    override fun observe(): Flowable<HordeProcessStatus> = processStatusSubject
        .toFlowable(BackpressureStrategy.LATEST)

    override fun update(status: HordeProcessStatus) {
        processStatusSubject.onNext(status)
    }
}
