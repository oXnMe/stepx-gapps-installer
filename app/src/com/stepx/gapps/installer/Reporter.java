package com.stepx.gapps.installer;

/** 执行过程往哪儿写。UI 实现它，测试可以换成打印到 logcat 的实现。 */
interface Reporter {

    int INFO = 0;
    int OK = 1;
    int WARN = 2;
    int BAD = 3;
    int STEP = 4;

    void line(String s, int level);

    /**
     * 同一行原地刷新 —— 下载进度用。
     *
     * 逐块进度走 {@link #line} 会把活动区刷成几十行，刷完只剩下最后一条，
     * 用户既看不到总量也看不到速度；走这里则是同一行反复改写，行数不变。
     *
     * @param key   同一路进度用同一个 key（例如 "dl:GMS"），不同的 key 各占一行
     * @param s     新的整行文本；传 null 表示这一路结束，把该行撤掉
     * @param level 颜色级别
     */
    default void tick(String key, String s, int level) {
        if (s != null) line(s, level);
    }
}
