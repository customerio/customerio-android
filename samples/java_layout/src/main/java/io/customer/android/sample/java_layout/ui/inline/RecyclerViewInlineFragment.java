package io.customer.android.sample.java_layout.ui.inline;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.customer.android.sample.java_layout.databinding.FragmentRecyclerviewInlineBinding;
import io.customer.android.sample.java_layout.databinding.ItemRecyclerviewContentBinding;
import io.customer.android.sample.java_layout.databinding.ItemRecyclerviewInlineMessageBinding;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;

public class RecyclerViewInlineFragment extends BaseFragment<FragmentRecyclerviewInlineBinding> {
    
    private static final String TAG = "RecyclerViewInlineFragment";
    
    // View types for RecyclerView
    private static final int VIEW_TYPE_CONTENT = 0;
    private static final int VIEW_TYPE_INLINE_MESSAGE = 1;

    @NonNull
    @Override
    protected FragmentRecyclerviewInlineBinding inflateViewBinding() {
        return FragmentRecyclerviewInlineBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void setupContent() {
        RecyclerViewAdapter adapter = new RecyclerViewAdapter();
        binding.recyclerView.setAdapter(adapter);
    }

    public static RecyclerViewInlineFragment newInstance() {
        return new RecyclerViewInlineFragment();
    }

    // Simple data model
    private static class ListItem {
        final int type;
        final String elementId;

        ListItem(int type) {
            this.type = type;
            this.elementId = null;
        }

        ListItem(int type, String elementId) {
            this.type = type;
            this.elementId = elementId;
        }
    }

    // RecyclerView Adapter
    private class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        
        private final List<ListItem> items;

        public RecyclerViewAdapter() {
            items = new ArrayList<>();
            // Create a mix of content and inline message items
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_INLINE_MESSAGE, "recycler-top"));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_INLINE_MESSAGE, "recycler-center"));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_INLINE_MESSAGE, "recycler-bottom"));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
            items.add(new ListItem(VIEW_TYPE_CONTENT));
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            
            if (viewType == VIEW_TYPE_INLINE_MESSAGE) {
                ItemRecyclerviewInlineMessageBinding binding = 
                    ItemRecyclerviewInlineMessageBinding.inflate(inflater, parent, false);
                return new InlineMessageViewHolder(binding);
            } else {
                ItemRecyclerviewContentBinding binding = 
                    ItemRecyclerviewContentBinding.inflate(inflater, parent, false);
                return new ContentViewHolder(binding);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ListItem item = items.get(position);
            
            if (holder instanceof InlineMessageViewHolder && item.elementId != null) {
                InlineMessageViewHolder inlineHolder = (InlineMessageViewHolder) holder;
                inlineHolder.binding.inlineMessageView.setElementId(item.elementId);
                inlineHolder.binding.inlineMessageView.setActionListener(
                    new InlineMessageActionListenerImpl(requireContext(), "RecyclerView - " + item.elementId)
                );
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    // ViewHolders
    private static class ContentViewHolder extends RecyclerView.ViewHolder {
        final ItemRecyclerviewContentBinding binding;

        ContentViewHolder(ItemRecyclerviewContentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class InlineMessageViewHolder extends RecyclerView.ViewHolder {
        final ItemRecyclerviewInlineMessageBinding binding;

        InlineMessageViewHolder(ItemRecyclerviewInlineMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
