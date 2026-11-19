package com.github.tvbox.osc.ui.fragment;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.LiveSourceBean;
import com.github.tvbox.osc.bean.MoreSourceBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.LocalFileActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.AboutDialog;
import com.github.tvbox.osc.ui.dialog.ApiDialog;
import com.github.tvbox.osc.ui.dialog.ApiHistoryDialog;
import com.github.tvbox.osc.ui.dialog.BackupDialog;
import com.github.tvbox.osc.ui.dialog.ConfigFlow;
import com.github.tvbox.osc.ui.dialog.DanmuApiDialog;
import com.github.tvbox.osc.ui.dialog.LiveSourceDialog;
import com.github.tvbox.osc.ui.dialog.SearchRemoteTvDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.XWalkInitDialog;
import com.github.tvbox.osc.util.ConfigRepository;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LocalVideoLauncher;
import com.github.tvbox.osc.util.LiveSourceLoader;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.PySpiderLoader;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;

import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class ModelSettingFragment extends BaseLazyFragment implements ConfigFlow.Host {
    private static final int REQUEST_LOCAL_CONFIG = 1001;
    private static final int REQUEST_BATCH_IMPORT_PY = 1005;
    private static final int REQUEST_LOCAL_STORE_FILE = 1002;
    private TextView tvDebugOpen;
    private TextView tvMediaCodec;
    private TextView tvParseWebView;
    private TextView tvPlay;
    private TextView tvRender;
    private TextView tvScale;
    private TextView tvApi;
    private TextView tvApiLine;
    private View llApi;
    private View llApiHistory;
    private View llApiLine;
    private TextView tvHomeApi;
    private TextView tvDns;
    private TextView tvHomeRec;
    private TextView tvHistoryNum;
    private TextView tvHistoryMerge;
    private TextView tvSearchView;
    private TextView tvSearchThread;
    private TextView tvShowPreviewText;
    private TextView tvFastSearchText;
    private TextView tvm3u8AdText;
    private TextView tvAutoSwitchLineText;
    private TextView tvRecStyleText;
    private TextView tvIjkCachePlay;
    private TextView tvHomeDefaultShow;
    private ApiDialog apiDialog;
    private boolean selectLocalLive;
    private TextView tvDanmuOpenText;
    private TextView tvDanmuApiText;
    private TextView tvStoreApi;
    private TextView tvLiveApiText;
    // ========== 线路缓存相关 ==========
    private long currentLineCacheTime = -1;
    private String currentLineCacheKey = "";

    public static ModelSettingFragment newInstance() {
        return new ModelSettingFragment().setArguments();
    }

    public ModelSettingFragment setArguments() {
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_model;
    }

    @Override
    protected void init() {
        tvFastSearchText = findViewById(R.id.showFastSearchText);
        tvFastSearchText.setText(Hawk.get(HawkConfig.FAST_SEARCH_MODE, true) ? "开启" : "关闭");
        tvm3u8AdText = findViewById(R.id.m3u8AdText);
        tvm3u8AdText.setText(Hawk.get(HawkConfig.M3U8_PURIFY, false) ? "开启" : "关闭");
        tvDanmuOpenText = findViewById(R.id.danmuOpenText);
        tvDanmuOpenText.setText(DanmuHelper.isOpen() ? "开启" : "关闭");
        tvDanmuApiText = findViewById(R.id.danmuApiText);
        refreshDanmuApiText();
        tvAutoSwitchLineText = findViewById(R.id.autoSwitchLineText);
        tvAutoSwitchLineText.setText(Hawk.get(HawkConfig.AUTO_SWITCH_LINE, true) ? "开启" : "关闭");
        tvRecStyleText = findViewById(R.id.showRecStyleText);
        tvRecStyleText.setText(Hawk.get(HawkConfig.HOME_REC_STYLE, false) ? "是" : "否");
        tvShowPreviewText = findViewById(R.id.showPreviewText);
        tvShowPreviewText.setText(Hawk.get(HawkConfig.SHOW_PREVIEW, true) ? "开启" : "关闭");
        tvDebugOpen = findViewById(R.id.tvDebugOpen);
        tvParseWebView = findViewById(R.id.tvParseWebView);
        tvMediaCodec = findViewById(R.id.tvMediaCodec);
        tvPlay = findViewById(R.id.tvPlay);
        tvRender = findViewById(R.id.tvRenderType);
        tvScale = findViewById(R.id.tvScaleType);
        llApi = findViewById(R.id.llApi);
        llApiHistory = findViewById(R.id.llApiHistory);
        llApiLine = findViewById(R.id.llApiLine);
        tvApi = findViewById(R.id.tvApi);
        tvApiLine = findViewById(R.id.tvApiLine);
        tvHomeApi = findViewById(R.id.tvHomeApi);
        tvDns = findViewById(R.id.tvDns);
        tvHomeRec = findViewById(R.id.tvHomeRec);
        tvHistoryNum = findViewById(R.id.tvHistoryNum);
        tvHistoryMerge = findViewById(R.id.tvHistoryMerge);
        tvSearchView = findViewById(R.id.tvSearchView);
        tvSearchThread = findViewById(R.id.tvSearchThread);
        tvIjkCachePlay = findViewById(R.id.tvIjkCachePlay);
        tvMediaCodec.setText(PlayerHelper.isSoftwareDecode(Hawk.get(HawkConfig.EXO_CODEC, Hawk.get(HawkConfig.IJK_CODEC, "硬解码"))) ? "软解码" : "硬解码");
        tvDebugOpen.setText(Hawk.get(HawkConfig.DEBUG_OPEN, false) ? "已打开" : "已关闭");
        tvParseWebView.setText(Hawk.get(HawkConfig.PARSE_WEBVIEW, true) ? "系统自带" : "XWalkView");
        tvApi.setText(Hawk.get(HawkConfig.API_URL, ""));
        refreshApiLineText();
        // 配置多仓地址右侧：显示当前选中的仓库名
        tvStoreApi = findViewById(R.id.tvStoreApi);
        refreshStoreApiText();
        // 直播地址右侧：显示当前直播源名称
        tvLiveApiText = findViewById(R.id.text_immersive_switch);
        refreshLiveApiText();
        // 线路切换右侧：显示当前选中的线路名
        final TextView tvMoreSourceApi = findViewById(R.id.tvMoreSourceApi);
        if (tvMoreSourceApi != null) {
            ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
            String current = Hawk.get(HawkConfig.API_URL, "");
            String lineName = "";
            for (String apiLine : apiLines) {
                if (current.equals(HistoryHelper.getApiLineUrl(apiLine))) {
                    lineName = HistoryHelper.getApiLineName(apiLine);
                    break;
                }
            }
            tvMoreSourceApi.setText(lineName);
        }
        // 配置多仓地址 -> 多仓列表对话框
        findViewById(R.id.default_more_store).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showStoreHouseDialog();
            }
        });
        // 线路切换 -> 从多仓URL动态获取线路
        findViewById(R.id.more_source).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showLineSwitchDialog();
            }
        });
        // 直播地址（优先显示直播源分支选择列表）
        findViewById(R.id.ll_immersive_switch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showLiveSourceDialog();
            }
        });

        tvDns.setText(OkGoHelper.dnsHttpsList.get(Hawk.get(HawkConfig.DOH_URL, 0)));
        tvHomeRec.setText(getHomeRecName(Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)));
        tvHistoryNum.setText(HistoryHelper.getHistoryNumName(Hawk.get(HawkConfig.HISTORY_NUM, 0)));
        tvHistoryMerge.setText(Hawk.get(HawkConfig.HISTORY_MERGE, false) ? "开启" : "关闭");
        tvSearchView.setText(getSearchView(Hawk.get(HawkConfig.SEARCH_VIEW, 0)));
        tvSearchThread.setText(Hawk.get(HawkConfig.SEARCH_THREAD_COUNT, 16) + "线");
        tvHomeApi.setText(ApiConfig.get().getHomeSourceBean().getName());
        tvScale.setText(PlayerHelper.getScaleName(Hawk.get(HawkConfig.PLAY_SCALE, 0)));
        tvPlay.setText(PlayerHelper.getPlayerName(Hawk.get(HawkConfig.PLAY_TYPE, 2)));
        tvRender.setText(PlayerHelper.getRenderName(Hawk.get(HawkConfig.PLAY_RENDER, 0)));
        tvIjkCachePlay.setText(Hawk.get(HawkConfig.IJK_CACHE_PLAY, false) ? "开启" : "关闭");
        tvHomeDefaultShow = findViewById(R.id.tvHomeText);
        tvHomeDefaultShow.setText(Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false) ? "直播" : "点播");
        final TextView homePageText = findViewById(R.id.home_page_text);
        if (homePageText != null) {
            homePageText.setText(Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false) ? "直播" : "点播");
        }
        // 首选项（直播/点播切换）
        findViewById(R.id.home_page).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.DEFAULT_LOAD_LIVE, !Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false));
                tvHomeDefaultShow.setText(Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false) ? "直播" : "点播");
                if (homePageText != null) {
                    homePageText.setText(Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false) ? "直播" : "点播");
                }
            }
        });
        findViewById(R.id.llDebug).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.DEBUG_OPEN, !Hawk.get(HawkConfig.DEBUG_OPEN, false));
                tvDebugOpen.setText(Hawk.get(HawkConfig.DEBUG_OPEN, false) ? "已打开" : "已关闭");
            }
        });
        findViewById(R.id.llParseWebVew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean useSystem = !Hawk.get(HawkConfig.PARSE_WEBVIEW, true);
                Hawk.put(HawkConfig.PARSE_WEBVIEW, useSystem);
                tvParseWebView.setText(Hawk.get(HawkConfig.PARSE_WEBVIEW, true) ? "系统自带" : "XWalkView");
                if (!useSystem) {
                    Toast.makeText(mContext, "注意: XWalkView只适用于部分低Android版本，Android5.0以上推荐使用系统自带", Toast.LENGTH_LONG).show();
                    XWalkInitDialog dialog = new XWalkInitDialog(mContext);
                    dialog.setOnListener(new XWalkInitDialog.OnListener() {
                        @Override
                        public void onchange() {
                        }
                    });
                    dialog.show();
                }
            }
        });
        findViewById(R.id.llBackup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                BackupDialog dialog = new BackupDialog(mActivity);
                dialog.show();
            }
        });
        findViewById(R.id.llAbout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                AboutDialog dialog = new AboutDialog(mActivity);
                dialog.show();
            }
        });
        findViewById(R.id.llWp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (!ApiConfig.get().wallpaper.isEmpty())
                    OkGo.<File>get(ApiConfig.get().wallpaper).execute(new FileCallback(requireActivity().getFilesDir().getAbsolutePath(), "wp") {
                        @Override
                        public void onSuccess(Response<File> response) {
                            ((BaseActivity) requireActivity()).changeWallpaper(true);
                        }

                        @Override
                        public void onError(Response<File> response) {
                            super.onError(response);
                        }

                        @Override
                        public void downloadProgress(Progress progress) {
                            super.downloadProgress(progress);
                        }
                    });
            }
        });
        findViewById(R.id.llWpRecovery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                File wp = new File(requireActivity().getFilesDir().getAbsolutePath() + "/wp");
                if (wp.exists())
                    wp.delete();
                ((BaseActivity) requireActivity()).changeWallpaper(true);
            }
        });
        findViewById(R.id.llHomeApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                List<SourceBean> sites = ApiConfig.get().getSwitchSourceBeanList();
                if (sites.size() > 0) {
                    SelectDialog<SourceBean> dialog = new SelectDialog<>(mActivity);
                    dialog.setTip("请选择首页数据源");
                    int select = sites.indexOf(ApiConfig.get().getHomeSourceBean());
                    if (select<0) select = 0;
                    dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
                        @Override
                        public void click(SourceBean value, int pos) {
                            dialog.dismiss();
                            ApiConfig.get().setSourceBean(value);
                            tvHomeApi.setText(ApiConfig.get().getHomeSourceBean().getName());
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
                        }

                        @Override
                        public String getDisplay(SourceBean val) {
                            return val.getName();
                        }
                    }, new DiffUtil.ItemCallback<SourceBean>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                            return oldItem == newItem;
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                            return oldItem.getKey().equals(newItem.getKey());
                        }
                    }, sites, select);
                    dialog.show();
                }
            }
        });
        findViewById(R.id.llDns).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int dohUrl = Hawk.get(HawkConfig.DOH_URL, 0);

                SelectDialog<String> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择安全DNS");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        tvDns.setText(OkGoHelper.dnsHttpsList.get(pos));
                        Hawk.put(HawkConfig.DOH_URL, pos);
//                        String url = OkGoHelper.getDohUrl(pos);
//                        OkGoHelper.dnsOverHttps.setUrl(url.isEmpty() ? null : HttpUrl.get(url));
                        OkGoHelper.reloadDns();
                        IjkMediaPlayer.toggleDotPort(pos > 0);
                    }

                    @Override
                    public String getDisplay(String val) {
                        return val;
                    }
                }, new DiffUtil.ItemCallback<String>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }
                }, OkGoHelper.dnsHttpsList, dohUrl);
                dialog.show();
            }
        });
        findViewById(R.id.llApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                apiDialog = new ApiDialog(mActivity);
                ApiDialog dialog = apiDialog;
                EventBus.getDefault().register(dialog);
                dialog.setOnListener(new ApiDialog.OnListener() {
                    @Override
                    public void onchange(String api) {
                        String oldApi = Hawk.get(HawkConfig.API_URL, "");
                        Hawk.put(HawkConfig.API_URL, api);
                        if (!HistoryHelper.isApiLineHistory(api)) {
                            // 只清来源标记：clearApiLineList()会把API_LINE_LIST整体清空，
                            // 用户手工添加的线路一并丢失；解除来源关系即可
                            HistoryHelper.clearApiLineSource();
                        }
                        tvApi.setText(api);
                        refreshApiLineText();
                        if (!oldApi.equals(api)) {
                            restartAppAfterConfigChanged();
                        }
                    }

                    @Override
                    public void onLocalConfig(boolean live) {
                        openLocalConfig(live);
                    }

                    @Override
                    public void onBatchImportPy() {
                        openBatchImportPy();
                    }
                });
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        ((BaseActivity) mActivity).hideSysBar();
                        EventBus.getDefault().unregister(dialog);
                        apiDialog = null;
                    }
                });
                dialog.show();
            }
        });

        findViewById(R.id.llApiHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
                if (history.isEmpty())
                    return;
                String current = Hawk.get(HawkConfig.API_URL, "");
                int idx = 0;
                if (history.contains(current))
                    idx = history.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(mActivity);
                dialog.setTip("历史配置列表");
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String value) {
                        String oldApi = Hawk.get(HawkConfig.API_URL, "");
                        if (!HistoryHelper.isApiLineHistory(value)) {
                            // 只清来源标记：clearApiLineList()会把API_LINE_LIST整体清空，
                            // 用户手工添加的线路一并丢失；解除来源关系即可
                            HistoryHelper.clearApiLineSource();
                        }
                        Hawk.put(HawkConfig.API_URL, value);
                        // 直播地址仅在跟随配置状态下切换，用户手工添加的直播源不被覆盖
                        HistoryHelper.followLiveApiOnLineSwitch(value, oldApi);
                        tvApi.setText(value);
                        refreshApiLineText();
                        dialog.dismiss();
                        if (!oldApi.equals(value)) {
                            restartAppAfterConfigChanged();
                        }
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        Hawk.put(HawkConfig.API_HISTORY, data);
                    }
                }, history, idx);
                dialog.show();
            }
        });

        findViewById(R.id.llApiLine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
                if (apiLines.isEmpty()) {
                    Toast.makeText(mContext, "线路列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                String current = Hawk.get(HawkConfig.API_URL, "");
                int idx = 0;
                for (int i = 0; i < apiLines.size(); i++) {
                    if (current.equals(HistoryHelper.getApiLineUrl(apiLines.get(i)))) {
                        idx = i;
                        break;
                    }
                }
                SelectDialog<String> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("线路选择");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        String newApi = HistoryHelper.getApiLineUrl(value);
                        String oldApi = Hawk.get(HawkConfig.API_URL, "");
                        if (newApi.isEmpty()) {
                            return;
                        }
                        Hawk.put(HawkConfig.API_URL, newApi);
                        // 直播地址仅在跟随配置状态下切换，用户手工添加的直播源不被覆盖
                        HistoryHelper.followLiveApiOnLineSwitch(newApi, oldApi);
                        HistoryHelper.setApiHistory(newApi);
                        tvApi.setText(newApi);
                        refreshApiLineText();
                        dialog.dismiss();
                        if (!oldApi.equals(newApi)) {
                            restartAppAfterConfigChanged();
                        }
                    }

                    @Override
                    public String getDisplay(String val) {
                        return HistoryHelper.getApiLineName(val);
                    }
                }, SelectDialogAdapter.stringDiff, apiLines, idx);
                dialog.show();
            }
        });


        findViewById(R.id.llMediaCodec).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                ArrayList<String> decodeItems = new ArrayList<>();
                decodeItems.add("硬解码");
                decodeItems.add("软解码");
                String current = Hawk.get(HawkConfig.EXO_CODEC, Hawk.get(HawkConfig.IJK_CODEC, "硬解码"));
                int defaultPos = PlayerHelper.isSoftwareDecode(current) ? 1 : 0;

                SelectDialog<String> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择解码方式");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        Hawk.put(HawkConfig.IJK_CODEC, value);
                        Hawk.put(HawkConfig.EXO_CODEC, value);
                        tvMediaCodec.setText(value);
                    }

                    @Override
                    public String getDisplay(String val) {
                        return val;
                    }
                }, SelectDialogAdapter.stringDiff, decodeItems, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llScale).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.PLAY_SCALE, 0);
                ArrayList<Integer> players = new ArrayList<>();
                players.add(0);
                players.add(1);
                players.add(2);
                players.add(3);
                players.add(4);
                players.add(5);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择默认画面缩放");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.PLAY_SCALE, value);
                        tvScale.setText(PlayerHelper.getScaleName(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return PlayerHelper.getScaleName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, players, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llPlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int playerType = Hawk.get(HawkConfig.PLAY_TYPE, 2);
                int defaultPos = 0;
                ArrayList<Integer> players = PlayerHelper.getExistPlayerTypes();
                ArrayList<Integer> renders = new ArrayList<>();
                for(int p = 0; p<players.size(); p++) {
                    renders.add(p);
                    if (players.get(p) == playerType) {
                        defaultPos = p;
                    }
                }
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择默认播放器");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Integer thisPlayerType = players.get(pos);
                        Hawk.put(HawkConfig.PLAY_TYPE, thisPlayerType);
                        tvPlay.setText(PlayerHelper.getPlayerName(thisPlayerType));
                        PlayerHelper.init();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        Integer playerType = players.get(val);
                        return PlayerHelper.getPlayerName(playerType);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, renders, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llRender).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.PLAY_RENDER, 0);
                ArrayList<Integer> renders = new ArrayList<>();
                renders.add(0);
                renders.add(1);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择默认渲染方式");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.PLAY_RENDER, value);
                        tvRender.setText(PlayerHelper.getRenderName(value));
                        PlayerHelper.init();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return PlayerHelper.getRenderName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, renders, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llHomeRec).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                types.add(2);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择首页列表数据");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.HOME_REC, value);
                        tvHomeRec.setText(getHomeRecName(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getHomeRecName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llSearchView).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.SEARCH_VIEW, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择搜索视图");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.SEARCH_VIEW, value);
                        tvSearchView.setText(getSearchView(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getSearchView(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        SettingActivity.callback = new SettingActivity.DevModeCallback() {
            @Override
            public void onChange() {
                findViewById(R.id.llDebug).setVisibility(View.VISIBLE);
            }
        };

        findViewById(R.id.showPreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.SHOW_PREVIEW, !Hawk.get(HawkConfig.SHOW_PREVIEW, true));
                tvShowPreviewText.setText(Hawk.get(HawkConfig.SHOW_PREVIEW, true) ? "开启" : "关闭");
            }
        });
        findViewById(R.id.llHistoryNum).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.HISTORY_NUM, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                types.add(2);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("保留历史记录数量");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.HISTORY_NUM, value);
                        tvHistoryNum.setText(HistoryHelper.getHistoryNumName(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return HistoryHelper.getHistoryNumName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llSearchThread).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int current = Hawk.get(HawkConfig.SEARCH_THREAD_COUNT, 16);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(12);
                types.add(16);
                types.add(24);
                types.add(36);
                types.add(48);
                int defaultPos = Math.max(0, types.indexOf(current));
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("搜索线程");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.SEARCH_THREAD_COUNT, value);
                        tvSearchThread.setText(value + "线");
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return val + "线";
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.showFastSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.FAST_SEARCH_MODE, !Hawk.get(HawkConfig.FAST_SEARCH_MODE, true));
                tvFastSearchText.setText(Hawk.get(HawkConfig.FAST_SEARCH_MODE, true) ? "开启" : "关闭");
            }
        });
        findViewById(R.id.m3u8Ad).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean is_purify=Hawk.get(HawkConfig.M3U8_PURIFY, false);
                Hawk.put(HawkConfig.M3U8_PURIFY, !is_purify);
                tvm3u8AdText.setText(!is_purify ? "开启" : "关闭");
            }
        });
        findViewById(R.id.danmuOpen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean open = !DanmuHelper.isOpen();
                DanmuHelper.setOpen(open);
                tvDanmuOpenText.setText(open ? "开启" : "关闭");
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, open));
            }
        });
        findViewById(R.id.danmuApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                DanmuApiDialog dialog = new DanmuApiDialog(mActivity);
                dialog.setOnListener(new DanmuApiDialog.OnListener() {
                    @Override
                    public void onChange(String api) {
                        refreshDanmuApiText();
                    }
                });
                dialog.show();
            }
        });
        findViewById(R.id.autoSwitchLine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean enable = !Hawk.get(HawkConfig.AUTO_SWITCH_LINE, true);
                Hawk.put(HawkConfig.AUTO_SWITCH_LINE, enable);
                tvAutoSwitchLineText.setText(enable ? "开启" : "关闭");
            }
        });
        findViewById(R.id.llHomeRecStyle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.HOME_REC_STYLE, !Hawk.get(HawkConfig.HOME_REC_STYLE, false));
                tvRecStyleText.setText(Hawk.get(HawkConfig.HOME_REC_STYLE, false) ? "是" : "否");
            }
        });

        findViewById(R.id.llHistoryMerge).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean historyMerge = !Hawk.get(HawkConfig.HISTORY_MERGE, false);
                Hawk.put(HawkConfig.HISTORY_MERGE, historyMerge);
                tvHistoryMerge.setText(historyMerge ? "开启" : "关闭");
            }
        });

        //下次进入
        findViewById(R.id.tvHomeLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.DEFAULT_LOAD_LIVE, !Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false));
                tvHomeDefaultShow.setText(Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false) ? "直播" : "点播");
            }
        });

        findViewById(R.id.llIjkCachePlay).setOnClickListener((view -> onClickIjkCachePlay(view)));
        findViewById(R.id.llClearCache).setOnClickListener((view -> onClickClearCache(view)));
    }

    private void restartAppAfterConfigChanged() {
        Toast.makeText(mContext, "配置已切换,即将重新加载!", Toast.LENGTH_SHORT).show();
        clearConfigSwitchCache();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mActivity != null && !mActivity.isFinishing()) {
                    mActivity.onBackPressed();
                }
            }
        }, 2500);
    }

    private void clearConfigSwitchCache() {
        try {
            SourceViewModel.clearRuntimeCache();
//            FileUtils.clearSpiderCacheFiles();
            LOG.i("echo-clear-config-switch-cache");
        } catch (Exception e) {
            LOG.i("echo-clear-config-switch-cache-error:" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void restartAppAfterCacheCleared() {
        Toast.makeText(mContext, "缓存已清空,即将回到主页!", Toast.LENGTH_LONG).show();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                restartApp();
            }
        }, 2500);
    }

    private void refreshApiLineText() {
        if (tvApiLine == null) return;
        ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        String current = Hawk.get(HawkConfig.API_URL, "");
        boolean showLine = HistoryHelper.isApiLineUrl(current);
        if (llApiLine != null) {
            // 新版等分网格需要每个格子都有内容，避免 GONE 造成空格子。
            llApiLine.setVisibility(View.VISIBLE);
        }
        updateApiRowWeight(showLine);
        String lineName = "";
        if (showLine) {
            for (String apiLine : apiLines) {
                if (current.equals(HistoryHelper.getApiLineUrl(apiLine))) {
                    lineName = HistoryHelper.getApiLineName(apiLine);
                    break;
                }
            }
        }
        tvApiLine.setText(lineName);
    }

    private void refreshDanmuApiText() {
        if (tvDanmuApiText == null) return;
        if (DanmakuApi.isUseDefault()) {
            tvDanmuApiText.setText("默认");
            return;
        }
        String custom = Hawk.get(HawkConfig.DANMU_API, "");
        if (!custom.isEmpty()) {
            tvDanmuApiText.setText("自定义");
            return;
        }
        String config = ApiConfig.get().getDanmaku();
        tvDanmuApiText.setText(config.isEmpty() ? "默认" : "接口");
    }

    private void updateApiRowWeight(boolean showLine) {
        // 新版左右双栏等分网格：行宽由 layout_weight 固定，不能再用旧版动态权重，
        // 否则会把 FrameLayout.LayoutParams 强转成 LinearLayout.LayoutParams 导致崩溃。
    }

    private void restartApp() {
        if (mContext == null) return;
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            System.exit(0);
        }
    }

    private void onClickIjkCachePlay(View v) {
        FastClickCheckUtil.check(v);
        Hawk.put(HawkConfig.IJK_CACHE_PLAY, !Hawk.get(HawkConfig.IJK_CACHE_PLAY, false));
        tvIjkCachePlay.setText(Hawk.get(HawkConfig.IJK_CACHE_PLAY, false) ? "开启" : "关闭");
    }

    private void openLocalConfig(boolean live) {
        selectLocalLive = live;
        if (!XXPermissions.isGranted(mContext, Permission.Group.STORAGE)) {
            Toast.makeText(getContext(), "请选择文件前需要先授予存储权限", Toast.LENGTH_SHORT).show();
            XXPermissions.with(mActivity)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(List<String> permissions, boolean all) {
                            if (all) {
                                Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                                openLocalFileActivity(selectLocalLive);
                            }
                        }

                        @Override
                        public void onDenied(List<String> permissions, boolean never) {
                            if (never) {
                                Toast.makeText(getContext(), "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                XXPermissions.startPermissionActivity(mActivity, permissions);
                            } else {
                                Toast.makeText(getContext(), "获取存储权限失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return;
        }
        openLocalFileActivity(live);
    }

    private void openLocalFileActivity(boolean live) {
        Intent intent = new Intent(mContext, LocalFileActivity.class);
        intent.putExtra(LocalFileActivity.EXTRA_LIVE, live);
        startActivityForResult(intent, REQUEST_LOCAL_CONFIG);
    }

    /** 批量导入py接口源：申请存储权限后打开py多选页面 */
    private void openBatchImportPy() {
        if (!XXPermissions.isGranted(mContext, Permission.Group.STORAGE)) {
            Toast.makeText(getContext(), "选择py文件前需要先授予存储权限", Toast.LENGTH_SHORT).show();
            XXPermissions.with(mActivity)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(List<String> permissions, boolean all) {
                            if (all) startBatchImportPyPicker();
                        }

                        @Override
                        public void onDenied(List<String> permissions, boolean never) {
                            if (never) {
                                Toast.makeText(getContext(), "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                XXPermissions.startPermissionActivity(mActivity, permissions);
                            } else {
                                Toast.makeText(getContext(), "获取存储权限失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return;
        }
        startBatchImportPyPicker();
    }

    private void startBatchImportPyPicker() {
        Intent intent = new Intent(mContext, LocalFileActivity.class);
        intent.putExtra(LocalFileActivity.EXTRA_PY_MULTI, true);
        startActivityForResult(intent, REQUEST_BATCH_IMPORT_PY);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (ConfigFlow.handleActivityResult(this, requestCode, resultCode, data)) {
            return;
        }
        if (requestCode == REQUEST_BATCH_IMPORT_PY) {
            handleBatchImportPyResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_LOCAL_CONFIG || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        // 选择的是视频文件：同文件夹整列传给详情播放器播放（与多仓"浏览"入口一致）
        if (LocalVideoLauncher.tryStartLocalVideoFromResult(mActivity, data.getData())) {
            return;
        }
        // 本地直播源分流：txt/m3u/m3u8 且内容为直播格式时写入 LIVE_API_URL（与多仓/线路入口一致）
        if (LiveSourceLoader.trySetLocalLiveSource(mContext, data.getData())) {
            return;
        }
        String api = localConfigToApi(data.getData());
        if (api == null || api.isEmpty()) {
            Toast.makeText(getContext(), "读取本地配置失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (apiDialog != null) {
            apiDialog.setLocalApi(api, selectLocalLive);
        }
    }

    /**
     * 批量导入py结果：把选中的多个py打包成本地站点配置，写入配置地址并立即生效。
     * 与手工输入配置地址走同一条 onchange 链路（写历史、刷UI、重启加载）。
     */
    private void handleBatchImportPyResult(int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null || mContext == null) {
            return;
        }
        ArrayList<String> pyPaths = data.getStringArrayListExtra(LocalFileActivity.EXTRA_PY_PATHS);
        if (pyPaths == null || pyPaths.isEmpty()) {
            Toast.makeText(getContext(), "未选择py文件", Toast.LENGTH_SHORT).show();
            return;
        }
        String api = PySpiderLoader.batchPyListConfigToApi(mContext, pyPaths);
        if (api == null || api.isEmpty()) {
            Toast.makeText(getContext(), "批量导入失败", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getContext(), "已导入 " + pyPaths.size() + " 个py接口源，正在生效", Toast.LENGTH_SHORT).show();
        // 复用配置地址变更链路：写历史/清线路来源/刷UI/重启加载，一步到位
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        HistoryHelper.setApiHistory(api);
        Hawk.put(HawkConfig.API_URL, api);
        if (!HistoryHelper.isApiLineHistory(api)) {
            HistoryHelper.clearApiLineSource();
        }
        tvApi.setText(api);
        refreshApiLineText();
        if (apiDialog != null) {
            apiDialog.setLocalApi(api, false);
        }
        if (!oldApi.equals(api)) {
            restartAppAfterConfigChanged();
        }
    }

    private String localConfigToApi(Uri uri) {
        return ConfigFlow.localConfigToApi(mContext, uri);
    }

    private void onClickClearCache(View v) {
        FastClickCheckUtil.check(v);
        String cachePath = FileUtils.getCachePath();
        File cacheDir = new File(cachePath);
        new Thread(() -> {
            try {
                ApiConfig.get().clearSpiderCache();
                if(cacheDir.exists())FileUtils.cleanDirectory(cacheDir);
                FileUtils.clearSpiderCacheFiles();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (mActivity != null) {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            restartAppAfterCacheCleared();
                        }
                    });
                }
            }
        }).start();
    }

    public static SearchRemoteTvDialog loadingSearchRemoteTvDialog;
    public static List<String> remoteTvHostList;
    public static boolean foundRemoteTv;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        SettingActivity.callback = null;
    }

    String getHomeRecName(int type) {
        if (type == 1) {
            return "站点推荐";
        } else if (type == 2) {
            return "观看历史";
        } else {
            return "豆瓣热播";
        }
    }

    String getSearchView(int type) {
        if (type == 0) {
            return "文字列表";
        } else {
            return "缩略图";
        }
    }

    // ===================== 多仓配置/线路切换相关方法 =====================

    /**
     * 显示多仓配置对话框。
     * 与主界面「配置」按钮共用 {@link ConfigFlow}，两个入口的对话框、解析逻辑、
     * 权限申请、本地文件回填完全一致，差异只体现在下面四个宿主回调。
     */
    private void showStoreHouseDialog() {
        ConfigFlow.openStoreHouse(this);
    }

    // ===================== ConfigFlow.Host（主界面 UserFragment 实现的是同一套接口） =====================

    @Override
    public android.app.Activity getHostActivity() {
        return mActivity;
    }

    @Override
    public void startLocalFilePicker(int requestCode) {
        startActivityForResult(ConfigFlow.buildLocalFileIntent(mContext), requestCode);
    }

    @Override
    public void startPyMultiPicker(int requestCode) {
        Intent intent = new Intent(mContext, LocalFileActivity.class);
        intent.putExtra(LocalFileActivity.EXTRA_PY_MULTI, true);
        startActivityForResult(intent, requestCode);
    }

    @Override
    public void hideSysBar() {
        if (mActivity instanceof BaseActivity) {
            ((BaseActivity) mActivity).hideSysBar();
        }
        // ConfigFlow在多仓/线路对话框dismiss时会回调这里，
        // 顺带刷新"多仓地址"右侧文本（此前只在初始化时刷一次，关掉对话框后不更新）
        refreshStoreApiText();
    }

    @Override
    public void onConfigLoaded(String url, String name) {
        // 设置页返回上层即回到首页重新加载配置（与"配置地址"入口同一条成熟链路）
        restartAppAfterConfigChanged();
    }

    /**
     * 线路切换：与主界面「线路」按钮共用 {@link ConfigFlow}（内部即 LineSwitchDialog）。
     */
    private void showLineSwitchDialog() {
        ConfigFlow.openLineSwitch(this);
    }

    // ===================== 多仓数据存储辅助方法（兼容ysc的custom_store_house格式） =====================

    private ArrayList<MoreSourceBean> getApiHistoryList() {
        return ConfigRepository.getApiHistoryList();
    }

    private void saveApiHistoryList(ArrayList<MoreSourceBean> list) {
        ConfigRepository.saveApiHistoryList(list);
    }

    /**
     * 刷新配置多仓地址右侧的仓库名显示
     */
    private void refreshStoreApiText() {
        if (tvStoreApi == null) return;
        String storeName = Hawk.get(HawkConfig.STORE_API_NAME, "");
        if (storeName == null || storeName.isEmpty()) {
            // 尝试从已保存的仓库列表中查找选中仓库名
            try {
                String json = Hawk.get(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED, "");
                if (json != null && !json.isEmpty()) {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    MoreSourceBean selected = gson.fromJson(json, MoreSourceBean.class);
                    if (selected != null && selected.getSourceName() != null) {
                        storeName = selected.getSourceName();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        tvStoreApi.setText(storeName);
    }

    // ===================== 线路缓存读写方法（参照ysc zj0/x1缓存核心） =====================

    /**
     * 清除线路缓存（在MoreSourceDialog删除/清空时调用）
     */
    public static void clearAllLineCache(android.content.Context context) {
        ConfigRepository.clearAllLineCache(context);
    }

    // ===================== 直播源相关方法 =====================
    private void showLiveSourceDialog() {
        LiveSourceDialog dialog = new LiveSourceDialog(mActivity);
        // EventBus注册已移入LiveSourceDialog.show()/dismiss()（与MoreSourceDialog同款），
        // 宿主不再register；dismiss处的unregister保留，兜底按返回键取消时未走dismiss()的情况
        dialog.setOnListener(new LiveSourceDialog.OnListener() {
            @Override
            public void onAdd(LiveSourceBean bean) {
                Hawk.put(HawkConfig.LIVE_API_URL, bean.getSourceUrl());
                Toast.makeText(mContext, "直播源已保存", Toast.LENGTH_SHORT).show();
                refreshLiveApiText();
            }
        });
        dialog.setOnBranchSelectListener(new LiveSourceDialog.OnBranchSelectListener() {
            @Override
            public void onBranchSelected(String displayName) {
                if (tvLiveApiText != null) {
                    tvLiveApiText.setText(displayName);
                }
                refreshLiveApiText();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                ((BaseActivity) mActivity).hideSysBar();
                // LiveSourceDialog.dismiss()内部已反注册，这里再unregister会因
                // "Subscriber to unregister was not registered before"抛EventBusException。
                // 只作为按返回键等未走dismiss()路径的兜底，故容忍失败。
                try {
                    EventBus.getDefault().unregister(dialog);
                } catch (Exception ignored) {
                }
            }
        });
        dialog.show();
    }

    private void refreshLiveApiText() {
        if (tvLiveApiText == null) return;
        String liveName = "";
        com.google.gson.JsonArray livesGroups = Hawk.get(HawkConfig.LIVE_GROUP_LIST, new com.google.gson.JsonArray());
        if (livesGroups.size() > 0) {
            int idx = ApiConfig.getLiveGroupIndex();
            if (idx >= livesGroups.size()) idx = 0;
            com.google.gson.JsonObject obj = livesGroups.get(idx).getAsJsonObject();
            String entryName = obj.has("name") ? obj.get("name").getAsString().trim() : "";
            String storeName = getSelectedStoreHouseName();
            liveName = buildLiveSourceDisplayName(entryName, storeName, idx);
        }
        if (liveName.startsWith("线路") && livesGroups.size() <= 1) {
            String liveUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
            if (!liveUrl.isEmpty()) {
                ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
                for (String item : liveHistory) {
                    String itemUrl = HistoryHelper.getApiLineUrl(item);
                    if (liveUrl.equals(itemUrl)) {
                        String historyName = HistoryHelper.getApiLineName(item);
                        if (historyName != null && !historyName.isEmpty()) {
                            liveName = historyName;
                            break;
                        }
                    }
                }
            }
        }
        tvLiveApiText.setText(liveName);
    }

    private String buildLiveSourceDisplayName(String entryName, String storeName, int index) {
        if (entryName == null) entryName = "";
        if (storeName == null || storeName.isEmpty()) storeName = "";
        if (entryName.isEmpty() || entryName.startsWith("http") || entryName.startsWith("clan://")) {
            if (!storeName.isEmpty()) return storeName + "直播";
            return "线路" + (index + 1);
        }
        if (!storeName.isEmpty()) return entryName + "直播" + storeName;
        return entryName;
    }

    private String getSelectedStoreHouseName() {
        String name = Hawk.get(HawkConfig.STORE_API_NAME, "");
        if (name != null && !name.isEmpty()) return name;
        try {
            String json = Hawk.get(HawkConfig.CUSTOM_STORE_HOUSE_SELECTED, "");
            if (json != null && !json.isEmpty()) {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                MoreSourceBean selected = gson.fromJson(json, MoreSourceBean.class);
                if (selected != null && selected.getSourceName() != null) {
                    return selected.getSourceName();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            ArrayList<MoreSourceBean> apiHistory = getApiHistoryList();
            String currentApi = Hawk.get(HawkConfig.API_URL, "");
            for (MoreSourceBean bean : apiHistory) {
                if (currentApi.equals(bean.getSourceUrl()) && bean.getSourceName() != null && !bean.getSourceName().isEmpty()) {
                    return bean.getSourceName();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
