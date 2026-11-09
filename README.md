# 闪传 FlashShare

手机直接当文件服务器：选文件 → 生成公网链接 → 朋友用浏览器打开就能下载。
不需要自己的服务器，文件不上传到任何网盘。

## 原理
- `FileServer`（NanoHTTPD）：只监听 127.0.0.1，提供文件列表页和下载（支持断点续传、可选密码）。
- `Tunnel`（JSch）：SSH 反向隧道到 localhost.run，得到 `https://xxxx.lhr.life`。
- `ShareService`：前台服务保活，到期自动停止。

## 编译（GitHub Actions）
1. 把本目录推到 GitHub 仓库的 main 分支。
2. 打开仓库 Actions → Build APK，等待完成。
3. 在运行详情的 Artifacts 里下载 `FlashShare-debug-apk`，解压得到 APK 安装。

## 已知风险（尚未在真机上测试）
- 隧道依赖 localhost.run，国内网络可能连不上或不稳定；服务改版也可能导致网址正则失效（见 Tunnel.kt）。
- JSch 在部分 Android 版本上的加密算法协商可能失败，首次运行请先用小文件测试。
- Android 15 对前台服务 dataSync 类型有每日时长限制。
- 对方下载期间，你的手机必须联网、不被系统杀后台。
