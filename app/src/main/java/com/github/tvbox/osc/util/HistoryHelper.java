package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public class HistoryHelper {
    private static final Integer[] hisNumArray = {30,50,100};
    private static final String API_LINE_SPLIT = "\t";

    public static String getHistoryNumName(int index){
        Integer value = getHisNum(index);
        return value + "条";
    }

    public static int getHisNum(int index){
        Integer value = null;
        if(index>=0 && index < hisNumArray.length){
            value = hisNumArray[index];
        }else{
            value = hisNumArray[0];
        }
        return value;
    }

    public static void setSearchHistory(String title){
        // 读取历史记录
        ArrayList<String> history = Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
        history.remove(title);
        history.add(0, title);
        // 保证最多只保留 30 条，超过的就删除最后一条
        if (history.size() > 30) {
            history.remove(history.size() - 1);
        }
        Hawk.put(HawkConfig.SEARCH_HISTORY, history);
    }

    public static void clearSearchHistory(){
        Hawk.put(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
    }

    public static void setLiveApiHistory(String value){
        ArrayList<String> history = Hawk.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
        if (!history.contains(value)) {
            history.add(0, value);
        }
        if (history.size() > 30) {
            history.remove(30);
        }
        Hawk.put(HawkConfig.LIVE_API_HISTORY, history);
    }

    public static void setApiHistory(String value){
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (!history.contains(value)) {
            history.add(0, value);
        }
        if (history.size() > 30) {
            history.remove(30);
        }
        Hawk.put(HawkConfig.API_HISTORY, history);
    }

    /**
     * 切换线路时同步直播地址（"直播跟随配置"语义的唯一实现）：
     * 仅当当前直播地址为空、或本就跟随旧配置地址（非用户手工设置）时才跟随切换；
     * 用户在直播源对话框手工添加/推送的直播源（LIVE_API_URL独立配置）不被覆盖。
     */
    public static void followLiveApiOnLineSwitch(String newApiUrl, String oldApiUrl) {
        String liveApiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
        if (liveApiUrl == null || liveApiUrl.trim().isEmpty() || liveApiUrl.equals(oldApiUrl)) {
            Hawk.put(HawkConfig.LIVE_API_URL, newApiUrl);
            setLiveApiHistory(newApiUrl);
        }
    }

    /**
     * 把一条线路合并进 API_LINE_LIST：按url去重（同url旧条目丢弃、名字以新传入为准）、
     * 新线路置顶。是"合并非覆盖"——用户手工添加的线路条目保留不动。
     */
    public static void mergeApiLineList(String lineName, String lineUrl) {
        if (lineUrl == null || lineUrl.trim().isEmpty()) return;
        String newLine = buildApiLine(lineName, lineUrl);
        String newUrl = getApiLineUrl(newLine);
        ArrayList<String> merged = new ArrayList<>();
        merged.add(newLine);
        ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        for (String line : apiLines) {
            if (newUrl.equals(getApiLineUrl(line))) continue;
            merged.add(line);
        }
        Hawk.put(HawkConfig.API_LINE_LIST, merged);
    }

    /**
     * 把一组线路合并进 API_LINE_LIST（按url去重、保留已有条目不覆盖），
     * 线路列表整体替换前（仓库展开）必须走这里，防止手工添加的线路丢失。
     *
     * @return 合并后的完整列表（含新线路置顶）
     */
    public static ArrayList<String> mergeApiLineListAll(ArrayList<String> newLines) {
        ArrayList<String> existing = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        LinkedHashMap<String, String> byUrl = new LinkedHashMap<>();
        for (String line : newLines) {
            byUrl.put(getApiLineUrl(line), line);
        }
        for (String line : existing) {
            String url = getApiLineUrl(line);
            if (!byUrl.containsKey(url)) {
                byUrl.put(url, line);
            }
        }
        ArrayList<String> merged = new ArrayList<>(byUrl.values());
        Hawk.put(HawkConfig.API_LINE_LIST, merged);
        return merged;
    }

    public static String buildApiLine(String name, String url) {
        String lineName = name == null ? "" : name.trim();
        String lineUrl = url == null ? "" : url.trim();
        if (lineName.isEmpty()) {
            lineName = lineUrl;
        }
        return lineName + API_LINE_SPLIT + lineUrl;
    }

    public static String getApiLineName(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String name = splitIndex >= 0 ? value.substring(0, splitIndex) : value;
        return name.trim();
    }

    public static String getApiLineUrl(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String url = splitIndex >= 0 ? value.substring(splitIndex + API_LINE_SPLIT.length()) : value;
        return url.trim();
    }

    public static boolean isApiLineUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String trimUrl = url.trim();
        ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        for (String apiLine : apiLines) {
            if (trimUrl.equals(getApiLineUrl(apiLine))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isApiLineSource(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String source = Hawk.get(HawkConfig.API_LINE_SOURCE, "");
        return url.trim().equals(source);
    }

    public static boolean isApiLineHistory(String url) {
        return isApiLineSource(url) || isApiLineUrl(url);
    }

    public static void clearApiLineList() {
        Hawk.put(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        Hawk.put(HawkConfig.API_LINE_SOURCE, "");
    }

    /**
     * 只清线路来源标记（切换到非线路配置时用）：API_LINE_LIST保留——
     * clearApiLineList会把用户手工添加的线路一并清空
     */
    public static void clearApiLineSource() {
        Hawk.put(HawkConfig.API_LINE_SOURCE, "");
    }
}
