# ============================================================================
# TVBoxOS v2.0 修复：开启 R8 (minifyEnabled true) 后主界面点线路(切换数据源)闪退
#
# 崩溃现象：
#   java.lang.ClassCastException: androidx.base.xl0 cannot be cast to androidx.base.uk0
#       at androidx.base.hd0.show(SourceFile:6)
#       at androidx.base.ve1.onClick(SourceFile:21)
#   （xl0/uk0/hd0/ve1 均为 R8 混淆重打包后的类名，关闭 R8 一切正常）
#
# 根因说明（三类敏感面叠加，R8 收缩/优化/类合并破坏反射与泛型契约）：
#   1) CymChad BaseRecyclerViewAdapterHelper 通过“泛型签名反射”实例化 ViewHolder，
#      R8 收缩后 View 构造函数被删、泛型签名丢失 → 库内兜底 new BaseViewHolder →
#      适配器桥接方法中的强转 (XxxViewHolder) 抛 ClassCastException；
#      本项目 UserFragment.homeHotVodAdapter、SettingActivity.sortAdapter 均在用
#   2) EventBus / Gson / Hawk 全部基于注解+反射工作，
#      R8 需要显式保留注解、@Subscribe 方法与泛型签名，否则订阅失效/持久化读回类型丢失
#   3) R8 类合并把事件/回调/ViewHolder 等小类合并后，泛型强转点直接失效
#
# 本文件只追加“保留规则”，与 proguard-rules.pro / proguard-python.pro /
# proguard-rules-drive.pro 可安全叠加，规则重复无副作用。
# ============================================================================

# —— 保留泛型签名 / 内部类 / 注解等反射必需元数据 ——
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# —— CymChad BaseRecyclerViewAdapterHelper（反射创建 ViewHolder，R8 下典型 CCE 来源）——
-keep class com.chad.library.adapter.base.** { *; }
-keep class * extends com.chad.library.adapter.base.BaseQuickAdapter { *; }
-keep class * extends com.chad.library.adapter.base.BaseViewHolder { *; }

# —— EventBus：保留 @Subscribe 订阅方法（推送/刷新事件链路）——
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep class org.greenrobot.eventbus.** { *; }
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-keep class com.github.tvbox.osc.event.** { *; }

# —— Gson / Hawk（Hawk 持久化基于 Gson 反射，R8 下类型签名丢失会毁掉数据读回）——
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.orhanobut.hawk.** { *; }

# —— 本应用 对话框 / 适配器 / Bean / 基类：全成员保留，防类合并与强转失效 ——
-keep class com.github.tvbox.osc.ui.dialog.** { *; }
-keep class * extends com.github.tvbox.osc.ui.dialog.BaseDialog { *; }
-keep class * extends android.app.Dialog { *; }
-keep class com.github.tvbox.osc.ui.adapter.** { *; }
-keep class * extends androidx.recyclerview.widget.RecyclerView$Adapter { *; }
-keep class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder { *; }
-keep class com.github.tvbox.osc.bean.** { *; }
-keep class com.github.tvbox.osc.base.** { *; }

# —— 播放器模块（dkplayer 内部存在反射调用，保留以防连带问题）——
# 注意：您工程原规则只保留了旧包名 com.dueeeke.videoplayer，
#       而代码已在用新包名 xyz.doikki（BaseDialog 里 CutoutUtil 就是），此处补上
-keep class xyz.doikki.** { *; }

# —— Python 模块（您工程原规则中此段被注释；chaquopy 需按类名反射，保留防连带问题）——
-keep class com.undcover.freedom.pyramid.** { *; }
-dontwarn com.undcover.freedom.pyramid.**
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# —— quickjs 引擎（您工程已保 com.whl.quickjs，两个包名都保上更稳妥）——
-keep class com.github.tvbox.quickjs.** {*;}

# —— EventBus 事件类（您工程只保了 @Subscribe 方法，事件类本身可被类合并）——
# com.github.tvbox.osc.event.** 已在上方保留，此处为防事件类放在其他包名的兜底
-keep class * extends org.greenrobot.eventbus.util.ThrowableFailureEvent { *; }

# ============================================================================
# v2.1 追加：BouncyCastle 加密库（类名固定 + 反序列化撞名根除）
#
# 背景：Hawk 持久化列表时会连同每个元素的“运行时类名”一起存盘。
# 开启 R8 的早期构建把 MoreSourceBean 混淆成了 androidx.base.xl0 之类的短名写入磁盘；
# 换构建后 R8 把这个名字重新分配给了别的类（例如 BC 的 NHPrivateKeyParameters），
# 读取时 Hawk 按旧类名 Class.forName 实例化出无关对象，
# LineSwitchDialog 遍历强转 MoreSourceBean 即 ClassCastException 闪退。
# v2.0 的 keep 规则只能保证“以后写入的名字稳定”，治不了已写盘的旧坏数据
# （旧数据已由 v2.1 代码层的启动清洗 + 读取端过滤自愈解决）。
# 这里再补一层保险：固定 BC 类名，防止任何旧混淆名在新构建里撞到 BC 类上。
# -keepnames：仅锁定名字，未用到的类仍可被收缩，兼顾包体
# ============================================================================
-keepnames class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
