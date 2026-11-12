package com.ying.iptv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ying.iptv.data.Channel;
import com.ying.iptv.data.Playlist;
import com.ying.iptv.data.PlaylistRepository;
import com.ying.iptv.data.Prefs;
import com.ying.iptv.data.Source;
import com.ying.iptv.player.PlayerController;
import com.ying.iptv.ui.ChannelAdapter;
import com.ying.iptv.ui.GroupAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 直播主界面：打开即播、遥控器换台、频道列表、收藏。 */
public class MainActivity extends AppCompatActivity
        implements ChannelAdapter.Listener, GroupAdapter.Listener, PlayerController.Listener {

    private static final String GROUP_FAVORITES = "@fav";
    private static final String GROUP_UNGROUPED = "@none";
    private static final String STATE_GROUP = "selected_group";
    /** 稳定播放这么久之后，才把自动换线状态复位，视为一次新的播放会话。 */
    private static final long STABLE_RESET_MS = 8000L;
    private static final long FAILOVER_WINDOW_MS = 60000L;
    private static final int MAX_AUTO_SWITCHES = 8;

    private Prefs prefs;
    private Playlist playlist;
    private Channel current;
    /** 当前频道正播的线路下标，默认 0（首条线路）。 */
    private int currentLine;
    /** 自动换线路重试的起始线路下标，-1 表示不在自动换线中。 */
    private int lineFallbackStart = -1;
    /** 所有线路首轮都失败后，是否已用过「完整重连」兜底，避免无限循环。 */
    private boolean finalRetryUsed;
    /** 自动换线的频次限制，防止抖动源在 READY/ERROR 之间反复触发无限换线。 */
    private int autoSwitchCount;
    private long autoSwitchWindowStart;
    /** 本次运行中各频道已验证稳定的线路，关闭「记忆」时也有效。 */
    private final Map<String, Integer> stableLines = new HashMap<>();
    private String selectedGroup;
    private String loadedSourceUrl;
    private boolean loadingPlaylist;
    private boolean playFailed;

    private PlayerView playerView;
    private PlayerController controller;

    private LinearLayout status;
    private ProgressBar progress;
    private TextView statusText;
    private LinearLayout emptyPanel;
    private TextView emptyAction;
    private LinearLayout overlay;
    private LinearLayout infoBar;
    private LinearLayout switchBanner;
    private TextView infoNumber, infoName, infoGroup, infoClock;
    private TextView bannerNumber, bannerName;
    private TextView listTitle, listCount, btnSettings, numberInput;
    private RecyclerView channelList, groupList;

    private ChannelAdapter channelAdapter;
    private GroupAdapter groupAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder digits = new StringBuilder();
    private long lastBackTime;

    private final Runnable commitDigits = () -> {
        String text = digits.toString();
        digits.setLength(0);
        numberInput.setVisibility(View.GONE);
        if (text.isEmpty() || playlist == null) return;
        try {
            int number = Integer.parseInt(text);
            Channel channel = playlist.byNumber(number);
            if (channel != null) {
                currentLine = preferredLineFor(channel);
                play(channel, true);
            } else toast("没有 " + number + " 号频道");
        } catch (NumberFormatException ignored) {
        }
    };

    private final Runnable hideBanner = () -> switchBanner.setVisibility(View.GONE);
    private final Runnable hideInfo = () -> infoBar.setVisibility(View.GONE);
    /** 稳定播放 8 秒后复位换线额度，之后再次断流才算新的故障回合。 */
    private final Runnable resetFailover = () -> {
        finalRetryUsed = false;
        autoSwitchCount = 0;
        autoSwitchWindowStart = 0;
        lineFallbackStart = -1;
        if (current != null) controller.setFastFailover(current.hasMultipleLines());
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = Prefs.get(this);
        if (savedInstanceState != null) selectedGroup = savedInstanceState.getString(STATE_GROUP);

        bindViews();
        setupLists();

        controller = new PlayerController(this, playerView);
        controller.setListener(this);

        loadPlaylist(false);
    }

    private void bindViews() {
        playerView = findViewById(R.id.player_view);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.status_text);
        emptyPanel = findViewById(R.id.empty_panel);
        emptyAction = findViewById(R.id.empty_action);
        overlay = findViewById(R.id.overlay);
        infoBar = findViewById(R.id.info_bar);
        switchBanner = findViewById(R.id.switch_banner);
        infoNumber = findViewById(R.id.info_number);
        infoName = findViewById(R.id.info_name);
        infoGroup = findViewById(R.id.info_group);
        infoClock = findViewById(R.id.info_clock);
        bannerNumber = findViewById(R.id.banner_number);
        bannerName = findViewById(R.id.banner_name);
        listTitle = findViewById(R.id.list_title);
        listCount = findViewById(R.id.list_count);
        btnSettings = findViewById(R.id.btn_settings);
        numberInput = findViewById(R.id.number_input);
        channelList = findViewById(R.id.channel_list);
        groupList = findViewById(R.id.group_list);

        emptyAction.setOnClickListener(v -> openSettings());
        btnSettings.setOnClickListener(v -> openSettings());
    }

    private void setupLists() {
        groupAdapter = new GroupAdapter(this);
        groupList.setLayoutManager(new LinearLayoutManager(this));
        groupList.setAdapter(groupAdapter);
        groupList.setNextFocusRightId(R.id.channel_list);

        channelAdapter = new ChannelAdapter(this);
        GridLayoutManager grid = new GridLayoutManager(this, 4);
        channelList.setLayoutManager(grid);
        channelList.setAdapter(channelAdapter);
        channelList.setItemAnimator(null);
        channelList.setNextFocusLeftId(R.id.group_list);
        btnSettings.setNextFocusDownId(R.id.channel_list);
        btnSettings.setNextFocusLeftId(R.id.channel_list);
    }

    // ---------------- 直播源加载 ----------------

    private void loadPlaylist(boolean force) {
        if (loadingPlaylist) return;
        Source source = prefs.currentSource();
        if (source == null) {
            // 尚未配置直播源：进入引导页，去设置里添加
            loadingPlaylist = false;
            emptyPanel.setVisibility(View.VISIBLE);
            emptyAction.requestFocus();
            return;
        }
        loadingPlaylist = true;
        loadedSourceUrl = source.url;
        emptyPanel.setVisibility(View.GONE);
        setStatus(getString(R.string.loading), true);

        PlaylistRepository.load(this, source.url, force, new PlaylistRepository.Callback() {
            @Override
            public void onLoading(boolean fromNetwork) {
                setStatus(getString(R.string.loading), true);
            }

            @Override
            public void onSuccess(Playlist result, boolean fromCache) {
                loadingPlaylist = false;
                playlist = result;
                hideStatus();
                refreshGroups();
                refreshChannelList();

                Channel target = current != null ? findByUrl(current.url) : findLastChannel();
                if (target == null && !playlist.channels.isEmpty()) {
                    target = playlist.channels.get(0);
                }
                if (target != null) {
                    // 恢复上次播放的线路（按上次保存的线路地址定位，找不到则用首条线路）
                    int line = target.lineIndexOfUrl(prefs.getLastUrl());
                    currentLine = line >= 0 ? line : 0;
                    play(target, current != null);
                }
            }

            @Override
            public void onError(String message) {
                loadingPlaylist = false;
                setStatus(getString(R.string.source_refresh_failed, message), false);
                if (playlist == null || playlist.isEmpty()) {
                    emptyPanel.setVisibility(View.VISIBLE);
                    emptyAction.requestFocus();
                }
            }
        });
    }

    private Channel findLastChannel() {
        String last = prefs.getLastUrl();
        if (last == null) return null;
        return findByUrl(last);
    }

    private Channel findByUrl(String url) {
        if (playlist == null || url == null) return null;
        for (Channel c : playlist.channels) {
            // 任一线路地址匹配即命中（多线路频道用非首条线路地址也能找到）
            if (c.lineIndexOfUrl(url) >= 0) return c;
        }
        return null;
    }

    // ---------------- 播放 ----------------

    private void play(Channel channel, boolean showBanner) {
        if (channel == null) return;
        current = channel;
        if (currentLine < 0 || currentLine >= channel.lineCount()) currentLine = 0;
        resetFailoverState();
        // 多线路频道：首轮失败直接换线；一圈试完后由 onFailed 关掉此标志做完整重试。
        controller.setFastFailover(channel.hasMultipleLines());
        String url = channel.lineUrl(currentLine);
        prefs.setLastUrl(url);
        controller.play(url);
        updateInfo(channel);
        channelAdapter.setSelected(channel.number);
        setStatus(getString(R.string.loading_channel, channel.name), true);
        if (showBanner && overlay.getVisibility() != View.VISIBLE) showSwitchBanner(channel);
    }

    /** 切换到当前频道的指定线路。automatic=true 为故障自动换线，不弹换台横幅。 */
    private void playLine(int line, boolean automatic) {
        if (current == null) return;
        if (line < 0 || line >= current.lineCount()) return;
        currentLine = line;
        playFailed = false;
        handler.removeCallbacks(resetFailover);
        if (!automatic) {
            resetFailoverState();
            controller.setFastFailover(current.hasMultipleLines());
        }
        String url = current.lineUrl(line);
        prefs.setLastUrl(url);
        controller.play(url);
        updateInfo(current);
        channelAdapter.setSelected(current.number);
        if (automatic) {
            setStatus(getString(R.string.switching_line, line + 1, current.lineCount()), true);
            switchBanner.setVisibility(View.GONE);
        } else {
            setStatus(getString(R.string.loading_channel, current.name), true);
            showSwitchBanner(current);
        }
    }

    private void resetFailoverState() {
        playFailed = false;
        lineFallbackStart = -1;
        finalRetryUsed = false;
        autoSwitchCount = 0;
        autoSwitchWindowStart = 0;
        handler.removeCallbacks(resetFailover);
    }

    /** 该频道上次稳定播放的线路：优先本次运行记录，其次持久化偏好。 */
    private int preferredLineFor(Channel channel) {
        Integer session = stableLines.get(channel.key());
        int line = session != null ? session : prefs.getPreferredLine(channel.key());
        return (line >= 0 && line < channel.lineCount()) ? line : 0;
    }

    private void switchChannel(int delta) {
        if (playlist == null || playlist.isEmpty()) return;
        int index = current == null ? -1 : playlist.channels.indexOf(current);
        if (index < 0) index = 0;
        int next = (index + delta + playlist.size()) % playlist.size();
        Channel nextChannel = playlist.channels.get(next);
        // 记住每个频道上次稳定播放的线路，避免每次换台都回到可能较差的首条线路
        currentLine = preferredLineFor(nextChannel);
        play(nextChannel, true);
    }

    @Override
    public void onReady() {
        playFailed = false;
        lineFallbackStart = -1;
        if (current != null) {
            // 记住该频道这次稳定播放的线路，下次换回它时直接使用。
            stableLines.put(current.key(), currentLine);
            prefs.setPreferredLine(current.key(), currentLine);
        }
        // 只有稳定播放一段时间才复位换线额度；抖动源短暂 READY 不重置，
        // 否则会形成「就绪 → 断流 → 换线」的无限循环。
        handler.removeCallbacks(resetFailover);
        handler.postDelayed(resetFailover, STABLE_RESET_MS);
        hideStatus();
    }

    @Override
    public void onBuffering() {
        if (!playFailed && current != null) setStatus("缓冲中…", true);
    }

    @Override
    public void onRetrying(int attempt) {
        setStatus(getString(R.string.retrying, attempt, PlayerController.MAX_RETRY), true);
    }

    @Override
    public void onFailed(String message) {
        handler.removeCallbacks(resetFailover);
        // 多线路频道：首轮错误直接换下一条线路，跳过 3 次空等重连；
        // 一圈线路都试过后，再用完整重连链兜底一次，最后才提示失败。
        if (current != null && current.hasMultipleLines() && !finalRetryUsed) {
            long now = SystemClock.elapsedRealtime();
            if (autoSwitchWindowStart == 0 || now - autoSwitchWindowStart > FAILOVER_WINDOW_MS) {
                autoSwitchWindowStart = now;
                autoSwitchCount = 0;
            }
            if (autoSwitchCount >= MAX_AUTO_SWITCHES) {
                // 源抖动过于频繁：停止自动换线，交给用户手动重试，避免无限循环。
                controller.setFastFailover(false);
                playFailed = true;
                setStatus(getString(R.string.play_failed, message), false);
                return;
            }
            if (lineFallbackStart < 0) lineFallbackStart = currentLine;
            int next = (currentLine + 1) % current.lineCount();
            if (next != lineFallbackStart) {
                autoSwitchCount++;
                playLine(next, true);
                return;
            }
            // 全部线路首轮都失败：回到该频道最稳的线路，做一次完整重连（3 次退避）。
            int start = preferredLineFor(current);
            lineFallbackStart = -1;
            finalRetryUsed = true;
            controller.setFastFailover(false);
            playLine(start, true);
            setStatus(getString(R.string.retrying_manual), true);
            return;
        }
        playFailed = true;
        controller.setFastFailover(false);
        setStatus(getString(R.string.play_failed, message), false);
    }

    // ---------------- 界面状态 ----------------

    private void setStatus(String text, boolean spinning) {
        statusText.setText(text);
        progress.setVisibility(spinning ? View.VISIBLE : View.GONE);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    private void updateInfo(Channel channel) {
        infoNumber.setText(channel.displayNumber());
        infoName.setText(channel.name);
        String group = (channel.group == null || channel.group.isEmpty())
                ? getString(R.string.ungrouped) : channel.group;
        if (channel.hasMultipleLines()) {
            String label = channel.lineLabel(currentLine);
            if (label != null) group = group + " · " + label;
        }
        Source source = prefs.currentSource();
        infoGroup.setText(source != null ? group + " · " + source.displayName() : group);
        infoClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
    }

    private void showSwitchBanner(Channel channel) {
        bannerNumber.setText(channel.displayNumber());
        String name = channel.name;
        if (channel.hasMultipleLines()) {
            String label = channel.lineLabel(currentLine);
            if (label != null) name = name + " · " + label;
        }
        bannerName.setText(name);
        switchBanner.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, 2600);
    }

    private void showInfoBar() {
        if (current == null) return;
        updateInfo(current);
        infoBar.setVisibility(View.VISIBLE);
        switchBanner.setVisibility(View.GONE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 6000);
    }

    // ---------------- 频道列表 ----------------

    private void showOverlay() {
        if (playlist == null || playlist.isEmpty()) {
            openSettings();
            return;
        }
        overlay.setVisibility(View.VISIBLE);
        infoBar.setVisibility(View.GONE);
        switchBanner.setVisibility(View.GONE);
        refreshGroups();
        refreshChannelList();
        focusCurrentChannel();
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
        channelList.clearFocus();
        groupList.clearFocus();
    }

    private void focusCurrentChannel() {
        int position = current == null ? 0 : channelAdapter.positionOf(current.number);
        if (position == RecyclerView.NO_POSITION) position = 0;
        final int target = position;
        channelList.post(() -> {
            RecyclerView.LayoutManager manager = channelList.getLayoutManager();
            if (manager instanceof GridLayoutManager) {
                ((GridLayoutManager) manager).scrollToPositionWithOffset(target, 0);
            }
            channelList.post(() -> {
                RecyclerView.ViewHolder holder = channelList.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
                else channelList.requestFocus();
            });
        });
    }

    private void refreshGroups() {
        if (playlist == null) return;
        List<GroupAdapter.Entry> entries = new ArrayList<>();
        entries.add(new GroupAdapter.Entry(null, getString(R.string.all_channels), playlist.size()));
        entries.add(new GroupAdapter.Entry(GROUP_FAVORITES, getString(R.string.favorites), favorites().size()));
        for (String group : playlist.groups) {
            entries.add(new GroupAdapter.Entry(group, group, playlist.byGroup(group).size()));
        }
        if (playlist.hasUngrouped) {
            entries.add(new GroupAdapter.Entry(GROUP_UNGROUPED, getString(R.string.ungrouped), ungrouped().size()));
        }
        groupAdapter.setData(entries, selectedGroup);
    }

    private void refreshChannelList() {
        if (playlist == null) return;
        List<Channel> channels = channelsForGroup(selectedGroup);
        channelAdapter.setData(channels, current == null ? -1 : current.number, prefs.getFavorites());

        String title;
        if (selectedGroup == null) title = getString(R.string.all_channels);
        else if (GROUP_FAVORITES.equals(selectedGroup)) title = getString(R.string.favorites);
        else if (GROUP_UNGROUPED.equals(selectedGroup)) title = getString(R.string.ungrouped);
        else title = selectedGroup;
        listTitle.setText(title);
        listCount.setText(getString(R.string.channel_count, channels.size()));
        groupAdapter.setSelected(selectedGroup);
    }

    private List<Channel> channelsForGroup(String group) {
        if (playlist == null) return new ArrayList<>();
        if (group == null) return playlist.channels;
        if (GROUP_FAVORITES.equals(group)) return favorites();
        if (GROUP_UNGROUPED.equals(group)) return ungrouped();
        return playlist.byGroup(group);
    }

    private List<Channel> favorites() {
        List<Channel> out = new ArrayList<>();
        if (playlist == null) return out;
        Set<String> keys = prefs.getFavorites();
        for (Channel c : playlist.channels) if (keys.contains(c.key())) out.add(c);
        return out;
    }

    private List<Channel> ungrouped() {
        List<Channel> out = new ArrayList<>();
        if (playlist == null) return out;
        for (Channel c : playlist.channels) {
            if (c.group == null || c.group.isEmpty()) out.add(c);
        }
        return out;
    }

    // ---------------- 回调 ----------------

    @Override
    public void onClick(Channel channel) {
        if (channel.hasMultipleLines()) {
            showLinePicker(channel);
            return;
        }
        currentLine = 0;
        play(channel, false);
        hideOverlay();
    }

    /** 多线路频道：弹出线路列表供选择（标题为频道名）。 */
    private void showLinePicker(Channel channel) {
        CharSequence[] labels = new CharSequence[channel.lineCount()];
        for (int i = 0; i < labels.length; i++) labels[i] = channel.lineLabel(i);
        new AlertDialog.Builder(this)
                .setTitle(channel.name)
                .setItems(labels, (dialog, which) -> {
                    currentLine = which;
                    play(channel, false);
                    hideOverlay();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onLongClick(Channel channel) {
        boolean added = prefs.toggleFavorite(channel.key());
        toast(added ? getString(R.string.favorite_add) : getString(R.string.favorite_remove));
        refreshGroups();
        refreshChannelList();
    }

    @Override
    public void onGroupClick(GroupAdapter.Entry entry) {
        selectedGroup = entry.key;
        refreshChannelList();
        channelList.post(() -> {
            RecyclerView.ViewHolder holder = channelList.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else channelList.requestFocus();
        });
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int code = event.getKeyCode();
            boolean overlayShown = overlay.getVisibility() == View.VISIBLE;

            if (code == KeyEvent.KEYCODE_BACK) {
                if (overlayShown) {
                    hideOverlay();
                    return true;
                }
                if (infoBar.getVisibility() == View.VISIBLE) {
                    infoBar.setVisibility(View.GONE);
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackTime < 2000) {
                    finish();
                } else {
                    lastBackTime = now;
                    toast(getString(R.string.press_again_exit));
                }
                return true;
            }

            if (code == KeyEvent.KEYCODE_MENU) {
                openSettings();
                return true;
            }

            switch (code) {
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (!overlayShown) {
                        switchChannel(1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (!overlayShown) {
                        switchChannel(-1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!overlayShown) {
                        switchChannel(-1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!overlayShown) {
                        switchChannel(1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!overlayShown) {
                        if (playFailed) {
                            playFailed = false;
                            if (current != null && current.hasMultipleLines()) {
                                // 失败态按 OK：先换下一条线路，比反复重试同一条死链有效。
                                playLine((currentLine + 1) % current.lineCount(), false);
                            } else {
                                controller.retry();
                                setStatus(getString(R.string.retrying_manual), true);
                            }
                        } else {
                            showOverlay();
                        }
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // 播放态右键同样可打开频道列表：失败态 OK 用于重试时，列表入口仍可用。
                    if (!overlayShown) {
                        showOverlay();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // 播放态左键：多线路频道切换到下一条线路；单线路不做任何事。
                    if (!overlayShown) {
                        if (current != null && current.hasMultipleLines()) {
                            int next = (currentLine + 1) % current.lineCount();
                            playLine(next, false);
                        }
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_INFO:
                    showInfoBar();
                    return true;
                default:
                    break;
            }

            if (!overlayShown && code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                onDigit(code - KeyEvent.KEYCODE_0);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void onDigit(int digit) {
        if (playlist == null || playlist.isEmpty()) return;
        digits.append(digit);
        if (digits.length() > 3) digits.deleteCharAt(0);
        numberInput.setText(digits.toString());
        numberInput.setVisibility(View.VISIBLE);
        handler.removeCallbacks(commitDigits);
        handler.postDelayed(commitDigits, 1200);
    }

    // ---------------- 生命周期 ----------------

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (current != null && playlist != null) play(current, false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) controller.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        Source source = prefs.currentSource();
        if (source == null) {
            if (!loadingPlaylist) {
                emptyPanel.setVisibility(View.VISIBLE);
                emptyAction.requestFocus();
            }
            return;
        }
        String url = source.url;
        boolean sourceChanged = loadedSourceUrl != null && !url.equals(loadedSourceUrl);
        if (prefs.needRefresh() || sourceChanged) {
            prefs.setNeedRefresh(false);
            loadPlaylist(true);
        } else if (!loadingPlaylist && playlist != null) {
            refreshGroups();
            refreshChannelList();
            // onStop() 会 stop() 播放器；从后台返回时恢复播放，避免停在黑屏。
            if (controller.isIdle() && current != null) {
                playFailed = false;
                setStatus(getString(R.string.loading_channel, current.name), true);
                controller.resume();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_GROUP, selectedGroup);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (controller != null) controller.release();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
