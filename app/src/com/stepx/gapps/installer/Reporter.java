package com.stepx.gapps.installer;

/** 执行过程往哪儿写。UI 实现它，测试可以换成打印到 logcat 的实现。 */
interface Reporter {

    int INFO = 0;
    int OK = 1;
    int WARN = 2;
    int BAD = 3;
    int STEP = 4;

    void line(String s, int level);
}
