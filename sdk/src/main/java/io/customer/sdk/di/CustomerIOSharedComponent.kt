package io.customer.sdk.di

import android.content.Context
import io.customer.sdk.repository.preference.SharedPreferenceRepository
import io.customer.sdk.repository.preference.SharedPreferenceRepositoryImp

class CustomerIOSharedComponent(context: Context) : DiGraph() {

    val sharedPreferenceRepository: SharedPreferenceRepository by lazy {
        override() ?: SharedPreferenceRepositoryImp(
            context = context
        )
    }
}
