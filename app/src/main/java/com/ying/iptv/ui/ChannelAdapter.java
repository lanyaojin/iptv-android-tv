package com.ying.iptv.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ying.iptv.R;
import com.ying.iptv.data.Channel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 频道卡片列表。 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {

    public interface Listener {
        void onClick(Channel channel);

        void onLongClick(Channel channel);
    }

    private final List<Channel> items = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private final Listener listener;
    private int selectedNumber = -1;

    public ChannelAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setData(List<Channel> channels, int selectedNumber, Set<String> favorites) {
        items.clear();
        if (channels != null) items.addAll(channels);
        this.selectedNumber = selectedNumber;
        this.favorites.clear();
        if (favorites != null) this.favorites.addAll(favorites);
        notifyDataSetChanged();
    }

    public void setSelected(int number) {
        int old = selectedNumber;
        selectedNumber = number;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).number == old) notifyItemChanged(i);
            if (items.get(i).number == number) notifyItemChanged(i);
        }
    }

    public int positionOf(int channelNumber) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).number == channelNumber) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).number;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Channel channel = items.get(position);
        holder.name.setText(channel.name);
        holder.number.setText(channel.displayNumber());
        holder.star.setVisibility(favorites.contains(channel.key()) ? View.VISIBLE : View.GONE);
        boolean playing = channel.number == selectedNumber;
        holder.name.setTextColor(playing ? Color.parseColor("#FF3EA6FF") : Color.parseColor("#FFF2F5F9"));
        holder.number.setTextColor(playing ? Color.parseColor("#FF3EA6FF") : Color.parseColor("#FF6B7787"));
        ImageLoader.load(holder.logo, channel.logo);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(channel);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongClick(channel);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name, number, star;
        final ImageView logo;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.name);
            number = itemView.findViewById(R.id.number);
            star = itemView.findViewById(R.id.star);
            logo = itemView.findViewById(R.id.logo);
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                float scale = hasFocus ? 1.06f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                v.setZ(hasFocus ? 10f : 0f);
            });
        }
    }
}
