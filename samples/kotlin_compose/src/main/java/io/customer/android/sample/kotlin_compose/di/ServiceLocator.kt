package io.customer.android.sample.kotlin_compose.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.customer.android.sample.kotlin_compose.data.persistance.AppDatabase
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepositoryImp
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepositoryImpl

// Extension property to create DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sample_app_kotlin_preferences")

/**
 * Simple service locator for dependency injection
 * Replaces Hilt to avoid Kotlin 2.1 compatibility issues
 */
object ServiceLocator {

    private var appContext: Context? = null
    private var _appDatabase: AppDatabase? = null
    private var _preferenceRepository: PreferenceRepository? = null
    private var _userRepository: UserRepository? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val appDatabase: AppDatabase
        get() = _appDatabase ?: run {
            val context = appContext ?: error("ServiceLocator not initialized")
            AppDatabase.getInstance(context).also { _appDatabase = it }
        }

    val preferenceRepository: PreferenceRepository
        get() = _preferenceRepository ?: run {
            val context = appContext ?: error("ServiceLocator not initialized")
            PreferenceRepositoryImp(context.dataStore).also { _preferenceRepository = it }
        }

    val userRepository: UserRepository
        get() = _userRepository ?: run {
            UserRepositoryImpl(appDatabase.userDao()).also { _userRepository = it }
        }

    // For testing - allows resetting dependencies
    fun reset() {
        _appDatabase = null
        _preferenceRepository = null
        _userRepository = null
        appContext = null
    }
}
