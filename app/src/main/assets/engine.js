/*
 * 注入到 FilePizza 发送页面的"遥控器"。原生界面通过它读取页面状态、操作页面。
 * 选择器全部来自 FilePizza 源码：
 *   #drop-zone-button         src/components/DropZone.tsx
 *   #start-button             src/components/StartButton.tsx
 *   input[type=password]      src/components/PasswordField.tsx
 *   #copyable-input-short-url src/components/CopyableInput.tsx（id 由 label 生成）
 *   "N Downloading, M Total"  src/components/Uploader.tsx
 *   Stop Upload 按钮          src/components/StopButton.tsx
 */
(function () {
  if (window.__fs) return;

  function q(s) {
    return document.querySelector(s);
  }

  function buttonByText(t) {
    var bs = document.querySelectorAll('button');
    for (var i = 0; i < bs.length; i++) {
      if ((bs[i].textContent || '').trim() === t) return bs[i];
    }
    return null;
  }

  // 页面创建分享频道时会 POST /api/create，请求体里有上传端的 PeerJS ID。
  // 朋友的中文下载页不需要向 file.pizza 查询，直接用这个 ID 连过来。
  var origFetch = window.fetch;
  window.fetch = function (input, init) {
    try {
      var u = typeof input === 'string' ? input : (input && input.url) || '';
      if (u.indexOf('/api/create') !== -1 && init && typeof init.body === 'string') {
        var id = JSON.parse(init.body).uploaderPeerID;
        if (id) window.__fsPeerId = id;
      }
    } catch (e) {}
    return origFetch.apply(this, arguments);
  };

  window.__fs = {
    state: function () {
      var s = { stage: 'starting' };
      if (window.__fsPeerId) s.peerId = window.__fsPeerId;
      var shortI = q('#copyable-input-short-url');
      var longI = q('#copyable-input-long-url');
      if (shortI && shortI.value) {
        s.stage = 'live';
        s.shortUrl = shortI.value;
        s.longUrl = longI ? longI.value : '';
      } else if (q('#start-button')) {
        s.stage = 'confirm';
      } else if (q('#drop-zone-button')) {
        s.stage = 'idle';
      }
      var txt = document.body ? document.body.innerText : '';
      var m = txt.match(/(\d+)\s+Downloading,\s+(\d+)\s+Total/);
      if (m) {
        s.active = parseInt(m[1], 10);
        s.total = parseInt(m[2], 10);
      }
      var al = q('[role="alert"]');
      if (al) s.error = (al.textContent || '').trim();
      return s;
    },

    // 返回元素中心点（CSS 像素）和设备像素比，原生侧据此合成一次真实触摸。
    // 必须用真实触摸：浏览器只允许在用户操作中弹出文件选择器。
    rect: function (sel) {
      var e = q(sel);
      if (!e) return null;
      e.scrollIntoView({ block: 'center' });
      var r = e.getBoundingClientRect();
      return {
        x: r.left + r.width / 2,
        y: r.top + r.height / 2,
        dpr: window.devicePixelRatio || 1
      };
    },

    // 受控输入框要用原生 setter 再派发 input 事件，React 才会收到
    setPassword: function (pw) {
      var i = q('input[type="password"]');
      if (!i) return false;
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(i, pw);
      i.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    },

    start: function () {
      var b = q('#start-button');
      if (!b) return false;
      b.click();
      return true;
    },

    stop: function () {
      var b = buttonByText('Stop Upload');
      if (!b) return false;
      b.click();
      return true;
    }
  };
})();
