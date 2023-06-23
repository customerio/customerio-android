package io.customer.android.sample.java_layout.ui.user;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.data.PreferencesDataStore;
import io.customer.android.sample.java_layout.data.model.User;
import io.customer.android.sample.java_layout.sdk.CustomerIORepository;
import io.customer.android.sample.java_layout.support.Optional;
import io.customer.android.sample.java_layout.utils.StringUtils;
import io.reactivex.rxjava3.core.Flowable;

public class AuthViewModel extends ViewModel {
    private final PreferencesDataStore preferencesDataStore;
    private final CustomerIORepository customerIORepository;
    private final LiveData<User> userDataObservable;
    private final LiveData<Boolean> userLoggedInStateObservable;

    public AuthViewModel(PreferencesDataStore preferencesDataStore, CustomerIORepository customerIORepository) {
        this.preferencesDataStore = preferencesDataStore;
        this.customerIORepository = customerIORepository;
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
        customerIORepository.clearIdentify();
        preferencesDataStore.clearUserData();
    }

    public void setLoggedInUser(@NonNull User user) {
        Map<String, String> attributes = new HashMap<String, String>() {{
            put("first_name", user.getDisplayName());
            put("email", user.getEmail());
            put("is_guest", StringUtils.fromBoolean(user.isGuest()));
        }};
        // Identify user profile before routing to next screen so it can be tracked
        customerIORepository.identify(user.getEmail(), attributes);
        preferencesDataStore.saveToUserData(User.toMap(user));
    }
}
