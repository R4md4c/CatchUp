/*
 * Copyright (c) 2017 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.service.hackernews

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.SingleEmitter
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DataResult
import io.sweers.catchup.service.api.LinkHandler
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceException
import io.sweers.catchup.service.api.ServiceKey
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.service.api.ServiceMetaKey
import io.sweers.catchup.service.api.SummarizationInfo
import io.sweers.catchup.service.api.TextService
import io.sweers.catchup.service.hackernews.model.HackerNewsStory
import io.sweers.catchup.util.d
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
private annotation class InternalApi

internal class HackerNewsService @Inject constructor(
    @InternalApi private val serviceMeta: ServiceMeta,
    private val database: dagger.Lazy<FirebaseDatabase>,
    private val linkHandler: LinkHandler)
  : TextService {

  override fun meta() = serviceMeta

  override fun fetchPage(request: DataRequest): Maybe<DataResult> {
    val page = request.pageId.toInt()
    val itemsPerPage = 25 // TODO Pref this
    return Single.create { emitter: SingleEmitter<DataSnapshot> ->
      val listener = object : ValueEventListener {
        override fun onDataChange(dataSnapshot: DataSnapshot) {
          emitter.onSuccess(dataSnapshot)
        }

        override fun onCancelled(firebaseError: DatabaseError) {
          d { "${firebaseError.code}" }
          emitter.onError(firebaseError.toException())
        }
      }

      val ref = database.get()
          .getReference("v0/topstories")
      emitter.setCancellable { ref.removeEventListener(listener) }
      ref.addValueEventListener(listener)
    }
        .flattenAsObservable { it.children }
        .skip(((page + 1) * itemsPerPage - itemsPerPage).toLong())
        .take(itemsPerPage.toLong())
        .map { d -> d.value as Long }
        .concatMapEager { id ->
          Observable.create<DataSnapshot> { emitter ->
            val ref = database.get()
                .getReference("v0/item/" + id)
            val listener = object : ValueEventListener {
              override fun onDataChange(dataSnapshot: DataSnapshot) {
                emitter.onNext(dataSnapshot)
                emitter.onComplete()
              }

              override fun onCancelled(firebaseError: DatabaseError) {
                d { "${firebaseError.code}" }
                emitter.onError(firebaseError.toException())
              }
            }
            emitter.setCancellable { ref.removeEventListener(listener) }
            ref.addValueEventListener(listener)
          }
        }
        .filter { it.hasChild("title") }  // Some HN items are just empty junk
        .map { HackerNewsStory.create(it) }
        .map {
          val url = it.url()
          with(it) {
            CatchUpItem(
                id = id(),
                title = title() ?: "Untitled",
                score = "+" to score(),
                timestamp = time(),
                author = by(),
                source = url?.let { HttpUrl.parse(it)!!.host() },
                commentCount = kids()?.size ?: 0,
                tag = null,
                itemClickUrl = url,
                itemCommentClickUrl = "https://news.ycombinator.com/item?id=${id()}",
                summarizationInfo = SummarizationInfo.from(url)
            )
          }
        }
        .toList()
        .map {
          // HN will eventually run out of items. Ideally we'd want to push this up into something
          // that maybe tries the next two pages and if both are empty, give up and don't infinitely
          // load.
          DataResult(it, if (it.isEmpty()) null else (page + 1).toString())
        }
        .toMaybe()
        .onErrorResumeNext { t: Throwable ->
          if (BuildConfig.DEBUG && t is IllegalArgumentException) {
            // Firebase didn't init
            Maybe.error(ServiceException(
                "Firebase wasn't able to initialize, likely due to missing credentials."))
          } else {
            Maybe.error(t)
          }
        }
  }

  override fun linkHandler() = linkHandler
}

@Module
abstract class HackerNewsModule {

  @IntoMap
  @ServiceMetaKey(SERVICE_KEY)
  @Binds
  internal abstract fun hackerNewsServiceMeta(@InternalApi meta: ServiceMeta): ServiceMeta

  @IntoMap
  @ServiceKey(SERVICE_KEY)
  @Binds
  internal abstract fun hackerNewsService(hackerNewsService: HackerNewsService): Service

  @Module
  companion object {

    private const val SERVICE_KEY = "hn"

    @InternalApi
    @Provides
    @JvmStatic
    internal fun provideMediumServiceMeta() = ServiceMeta(
        SERVICE_KEY,
        R.string.hn,
        R.color.hnAccent,
        R.drawable.logo_hn,
        pagesAreNumeric = true,
        firstPageKey = "0"
    )

    @Provides
    @JvmStatic
    internal fun provideDataBase() =
        FirebaseDatabase.getInstance("https://hacker-news.firebaseio.com/")

  }
}
