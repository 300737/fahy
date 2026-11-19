package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.player.ExoMediaPlayerFactory;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.MpvPlayer;
import com.github.tvbox.osc.player.thirdparty.NovaPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.player.thirdparty.VlcPlayer;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;

import tv.danmaku.ijk.media.player.IjkLibLoader;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        updateCfg(videoView,playerCfg,-1);
    }
    public static void updateCfg(VideoView videoView, JSONObject playerCfg,int forcePlayerType) {
        int playerType = Hawk.get(HawkConfig.PLAY_TYPE, 2);
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, 0);
        String ijkCode = Hawk.get(HawkConfig.IJK_CODEC, "硬解码");
        int scale = Hawk.get(HawkConfig.PLAY_SCALE, 0);
        try {
            playerType = playerCfg.getInt("pl");
            renderType = playerCfg.getInt("pr");
            ijkCode = playerCfg.getString("ijk");
            scale = playerCfg.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if(forcePlayerType>=0)playerType = forcePlayerType;
        // 双 media3 版本策略：
        // - 1.4.1 兼容版(LEGACY_MEDIA3=true)：原生 minSdk19，EXO 在安卓5/6 上直接可用，不做回退。
        // - 1.11.1 默认版(LEGACY_MEDIA3=false)：音频链路无条件 new AudioTrack.Builder()/PlaybackParams
        //   （均 API23+），API21/22 会 NoClassDefFoundError 导致 EXO 一播放就闪退，故 API<23 时
        //   把 EXO(2/3) 自动回退到内置 IJK(1)；API>=23 完全保持原逻辑。
        boolean exoFallbackLogged = false;
        if (!BuildConfig.LEGACY_MEDIA3 && Build.VERSION.SDK_INT < 23 && (playerType == 2 || playerType == 3)) {
            playerType = 1;
            exoFallbackLogged = true;
        }
        if (exoFallbackLogged) {
            LOG.i("player fallback to IJK: sdk=" + Build.VERSION.SDK_INT + " (EXO 1.11.1 needs API23+)");
        }
        IJKCode codec = ApiConfig.get().getIJKCodec(ijkCode);
        PlayerFactory playerFactory;
        if (playerType == 1) {
            playerFactory = new PlayerFactory<IjkMediaPlayer>() {
                @Override
                public IjkMediaPlayer createPlayer(Context context) {
                    return new IjkMediaPlayer(context, codec);
                }
            };
            try {
                tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                    @Override
                    public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                        try {
                            System.loadLibrary(s);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (playerType == 2 || playerType == 3) {
            // 播放器只有 EXO；硬解/软解由独立的解码设置决定（3 为旧版软解配置，兼容迁移）
            boolean soft = isSoftwareDecode(getExoDecode(playerCfg));
            if (playerType == 3 && (playerCfg == null || !playerCfg.has("exo"))) soft = true;
            playerFactory = ExoMediaPlayerFactory.create(soft);
        } else {
            playerFactory = ExoMediaPlayerFactory.create(false);
        }
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        if(videoView!=null){
            videoView.setPlayerFactory(playerFactory);
            videoView.setRenderViewFactory(renderViewFactory);
            videoView.setScreenScaleType(scale);
        }
    }

    public static void updateCfg(VideoView videoView) {
        int playType = Hawk.get(HawkConfig.PLAY_TYPE, 2);
        PlayerFactory playerFactory;
        if (playType == 1) {
            playerFactory = new PlayerFactory<IjkMediaPlayer>() {
                @Override
                public IjkMediaPlayer createPlayer(Context context) {
                    return new IjkMediaPlayer(context, null);
                }
            };
            try {
                tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                    @Override
                    public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                        try {
                            System.loadLibrary(s);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (playType == 2 || playType == 3) {
            // 播放器只有 EXO；3 为旧版软解配置，兼容迁移
            boolean soft = playType == 3 || isSoftwareDecode(Hawk.get(HawkConfig.EXO_CODEC, Hawk.get(HawkConfig.IJK_CODEC, "硬解码")));
            playerFactory = ExoMediaPlayerFactory.create(soft);
        } else {
            playerFactory = ExoMediaPlayerFactory.create(false);
        }
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, 0);
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
    }


    public static void init() {
        try {
            tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                @Override
                public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                    try {
                        System.loadLibrary(s);
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 解码方式：软解码 / 硬解码。播放器与解码方式互相独立。 */
    public static boolean isSoftwareDecode(String codec) {
        return codec != null && codec.contains("软");
    }

    public static String getDecodeName(boolean soft) {
        return soft ? "软解码" : "硬解码";
    }

    /** 兼容旧配置：没有 exo 字段时沿用 IJK 解码设置。 */
    public static String getExoDecode(JSONObject playerCfg) {
        if (playerCfg != null && playerCfg.has("exo")) {
            return playerCfg.optString("exo", "硬解码");
        }
        if (playerCfg != null && playerCfg.has("ijk")) {
            return playerCfg.optString("ijk", "硬解码");
        }
        return Hawk.get(HawkConfig.EXO_CODEC, Hawk.get(HawkConfig.IJK_CODEC, "硬解码"));
    }

    public static String getPlayerName(int playType) {
        HashMap<Integer, String> playersInfo = getPlayersInfo();
        if (playersInfo.containsKey(playType)) {
            return playersInfo.get(playType);
        } else {
            return "EXO播放器";
        }
    }

    private static HashMap<Integer, String> mPlayersInfo = null;
    public static HashMap<Integer, String> getPlayersInfo() {
        if (mPlayersInfo == null) {
            HashMap<Integer, String> playersInfo = new HashMap<>();
            playersInfo.put(1, "IJK播放器");
            playersInfo.put(2, "EXO播放器");
            playersInfo.put(10, "MX播放器");
            playersInfo.put(11, "Reex播放器");
            playersInfo.put(12, "Kodi播放器");
            playersInfo.put(13, "附近TVBox");
            playersInfo.put(14, "VLC播放器");
            playersInfo.put(15, "MPV播放器");
            playersInfo.put(16, "Nova播放器");
            mPlayersInfo = playersInfo;
        }
        return mPlayersInfo;
    }

    private static HashMap<Integer, Boolean> mPlayersExistInfo = null;
    public static HashMap<Integer, Boolean> getPlayersExistInfo() {
        if (mPlayersExistInfo == null) {
            HashMap<Integer, Boolean> playersExist = new HashMap<>();
            playersExist.put(1, true);
            playersExist.put(2, true);
            playersExist.put(10, MXPlayer.getPackageInfo() != null);
            playersExist.put(11, ReexPlayer.getPackageInfo() != null);
            playersExist.put(12, Kodi.getPackageInfo() != null);
            playersExist.put(13, RemoteTVBox.getAvalible() != null);
            playersExist.put(14, VlcPlayer.getPackageInfo() != null);
            playersExist.put(15, MpvPlayer.getPackageInfo() != null);
            playersExist.put(16, NovaPlayer.getPackageInfo() != null);
            mPlayersExistInfo = playersExist;
        }
        return mPlayersExistInfo;
    }

    public static Boolean getPlayerExist(int playType) {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.get(playType);
        } else {
            return false;
        }
    }

    public static ArrayList<Integer> getExistPlayerTypes() {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        ArrayList<Integer> existPlayers = new ArrayList<>();
        // 固定顺序：IJK -> EXO -> 第三方播放器。解码方式不在播放器列表里。
        int[] orderedTypes = {1, 2, 10, 11, 12, 13, 14, 15, 16};
        for (int playerType : orderedTypes) {
            if (Boolean.TRUE.equals(playersExistInfo.get(playerType))) {
                existPlayers.add(playerType);
            }
        }
        return existPlayers;
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers) {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers);
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        boolean callResult = false;
        switch (playerType) {
            case 10: {
                callResult = MXPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 11: {
                callResult = ReexPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 12: {
                callResult = Kodi.run(activity, url, title, subtitle, headers);
                break;
            }
            case 13: {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers);
                break;
            }
            case 14: {
                callResult = VlcPlayer.run(activity, url, title, subtitle, progress);
                break;
            }
            case 15: {
                callResult = MpvPlayer.run(activity, url, title, subtitle, progress);
                break;
            }
            case 16: {
                callResult = NovaPlayer.run(activity, url, title, subtitle, headers, progress);
                break;
            }
        }
        return callResult;
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    public static String getScaleName(int screenScaleType) {
        String scaleText = "默认";
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_DEFAULT:
                scaleText = "默认";
                break;
            case VideoView.SCREEN_SCALE_16_9:
                scaleText = "16:9";
                break;
            case VideoView.SCREEN_SCALE_4_3:
                scaleText = "4:3";
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                scaleText = "填充";
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                scaleText = "原始";
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                scaleText = "裁剪";
                break;
        }
        return scaleText;
    }

    public static String getDisplaySpeed(long speed,boolean show) {
        if(speed > 1048576)
            return new DecimalFormat("#.00").format(speed / 1048576d) + "Mb/s";
        else if(speed > 1024)
            return (speed / 1024) + "Kb/s";
        else
            return speed > 0?speed + "B/s":(show?"0B/s":"");
    }
    public static String getDisplaySpeedBps(long speed, boolean show) {
        long bitSpeed = speed * 8; // 字节转比特
        if (bitSpeed >= 1_000_000_000) {
            return new DecimalFormat("0.00").format(bitSpeed / 1_000_000_000d) + "Gbps";
        } else if (bitSpeed >= 1_000_000) {
            return new DecimalFormat("0.0").format(bitSpeed / 1_000_000d) + "Mbps";
        } else if (bitSpeed >= 1_000) {
            return new DecimalFormat("0.0").format(bitSpeed / 1_000d) + "Kbps";
        } else {
            return bitSpeed > 0 ? bitSpeed + "bps" : (show ? "0bps" : "");
        }
    }
}
