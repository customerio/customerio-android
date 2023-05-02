package io.customer.android.sample.kotlin_compose.di

import dagger.Provides
import dagger.hilt.android.scopes.ViewModelScoped
import io.customer.android.sample.kotlin_compose.data.persistance.UserDao
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepositoryImpl

object RepositoryModule {

    @Provides
    @ViewModelScoped
    fun provideUserRepository(userDao: UserDao): UserRepository {
        return UserRepositoryImpl(userDao = userDao)
    }
}
