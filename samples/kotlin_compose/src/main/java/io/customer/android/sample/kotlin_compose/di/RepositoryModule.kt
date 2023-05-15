package io.customer.android.sample.kotlin_compose.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.customer.android.sample.kotlin_compose.data.persistance.UserDao
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepositoryImp
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepositoryImpl

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    fun provideUserRepository(userDao: UserDao): UserRepository {
        return UserRepositoryImpl(userDao = userDao)
    }

    @Provides
    fun providePreferencesRepository(dataStore: DataStore<Preferences>): PreferenceRepository {
        return PreferenceRepositoryImp(dataStore)
    }
}
