# 闪传 FlashShare

参考开源项目 [FilePizza](https://github.com/kern/filepizza) 的思路：
文件留在你手机的网页里，朋友打开链接后，两边的浏览器通过 WebRTC 直接传输。
不需要自己的服务器，也不需要内网穿透隧道。

## 原理
- App 用 WebView 加载 `https://file.pizza/`（FilePizza 作者托管的公共实例）。
- 在页面里选文件、点开始，页面生成下载链接和二维码，发给朋友即可。
- 中间只有 PeerJS 信令服务（`0.peerjs.com`）和 STUN 服务，文件内容不经过它们。
- `MainActivity` 拦截页面对 `/api/ice` 的请求，把 Google STUN 换成国内可用的 STUN 列表。
- `ShareService` 是前台服务 + 唤醒锁，保活用；通知上的"停止分享"会结束整个 App。

## 朋友打开的中文下载页
`site/index.html` 是纯静态页面，由 GitHub Pages 发布（`.github/workflows/pages.yml`），
用 PeerJS 直接连到你手机里的网页，说的是 FilePizza 的同一套消息协议。
链接形如 `https://<用户名>.github.io/FlashShare/#<上传端PeerJS ID>`，ID 在 `#` 后面，不会发给任何服务器。
第一次使用前要在仓库 Settings → Pages → Source 选 "GitHub Actions"。

## 编译（GitHub Actions）
推送到 main 分支后，在仓库 Actions → Build APK 的 Artifacts 里下载 `FlashShare-debug-apk`。

## 使用注意
- 发送期间 App 必须保持运行，对方下载完再退出。返回键只会把 App 放到后台，不会退出。
- 第一次打开会弹出"忽略电池优化"授权框，请选允许。
- ColorOS 等系统还需要在 设置 → 电池 → 应用耗电管理 里给闪传"允许后台运行"。

## 已知风险（尚未在真机上测试）
- file.pizza 和 0.peerjs.com 在你的网络下能否访问，需要实测。
- 双方都在严格 NAT 后面时，没有 TURN 中继就可能连不上。
- 浏览器版本过旧可能不兼容，需要较新的系统 WebView。
