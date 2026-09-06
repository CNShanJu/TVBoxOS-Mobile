package com.github.tvbox.osc.update;

/**
 * 一次更新检查得到的结果(不可变)。
 * <p>
 * 不同 {@link Updater} 实现均可产出该模型,UI 只依赖它渲染版本与下载信息,
 * 便于后续按配置切换到其他更新实现。
 */
public final class UpdateInfo {

    /** 新版本号(展示名,如 "3.2.0";由实现从远端信息解析) */
    public final String versionName;

    /** 原始版本标识(如 GitHub tag "v3.2.0";可为空) */
    public final String versionTag;

    /** 版本号 code;实现未知时填 -1 */
    public final int versionCode;

    /** APK 下载地址 */
    public final String downloadUrl;

    /** APK 文件名(用于本地落盘与展示;可为空) */
    public final String apkName;

    /** APK 大小(字节);未知填 -1 */
    public final long apkSize;

    /** 更新说明(可为空) */
    public final String releaseNote;

    public UpdateInfo(String versionName, String versionTag, int versionCode,
                      String downloadUrl, String apkName, long apkSize, String releaseNote) {
        this.versionName = versionName;
        this.versionTag = versionTag;
        this.versionCode = versionCode;
        this.downloadUrl = downloadUrl;
        this.apkName = apkName;
        this.apkSize = apkSize;
        this.releaseNote = releaseNote;
    }
}
