package com.ying.iptv.data;

/** 一条直播源配置。 */
public class Source {

    public String name;
    public String url;

    public Source(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public String displayName() {
        return (name == null || name.isEmpty()) ? url : name;
    }
}
