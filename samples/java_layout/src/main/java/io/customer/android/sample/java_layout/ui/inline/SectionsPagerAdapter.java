package io.customer.android.sample.java_layout.ui.inline;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import io.customer.android.sample.java_layout.ui.inline.compose.ComposeInlineExampleFragment;

public class SectionsPagerAdapter extends FragmentStateAdapter {

    public SectionsPagerAdapter(@NonNull FragmentManager fragmentManager, @NonNull Lifecycle lifecycle) {
        super(fragmentManager, lifecycle);
    }

    @Override
    public int getItemCount() {
        return 4;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return SingleInlineFragment.newInstance();
            case 1:
                return MultipleInlineFragment.newInstance();
            case 2:
                return ComposeInlineExampleFragment.newInstance();
            case 3:
                return RecyclerViewInlineFragment.newInstance();
            default:
                throw new IllegalArgumentException("Invalid position: " + position);
        }
    }
}
