#!/bin/bash
#
# 构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# 不依赖 Gradle，只用 Android SDK 命令行工具 + JDK，
# 所以在没有 Gradle 缓存的环境（比如 CI）里也能直接跑。
#
# 用法：
#   ./build_manual.sh            # 用 version.properties 里的版本号
#   SIGN=0 ./build_manual.sh     # 不打签名（CI 默认，产出未签名 APK）
#
# 路径都可以通过环境变量覆盖，本地和 CI 通用。
set -e
ulimit -f unlimited 2>/dev/null

# 定位仓库根目录（脚本可能从任意目录被调用）
ROOT=$(cd "$(dirname "$0")" && pwd)

ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$ROOT/../toolchain/android-sdk}}
BT=${BT:-$ANDROID_HOME/build-tools/34.0.0}
PLAT=${PLAT:-$ANDROID_HOME/platforms/android-34/android.jar}

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK=$JAVA_HOME
else
  JDK=${JDK:-/usr/lib/jvm/java-17-openjdk-amd64}
fi

SRC=$ROOT/app/src/main
OUT=$ROOT/build-manual
VER_FILE=$ROOT/version.properties
VERSION_NAME=$(grep '^VERSION_NAME=' "$VER_FILE" | cut -d= -f2 | tr -d ' \r')
VERSION_CODE=$(grep '^VERSION_CODE=' "$VER_FILE" | cut -d= -f2 | tr -d ' \r')
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "!! 读不到版本号，检查 $VER_FILE"; exit 1
fi
APK="$ROOT/密盒-VaultKey-v${VERSION_NAME}.apk"
echo "== 构建 v${VERSION_NAME} (code ${VERSION_CODE}) =="
KS=${KS:-$ROOT/vaultkey.jks}

rm -rf $OUT
mkdir -p $OUT/gen $OUT/obj $OUT/dex

echo "== 1/5 编译资源 =="
$BT/aapt2 compile --dir $SRC/res -o $OUT/res.zip

# R 类生成在 com.vaultkey（与源码 import 一致），真机 applicationId 用 com.vaultkey.app
sed 's|<manifest xmlns|<manifest package="com.vaultkey" xmlns|' $SRC/AndroidManifest.xml > $OUT/mf_r.xml
sed 's|<manifest xmlns|<manifest package="com.vaultkey.app" xmlns|' $SRC/AndroidManifest.xml > $OUT/mf.xml

echo "== 2/5 链接并生成 R.java =="
$BT/aapt2 link -I $PLAT \
  --manifest $OUT/mf_r.xml \
  -o $OUT/linked_r.apk \
  --java $OUT/gen \
  --min-sdk-version 28 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  $OUT/res.zip
$BT/aapt2 link -I $PLAT \
  --manifest $OUT/mf.xml \
  -o $OUT/linked.apk \
  --min-sdk-version 28 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  $OUT/res.zip

echo "== 3/5 编译 Java =="
find $SRC/java $OUT/gen -name "*.java" > $OUT/sources.txt
$JDK/bin/javac -source 17 -target 17 -classpath $PLAT \
  -d $OUT/obj -nowarn @"$OUT/sources.txt"

echo "== 4/5 打包 dex =="
$BT/d8 --lib $PLAT --min-api 28 --output $OUT/dex $(find $OUT/obj -name "*.class")

echo "== 5/5 组装并签名 =="
cp $OUT/linked.apk $OUT/unsigned.apk
cd $OUT/dex && $BT/aapt add -f $OUT/unsigned.apk classes.dex >/dev/null 2>&1 || \
  (cd $OUT && $JDK/bin/jar uf $OUT/unsigned.apk -C $OUT/dex classes.dex)
cd $OUT
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk

#
# 签名：仓库里不含密钥（.gitignore 已排除 *.jks），所以默认不签名。
# 有密钥时设 KS=/path/to/key.jks 即可；没有就产出未签名 APK，
# 构建照样成功 —— CI 上尤其需要这样，否则每个 fork 都会失败。
#
if [ "${SIGN:-1}" = "1" ] && [ -f "$KS" ]; then
  echo "== 签名（$KS） =="
  $BT/apksigner sign --ks "$KS" --ks-key-alias "${KS_ALIAS:-vaultkey}" \
    --ks-pass "pass:${KS_PASS:-vaultkey}" --key-pass "pass:${KS_PASS:-vaultkey}" \
    --out "$APK" $OUT/aligned.apk
else
  echo "== 跳过签名（未找到 $KS，或 SIGN=0） =="
  cp $OUT/aligned.apk "$APK"
fi

echo "== 完成 =="
ls -lh "$APK"
