package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;

    public void init(VideoView videoView) {
        try {
            // Hawk 可能因历史遗留把 LIVE_PLAY_TYPE / PLAY_TYPE 存成 String，
            // 直接强转 Integer 会 ClassCastException 闪退。统一用 parseInt 兜底。
            int savedPlayerType = safeGetPlayerType(HawkConfig.LIVE_PLAY_TYPE, 2);
            if (savedPlayerType == 0) savedPlayerType = 2;
            // 旧版 pl=3 表示 EXO软解，迁移为 EXO播放器(2) + 软解码
            if (savedPlayerType == 3) {
                savedPlayerType = 2;
                Hawk.put(HawkConfig.LIVE_PLAY_TYPE, 2);
                Hawk.put(HawkConfig.EXO_CODEC, "软解码");
            }
            defaultPlayerConfig.put("pl", savedPlayerType);
            defaultPlayerConfig.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, "硬解码"));
            defaultPlayerConfig.put("exo", Hawk.get(HawkConfig.EXO_CODEC, Hawk.get(HawkConfig.IJK_CODEC, "硬解码")));
            defaultPlayerConfig.put("pr", safeGetInt(HawkConfig.PLAY_RENDER, 0));
            defaultPlayerConfig.put("sc", safeGetInt(HawkConfig.LIVE_PLAY_SCALE, 0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
    }

    private int safeGetPlayerType(String key, int defaultVal) {
        Object val = Hawk.get(key, null);
        if (val instanceof Integer) return (int) val;
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) { return defaultVal; }
        }
        // 如果 LIVE_PLAY_TYPE 没存过，尝试从 PLAY_TYPE 读
        if (key.equals(HawkConfig.LIVE_PLAY_TYPE)) {
            return safeGetPlayerType(HawkConfig.PLAY_TYPE, defaultVal);
        }
        return defaultVal;
    }

    private int safeGetInt(String key, int defaultVal) {
        Object val = Hawk.get(key, null);
        if (val instanceof Integer) return (int) val;
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig);
        try {
            currentPlayerConfig = new JSONObject(defaultPlayerConfig.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** 播放器索引：0=ijk，1=exo。与解码方式无关。 */
    public int getLivePlayerType() {
        int playerTypeIndex = 1;
        try {
            int playerType = currentPlayerConfig.getInt("pl");
            if (playerType == 1) {
                playerTypeIndex = 0;
            } else if (playerType == 2 || playerType == 3) {
                playerTypeIndex = 1;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return playerTypeIndex;
    }

    /** 解码索引：0=硬解，1=软解。与播放器无关。 */
    public int getLiveDecodeType() {
        try {
            String codec;
            if (currentPlayerConfig.getInt("pl") == 1) {
                codec = currentPlayerConfig.optString("ijk", "硬解码");
            } else {
                codec = PlayerHelper.getExoDecode(currentPlayerConfig);
            }
            return PlayerHelper.isSoftwareDecode(codec) ? 1 : 0;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int getLivePlayerScale() {
        try {
            return currentPlayerConfig.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }

    /** 只切换播放器：0=ijk，1=exo。不改解码方式。 */
    public void changeLivePlayerType(VideoView videoView, int playerType) {
        JSONObject playerConfig = currentPlayerConfig;
        try {
            playerConfig.put("pl", playerType == 0 ? 1 : 2);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        try {
            defaultPlayerConfig.put("pl", playerConfig.getInt("pl"));
            Hawk.put(HawkConfig.LIVE_PLAY_TYPE, playerConfig.getInt("pl"));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }

    /** 只切换解码方式：0=硬解，1=软解。不改播放器。 */
    public void changeLiveDecodeType(VideoView videoView, int decodeType) {
        JSONObject playerConfig = currentPlayerConfig;
        String codec = decodeType == 1 ? "软解码" : "硬解码";
        try {
            playerConfig.put("ijk", codec);
            playerConfig.put("exo", codec);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        try {
            defaultPlayerConfig.put("ijk", codec);
            defaultPlayerConfig.put("exo", codec);
            Hawk.put(HawkConfig.IJK_CODEC, codec);
            Hawk.put(HawkConfig.EXO_CODEC, codec);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }

    public boolean switchLivePlayer(VideoView videoView) {
        JSONObject playerConfig = currentPlayerConfig;
        if (playerConfig == null) {
            LOG.i("echo-liveSwitchPlayer: skip empty player config");
            return false;
        }
        try {
            int playerType = playerConfig.getInt("pl");
            // 播放器只在 IJK(1) / EXO(2) 之间切换，解码方式保持不变
            int switchPlayerType;
            if (playerType == 1) {
                switchPlayerType = 2;
            } else if (playerType == 2) {
                switchPlayerType = 1;
            } else {
                switchPlayerType = playerType;
            }
            if (switchPlayerType == playerType) {
                LOG.i("echo-liveSwitchPlayer: skip unsupported playerType=" + playerType);
                return false;
            }
            LOG.i("echo-liveSwitchPlayer: " + playerType + " -> " + switchPlayerType);
            playerConfig.put("pl", switchPlayerType);
        } catch (JSONException e) {
            LOG.i("echo-liveSwitchPlayer error: " + e.getMessage());
            return false;
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        currentPlayerConfig = playerConfig;
        return true;
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale){
        videoView.setScreenScaleType(playerScale);
        Hawk.put(HawkConfig.LIVE_PLAY_SCALE, playerScale);

        JSONObject playerConfig = currentPlayerConfig;
        try {
            playerConfig.put("sc", playerScale);
            defaultPlayerConfig.put("sc", playerScale);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }
}
