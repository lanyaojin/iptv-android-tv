package com.ying.iptv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ying.iptv.data.Channel;
import com.ying.iptv.data.M3uParser;
import com.ying.iptv.data.Playlist;

import org.junit.Test;

import java.util.List;

/** M3U 解析器单元测试（纯 JVM）。 */
public class M3uParserTest {

    @Test
    public void parsesStandardPlaylist() {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" tvg-name=\"CCTV1\" tvg-logo=\"http://x/1.png\" group-title=\"央视\",CCTV1综合\n"
                + "http://host/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV2财经\n"
                + "http://host/2.m3u8\n"
                + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\n"
                + "http://host/3.m3u8\n";

        Playlist playlist = M3uParser.parse(text);

        assertEquals(3, playlist.size());
        assertEquals(2, playlist.groups.size());
        assertEquals("央视", playlist.channels.get(0).group);
        assertEquals("http://x/1.png", playlist.channels.get(0).logo);
        assertEquals("id:cctv1", playlist.channels.get(0).key());
        assertEquals("url:http://host/2.m3u8", playlist.channels.get(1).key());
        assertEquals(2, playlist.byGroup("央视").size());
    }

    @Test
    public void keepsCommasInsideName() {
        String text = "#EXTINF:-1 tvg-name=\"A,B\" group-title=\"组\",显示名,带逗号\nhttp://host/a.m3u8\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals(1, playlist.size());
        assertEquals("显示名,带逗号", playlist.channels.get(0).name);
        assertEquals("组", playlist.channels.get(0).group);
    }

    @Test
    public void appliesExtGrpWhenExtInfHasNoGroup() {
        String text = "#EXTGRP:纪录片\n"
                + "#EXTINF:-1,探索频道\n"
                + "http://host/doc.m3u8\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals("纪录片", playlist.channels.get(0).group);
    }

    @Test
    public void toleratesBomCrlfAndDirectives() {
        String text = "\uFEFF#EXTM3U\r\n"
                + "#EXTINF:-1,频道一\r\n"
                + "#EXTVLCOPT:http-user-agent=Mozilla\r\n"
                + "#KODIPROP:inputstream=ffmpeg\r\n"
                + "http://host/1.m3u8\r\n";

        Playlist playlist = M3uParser.parse(text);

        assertEquals(1, playlist.size());
        assertEquals("频道一", playlist.channels.get(0).name);
        assertEquals(null, playlist.channels.get(0).group);
        assertTrue(playlist.hasUngrouped);
    }

    @Test
    public void handlesEmptyAndNull() {
        assertEquals(0, M3uParser.parse(null).size());
        assertEquals(0, M3uParser.parse("").size());
        assertEquals(0, M3uParser.parse("#EXTM3U\n").size());
    }

    @Test
    public void resolvesChannelsByNumber() {
        String text = "#EXTINF:-1,A\nhttp://h/1\n#EXTINF:-1,B\nhttp://h/2\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals("A", playlist.byNumber(1).name);
        assertEquals("B", playlist.byNumber(2).name);
        assertNull(playlist.byNumber(0));
        assertNull(playlist.byNumber(3));
        assertEquals("001", playlist.byNumber(1).displayNumber());
    }

    @Test
    public void skipsNonPlayableLines() {
        String text = "#EXTINF:-1,坏频道\n"
                + "not-a-url\n"
                + "#EXTINF:-1,好频道\n"
                + "https://h/ok.m3u8\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals(1, playlist.size());
        assertEquals("好频道", playlist.channels.get(0).name);
        assertEquals(1, playlist.channels.get(0).number);
    }

    @Test
    public void groupsPreserveOrderWithoutDuplicates() {
        String text = "#EXTINF:-1 group-title=\"B\",1\nhttp://h/1\n"
                + "#EXTINF:-1 group-title=\"A\",2\nhttp://h/2\n"
                + "#EXTINF:-1 group-title=\"B\",3\nhttp://h/3\n";
        Playlist playlist = M3uParser.parse(text);
        List<String> groups = playlist.groups;

        assertEquals(2, groups.size());
        assertEquals("B", groups.get(0));
        assertEquals("A", groups.get(1));
        assertFalse(playlist.hasUngrouped);
    }

    @Test
    public void channelKeyPrefersTvgId() {
        Channel withId = new Channel(1, "n", "http://u", "g", null, "tid");
        Channel noId = new Channel(2, "n", "http://u2", "g", null, null);

        assertEquals("id:tid", withId.key());
        assertEquals("url:http://u2", noId.key());
        assertNotNull(withId.name);
    }
}
