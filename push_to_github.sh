#!/bin/bash
#
# 把本地仓库推到你自己的 GitHub 仓库。
#
# 用法（二选一）：
#   ./push_to_github.sh https://github.com/你的用户名/VaultKey.git
#   ./push_to_github.sh git@github.com:你的用户名/VaultKey.git
#
# 前提：
#   1. 已在 GitHub 网页上建好一个**空仓库**（不要勾 README / .gitignore / LICENSE）
#   2. 已配置好身份认证，任选其一：
#      - HTTPS: git config --global credential.helper store   （首次推送会问用户名+Personal Access Token）
#      - SSH:   ssh-keygen -t ed25519，把公钥加到 GitHub → Settings → SSH keys
#
set -e

URL=${1:-https://github.com/tchw521/VaultKey.git}

cd "$(dirname "$0")"

echo "== 目标仓库：$URL"

# 已加过 origin 就改地址，没加过就新增
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$URL"
else
  git remote add origin "$URL"
fi

# 统一用 main
git branch -M main 2>/dev/null || true

echo "== 推送代码 =="
git push -u origin main

echo "== 推送版本标签 =="
# 只推当前源码对应的标签。历史 APK 的标签用 publish_releases.sh 单独建。
git tag -f "$(grep '^VERSION_NAME=' version.properties | cut -d= -f2 | tr -d ' \r' | sed 's/^/v/')" 2>/dev/null
git push --tags

echo ""
echo "✅ 推送完成：$URL"
echo ""
echo "下一步：把 51 个历史 APK 发布到 Release ——"
echo "  1) 装 gh：https://cli.github.com/"
echo "  2) gh auth login"
echo "  3) mkdir apks && 把 密盒-历史APK-51个版本.zip 解压进去"
echo "  4) ./publish_releases.sh 你的用户名/VaultKey"
echo ""
echo "有问题的版本会自动在 Release 顶部加上 🔴/🟠 警告。"
