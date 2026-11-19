# ===== drive_module consumer rules =====

# 模块数据 bean（Gson 反序列化 / Serializable / Room 实体）
-keep class com.github.tvbox.osc.drive.bean.** { *; }
-keep class com.github.tvbox.osc.drive.cache.** { *; }
-keep class com.github.tvbox.osc.drive.data.** { *; }
-keep class com.github.tvbox.osc.drive.localvideo.bean.** { *; }

# SMBJ（SMB 2/3，内部有 ServiceLoader 与反射）
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# JCIFS-NG（SMB 1/CIFS）
-keep class jcifs.** { *; }
-keep class eu.agno3.** { *; }
-dontwarn jcifs.**
-dontwarn eu.agno3.**

# Sardine（WebDAV）
-dontwarn com.thegrizzlylabs.sardineandroid.**

# Commons Compress / XZ（ZIP/7Z 解压）
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**
