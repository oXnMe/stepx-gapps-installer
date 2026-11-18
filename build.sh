#!/bin/sh
# 构建 StepX GApps 安装器 —— 不用 Gradle，只用 SDK 里那几个命令行工具。
#
# 为什么不用 Gradle：这个 app 一共 21 个源文件、界面全部代码绘制、没有任何
# AndroidX 依赖，Gradle 要下的东西比源码大三个数量级。同理，CI 里也只跑这个脚本。
#
# 用法：
#   ./build.sh                 出 out/stepx-gapps-installer.apk
#   ./build.sh --install       构建完顺手装到连着的设备（走 pm install-create）
#   VERSION_CODE=35 VERSION_NAME=0.35 ./build.sh
#
# 签名 key 不在仓库里：默认读 keystore/release.jks + keystore/pass（都被忽略），
# 也可以用 KS_FILE / KS_PASS / KS_KEYPASS 指过去；都没有就生成临时 key 出包。
#
# 依赖（都从公开地址取，仓库里不放二进制）：
#   JDK 17、Android SDK 的 build-tools（aapt2 / aidl / d8 / zipalign / apksigner）
#   与 platforms 里任意 android.jar
#   Shizuku 的 api / provider 两个 aar（首次运行自动下到 .deps/）
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/out
BUILD=${TMPDIR:-/tmp}/stepx-gapps-build
DEPS=$HERE/.deps

VERSION_CODE=${VERSION_CODE:-35}
VERSION_NAME=${VERSION_NAME:-0.35}

# 签名：key 不进仓库。公开的 key 等于谁都能签出一个「同包名、同签名」的包，
# 装到已装过的机器上就是一次静默覆盖升级，所以这里只认外面给的路径：
#   本地  $HERE/keystore/release.jks（.gitignore 里已排除），或 KS_FILE 指过去
#   CI    secrets.KEYSTORE_B64 解码到 $HERE/keystore/release.jks
# 两处都没有就现生成一把临时的 —— 包能装，但签名不同，盖不掉已装的版本。
KS=${KS_FILE:-$HERE/keystore/release.jks}
KS_ALIAS=${KS_ALIAS:-gapps}
KS_PASS=${KS_PASS:-}
KS_KEYPASS=${KS_KEYPASS:-$KS_PASS}

# 本地图省事：口令放 keystore/pass（和 key 一样被 .gitignore 排除），
# 这样日常 ./build.sh 不用带环境变量；CI 走 secrets，覆盖这里。
if [ -z "$KS_PASS" ] && [ -f "$(dirname "$KS")/pass" ]; then
    KS_PASS=$(tr -d ' \r\n' < "$(dirname "$KS")/pass")
    KS_KEYPASS=${KS_KEYPASS:-$KS_PASS}
fi

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ------------------------------- 工具链定位 -------------------------------
# 两种布局都认：SDK 根目录（build-tools/<ver>/、platforms/android-<n>/），
# 以及本机这种把某一个 build-tools 目录直接摊开的 ~/android-tools/android-14。

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
    SDK=$ANDROID_HOME
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/build-tools" ]; then
    SDK=$ANDROID_SDK_ROOT
else
    SDK=
fi

if [ -n "$SDK" ]; then
    BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
    [ -n "$BT" ] || die "在 $SDK/build-tools 下没找到任何 build-tools"
    if [ -n "${ANDROID_JAR:-}" ]; then
        :
    else
        ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
    fi
else
    BT=${ANDROID_TOOLS:-$HOME/android-tools/android-14}
    ANDROID_JAR=${ANDROID_JAR:-$HOME/android-tools/platforms/android-36/android.jar}
fi

for t in aapt2 aidl d8 zipalign apksigner; do
    [ -x "$BT/$t" ] || die "缺 $BT/$t"
done
[ -f "$ANDROID_JAR" ] || die "缺 android.jar：$ANDROID_JAR"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    :
elif [ -x "$HOME/android-tools/jdk/Contents/Home/bin/javac" ]; then
    JAVA_HOME=$HOME/android-tools/jdk/Contents/Home
else
    JAVA_HOME=$(dirname "$(dirname "$(command -v javac)")")
fi
export JAVA_HOME
JAVAC=$JAVA_HOME/bin/javac

# ------------------------------- Shizuku 依赖 -------------------------------
MAVEN=https://repo1.maven.org/maven2/dev/rikka/shizuku
SHIZUKU_VER=${SHIZUKU_VER:-13.1.5}

fetch_aar() {   # $1=模块名
    name=$1
    [ -f "$DEPS/$name/classes.jar" ] && return 0
    url=$MAVEN/$name/$SHIZUKU_VER/$name-$SHIZUKU_VER.aar
    say "== 下载 $name-$SHIZUKU_VER.aar =="
    mkdir -p "$DEPS/$name"
    curl -fsSL "$url" -o "$DEPS/$name/$name-$SHIZUKU_VER.aar" \
        || die "下载失败：$url"
    (cd "$DEPS/$name" && unzip -oq "$name-$SHIZUKU_VER.aar" classes.jar) \
        || die "解包失败：$name-$SHIZUKU_VER.aar"
}
fetch_aar api
fetch_aar provider
# moe.shizuku.server.* 的 AIDL 没有随 api 包发布，仓库 app/aidl 下放了一份，
# 由下面的 aidl 步骤编译（api 包里只有 IShizukuService 的空壳接口）。

INSTALL=0
for a in "$@"; do
    case "$a" in
        --install) INSTALL=1 ;;
        *) die "未知参数：$a" ;;
    esac
done

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT"

say "== aapt2 link（清单 + assets + 图标）=="
# 界面全部代码绘制，资源目录里只有启动图标。没资源时不加这一步，
# 免得给一个空目录让 aapt2 报错。
RES_FLATS=
if [ -d "$HERE/app/res" ]; then
    mkdir -p "$BUILD/res"
    "$BT/aapt2" compile --dir "$HERE/app/res" -o "$BUILD/res"
    RES_FLATS=$(find "$BUILD/res" -name '*.flat' | sort | tr '\n' ' ')
    [ -n "$RES_FLATS" ] || die "app/res 编译后没有任何 .flat"
fi
"$BT/aapt2" link -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$HERE/app/AndroidManifest.xml" \
    -A "$HERE/app/assets" \
    $RES_FLATS \
    --min-sdk-version 34 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
say "  base.apk $(wc -c < "$BUILD/base.apk") bytes"

say "== aidl =="
mkdir -p "$BUILD/aidlsrc"
AIDL_FW=$(dirname "$ANDROID_JAR")/framework.aidl
[ -f "$AIDL_FW" ] || die "缺 framework.aidl：$AIDL_FW"
for f in "$HERE"/app/aidl/moe/shizuku/server/*.aidl; do
    "$BT/aidl" -I"$HERE/app/aidl" -p"$AIDL_FW" -o"$BUILD/aidlsrc" "$f"
done
say "  生成 $(find "$BUILD/aidlsrc" -name '*.java' | wc -l | tr -d ' ') 个 java 文件"

say "== javac =="
CP="$ANDROID_JAR:$DEPS/api/classes.jar:$DEPS/provider/classes.jar"
"$JAVAC" --release 8 -nowarn -classpath "$CP" -d "$BUILD/classes" \
    $(find "$HERE/app/src" "$BUILD/aidlsrc" -name '*.java')

say "== d8（本应用 + Shizuku api/provider 都要打进 dex）=="
"$BT/d8" --min-api 34 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class') \
    "$DEPS/api/classes.jar" "$DEPS/provider/classes.jar"
say "  classes.dex $(wc -c < "$BUILD/dex/classes.dex") bytes"

say "== 合进 apk =="
# aapt2 出的 base.apk 只有清单和 assets，dex 由 d8 单独产出，这里合进去；
# 顺手丢掉 META-INF —— aapt2 留下的空签名目录会让 apksigner 报重复。
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import sys, zipfile, os

base, dex, dst = sys.argv[1:4]
data = open(dex, 'rb').read()
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(dst, 'w')
for item in zin.infolist():
    if item.filename.startswith('META-INF/'):
        continue
    info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
    info.compress_type = item.compress_type
    info.external_attr = item.external_attr
    zout.writestr(info, zin.read(item.filename))
info = zipfile.ZipInfo('classes.dex', date_time=(2026, 1, 1, 0, 0, 0))
info.compress_type = zipfile.ZIP_DEFLATED
zout.writestr(info, data)
zout.close(); zin.close()
print('  unsigned.apk %d bytes' % os.path.getsize(dst))
PY

say "== 签名 =="
"$BT/zipalign" -p -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
APK=$OUT/stepx-gapps-installer.apk

if [ ! -f "$KS" ]; then
    KS=$BUILD/ephemeral.jks
    KS_ALIAS=ephemeral
    KS_PASS=${KS_PASS:-ephemeral}
    KS_KEYPASS=$KS_PASS
    say "  没找到签名 key，现生成一把临时的：盖不掉已装版本"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
        -alias "$KS_ALIAS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -keyalg RSA -keysize 2048 -validity 365 -dname "CN=ephemeral" \
        >/dev/null 2>&1 || die "临时 key 生成失败"
elif [ -z "$KS_PASS" ]; then
    die "找到 $KS 但没有密码：设 KS_PASS（口令不同再加 KS_KEYPASS）"
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_KEYPASS" \
    --ks-key-alias "$KS_ALIAS" --out "$APK" "$BUILD/aligned.apk"
say "  $APK $(wc -c < "$APK") bytes  (v$VERSION_NAME · $VERSION_CODE)"

if [ "$INSTALL" = 1 ]; then
    ADB=${ADB:-adb}
    SZ=$(wc -c < "$APK")
    say "== 安装 $SZ bytes =="
    "$ADB" push "$APK" /data/local/tmp/sgi.apk >/dev/null
    SID=$("$ADB" shell "pm install-create -r -t -S $SZ" | grep -o '\[[0-9]*\]' | tr -d '[]')
    "$ADB" shell "pm install-write -S $SZ $SID base.apk /data/local/tmp/sgi.apk" >/dev/null
    "$ADB" shell "pm install-commit $SID" | tail -1
    "$ADB" shell "rm -f /data/local/tmp/sgi.apk"
fi
