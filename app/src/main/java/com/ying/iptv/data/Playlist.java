package com.ying.iptv.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 一份已解析的直播源。 */
public class Playlist {

    public final List<Channel> channels;
    /** 按出现顺序去重的分组名，不含 null（未分组）。 */
    public final List<String> groups;
    public final boolean hasUngrouped;

    public Playlist(List<Channel> channels) {
        this.channels = channels;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        boolean ungrouped = false;
        for (Channel c : channels) {
            if (c.group == null || c.group.isEmpty()) ungrouped = true;
            else set.add(c.group);
        }
        this.groups = new ArrayList<>(set);
        this.hasUngrouped = ungrouped;
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    public int size() {
        return channels.size();
    }

    /** group 为 null 表示全部频道。 */
    public List<Channel> byGroup(String group) {
        if (group == null) return channels;
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels) {
            String g = (c.group == null || c.group.isEmpty()) ? null : c.group;
            if (group.equals(g)) out.add(c);
        }
        return out;
    }

    /** 按频道号（1 起）查找，越界返回 null。 */
    public Channel byNumber(int number) {
        if (number < 1 || number > channels.size()) return null;
        return channels.get(number - 1);
    }

    public int indexOf(Channel channel) {
        return channels.indexOf(channel);
    }
}
