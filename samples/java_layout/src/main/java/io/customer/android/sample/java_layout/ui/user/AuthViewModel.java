package io.customer.android.sample.java_layout.ui.user;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import io.customer.android.sample.java_layout.data.PreferencesDataStore;
import io.customer.android.sample.java_layout.data.model.User;
import io.customer.android.sample.java_layout.support.Optional;
import io.reactivex.rxjava3.core.Flowable;

public class AuthViewModel extends ViewModel {
    private final PreferencesDataStore preferencesDataStore;
    private final LiveData<User> userDataObservable;
    private final LiveData<Boolean> userLoggedInStateObservable;

    public AuthViewModel(PreferencesDataStore preferencesDataStore) {
        this.preferencesDataStore = preferencesDataStore;
        Flowable<Optional<User>> publisher = preferencesDataStore.userData().map(User::fromMap);
        this.userDataObservable = LiveDataReactiveStreams.fromPublisher(
                publisher.filter(Optional::isPresent).map(Optional::get));
        this.userLoggedInStateObservable = Transformations.map(
                LiveDataReactiveStreams.fromPublisher(publisher), Optional::isPresent);
    }

    public LiveData<User> getUserDataObservable() {
        return userDataObservable;
    }

    public LiveData<Boolean> getUserLoggedInStateObservable() {
        return userLoggedInStateObservable;
    }

    public void clearLoggedInUser() {
        preferencesDataStore.clearUserData();
    }

    public void setLoggedInUser(@NonNull User user) {
        preferencesDataStore.saveToUserData(User.toMap(user));
    }
}
