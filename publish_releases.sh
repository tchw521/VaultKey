#!/bin/bash
#
# 把历史 APK 批量发布到 GitHub Release。
#
# 前提：
#   1. 装了 gh（https://cli.github.com/）并 gh auth login 过
#   2. 在本仓库目录下执行
#   3. APK 都放在 ./apks/ 里，命名形如 密盒-VaultKey-v3.13.0.apk
#
# 用法：
#   ./publish_releases.sh <你的仓库，如 user/VaultKey>
#
# 说明：
#   APK 不进 Git 仓库 —— Git 会为每个版本存一份完整副本，
#   51 个版本滚下来仓库会很难 clone。放 Release 不占仓库体积。

set -e
REPO=${1:-tchw521/VaultKey}
DIR=${APK_DIR:-./apks}

#
# 有问题的版本：不跳过，照常发布，但在 Release 里加警告。
# 分类见「版本问题说明.md」。
#
FATAL="v1.0.0 v2.0.0 v3.9.2 v3.12.1"      # 启动即闪退
SEVERE="v3.10.1 v3.7.0"                    # 核心功能崩溃

warn_for() {
  tag="$1"
  for t in $FATAL; do
    [ "$tag" = "$t" ] && echo "🔴 **该版本启动即闪退，请勿安装。**
问题见「版本问题说明.md」，修复于下一个版本。" && return
  done
  for t in $SEVERE; do
    [ "$tag" = "$t" ] && echo "🟠 **该版本核心功能会崩溃，请勿安装。**
问题见「版本问题说明.md」，修复于下一个版本。" && return
  done
  echo ""
}

count=0
for apk in "$DIR"/密盒-VaultKey-v*.apk; do
  [ -e "$apk" ] || { echo "!! $DIR 里没有 APK"; exit 1; }

  base=$(basename "$apk")
  ver=$(echo "$base" | sed -E 's/.*-v([0-9]+\.[0-9]+(\.[0-9]+)?)\.apk/\1/')
  tag="v$ver"

  WARN=$(warn_for "$tag")

  # 已存在就只补传文件，不重复创建
  if gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then
    echo "== $tag 已存在，补传文件"
    gh release upload "$tag" "$apk" --repo "$REPO" --clobber
  else
    echo "== 创建 $tag"
    gh release create "$tag" "$apk" \
      --repo "$REPO" \
      --title "密盒 v$ver" \
      --notes "$WARN
密盒 v$ver 安装包。

完整更新历史见仓库内的「更新日志.md」；各版本的已知问题见「版本问题说明.md」。

> 覆盖安装需与旧版签名一致；签名不同请先卸载旧版。"
  fi
  count=$((count + 1))
done

echo ""
echo "完成，共处理 $count 个版本"
