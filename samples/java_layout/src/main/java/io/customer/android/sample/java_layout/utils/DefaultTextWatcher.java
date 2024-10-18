package io.customer.android.sample.java_layout.utils;

import android.text.Editable;
import android.text.TextWatcher;

/**
 * Convenience class to prevent classes from having to override all methods and only override the
 * ones desired.
 */
public class DefaultTextWatcher implements TextWatcher {
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        /* NO OP */
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        /* NO OP */
    }

    @Override
    public void afterTextChanged(Editable s) {
        /* NO OP */
    }
}
