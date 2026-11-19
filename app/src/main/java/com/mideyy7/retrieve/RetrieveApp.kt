package com.mideyy7.retrieve

import android.app.Application
import android.content.Context
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.search.KeywordIndexStore

/**
 * Application-wide singletons. Kept lazy and free of native calls in onCreate: Robolectric runs
 * unit tests with this Application class on the JVM, where the native library can't be loaded.
 */
class RetrieveApp : Application() {
    val database: RetrieveDatabase by lazy { RetrieveDatabase.get(this) }
    val keywordIndex: KeywordIndexStore by lazy { KeywordIndexStore(this, database) }
}

val Context.retrieveApp: RetrieveApp get() = applicationContext as RetrieveApp
