package dev.agenvas.provider.domain;

/** Mock Provider 的可选确定性结果，用于覆盖成功、明确失败和提交歧义路径。 */
public enum MockFixture {
    /** 返回本地合成媒体并同步报告完成。 */
    SUCCESS,
    /** 模拟 Provider 明确拒绝或生成失败。 */
    FAILURE,
    /** 模拟提交已发出但本地无法确定是否受理。 */
    UNKNOWN
}
