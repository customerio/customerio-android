package io.customer.android.sample.kotlin_compose.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import io.customer.android.sample.kotlin_compose.data.persistance.UserDao
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepositoryImp
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepositoryImpl

@Module
@InstallIn(ViewModelComponent::class)
object RepositoryModule {
    @Provides
    @ViewModelScoped
    fun provideUserRepository(userDao: UserDao): UserRepository {
        return UserRepositoryImpl(userDao = userDao)
    }

    @Provides
    @ViewModelScoped
    fun providePreferencesRepository(dataStore: DataStore<Preferences>): PreferenceRepository {
        return PreferenceRepositoryImp(dataStore)
    }
}
