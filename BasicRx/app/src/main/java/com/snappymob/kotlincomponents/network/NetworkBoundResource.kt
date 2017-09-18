package com.snappymob.kotlincomponents.network

import android.support.annotation.MainThread
import android.support.annotation.WorkerThread
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject

abstract class NetworkBoundResource<ResultType, RequestType> @MainThread
constructor(private val appExecutors: AppExecutors) {

    private val result = PublishSubject.create<Resource<ResultType>>()

    init {
        loadFromDb().subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ value ->
                    if (shouldFetch(value)) {
                        fetchFromNetwork()
                    } else {
                        result.onNext(Resource.success(value))
                    }
                })
    }

    fun fetchFromNetwork() {
        val apiResponse = createCall()
        //send a loading event
        result.onNext(Resource.loading(null))
        apiResponse.subscribeOn(Schedulers.from(appExecutors.networkIO()))
                .observeOn(Schedulers.from(appExecutors.mainThread()))
                .subscribe({ response ->
                    appExecutors
                            .diskIO()
                            .execute {
                                saveCallResult(response)
                                appExecutors.mainThread()
                                        .execute {
                                            // we specially request a new live data,
                                            // otherwise we will get immediately last cached value,
                                            // which may not be updated with latest results received from network.
                                            loadFromDb()
                                                    .subscribeOn(Schedulers.from(appExecutors.networkIO()))
                                                    .observeOn(Schedulers.from(appExecutors.mainThread()))
                                                    .subscribe({
                                                        result.onNext(Resource.success(it))
                                                    })
                                        }
                            }
                }, { error ->
                    onFetchFailed()
                    result.onNext(Resource.error(error.localizedMessage, null))
                })

    }

    fun asFlowable(): Flowable<Resource<ResultType>> {
        return result.toFlowable(BackpressureStrategy.BUFFER)
    }

    protected open fun onFetchFailed() {}

    @WorkerThread
    protected abstract fun saveCallResult(item: RequestType)

    @MainThread
    protected abstract fun shouldFetch(data: ResultType?): Boolean

    @MainThread
    protected abstract fun loadFromDb(): Flowable<ResultType>

    @MainThread
    protected abstract fun createCall(): Flowable<RequestType>

}