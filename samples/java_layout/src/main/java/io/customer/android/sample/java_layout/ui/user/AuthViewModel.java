package io.customer.android.sample.java_layout.ui.user;

import android.os.Build;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
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
        Flowable<Optional<User>> publisher = preferencesDataStore.userData().map(
                bundle -> Optional.ofNullable(User.fromMap(bundle)));
        this.userDataObservable = LiveDataReactiveStreams.fromPublisher(
                publisher.filter(Optional::isPresent).map(Optional::get));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            this.userLoggedInStateObservable = LiveDataReactiveStreams.fromPublisher(
                    publisher.map(Optional::isPresent));
        } else {
            //noinspection Convert2MethodRef
            this.userLoggedInStateObservable = LiveDataReactiveStreams.fromPublisher(publisher.map(optional -> optional.isPresent()));
        }
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

    public void setLoggedInUser(User user) {
        preferencesDataStore.saveToUserData(user.toMap());
    }
}
