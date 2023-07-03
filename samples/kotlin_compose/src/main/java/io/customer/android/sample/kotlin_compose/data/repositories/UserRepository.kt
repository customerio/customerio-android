package io.customer.android.sample.kotlin_compose.data.repositories

import io.customer.android.sample.kotlin_compose.data.models.User
import io.customer.android.sample.kotlin_compose.data.persistance.UserDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface UserRepository {
    suspend fun isLoggedIn(): Boolean
    fun getUser(email: String? = null): Flow<User?>
    suspend fun login(email: String, name: String, isGuest: Boolean = false)
    suspend fun deleteUser(user: User)
    suspend fun deleteAllUsers()
}

class UserRepositoryImpl(
    private val userDao: UserDao
) : UserRepository {

    override suspend fun isLoggedIn(): Boolean = userDao.getUserCount() > 0

    override fun getUser(email: String?): Flow<User?> {
        val usersList = if (email.isNullOrEmpty()) {
            userDao.observeAll()
        } else {
            userDao.observeAllByEmail(emails = arrayOf(email))
        }
        return usersList.map { it.firstOrNull() }
    }

    override suspend fun login(email: String, name: String, isGuest: Boolean) {
        return userDao.insert(User(email = email, name = name, isGuest = isGuest))
    }

    override suspend fun deleteUser(user: User) {
        userDao.delete(user = user)
    }

    override suspend fun deleteAllUsers() {
        userDao.deleteAll()
    }
}
