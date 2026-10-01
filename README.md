# 密盒 VaultKey

一个**本地优先**的安卓密码管理器。账号密码、网址收藏都存在本机并加密，
不依赖任何自建服务器；多设备同步走你自己的 WebDAV（坚果云等）。

当前版本 v3.13.1，APK 约 332KB。

仓库地址：<https://github.com/tchw521/VaultKey>

```bash
git clone https://github.com/tchw521/VaultKey.git
```

## 特性

**密码箱**
- AES-256-GCM 加密，主密钥存 Android Keystore，支持指纹解锁
- AES + PBKDF2 派生，没有硬编码密钥；数据库里的字段逐条加密
- 自动填充服务 + 无障碍辅助填充
- 悬浮窗：在别的 App 里也能取用账号密码，可拖动、长按呼出菜单
- 密码生成器（四类字符各自保底，不会勾了数字却一个数字都没有）
- 密码体检：弱密码、重复密码、长期未改
- 按域名聚合「找回」——厘清某网站到底用哪个邮箱注册的
- TOTP 动态口令、标签、自定义字段、附件、回收站

**链接库**
- 分类、标签、搜索，与密码箱共用一套分类体系但数据相互隔离
- 点击直接跳转浏览器，自动补全 `https://`

**同步与备份**
- WebDAV 双向同步（坚果云已实测可用），自动规避目录名/扩展名限制
- 换机直传：同 WiFi 下两台手机直接传，无需服务器
- 本地备份文件夹：授权一次，之后导出自动落盘
- 导入导出：加密 .dbox / Bitwarden JSON / CSV

**其他**
- 卡片 OCR（从相册选图，不需要相机权限）
- 三套内置图标、自定义启动图、自定义头像
- 深色模式、多套皮肤、自定义主题色
- 桌面小部件、通知栏速搜

## 编译

**不用 Gradle**，只需要 Android SDK 命令行工具 + JDK 17：

```bash
./build_manual.sh
```

产出 `密盒-VaultKey-v<版本>.apk`。版本号统一写在 `version.properties`。

- 默认**不签名**（仓库不含密钥）。要签名：
  ```bash
  KS=/path/to/your.jks KS_ALIAS=alias KS_PASS=密码 ./build_manual.sh
  ```
- 也可以直接用 Android Studio 打开 `app/build.gradle` 构建。

## 自动发布

推送 tag 即自动编译并把 APK 挂到 Release：

```bash
git tag v3.13.1 && git push origin v3.13.1
```

见 `.github/workflows/release.yml`。

## 目录结构

```
app/src/main/java/com/vaultkey/
├── crypto/    加解密、Keystore、生物识别
├── data/      数据库（Db.java）、偏好、备份目录
├── service/   自动填充、无障碍、悬浮窗
├── sync/      WebDAV 同步
├── ui/        界面
├── util/      图标、OCR、局域网传输、CSV/JSON 编解码
└── webdav/    MiniHttp（自建 HTTP 客户端，支持 MKCOL/PROPFIND）
```

## 隐私

- 全部数据存本机，**没有任何服务器**
- 不做遥测：仅在 WebDAV 同步、抓取网站图标时才联网
- 主密钥不放代码里，由主密码 PBKDF2 派生 + Android Keystore 保护
- 解密失败一律返回空串，绝不降级成明文

权限说明（都只用于对应功能，用到时才申请）：

| 权限 | 用途 |
|---|---|
| `INTERNET` | WebDAV 同步、抓取网站图标 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗（默认关闭，点条目才开） |
| `USE_BIOMETRIC` | 指纹解锁 |
| `QUERY_ALL_PACKAGES` | 自动填充时列出可关联的 App |
| `FOREGROUND_SERVICE` | 悬浮窗后台保活（用完即走，90 秒自动停） |

**没有**相机、位置、通讯录、读取存储权限 —— 卡片 OCR 走相册选图，
不需要相机权限。

## 已知取舍

- 最低 Android 9（API 28）——为了用 Keystore 的生物识别绑定
- 卡片 OCR 只支持从相册选图：Android 14 起拍照拿不到原图
- 悬浮窗密码默认打码：它浮在别的应用之上，可能被截屏

## 作者

**tchw521** —— <https://github.com/tchw521>

## 支持这个项目

如果密盒帮到了你，可以请作者喝杯咖啡 ☕

| 支付宝 | 微信支付 |
|---|---|
| <img src="app/src/main/res/drawable/sponsor_alipay.webp" width="180"> | <img src="app/src/main/res/drawable/sponsor_wechat.webp" width="180"> |

App 内也有入口：设置中心 → 关于 → 赞助作者。

赞助完全是自愿的，不影响任何功能 —— 这个项目的全部代码一直都是开源的。

## 免责声明

本软件按「原样」提供，**不作任何明示或暗示的担保**。

- 作者不对因设备故障、误操作或软件缺陷导致的数据丢失负责
- 主密码不上传、无法找回 —— **一旦遗忘，数据将无法恢复**
- 使用本软件产生的任何直接或间接损失，作者不承担责任

**请务必自行备份。**

完整条款见 [LICENSE](LICENSE)（MIT）。

## 历史版本的已知问题

开源的是完整演进过程，**包括踩过的坑**。

- [版本问题说明.md](版本问题说明.md) —— 哪些版本会闪退、哪些有功能异常、装哪个版本
- [更新日志.md](更新日志.md) —— 每个版本的改动与根因分析

**要装来用请直接用最新版 v3.13.0。**
v1.0.0 / v2.0.0 / v3.9.2 / v3.12.1 这四个版本启动即闪退，请勿安装。

## 协议

MIT。见 [LICENSE](LICENSE)。
