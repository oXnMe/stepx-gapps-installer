#!/system/bin/sh
# StepX GMS/Play 自愈 —— 无需电脑，无需 root，Shizuku/aShell/Termux 直接跑
#
# 用法：
#     sh /sdcard/stepx-fix.sh          # 检测到异常才动手
#     sh /sdcard/stepx-fix.sh force    # 无条件强制重注入
#     sh /sdcard/stepx-fix.sh cert     # 只查认证拦截页，不做任何修复（秒回）
#
# 能处理五类故障：
#   A. 注入的特权权限被权限重算清掉
#      Play 的 MANAGE_USERS / GMS 的 INTERACT_ACROSS_USERS 丢失
#      -> 重启 openclaw_env 触发 boot.py 重注入，约 80 秒
#   B. GMS 处于 AMS 幽灵态（进程已死，但 ProcessRecord 卡在旧 pid 上）
#      - 表现为 Play 里 GMS 起不来、报 "launching app became null"
#      - 处理：am crash（杀进程）+ force-stop（清 ProcessRecord）+ startservice 拉起
#      - 关键顺序：进程已死时 force-stop 才会清掉那条卡死的记录
#   C. GMS 活着但 binder 大面积 DEAD
#      - 表现为 Play 里点不动、安装无反应、账号区加载不出来
#      - 处理同 B：杀掉重来，权限是 PMS 内存态，重启进程不丢
#   D. 三件套被 StepOS 置成 enabled=2（重启后必现）
#      - lastDisabledCaller=android，是系统服务干的，不是用户或本脚本
#      - pm enable / pm disable-user 全部被拒：
#        "Shell cannot change component state for null to 1"
#        规则是「shell 只能改自己被禁用的包」，被 android 禁的 shell 无权回滚
#      - 唯一可靠的解法：同版本覆盖安装 pm install -r，装包会把启用态重置为 0
#   G. 前台服务类型权限缺失，下载服务起不来（2026-11-11 新增）
#      - 表象很有迷惑性：Play 能打开、货架和搜索都正常，只是**所有下载都失败**
#      - 判据：appops get com.android.vending ACTIVATE_VPN 不是 allow
#      - 根因：Play 53.9.x / GMS 26.4x 的 targetSdk 涨到 37，DownloadService 用
#        systemExempted 类型的前台服务，AOSP 要求
#          allOf [FOREGROUND_SERVICE_SYSTEM_EXEMPTED]（已有）
#          anyOf [SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, ACTIVATE_VPN]
#        而两个 manifest 都没声明前两个，只剩 appop 这一条，默认 ignore
#      - 后果：startForeground 抛 SecurityException，:background 进程反复重启
#      - 修法：appops set <pkg> ACTIVATE_VPN allow（本脚本自动做，无需重注入）
#
# 还有一类本脚本「只检测、不修」的（2026-11-03 补）：
#   E. 一打开 Play 就是「此设备未获得 Play 保护机制认证」整页拦截
#      - 归 Play 自己的 flag 管，不是权限问题，重注入对它无效
#      - 判据：topResumedActivity 落在 gmscompliance.ui.UncertifiedDeviceActivity
#      - 修它要走 Google 兼容补丁的 play scope，见 README §8.8.10
#      - 单独跑 `sh /sdcard/stepx-fix.sh cert` 可以秒查，不用等 80 秒
#
# 注意：A 和 D 都会让包记录里的权限被重算，所以任何一次 install -r 之后
# 都必须重注入（本脚本已经自动串起来）。
#
# 注意：GMS / Play 一旦版本变化，注入态会被权限重算抹掉，Play 侧的认证补丁
# 也可能被服务端刷回。脚本在 /sdcard/stepx-fix.state 记 versionCode 账：
#   - 首次运行只记账，不触发（避免刚装完就白等 80 秒）
#   - 版本一变就按 force 处理
#   - 救不回来时不推进记录，下次运行继续强制
#   - 删掉该文件等于回到「首次运行」
# 退出码：0 正常 / 1 权限没救回 / 2 GMS 进程或 binder 没救回 /
#         3 包仍禁用 / 4 需重启 / 5 认证拦截页（E 类，不自动修）/
#         6 下载服务的 appop 没补上（G 类）
#
# 网关：127.0.0.1:18789 是 StepClaw exec 网关，只监听回环，uid 2000 即可访问。

TOKEN="695d652c1ac6bf6df7a1b4ddbbf79815ae672428b87cac9fce24f7d07292c00e"
API="http://127.0.0.1:18789/plugins/stepclaw-exec/run"
BODY='{"command":["python3","/usr/local/lib/stepx-gms/recover.py"],"timeoutMs":50000}'
GMS="com.google.android.gms"
PLAY="com.android.vending"
GSF="com.google.android.gsf"
# 兜底重装时 APK 的落脚点；可用 STEPX_APKS 覆盖。
# 必须在 /data/local/tmp 下：pm install 是 system_server 去读文件，
# 它读不了 FUSE 挂载的 /sdcard（avc denied read on u:object_r:fuse:s0）。
APKS="${STEPX_APKS:-/data/local/tmp/stepx-apks}"
# 版本记账：GMS / Play 的 versionCode。变了就说明包被更新过 —— PMS 会重算
# 权限（注入态丢失），Play 侧的认证补丁也可能被服务端按新版本刷回，两种都
# 与「权限看着还在」并不矛盾。文件放在 /sdcard 下，shell 可写。
STATE="${STEPX_STATE:-/sdcard/stepx-fix.state}"

# ---------------------------------- 检测 ----------------------------------

perm_play() { dumpsys package $PLAY 2>/dev/null | grep -q "MANAGE_USERS: granted=true"; }
perm_gms()  { dumpsys package $GMS  2>/dev/null | grep -q "INTERACT_ACROSS_USERS: granted=true"; }
gms_alive() { [ -n "$(pidof $GMS 2>/dev/null)" ]; }
play_alive(){ [ -n "$(pidof $PLAY 2>/dev/null)" ]; }

# enabled=0 默认 / 1 已启用 / 2 已禁用 / 3 用户禁用 / 4 用户禁用直到重启
pkg_enabled_state() {
    dumpsys package "$1" 2>/dev/null | grep -o 'enabled=[0-9]' | head -1 | cut -d= -f2
}
pkg_disabled() {
    S=$(pkg_enabled_state "$1")
    [ "$S" = "2" ] || [ "$S" = "3" ] || [ "$S" = "4" ]
}

gms_dead_links() {
    dumpsys activity processes 2>/dev/null \
        | grep "ConnectionRecord.*$GMS" \
        | grep -c "DEAD" 2>/dev/null
}
gms_conn_total() {
    dumpsys activity processes 2>/dev/null \
        | grep -c "ConnectionRecord.*$GMS" 2>/dev/null
}

# GMS >= 26.42.31 的 SingletonComponentRouterProvider 带 singleUser=true，
# attach 阶段会被 isSingleton() 的 checkUidPermission 打死。
# 注意：dumpsys 里 "INTERACT_ACROSS_USERS: granted=true" 满足不了这个检查（2026-10-31 实测）。
su_gate_broken() {
    logcat -d 2>/dev/null | grep -q "FLAG_SINGLE_USER"
}

# 认证拦截页：Play 的 UncertifiedDeviceActivity 是不是正在前台。
# 只看 "Resumed" 两个字段，不整份 grep —— task 快照里会留着这个类名的
# 历史记录，整份 grep 会把「曾经被拦过」误判成「现在正被拦」。
# 只反映当前前台：Play 没打开时一律判「不在」。
cert_activity() {
    dumpsys activity activities 2>/dev/null \
        | grep -E "topResumedActivity=|mResumedActivity:" \
        | grep -o "[A-Za-z0-9_.]*UncertifiedDeviceActivity" \
        | head -1
}

# 前台服务类型权限（2026-11-11 实测，Play 53.9.23 / GMS 26.43.30 起必需）
#
# 两个包都是 targetSdk=37。AOSP 对 targetSdk>=34 且使用 systemExempted 类型
# 的前台服务要求**两组同时满足**：
#     allOf  [FOREGROUND_SERVICE_SYSTEM_EXEMPTED]        ← normal，装机即授予，两个包都已有
#     anyOf  [SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, ACTIVATE_VPN appop]
# 而 Play / GMS 的 manifest 里**前两个一条都没声明**（requested permissions 里查不到），
# 只剩 ACTIVATE_VPN 这个 appop 一条路，默认值又是 ignore。
#
# 后果不是「下载慢」，是 DownloadService.onStartCommand 里 startForeground 直接抛
# SecurityException，:background 进程三两秒重生一次，所有下载全部失败：
#     java.lang.SecurityException: Starting FGS with type systemExempted ...
#     at com.google.android.finsky.downloadservice.DownloadService.onStartCommand
# Play 主界面不崩，所以表象只是「能打开、装不上」，很容易误判成网络或认证问题。
fgs_op() {
    appops get "$1" ACTIVATE_VPN 2>/dev/null | grep -q "ACTIVATE_VPN: allow"
}
fix_fgs_op() {
    appops set "$1" ACTIVATE_VPN allow 2>/dev/null
}

# 「不受电池优化限制」。上游 manage.py 对 GMS / Play 两个包都设了这一条
# （cmd appops set <pkg> SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow），
# 理由是 StepOS 会冻结后台进程，DownloadService 正好跑在后台。
power_op() {
    appops get "$1" SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS 2>/dev/null \
        | grep -q "SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS: allow"
}
fix_power_op() {
    appops set "$1" SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow 2>/dev/null
}

# versionCode。dumpsys 里后面还有 split 各自的版本行，只取第一处。
pkg_ver() {
    dumpsys package "$1" 2>/dev/null \
        | grep -o "versionCode=[0-9]*" | head -1 | cut -d= -f2
}

# 读回上次记的版本，输出 "gms play"；没记过或格式不对就输出空。
state_ver() {
    [ -f "$STATE" ] || return 1
    sed -n 's/^versions=gms:\([0-9][0-9]*\) play:\([0-9][0-9]*\)$/\1 \2/p' "$STATE" 2>/dev/null | head -1
}
state_put() {
    echo "versions=gms:$1 play:$2" > "$STATE" 2>/dev/null
}

show() {
    if pkg_disabled $GSF; then echo "  GSF 启用  : 被禁用（$(pkg_enabled_state $GSF)）"; else echo "  GSF 启用  : 正常（$(pkg_enabled_state $GSF)）"; fi
    if pkg_disabled $GMS; then echo "  GMS 启用  : 被禁用（$(pkg_enabled_state $GMS)）"; else echo "  GMS 启用  : 正常（$(pkg_enabled_state $GMS)）"; fi
    if pkg_disabled $PLAY;then echo "  Play 启用 : 被禁用（$(pkg_enabled_state $PLAY)）"; else echo "  Play 启用 : 正常（$(pkg_enabled_state $PLAY)）"; fi
    if perm_play; then echo "  Play 权限 : 正常"; else echo "  Play 权限 : 缺失"; fi
    if perm_gms;  then echo "  GMS 权限  : 正常"; else echo "  GMS 权限  : 缺失"; fi
    if gms_alive; then echo "  GMS 进程  : 在（$(pidof $GMS)）"; else echo "  GMS 进程  : 不在"; fi
    if ! gms_alive && su_gate_broken; then
        echo "  GMS 单用户: 被拦（FLAG_SINGLE_USER，见 README §8.7）"
    fi
    if play_alive;then echo "  Play 进程 : 在（$(pidof $PLAY)）"; else echo "  Play 进程 : 不在"; fi
    if fgs_op $PLAY; then echo "  下载服务  : 正常（ACTIVATE_VPN allow）"; else echo "  下载服务  : 会被拦（ACTIVATE_VPN 不是 allow，下载必失败）"; fi
    CA=$(cert_activity)
    if [ -n "$CA" ]; then echo "  Play 认证 : 被拦截（$CA）"; else echo "  Play 认证 : 未被拦截"; fi
    D=$(gms_dead_links)
    T=$(gms_conn_total)
    if [ "$D" -gt 10 ] 2>/dev/null; then
        echo "  GMS 服务  : 异常（$D/$T 条 binder 已死）"
    else
        echo "  GMS 服务  : 正常（$D/$T 条 dead）"
    fi
}

# --------------------------------- 修复动作 --------------------------------

restart_play() {
    am force-stop $PLAY >/dev/null 2>&1
    sleep 3
    am start -n $PLAY/com.google.android.finsky.activities.MainActivity >/dev/null 2>&1
}

# D 类：StepOS 把包置成 enabled=2 之后，pm 的任何状态变更都被拒，
# 只有「重新装一遍」能把启用态重置成 0。
# 装的是设备上自己那份 base.apk + 全部 split：版本、签名天然一致，
# 不需要另存 APK 副本，也不会被 VERSION_DOWNGRADE 拦住。
# 先 force-stop 再装：让进程先干净退出，否则 AMS 会留一条卡在旧 pid 上的
# ProcessRecord，之后 GMS 再也拉不起来（连重启 system_server 都不一定救得回）。
fix_pkg() {
    PKG=$1; NAME=$2
    echo "  $NAME 被禁用（enabled=$(pkg_enabled_state $PKG)），回装自身 APK 重置"
    am force-stop "$PKG" >/dev/null 2>&1
    sleep 2

    set -- $(pm path "$PKG" 2>/dev/null | sed 's/package://')
    if [ $# -eq 0 ]; then
        echo "    pm path 为空 —— 包已经被卸载了，需要手工装回去"
        return 1
    fi

    OUT=$(pm install -r "$@" 2>&1 | tail -1)
    echo "    $OUT"
    if ! pkg_disabled "$PKG"; then
        return 0
    fi

    # 回装被签名校验拒绝：PMS 里记录的签名和 APK 对不上（实测重签/篡改过），
    # 这种情况只能把 APK 拷出来、卸载、再装一份新的。
    echo "    回装被拒（PMS 记录的签名与 APK 不符），改为 卸载 + 重装"
    D="$APKS/$NAME"
    rm -rf "$D"; mkdir -p "$D"
    for f in "$@"; do cp "$f" "$D/" 2>/dev/null; done
    if [ "$(ls "$D"/*.apk 2>/dev/null | wc -l)" -eq 0 ]; then
        echo "    拷贝 APK 失败"
        return 1
    fi

    pm uninstall "$PKG" >/dev/null 2>&1
    sleep 2
    # 卸载后 USB 安装白名单键会变成 *:0，不改成 1 会被 USER_RESTRICTED 挡住
    settings put secure "usb_install_item_$PKG" "$NAME:1" >/dev/null 2>&1

    set -- "$D"/*.apk
    OUT=$(pm install -r "$@" 2>&1 | tail -1)
    echo "    $OUT"
    if pkg_disabled "$PKG"; then
        echo "    仍然失败 —— 进 设置 → 应用 → $NAME → 启用 手工收尾"
        return 1
    fi
    return 0
}

fix_disabled() {
    echo ""
    echo "检测到包被 StepOS 禁用（开机自动写 enabled=2，lastDisabledCaller=android）"
    RC=0
    GSF_REDONE=0

    # 顺序固定：GSF 先（它定义 READ_GSERVICES），再 GMS，最后 Play
    if pkg_disabled $GSF; then
        fix_pkg $GSF GSF || RC=1
        GSF_REDONE=1
    fi
    pkg_disabled $GMS && { fix_pkg $GMS GMS || RC=1; }

    # GSF 一重装，Play 的 READ_GSERVICES 就掉成 false（授权跟着 GSF 的包记录重建），
    # Play 起来会在 ClassicApplication 初始化阶段直接崩（Failed to execute tasks
    # before Dagger）。所以只要动过 GSF，Play 就必须重装一次，哪怕它没被禁用。
    if [ $GSF_REDONE -eq 1 ] || pkg_disabled $PLAY; then
        fix_pkg $PLAY Play || RC=1
    fi
    return $RC
}

gms_restart() {
    echo "  1/4 结束 GMS 进程（am crash）"
    am crash $GMS >/dev/null 2>&1
    i=0
    while [ $i -lt 6 ]; do
        sleep 1; i=$((i + 1))
        gms_alive || break
    done
    if gms_alive; then
        echo "      am crash 没效果，改用 force-stop"
        am force-stop $GMS >/dev/null 2>&1
        sleep 2
    fi

    # 这一步是整套流程的关键：进程已死时 force-stop 才会清掉 AMS 里那条
    # 卡在旧 pid 上的 ProcessRecord，否则之后任何拉起都会被 "launching app
    # became null" / "is attached to a previous process" 挡住。
    echo "  2/4 清 AMS 幽灵记录（force-stop）"
    am force-stop $GMS >/dev/null 2>&1
    cmd activity force-stop $GMS >/dev/null 2>&1
    sleep 2

    # 不抬 bucket，后台 service 启动会被 "app is in background ... idle" 拦掉
    echo "  3/4 抬高 standby bucket"
    cmd activity set-standby-bucket $GMS active >/dev/null 2>&1

    echo "  4/4 拉起 GMS"
    am startservice -n $GMS/.ads.identifier.service.AdvertisingIdService >/dev/null 2>&1
    i=0
    while [ $i -lt 8 ]; do
        sleep 2; i=$((i + 1))
        gms_alive && break
    done

    if ! gms_alive; then
        echo "      仍未起来，让 Play 去 bind 它"
        restart_play
        i=0
        while [ $i -lt 10 ]; do
            sleep 2; i=$((i + 1))
            gms_alive && break
        done
    fi

    # GMS 换了新进程，Play 手里还是旧的 binder，必须让它重连
    [ -n "$(pidof $GMS)" ] && restart_play
}

recover_perm() {
    if ! curl -s -m 5 -o /dev/null "http://127.0.0.1:18789/" 2>/dev/null; then
        echo ""
        echo "错误：本机网关 127.0.0.1:18789 无响应。"
        echo "openclaw_env 可能已经不在了，这种情况只能重启手机。"
        return 1
    fi

    OLD_PID=$(pgrep -f openclaw_env 2>/dev/null | head -1)
    [ -z "$OLD_PID" ] && OLD_PID="none"
    echo ""
    echo "需要重注入（当前 PID: $OLD_PID）..."

    curl -s -m 60 -X POST "$API" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$BODY" >/dev/null 2>&1

    echo -n "等待进程树重启"
    i=0
    RESTARTED=0
    while [ $i -lt 15 ]; do
        sleep 3
        i=$((i + 1))
        NEW_PID=$(pgrep -f openclaw_env 2>/dev/null | head -1)
        if [ -n "$NEW_PID" ] && [ "$NEW_PID" != "$OLD_PID" ]; then
            RESTARTED=1
            echo ""
            echo "已重启（新 PID: $NEW_PID）"
            break
        fi
        printf "."
    done

    if [ $RESTARTED -eq 0 ]; then
        echo ""
        echo "进程树没有变化。请再跑一次；仍不行则重启手机。"
        return 1
    fi

    echo -n "注入中"
    i=0
    while [ $i -lt 16 ]; do
        sleep 5
        i=$((i + 1))
        printf "."
    done
    echo ""
    return 0
}

# ---------------------------------- 主流程 ---------------------------------

echo "StepX 自愈"
echo "=========="

FORCE=0
[ "$1" = "force" ] && FORCE=1

echo "当前状态："
show

# --- cert：只查认证拦截页，立刻返回，不碰任何状态 ---
if [ "$1" = "cert" ]; then
    echo ""
    A=$(cert_activity)
    if [ -n "$A" ]; then
        echo "在认证拦截页：$A"
        echo "→ 这是 Play 自己的 flag 在拦，不是权限问题，重注入对它无效。"
        echo "→ 重跑 Google 兼容补丁的 play scope（README §8.8.10），把"
        echo "  GmscoreCompliance__activities_to_block_on_uncertified_devices 清空。"
        exit 5
    fi
    echo "前台不是认证拦截页。"
    exit 0
fi

# --- 版本记账：GMS / Play 一更新就无条件强制重注入 ---
GV=$(pkg_ver $GMS)
PV=$(pkg_ver $PLAY)
PREV=$(state_ver)
if [ -z "$GV" ] || [ -z "$PV" ]; then
    echo ""
    echo "读不到 versionCode（dumpsys 异常），本次跳过版本记账。"
elif [ -z "$PREV" ]; then
    echo ""
    echo "首次运行：记下当前版本（GMS $GV / Play $PV），之后版本一变才自动强制。"
    state_put "$GV" "$PV"
else
    PREV_G=$(echo "$PREV" | cut -d' ' -f1)
    PREV_P=$(echo "$PREV" | cut -d' ' -f2)
    if [ "$PREV_G" != "$GV" ] || [ "$PREV_P" != "$PV" ]; then
        echo ""
        echo "版本变化：GMS $PREV_G → $GV，Play $PREV_P → $PV"
        echo "→ 权限会被 PMS 重算，认证补丁也可能被服务端刷回，按强制重注入处理。"
        FORCE=1
    fi
fi

FAIL=0
NEED=0
[ $FORCE -eq 1 ] && NEED=1

# --- D. 包被禁用（重启后必现，必须先于权限处理）---
if pkg_disabled $GSF || pkg_disabled $GMS || pkg_disabled $PLAY; then
    fix_disabled || FAIL=1
    NEED=1
fi

# --- A′. FLAG_SINGLE_USER 门禁（必须先做，且必须在重注入之前） ---
# 顺序有讲究：pm grant 会触发权限重算、抹掉注入，所以要放在 recover_perm 前面，
# 让随后的重注入把 153 项补回来。
if ! gms_alive && su_gate_broken; then
    echo ""
    echo "检测到 GMS 被 FLAG_SINGLE_USER 拦在 attach 阶段，补授 INTERACT_ACROSS_USERS..."
    pm grant $GMS android.permission.INTERACT_ACROSS_USERS && echo "  已授予" || echo "  授予失败"
    NEED=1
fi

# --- A. 权限 ---
perm_play || NEED=1
perm_gms  || NEED=1

# --- G. 前台服务类型权限 / 电池优化豁免（2026-11-11 新增）---
# 两项都是 appops，改它不触发 PMS 权限重算，所以故意不设 NEED=1 ——
# 只是 appops 掉了的场景，不必跟着等一次 80 秒的重注入。
for _p in $PLAY $GMS; do
    if ! fgs_op $_p; then
        echo ""
        echo "$_p 缺 ACTIVATE_VPN，下载服务会在 startForeground 处抛 SecurityException。补上..."
        if fix_fgs_op $_p; then echo "  已授予 ACTIVATE_VPN"; else echo "  授予失败"; FAIL=1; fi
    fi
    if ! power_op $_p; then
        if fix_power_op $_p; then echo "  已加电池优化豁免（$_p）"; fi
    fi
done

# 装过包就一定要重注入（权限会被重算），即使上面个别包没救回来也要先补权限
if [ $NEED -eq 1 ]; then
    recover_perm || FAIL=1
fi

# --- B/C. GMS 幽灵态 / binder 全死 ---
D=$(gms_dead_links)
if [ $FAIL -eq 0 ]; then
    if ! gms_alive; then
        echo ""
        echo "GMS 进程不在，开始重启 GMS..."
        gms_restart
    elif [ "$D" -gt 10 ] 2>/dev/null; then
        echo ""
        echo "GMS binder 已大面积失效（$D 条 DEAD），开始重启 GMS..."
        gms_restart
    fi
fi

# --- 收尾判定 ---
echo ""
echo "最终状态："
show

echo ""
if pkg_disabled $GSF || pkg_disabled $GMS || pkg_disabled $PLAY; then
    echo "仍有包处于禁用态 —— 自动重置没成功。"
    echo "→ 进 设置 → 应用 → 对应应用 → 启用；或把同版本 APK 放进 $APKS/ 再跑一次。"
    exit 3
fi
if ! perm_play; then
    echo "Play 权限仍然缺失 —— 重注入没成功。再跑一次本脚本；仍失败则重启手机。"
    exit 1
fi
if ! perm_gms; then
    echo "GMS 权限仍然缺失 —— 重注入没成功。再跑一次本脚本；仍失败则重启手机。"
    exit 1
fi

if ! fgs_op $PLAY; then
    echo "Play 的 ACTIVATE_VPN 仍不是 allow —— 下载会继续失败（见 README §8.1.2）。"
    echo "→ 手动补一次：appops set $PLAY ACTIVATE_VPN allow"
    exit 6
fi

D=$(gms_dead_links)
T=$(gms_conn_total)
if [ "$D" -gt 10 ] 2>/dev/null; then
    echo "GMS 的 binder 仍然大面积失效（$D/$T）。"
    echo "→ 再跑一次本脚本；仍不行就重启手机。"
    exit 2
fi

if ! gms_alive; then
    echo "GMS 进程仍然不在。"
    if su_gate_broken; then
        echo "→ FLAG_SINGLE_USER 已补授，但 AMS 里那条 pid 非 0 的旧记录只能靠重启清掉。"
        echo "→ 请重启手机，解锁后立刻再跑一次本脚本。"
        exit 4
    fi
    echo "→ 再跑一次本脚本；仍不行就重启手机。"
    exit 2
fi

# 权限 / 进程 / 包状态都到这儿已经正常，版本记账才推进到当前值。
# 上面的失败分支故意不记 —— 版本变了但没救回来时，下次运行要再强制一次。
[ -n "$GV" ] && [ -n "$PV" ] && state_put "$GV" "$PV"

# --- E. 认证拦截页（只报，不修）---
A=$(cert_activity)
if [ -n "$A" ]; then
    echo ""
    echo "权限与进程都正常，但 Play 被认证拦截页挡住（$A）。"
    echo "→ 本地 flag 被刷回了，重注入治不了它。"
    echo "→ 重跑 Google 兼容补丁的 play scope，见 README §8.8.10。"
    exit 5
fi

echo "完成：可以直接打开 Play 商店。"
exit 0
