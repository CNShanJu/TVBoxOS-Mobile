package com.github.tvbox.osc.util;

/**
 * 直播播放器内核类型纯映射(自 LivePlayerManager 抽取,等价搬移):
 * 详情/设置弹窗的"内核索引"(0..3)↔ (内核 pl, 软/硬解码) 双向转换。
 * <ul>
 *   <li>pl 0=系统 1=ijk 2=Exo(与 PlayConfig/PlayerHelper 语义一致)</li>
 *   <li>ijk 下解码:硬解码/软解码</li>
 * </ul>
 * 无 Android/JSON 依赖,可 JVM 单测。
 */
public final class LivePlayerTypes {

    public static final int PLAYER_SYSTEM = 0;
    public static final int PLAYER_IJK = 1;
    public static final int PLAYER_EXO = 2;

    private LivePlayerTypes() {
    }

    /** 内核索引(设置弹窗用):0=系统,1=ijk硬,2=ijk软,3=Exo */
    public static int typeIndex(int playerType, String ijkCodec) {
        switch (playerType) {
            case PLAYER_SYSTEM:
                return 0;
            case PLAYER_IJK:
                return "硬解码".equals(ijkCodec) ? 1 : 2;
            case PLAYER_EXO:
                return 3;
            default:
                return 0;
        }
    }

    /** 由内核索引回填 pl 与解码方式 */
    public static int playerTypeOf(int index) {
        switch (index) {
            case 1:
            case 2:
                return PLAYER_IJK;
            case 3:
                return PLAYER_EXO;
            default:
                return PLAYER_SYSTEM;
        }
    }

    public static String ijkCodecOf(int index) {
        switch (index) {
            case 1:
                return "硬解码";
            default:
                return "软解码"; // 0(系统)/2(ijk软)/3(Exo) 统一软
        }
    }
}
