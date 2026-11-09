/*
 * 注入到 FilePizza 发送页面的脚本：把英文界面换成中文，并隐藏披萨图、页脚、主题切换。
 * 文案来源：FilePizza 源码（src/app/page.tsx、src/components/*.tsx）。
 * 用 MutationObserver 持续翻译，页面是单页应用，内容会动态变化。
 */
(function () {
  if (window.__flashshare_zh) return;
  window.__flashshare_zh = true;

  var D = {
    'Peer-to-peer file transfers in your browser.': '文件直接传给对方，不经过服务器。',
    'Drop a file to get started': '点这里选择文件',
    'You are about to start uploading': '即将分享',
    'You are uploading': '正在分享',
    'Add more files': '添加更多文件',
    'Cancel': '取消',
    'Start': '开始分享',
    'Password (optional)': '访问密码（可不填）',
    'Password': '访问密码',
    'Enter a secret password for this slice of FilePizza...': '输入密码，对方需要密码才能下载',
    "The downloader must provide this password to start downloading the file. If you don't specify a password here, any downloader with the link to the file will be able to download it. It is not used to encrypt the file, as this is performed by WebRTC's DTLS already.":
      '对方必须输入此密码才能下载。不设置密码时，任何拿到链接的人都能下载。密码不用来加密文件，传输本身已由 WebRTC 加密。',
    'Leave this tab open. FilePizza does not store files.': '请保持本页面打开，文件不会上传到任何服务器。',
    'Creating channel...': '正在生成下载链接…',
    'Initializing WebRTC peer...': '正在连接中转服务…',
    'Uh oh... Something went wrong.': '出错了，请返回重试。',
    'Long URL': '长链接',
    'Short URL': '短链接',
    'Copy': '复制',
    'Copied': '已复制',
    'Stop Upload': '停止分享',
    'Stop Download': '停止下载',
    'Downloading,': '人正在下载，',
    'Total': '人连接过',
    'Downloader': '下载者',
    'Completed:': '已完成：',
    'files': '个文件',
    'file': '个文件',
    'Current file:': '当前文件：',
    'UPLOADING': '传输中',
    'PENDING': '等待中',
    'READY': '就绪',
    'PAUSED': '已暂停',
    'DONE': '已完成',
    'AUTHENTICATING': '验证密码中',
    'INVALID PASSWORD': '密码错误',
    'CLOSED': '已断开',
    'Invalid password': '密码错误',
    'By selecting a file, you agree to': '选择文件即表示你同意',
    'our terms': '使用条款',
    'FilePizza Terms': '使用条款',
    'Files are shared directly between browsers — no server storage': '文件在两端浏览器之间直接传输，不经服务器存储',
    'Only upload files you have the right to share': '只分享你有权分享的文件',
    'Share download links only with known recipients': '只把链接发给认识的人',
    'No illegal or harmful content allowed': '禁止违法或有害内容',
    'By uploading a file, you confirm that you understand and agree to these terms.': '分享文件即表示你理解并同意这些条款。',
    'Got it!': '知道了',
    'Toggle mode': '切换主题'
  };

  var RULES = [
    [/^(\d+) files?$/, '$1 个文件']
  ];

  function tr(s) {
    var m = s.match(/^(\s*)([\s\S]*?)(\s*)$/);
    var core = m[2].replace(/\s+/g, ' ');
    if (!core) return s;
    var t = D[core];
    if (t === undefined) {
      for (var i = 0; i < RULES.length; i++) {
        if (RULES[i][0].test(core)) {
          t = core.replace(RULES[i][0], RULES[i][1]);
          break;
        }
      }
    }
    return t === undefined ? s : m[1] + t + m[3];
  }

  function walk(n) {
    if (n.nodeType === 3) {
      var v = tr(n.nodeValue);
      if (v !== n.nodeValue) n.nodeValue = v;
    } else if (n.nodeType === 1) {
      var tag = n.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE') return;
      // 不翻译 aria-label：下面的隐藏样式要靠它定位
      ['placeholder', 'title'].forEach(function (a) {
        if (n.hasAttribute && n.hasAttribute(a)) {
          var x = n.getAttribute(a);
          var y = tr(x);
          if (y !== x) n.setAttribute(a, y);
        }
      });
      if (tag === 'svg' && n.getAttribute('aria-label') === 'FilePizza logo' && !n.__zhTitle) {
        n.__zhTitle = true;
        var h = document.createElement('h1');
        h.textContent = '闪传';
        h.style.cssText = 'font-size:32px;font-weight:800;margin:8px 0 0;color:#3668e8;';
        if (n.parentNode) n.parentNode.insertBefore(h, n);
      }
      for (var c = n.firstChild; c; c = c.nextSibling) walk(c);
    }
  }

  var style = document.createElement('style');
  style.textContent =
    'footer{display:none !important;}' +
    'svg[aria-label="Pizza"],svg[aria-label="Rotating pizza"],svg[aria-label^="Arrow pointing"],svg[aria-label="FilePizza logo"]{display:none !important;}' +
    'div[class*="w-[300px]"][class*="h-[300px]"]{display:none !important;}' +
    'button:has(> span.sr-only){display:none !important;}';
  (document.head || document.documentElement).appendChild(style);

  new MutationObserver(function (muts) {
    for (var i = 0; i < muts.length; i++) {
      var m = muts[i];
      if (m.type === 'characterData') {
        walk(m.target);
      } else if (m.type === 'attributes') {
        walk(m.target);
      } else {
        for (var j = 0; j < m.addedNodes.length; j++) walk(m.addedNodes[j]);
      }
    }
  }).observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ['placeholder', 'title']
  });

  walk(document.documentElement);
})();
