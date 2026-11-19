package com.github.tvbox.osc.bean;

import java.util.List;

/**
 * 多仓数据模型（参照ysc MoreSourceBean，继承BaseItem）
 * 继承BaseItem后支持DiffUtil的getUniKey去重对比
 */
public class MoreSourceBean extends BaseItem {
    private boolean isLocalPackage;
    private boolean isSelected;
    private List<MoreSourceBean> localLineUrls;
    private boolean showDelete;
    private String sourceName = "";
    private String sourceUrl = "";

    public List<MoreSourceBean> getLocalLineUrls() {
        return this.localLineUrls;
    }

    public void setLocalLineUrls(List<MoreSourceBean> localLineUrls) {
        this.localLineUrls = localLineUrls;
    }

    public boolean isLocalPackage() {
        return this.isLocalPackage;
    }

    public void setLocalPackage(boolean localPackage) {
        this.isLocalPackage = localPackage;
    }

    public boolean isSelected() {
        return this.isSelected;
    }

    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }

    public boolean getShowDelete() {
        return this.showDelete;
    }

    public void setShowDelete(boolean showDelete) {
        this.showDelete = showDelete;
    }

    public String getSourceName() {
        return this.sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName != null ? sourceName : "";
    }

    public String getSourceUrl() {
        return this.sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl != null ? sourceUrl : "";
    }

    /**
     * 唯一标识（参照ysc MoreSourceBean.getUniKey）
     * 用于DiffUtil对比、LinkedHashMap去重、contains判断。
     * url是唯一身份、name只是显示名：以前拼(url+name).hashCode()，
     * 同一url换个名字就被当成新项，多仓/线路重复入库
     */
    @Override
    public String getUniKey() {
        return this.sourceUrl == null ? "" : this.sourceUrl;
    }
}
