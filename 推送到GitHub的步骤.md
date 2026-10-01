# 推送到 GitHub 的具体步骤

仓库已建好：<https://github.com/tchw521/VaultKey>（空仓库，正确）

## 第 1 步：生成 Token

打开 **<https://github.com/settings/tokens>**

1. 右上角 **Generate new token** → **Generate new token (classic)**
2. Note：随便填，比如 `VaultKey`
3. Expiration：选 **No expiration**
4. **勾选 `repo`**（推送代码必需；勾上后子项自动全选）
5. 拉到底点 **Generate token**

⚠️ 生成的 `ghp_xxxx` **只显示一次**，页面关掉就没了 —— 当场复制保存。

> 别去 Installed GitHub Apps / SSH and GPG keys 那里找，
> 那些不是这个用途。推送代码用 classic token 最简单。

## 第 2 步：推送

```bash
cd VaultKey-OpenSource
./push_to_github.sh
```

（脚本已内置仓库地址，不用再传参数）

交互提示这么填：

| 提示 | 填什么 |
|---|---|
| Username | `tchw521` |
| Password | **刚才那串 token**，不是登录密码 |

想让 git 记住，避免每次都问：

```bash
git config --global credential.helper store
```

## 第 3 步：确认

刷新 <https://github.com/tchw521/VaultKey> —— 能看到代码就成功了。

推送内容：108 个文件 + `v3.13.0` 标签。
仓库里**不含**签名密钥和 APK（`.gitignore` 已排除，已验证）。

## 第 4 步（可选）：发布 51 个历史 APK

```bash
# 装 gh：https://cli.github.com/
gh auth login          # 选 HTTPS，粘贴同一个 token

mkdir apks && cd apks && unzip ../../密盒-历史APK-51个版本.zip && cd ..
./publish_releases.sh
```

脚本会自动：
- 为每个版本建一个 Release（v1.0.0 … v3.13.0）
- 把对应 APK 挂上去
- 给问题版本加警告：🔴 启动闪退 / 🟠 核心功能崩溃

已存在同名 Release 时只补传文件，重复运行不会出错。

## 常见报错

| 报错 | 原因 | 怎么办 |
|---|---|---|
| `Authentication failed` | token 没勾 `repo`，或填成了登录密码 | 重新生成，勾上 `repo` |
| `repository not found` | 地址写错，或仓库是私有且 token 没权限 | 核对地址 |
| `Permission denied (publickey)` | 用了 SSH 地址但没配 SSH key | 改用 HTTPS 地址 |
| `failed to push some refs` | 建仓库时勾了 README/LICENSE | 先在网页删掉，或 `git pull --rebase origin main` |

## 自动构建

推完 tag 后 GitHub Actions 会自动编译：

```bash
git tag v3.13.1 && git push origin v3.13.1
```

产出的是**未签名** APK（仓库不含密钥）。要签名版见 `发版流程.md`。
