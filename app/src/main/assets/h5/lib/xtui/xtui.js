/*
 * 星特安全 H5 公共脚本（xtui）。
 *
 * 职责：
 *   1. 界面风格判定与切换（custom = 星特自有样式 / mdui = Material You）
 *   2. 表单渲染：同一份 schema 生成两种风格的 DOM，页面不再各写一遍
 *   3. 常用格式化与 DOM 小工具
 *
 * 风格偏好存 dot_config.h5_ui_mode，默认 custom。
 * mdui 体积较大（约 360KB），只在 mdui 模式下同步加载，custom 模式零开销。
 *
 * 依赖 Java 侧 H5BaseActivity 注册的公共桥 XTB（getUiMode / setUiMode /
 * getPref / setPref / isDark / toast / close）。XTB 不存在时全部降级为
 * custom 模式，页面仍可正常工作。
 */
(function () {
  var MODE_CUSTOM = 'custom';
  var MODE_MDUI = 'mdui';

  function readMode() {
    try {
      return XTB.getUiMode() === MODE_MDUI ? MODE_MDUI : MODE_CUSTOM;
    } catch (e) {
      return MODE_CUSTOM;
    }
  }

  function applyClass(mode) {
    var r = document.documentElement;
    r.classList.remove('xtui-custom');
    r.classList.remove('xtui-mdui');
    r.classList.add('xtui-' + mode);
  }

  var MODE = readMode();
  applyClass(MODE);

  // mdui 只在此模式下加载；document.write 在 head 解析期执行，能保证
  // 样式先于 body 渲染，不会出现无样式闪烁。
  if (MODE === MODE_MDUI) {
    document.write('<link rel="stylesheet" href="lib/mdui/mdui.css">');
    document.write('<script src="lib/mdui/mdui.global.js"><\/script>');
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function el(id) {
    return document.getElementById(id);
  }

  function fmtInterval(ticks) {
    var ms = Number(ticks) * 100;
    if (!(ms > 0)) return '0 秒';
    if (ms < 1000) return (ms / 1000).toFixed(1) + ' 秒';
    if (ms < 60000) {
      var s = ms / 1000;
      return (s === Math.round(s) ? s : s.toFixed(1)) + ' 秒';
    }
    var m = ms / 60000;
    return (m === Math.round(m) ? m : m.toFixed(1)) + ' 分钟';
  }

  // ==================== 控件 HTML ====================

  function toggleHtml(id) {
    if (MODE === MODE_MDUI) {
      return '<mdui-switch id="' + id + '"></mdui-switch>';
    }
    return '<label class="xtui-toggle"><input type="checkbox" id="' + id + '">'
      + '<span class="xtui-slider"></span></label>';
  }

  function sliderHtml(id, min, max, step) {
    if (MODE === MODE_MDUI) {
      return '<mdui-slider id="' + id + '" min="' + min + '" max="' + max
        + '" step="' + step + '"></mdui-slider>';
    }
    return '<input class="xtui-range" type="range" id="' + id + '" min="' + min
      + '" max="' + max + '" step="' + step + '">';
  }

  function btnHtml(id, text, variant) {
    var v = variant || 'text';
    if (MODE === MODE_MDUI) {
      var mv = v === 'filled' ? 'filled' : (v === 'tonal' ? 'tonal' : 'text');
      return '<mdui-button id="' + id + '" full-width variant="' + mv + '">'
        + esc(text) + '</mdui-button>';
    }
    var cls = 'xtui-btn' + (v === 'filled' ? ' xtui-btn-primary' : '');
    return '<button class="' + cls + '" id="' + id + '">' + esc(text) + '</button>';
  }

  function inputHtml(id, placeholder, btnText, btnId) {
    if (MODE === MODE_MDUI) {
      return '<mdui-text-field id="' + id + '" variant="outlined" clearable'
        + (placeholder ? ' placeholder="' + esc(placeholder) + '"' : '') + '></mdui-text-field>'
        + (btnText ? '<mdui-button id="' + (btnId || (id + '_btn')) + '" full-width variant="tonal">'
          + esc(btnText) + '</mdui-button>' : '');
    }
    return '<input class="xtui-field" type="text" id="' + id + '"'
      + (placeholder ? ' placeholder="' + esc(placeholder) + '"' : '') + '>'
      + (btnText ? '<button class="xtui-btn" id="' + (btnId || (id + '_btn')) + '">'
        + esc(btnText) + '</button>' : '');
  }

  function checksHtml(id, items) {
    var h = '<div class="xtui-checks" id="' + id + '">';
    for (var i = 0; i < items.length; i++) {
      h += '<label class="xtui-check"><input type="checkbox" data-v="'
        + items[i].value + '"><span>' + esc(items[i].label) + '</span></label>';
    }
    return h + '</div>';
  }

  // ==================== 取值 / 赋值 ====================

  function getChecked(id) {
    var n = el(id);
    return n ? !!n.checked : false;
  }

  function setChecked(id, v) {
    var n = el(id);
    if (n) n.checked = !!v;
  }

  function getValue(id) {
    var n = el(id);
    return n ? n.value : '';
  }

  function setValue(id, v) {
    var n = el(id);
    if (n) n.value = (v == null ? '' : v);
  }

  function getMask(id) {
    var box = el(id);
    if (!box) return 0;
    var list = box.querySelectorAll('input[data-v]');
    var m = 0;
    for (var i = 0; i < list.length; i++) {
      if (list[i].checked) m |= parseInt(list[i].getAttribute('data-v'), 10);
    }
    return m;
  }

  function setMask(id, mask) {
    var box = el(id);
    if (!box) return;
    var list = box.querySelectorAll('input[data-v]');
    for (var i = 0; i < list.length; i++) {
      var v = parseInt(list[i].getAttribute('data-v'), 10);
      list[i].checked = (mask & v) !== 0;
    }
  }

  // ==================== schema 渲染 ====================

  /**
   * 把 schema 渲染进 container。
   *
   * item.type 支持：
   *   switch  { key, label, tip }
   *   slider  { key, min, max, step, fmt, tip }
   *   checks  { key, label, items:[{value,label}] }
   *   text    { key, rows, saveLabel }
   *   button  { key, label, variant: text|filled|tonal }
   *   note    { text }
   *
   * api 回调：onSwitch(key, val) / onSlider(key, val) /
   *           onMask(key, mask) / onSave(key, text) / onButton(key)
   */
  function render(container, schema, api) {
    var html = '';
    for (var i = 0; i < schema.length; i++) {
      var sec = schema[i];
      var inner = '';
      if (sec.title) inner += '<div class="xtui-section">' + esc(sec.title) + '</div>';
      var items = sec.items || [];
      for (var j = 0; j < items.length; j++) {
        var it = items[j];
        if (it.type === 'switch') {
          inner += '<div class="xtui-row"><div class="xtui-row-label">' + esc(it.label)
            + '</div>' + toggleHtml(it.key) + '</div>';
          if (it.tip) inner += '<div class="xtui-tip">' + esc(it.tip) + '</div>';
        } else if (it.type === 'slider') {
          inner += '<div class="xtui-tip" id="' + it.key + '_tip"></div>'
            + sliderHtml(it.key, it.min, it.max, it.step || 1);
          if (it.tip) inner += '<div class="xtui-tip">' + esc(it.tip) + '</div>';
        } else if (it.type === 'checks') {
          if (it.label) inner += '<div class="xtui-tip">' + esc(it.label) + '</div>';
          inner += checksHtml(it.key, it.items || []);
        } else if (it.type === 'text') {
          inner += '<textarea class="xtui-field" id="' + it.key + '" rows="'
            + (it.rows || 3) + '"></textarea>'
            + btnHtml(it.key + '_save', it.saveLabel || '保存', 'filled');
        } else if (it.type === 'input') {
          inner += inputHtml(it.key, it.placeholder,
            it.btnLabel, it.key + '_btn');
          if (it.tip) inner += '<div class="xtui-tip">' + esc(it.tip) + '</div>';
        } else if (it.type === 'button') {
          inner += btnHtml(it.key, it.label, it.variant);
        } else if (it.type === 'note') {
          var ncls = it.mono ? 'xtui-mono' : 'xtui-tip';
          inner += '<div class="' + ncls + '"'
            + (it.key ? ' id="' + it.key + '"' : '') + '>'
            + esc(it.text == null ? '' : it.text) + '</div>';
        }
      }
      html += '<div class="xtui-card">' + inner + '</div>';
    }
    container.innerHTML = html;

    for (var k = 0; k < schema.length; k++) {
      var its = schema[k].items || [];
      for (var m = 0; m < its.length; m++) {
        bindItem(its[m], api);
      }
    }
  }

  function bindItem(it, api) {
    if (it.type === 'switch') {
      var n = el(it.key);
      if (n) n.addEventListener('change', function () {
        api.onSwitch(it.key, !!n.checked);
      });
    } else if (it.type === 'slider') {
      var s = el(it.key);
      if (s) {
        var upd = function () {
          var v = Number(s.value);
          var tip = el(it.key + '_tip');
          if (tip && it.fmt) tip.textContent = it.fmt(v);
          api.onSlider(it.key, v);
        };
        s.addEventListener('input', upd);
        s.addEventListener('change', upd);
      }
    } else if (it.type === 'checks') {
      var box = el(it.key);
      if (box) {
        var list = box.querySelectorAll('input[data-v]');
        for (var i = 0; i < list.length; i++) {
          list[i].addEventListener('change', function () {
            api.onMask(it.key, getMask(it.key));
          });
        }
      }
    } else if (it.type === 'text') {
      var sv = el(it.key + '_save');
      if (sv) sv.addEventListener('click', function () {
        api.onSave(it.key, getValue(it.key));
      });
    } else if (it.type === 'input') {
      var ib = el(it.key + '_btn');
      if (ib) ib.addEventListener('click', function () {
        api.onButton(it.key + '_btn', getValue(it.key));
      });
    } else if (it.type === 'button') {
      var b = el(it.key);
      if (b) b.addEventListener('click', function () { api.onButton(it.key); });
    }
  }

  /** 用一份状态对象回填所有控件（只读，不触发回调） */
  function apply(schema, state) {
    for (var i = 0; i < schema.length; i++) {
      var items = schema[i].items || [];
      for (var j = 0; j < items.length; j++) {
        var it = items[j];
        if (it.type === 'switch') {
          setChecked(it.key, state[it.key]);
        } else if (it.type === 'slider') {
          var v = Number(state[it.key]);
          if (isNaN(v)) v = it.min || 0;
          setValue(it.key, v);
          var tip = el(it.key + '_tip');
          if (tip && it.fmt) tip.textContent = it.fmt(v);
        } else if (it.type === 'checks') {
          setMask(it.key, Number(state[it.key]) || 0);
        } else if (it.type === 'text') {
          setValue(it.key, state[it.key]);
        }
      }
    }
  }

  // ==================== 上拉菜单（sheet） ====================

  /**
   * 通用底部上拉菜单，带滑入/滑出动画与背景淡入。
   *
   * XTUI.sheet({
   *   title: '标题',
   *   items: [{ key, label, sub, variant: 'text'|'filled'|'tonal'|'danger' }],
   *   onPick: function (key) {}
   * });
   *
   * 也可用 XTUI.sheetHtml(html, onReady) 传入自定义 HTML。
   * 动画时长与曲线走 CSS 变量，关闭时等动画结束再移除节点。
   */
  var SHEET_ANIM_MS = 240;

  function sheetRoot() {
    var r = document.getElementById('xtuiSheet');
    if (!r) {
      r = document.createElement('div');
      r.id = 'xtuiSheet';
      r.className = 'xtui-sheet-root';
      document.body.appendChild(r);
    }
    return r;
  }

  function closeSheet(immediate) {
    var r = document.getElementById('xtuiSheet');
    if (!r) return;
    if (r.getAttribute('data-open') !== '1') return;
    r.setAttribute('data-open', '0');
    if (immediate) { r.innerHTML = ''; return; }
    setTimeout(function () {
      if (r.getAttribute('data-open') === '0') r.innerHTML = '';
    }, SHEET_ANIM_MS);
  }

  /** 判断当前是否有菜单打开 */
  function isSheetOpen() {
    var r = document.getElementById('xtuiSheet');
    return !!r && r.getAttribute('data-open') === '1';
  }

  function openSheetHtml(html, onReady) {
    var r = sheetRoot();
    r.innerHTML = '<div class="xtui-sheet-mask"></div>'
      + '<div class="xtui-sheet-panel"><div class="xtui-sheet-grip"></div>'
      + '<div class="xtui-sheet-body">' + html + '</div></div>';
    // 先以关闭态挂载，再于下一帧打开，保证过渡动画被触发
    r.setAttribute('data-open', '0');
    var mask = r.querySelector('.xtui-sheet-mask');
    if (mask) mask.addEventListener('click', function () { closeSheet(); });
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { r.setAttribute('data-open', '1'); });
    });
    if (onReady) onReady(r);
    return r;
  }

  function sheet(opts) {
    var o = opts || {};
    var items = o.items || [];
    var html = '';
    if (o.title) html += '<div class="xtui-sheet-title">' + esc(o.title) + '</div>';
    if (o.desc) html += '<div class="xtui-sheet-desc">' + esc(o.desc) + '</div>';
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var cls = 'xtui-sheet-item';
      if (it.variant === 'danger') cls += ' danger';
      else if (it.variant === 'filled') cls += ' filled';
      html += '<button class="' + cls + '" data-key="' + esc(it.key) + '">'
        + '<span class="xtui-sheet-label">' + esc(it.label) + '</span>'
        + (it.sub ? '<span class="xtui-sheet-sub">' + esc(it.sub) + '</span>' : '')
        + '</button>';
    }
    var cancelText = o.cancelText === undefined ? '取消' : o.cancelText;
    if (cancelText) {
      html += '<button class="xtui-sheet-item xtui-sheet-cancel" data-key="__cancel">'
        + '<span class="xtui-sheet-label">' + esc(cancelText) + '</span></button>';
    }

    openSheetHtml(html, function (r) {
      var btns = r.querySelectorAll('.xtui-sheet-item');
      for (var j = 0; j < btns.length; j++) {
        btns[j].addEventListener('click', function () {
          var k = this.getAttribute('data-key');
          closeSheet();
          if (k !== '__cancel' && o.onPick) o.onPick(k);
        });
      }
    });
  }

  // ==================== 锚点菜单（menu） ====================

  /**
   * 锚点弹出菜单，类似桌面右键菜单。
   *
   * XTUI.menu({
   *   anchor: 'ui_mode',            // 元素 id 或元素本身；也可传 x / y 屏幕坐标
   *   x: 100, y: 200,               // 无 anchor 时直接用坐标
   *   align: 'start' | 'end',       // 相对锚点的水平对齐，默认 start
   *   items: [{ key, label, sub, variant: 'text'|'filled'|'danger' }],
   *   onPick: function (key) {}
   * });
   *
   * 只把面板本身设为 pointer-events:auto，外层整层保持 none，
   * 因此不会像早期 sheet 那样在关闭后继续拦截页面点击。
   * 关闭时机：选中菜单项 / 点面板外任意位置。
   */
  var MENU_ANIM_MS = 160;
  var MENU_GAP = 6;
  var MENU_MARGIN = 8;

  function menuRoot() {
    var r = document.getElementById('xtuiMenu');
    if (!r) {
      r = document.createElement('div');
      r.id = 'xtuiMenu';
      r.className = 'xtui-menu-root';
      document.body.appendChild(r);
    }
    return r;
  }

  /** 最近一次打开菜单的锚点元素：点锚点本身不算"点击外部" */
  var lastMenuAnchor = null;

  function outsideMenuHandler(e) {
    var r = document.getElementById('xtuiMenu');
    if (!r || r.getAttribute('data-open') !== '1') return;
    var panel = r.querySelector('.xtui-menu-panel');
    if (panel && panel.contains(e.target)) return;
    // 点在锚点上不关，交给锚点自己的 click 做开/关切换
    if (lastMenuAnchor && lastMenuAnchor.contains && lastMenuAnchor.contains(e.target)) return;
    closeMenu();
  }

  function closeMenu(immediate) {
    var r = document.getElementById('xtuiMenu');
    if (!r) return;
    if (r.getAttribute('data-open') !== '1') return;
    r.setAttribute('data-open', '0');
    document.removeEventListener('pointerdown', outsideMenuHandler, true);
    if (immediate) {
      r.innerHTML = '';
      r.removeAttribute('data-open');
      return;
    }
    setTimeout(function () {
      if (r.getAttribute('data-open') !== '0') return;
      r.innerHTML = '';
      // 动画结束彻底摘掉属性，root 回到 display:none，
      // 不在页面上留一层空的全屏容器
      r.removeAttribute('data-open');
    }, MENU_ANIM_MS);
  }

  function isMenuOpen() {
    var r = document.getElementById('xtuiMenu');
    return !!r && r.getAttribute('data-open') === '1';
  }

  function menu(opts) {
    var o = opts || {};
    var items = o.items || [];
    closeMenu(true);

    var r = menuRoot();
    var html = '<div class="xtui-menu-panel">';
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var cls = 'xtui-menu-item';
      if (it.variant === 'filled') cls += ' filled';
      else if (it.variant === 'tonal') cls += ' tonal';
      else if (it.variant === 'danger') cls += ' danger';
      html += '<button class="' + cls + '" data-key="' + esc(it.key) + '">'
        + '<span class="xtui-menu-label">' + esc(it.label) + '</span>'
        + (it.sub ? '<span class="xtui-menu-sub">' + esc(it.sub) + '</span>' : '')
        + '</button>';
    }
    html += '</div>';
    r.innerHTML = html;
    r.setAttribute('data-open', '0');

    var panel = r.querySelector('.xtui-menu-panel');

    // 计算锚点矩形；锚点元素同时记到 lastMenuAnchor
    var rect = null;
    lastMenuAnchor = null;
    if (o.anchor) {
      lastMenuAnchor = (typeof o.anchor === 'string') ? el(o.anchor) : o.anchor;
      if (lastMenuAnchor && lastMenuAnchor.getBoundingClientRect) {
        rect = lastMenuAnchor.getBoundingClientRect();
      }
    }

    // 先量面板尺寸，再定位（此时已在 DOM 中，但透明不可见）
    var pw = panel.offsetWidth;
    var ph = panel.offsetHeight;
    var vw = window.innerWidth;
    var vh = window.innerHeight;
    var x, y;

    if (rect) {
      x = (o.align === 'end') ? (rect.right - pw) : rect.left;
      y = rect.bottom + MENU_GAP;
      if (y + ph > vh - MENU_MARGIN) {
        var up = rect.top - MENU_GAP - ph;
        y = (up >= MENU_MARGIN) ? up : Math.max(MENU_MARGIN, vh - MENU_MARGIN - ph);
      }
    } else {
      x = Number(o.x) || 0;
      y = Number(o.y) || 0;
      if (y + ph > vh - MENU_MARGIN) y = Math.max(MENU_MARGIN, y - ph);
    }
    x = Math.min(Math.max(MENU_MARGIN, x), Math.max(MENU_MARGIN, vw - MENU_MARGIN - pw));
    y = Math.min(Math.max(MENU_MARGIN, y), Math.max(MENU_MARGIN, vh - MENU_MARGIN - ph));

    panel.style.left = Math.round(x) + 'px';
    panel.style.top = Math.round(y) + 'px';
    // 缩放起点朝向锚点：右对齐时从右上角展开，否则从左上角
    panel.style.transformOrigin = (o.align === 'end') ? 'top right' : 'top left';

    var btns = panel.querySelectorAll('.xtui-menu-item');
    for (var j = 0; j < btns.length; j++) {
      btns[j].addEventListener('click', function () {
        var k = this.getAttribute('data-key');
        closeMenu();
        if (o.onPick) o.onPick(k);
      });
    }

    /*
     * 打开方式：强制一次同步布局，再同步切换属性。
     *
     * 为什么不用 requestAnimationFrame：
     *   部分 WebView 会节流或延迟 rAF，初始态（opacity:0 / scale(.92)）
     *   和打开态可能落在同一帧提交，浏览器看不到「从哪来」，过渡直接被跳过，
     *   表现就是「菜单是弹出来了，但没有动画」。
     *
     * 读一次 offsetHeight 会立即结算样式与布局（强制 reflow），
     * 此刻面板的初始态已被浏览器确认，紧接着改 data-open 就会走过渡。
     * 定位写在 reflow 之前，同样被这次结算一并落实，
     * 避免出现「先按旧位置闪一帧再跳到正确位置」。
     */
    void panel.offsetHeight;
    r.setAttribute('data-open', '1');
    // 捕获阶段监听，保证点到页面任何位置都能关掉
    setTimeout(function () {
      document.addEventListener('pointerdown', outsideMenuHandler, true);
    }, 0);
    return r;
  }

  window.XTUI = {
    MODE_CUSTOM: MODE_CUSTOM,
    MODE_MDUI: MODE_MDUI,

    /* 上拉菜单：各页统一用这套，不再各写一遍 overlay */
    sheet: sheet,
    openSheetHtml: openSheetHtml,
    closeSheet: closeSheet,
    isSheetOpen: isSheetOpen,

    /* 锚点菜单：点击某处就地弹出，类似右键菜单 */
    menu: menu,
    closeMenu: closeMenu,
    isMenuOpen: isMenuOpen,

    mode: function () { return MODE; },
    isMdui: function () { return MODE === MODE_MDUI; },

    /** 切换界面风格：写入偏好后整页重载，保证 mdui 正确加载/卸载 */
    setMode: function (m) {
      var target = (m === MODE_MDUI) ? MODE_MDUI : MODE_CUSTOM;
      try { XTB.setUiMode(target); } catch (e) { return MODE; }
      if (target !== MODE) location.reload();
      return target;
    },
    toggleMode: function () {
      return window.XTUI.setMode(MODE === MODE_MDUI ? MODE_CUSTOM : MODE_MDUI);
    },

    el: el,
    esc: esc,
    fmtInterval: fmtInterval,
    render: render,
    apply: apply,
    bindItem: bindItem,
    toggleHtml: toggleHtml,
    sliderHtml: sliderHtml,
    btnHtml: btnHtml,
    checksHtml: checksHtml,
    getChecked: getChecked,
    setChecked: setChecked,
    getValue: getValue,
    setValue: setValue,
    getMask: getMask,
    setMask: setMask
  };
})();
