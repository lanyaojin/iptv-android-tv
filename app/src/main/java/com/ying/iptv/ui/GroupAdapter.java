package com.ying.iptv.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ying.iptv.R;

import java.util.ArrayList;
import java.util.List;

/** 左侧分组列表。 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

    public interface Listener {
        void onGroupClick(Entry entry);
    }

    public static class Entry {
        public final String key;   // null = 全部；@fav = 收藏
        public final String label;
        public final int count;

        public Entry(String key, String label, int count) {
            this.key = key;
            this.label = label;
            this.count = count;
        }
    }

    private final List<Entry> items = new ArrayList<>();
    private final Listener listener;
    private String selectedKey;

    public GroupAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setData(List<Entry> entries, String selectedKey) {
        items.clear();
        if (entries != null) items.addAll(entries);
        this.selectedKey = selectedKey;
        notifyDataSetChanged();
    }

    public void setSelected(String key) {
        selectedKey = key;
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        Entry e = items.get(position);
        return e.key == null ? 0 : e.key.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Entry entry = items.get(position);
        holder.title.setText(entry.label);
        holder.count.setText(String.valueOf(entry.count));
        boolean selected = (selectedKey == null && entry.key == null)
                || (selectedKey != null && selectedKey.equals(entry.key));
        holder.title.setTextColor(Color.parseColor(selected ? "#FF3EA6FF" : "#FFF2F5F9"));
        holder.itemView.setAlpha(selected ? 1f : 0.92f);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onGroupClick(entry);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, count;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            count = itemView.findViewById(R.id.count);
        }
    }
}
