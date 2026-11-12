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

    @Test
    public void mergesSameTvgIdAcrossGroupsIntoMultipleLines() {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1综合\" tvg-name=\"CCTV1综合\" group-title=\"央视频\",CCTV1综合\n"
                + "http://host/relay/ysp-cctv1.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1综合\" tvg-name=\"CCTV1综合\" group-title=\"央视\",CCTV1综合\n"
                + "http://host/608807420\n"
                + "#EXTINF:-1 tvg-id=\"CCTV2财经\" group-title=\"央视\",CCTV2财经\n"
                + "http://host/608807421\n";

        Playlist playlist = M3uParser.parse(text);

        assertEquals(2, playlist.size());
        Channel cctv1 = playlist.channels.get(0);
        assertEquals("CCTV1综合", cctv1.name);
        assertEquals(2, cctv1.lineCount());
        assertTrue(cctv1.hasMultipleLines());
        assertEquals("央视频", cctv1.lineLabel(0));
        assertEquals("央视", cctv1.lineLabel(1));
        assertEquals("http://host/relay/ysp-cctv1.m3u8", cctv1.lineUrl(0));
        assertEquals("http://host/608807420", cctv1.lineUrl(1));
        assertEquals("http://host/relay/ysp-cctv1.m3u8", cctv1.url);
        assertEquals("央视", cctv1.group);
        assertEquals(1, cctv1.number);
        assertEquals(1, cctv1.lineIndexOfUrl("http://host/608807420"));
        assertEquals(-1, cctv1.lineIndexOfUrl("http://host/not-exist"));
        // 重编号连续：合并后 CCTV2 为 2 号
        assertEquals(2, playlist.channels.get(1).number);
        assertEquals("CCTV2财经", playlist.channels.get(1).name);
        assertEquals(1, playlist.channels.get(1).lineCount());
        // 分组归一后：央视频并入央视，CCTV1 与 CCTV2 都在央视分组
        assertEquals(0, playlist.byGroup("央视频").size());
        assertEquals(2, playlist.byGroup("央视").size());
    }

    @Test
    public void doesNotMergeSameNameWithDifferentTvgId() {
        String text = "#EXTINF:-1 tvg-id=\"A1\" group-title=\"组1\",同名频道\nhttp://h/1\n"
                + "#EXTINF:-1 tvg-id=\"A2\" group-title=\"组2\",同名频道\nhttp://h/2\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals(2, playlist.size());
        assertEquals(1, playlist.channels.get(0).lineCount());
        assertEquals(1, playlist.channels.get(1).lineCount());
    }

    @Test
    public void mergesSameNameWithoutTvgId() {
        String text = "#EXTINF:-1 group-title=\"组1\",重名频道\nhttp://h/1\n"
                + "#EXTINF:-1 group-title=\"组2\",重名频道\nhttp://h/2\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals(1, playlist.size());
        assertEquals(2, playlist.channels.get(0).lineCount());
        assertEquals("组1", playlist.channels.get(0).lineLabel(0));
        assertEquals("组2", playlist.channels.get(0).lineLabel(1));
    }

    @Test
    public void disambiguatesSameGroupLabelWithChannelName() {
        String text = "#EXTINF:-1 tvg-id=\"CCTV16奥林匹克\" group-title=\"央视频\",CCTV16奥林匹克\nhttp://h/16\n"
                + "#EXTINF:-1 tvg-id=\"CCTV16奥林匹克\" group-title=\"央视频\",CCTV16 4K\nhttp://h/16-4k\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals(1, playlist.size());
        Channel c = playlist.channels.get(0);
        assertEquals(2, c.lineCount());
        assertEquals("央视频", c.lineLabel(0));
        assertEquals("央视频（CCTV16 4K）", c.lineLabel(1));
    }

    @Test
    public void singleChannelUnaffectedByMerge() {
        String text = "#EXTINF:-1 tvg-id=\"only\" group-title=\"组\",唯一频道\nhttp://h/only\n";
        Playlist playlist = M3uParser.parse(text);

        assertEquals(1, playlist.size());
        Channel c = playlist.channels.get(0);
        assertEquals(1, c.lineCount());
        assertFalse(c.hasMultipleLines());
        assertEquals("组", c.lineLabel(0));
        assertEquals("http://h/only", c.lineUrl(0));
        assertEquals(0, c.lineIndexOfUrl("http://h/only"));
    }

    @Test
    public void mergesYangshipinGroupIntoYangshi() {
        String text = "#EXTINF:-1 tvg-id=\"CCTV1综合\" group-title=\"央视频\",CCTV1综合\nhttp://h/ysp1\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1综合\" group-title=\"央视\",CCTV1综合\nhttp://h/migu1\n"
                + "#EXTINF:-1 tvg-id=\"CCTV16奥林匹克\" group-title=\"央视频\",CCTV16奥林匹克\nhttp://h/ysp16\n"
                + "#EXTINF:-1 tvg-id=\"CCTV4欧洲\" group-title=\"央视\",CCTV4欧洲\nhttp://h/migu4eu\n";
        Playlist playlist = M3uParser.parse(text);

        // CCTV1 合并为一条，CCTV16 与 CCTV4欧洲各一条，共 3 条
        assertEquals(3, playlist.size());
        // 分组列表只有"央视"，没有"央视频"
        assertEquals(1, playlist.groups.size());
        assertEquals("央视", playlist.groups.get(0));
        assertEquals(3, playlist.byGroup("央视").size());
        assertEquals(0, playlist.byGroup("央视频").size());
        // 合并后的 CCTV1 归属央视，但两条线路 label 仍区分
        Channel cctv1 = playlist.channels.get(0);
        assertEquals("央视", cctv1.group);
        assertEquals(2, cctv1.lineCount());
        assertEquals("央视频", cctv1.lineLabel(0));
        assertEquals("央视", cctv1.lineLabel(1));
    }

    @Test
    public void reclassifiesMisgroupedChannelsByName() {
        String text = "#EXTINF:-1 tvg-id=\"hunan\" group-title=\"央视频\",湖南卫视\nhttp://h/hunan\n"
                + "#EXTINF:-1 tvg-id=\"hunan\" group-title=\"卫视\",湖南卫视\nhttp://h/hunan2\n"
                + "#EXTINF:-1 tvg-id=\"cgtn\" group-title=\"央视频\",CGTN\nhttp://h/cgtn\n"
                + "#EXTINF:-1 tvg-id=\"cetv1\" group-title=\"央视频\",CETV1\nhttp://h/cetv1\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"央视频\",CCTV1综合\nhttp://h/cctv1\n";
        Playlist playlist = M3uParser.parse(text);

        // 湖南卫视两源合并为一条，归卫视；CGTN 归国际；CETV1 归教育；CCTV1 归央视
        assertEquals(4, playlist.size());
        assertEquals(1, playlist.byGroup("卫视").size());
        assertEquals("卫视", playlist.byGroup("卫视").get(0).group);
        assertEquals(1, playlist.byGroup("国际").size());
        assertEquals(1, playlist.byGroup("教育").size());
        assertEquals(1, playlist.byGroup("央视").size());
        assertEquals(0, playlist.byGroup("央视频").size());
        // 合并后的湖南卫视仍保留两条线路 label
        Channel hunan = playlist.byGroup("卫视").get(0);
        assertEquals(2, hunan.lineCount());
        assertEquals("央视频", hunan.lineLabel(0));
        assertEquals("卫视", hunan.lineLabel(1));
    }
}
