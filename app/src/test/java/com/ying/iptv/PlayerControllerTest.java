package com.ying.iptv.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

/** 播放源类型判定与错误分类的纯逻辑测试（无需实例化播放器）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PlayerControllerTest {

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = PlayerController.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static boolean looks(String url) throws Exception {
        return (Boolean) call("looksLikeHls", new Class<?>[]{String.class}, url);
    }

    @Test
    public void hlsDetectionCoversPlainRelayPaths() throws Exception {
        assertTrue(looks("http://nas/relay/ysp-cctv1.m3u8"));
        assertTrue(looks("http://nas/relay/abc?token=1"));
        assertTrue(looks("http://cdn/live/cctv1"));
        assertTrue(looks("http://cdn/hls/stream"));
        assertTrue(looks("http://cdn/proxy/channel"));
        assertFalse(looks("http://nas/608807420"));      // 无扩展名的中转短链
        assertFalse(looks("http://cdn/clip.ts"));        // 明确的文件流
        assertFalse(looks("http://cdn/movie.mp4"));
        assertFalse(looks((String) null));
    }

    @Test
    public void unrecognizedContentIsDeterministic() throws Exception {
        Class<?>[] t = {Throwable.class};
        Exception e = new Exception("None of the available extractors could read the stream");
        assertTrue((Boolean) call("isUnrecognizedContent", t, e));
        assertFalse((Boolean) call("isUnrecognizedContent", t, new Exception("connection timeout")));
    }

    @Test
    public void friendlyMessagesAreChineseAndShort() throws Exception {
        Class<?>[] t = {Throwable.class};
        String dns = (String) call("friendly", t, new Exception("Unable to resolve host \"nas\""));
        String timeout = (String) call("friendly", t, new Exception("connect timed out"));
        assertTrue(dns.contains("网络") || dns.contains("域名"));
        assertTrue(timeout.contains("超时"));
        assertTrue(dns.length() < 20 && timeout.length() < 20);
    }
}
