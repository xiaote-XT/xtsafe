# xtui 组件说明

星特安全的原生 UI 公共层，位于 `xiaote/xtui/`，共三个文件：

| 文件 | 职责 |
| --- | --- |
| `XtUi.java` | 骨架、卡片、文字、按钮、开关、滑块、状态栏、返回钮、按下反馈 |
| `XtDialog.java` | 系统 AlertDialog 的构造与整窗染色（含深色适配） |
| `XtToast.java` | 轻提示：系统 Toast 样式化 + 悬浮胶囊 pill / 横幅 banner |

**核心特性：零 R 引用。** 三个文件不依赖本项目的任何资源或代码，把整个 `xtui` 目录复制到另一个工程即可直接使用（返回钮图标是可选项，见下文）。

---

## 一、设计规范（硬性数值）

改这三个文件里的 public static 字段即可整体调风格。

### 颜色

| 用途 | 浅色 | 深色 |
| --- | --- | --- |
| 主题色 | `#FF6A00` | 同 |
| 主题色按下态 | `#FFE55A00` | 同 |
| 页面底色 `bg` | `#FFFFFF` | `#1E1E1E` |
| 卡片底色 `card` | `#FFFFFF` | `#1E1E1E` |
| 正文 `text` | `#1E1E1E` | `#FFFFFF` |
| 次要文字 `muted` | `#5E5E5E` | `#B0B0B0` |
| 描边 `border` | `#DDDDDD` | `#444444` |
| Toast 底 / 字 | `#FBFBFB` / `#161616` | `#161616` / `#FFFFFF` |

前景与背景同色，靠 **0.75dp 细描边**分层，**不使用阴影**。

### 尺寸

| 项 | 值 |
| --- | --- |
| 主圆角（卡片 / 按钮 / 输入框） | 16dp |
| 上拉面板圆角 | 20dp |
| 菜单圆角 | 12dp |
| 描边宽度 | 0.75dp |
| 卡片内边距 | 16dp |
| 卡片下间距 | 14dp |
| 按钮文字 | 16sp |
| 按钮内边距 | 上下 13dp / 左右 16dp |
| 分区小标题 | 20sp 居中主题色 |
| 按下缩放 | 0.97，120ms 渐变，松手延迟 100ms 回弹 |
| 开关 | 50 × 24dp 胶囊 |

---

## 二、快速上手

### 1. 一个标准页面

```java
public class DemoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 骨架：column 自带页面底色 + 16dp 内边距
        LinearLayout root = XtUi.column(this);
        // scroll 把 root 装进 ScrollView 并返回它
        ScrollView scrollView = XtUi.scroll(this, root);

        // 大标题 + 状态栏同色
        root.addView(XtUi.title(this, "示例页"));
        XtUi.applyStatusBar(this);

        // 一张带小标题的卡片（小标题在卡片内部）
        LinearLayout card = XtUi.cardWithSection(this, "基础设置");

        XtUi.addButton(card, this, "按钮一", new View.OnClickListener() {
            @Override public void onClick(View v) {
                XtToast.show(DemoActivity.this, "点了一下");
            }
        });

        // 一行：左标签 + 右开关
        XtUi.XtSwitch sw = XtUi.switchView(this, false);
        sw.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(XtUi.XtSwitch v, boolean checked) {
                // 写偏好
            }
        });
        card.addView(XtUi.row(this, "启用某功能", sw));

        root.addView(card);

        // 注意：root 已经在 scrollView 里了，不能再 addView(root)
        setContentView(scrollView);

        // 左上角圆形返回钮（可选，需要图标资源，见下文）
        XtUi.attachBack(this);
    }
}
```

**最容易踩的坑**：`XtUi.scroll(this, root)` 内部已经 `addView(root)`。再写 `scrollView.addView(root)` 会抛
`IllegalStateException: ScrollView can host only one direct child`。

### 2. 不需要滚动的页面（列表类）

列表要撑满剩余高度，不能塞进 ScrollView：

```java
LinearLayout root = XtUi.column(this);
root.setPadding(0, 0, 0, 0);          // 去掉整体内边距，由各区自行留白

root.addView(XtUi.title(this, "列表页"));
XtUi.applyStatusBar(this);

ListView lv = new ListView(this);
lv.setBackgroundColor(XtUi.colorBg(this));
lv.setDivider(new ColorDrawable(XtUi.colorBorder(this)));
lv.setDividerHeight(Math.max(1, XtUi.dp(this, XtUi.borderDp)));

// 用 weight=1 撑满剩余空间
root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));

setContentView(root);
XtUi.attachBack(this);
```

### 3. 弹窗

```java
// 简易：标题 + 内容 + 一个确定
XtDialog.show(this, "提示", "内容");

// 双按钮
XtDialog.show(this, "确认", "确定删除？",
        "删除", new DialogInterface.OnClickListener() { ... },
        "取消", null);

// 需要 setCancelable(false) 时，用 builder + create + tint 手动收尾
AlertDialog dlg = XtDialog.builder(this, "标题", "内容")
        .setPositiveButton("确定", null)
        .create();
dlg.setCancelable(false);
dlg.show();
XtDialog.tint(dlg);           // 必须显式调，否则没有圆角底板与深色适配

// 带输入框
EditText input = XtDialog.input(this);
AlertDialog d = XtDialog.inputBuilder(this, "标题", "说明", input)
        .setPositiveButton("确定", listener)
        .create();
d.show();
XtDialog.tint(d);

// 单选 / 多选（列表深色适配由 tint 自动处理）
AlertDialog d2 = new AlertDialog.Builder(this)
        .setTitle("选择")
        .setSingleChoiceItems(items, checked, listener)
        .setNegativeButton("取消", null)
        .create();
d2.show();
XtDialog.tint(d2);
```

**`tint()` 做了什么**：
1. 窗口背景置透明，宽度收到屏幕 90%
2. 给根布局铺卡色底 + 16dp 圆角（**无描边**，按需求不加细边框）
3. 标题 / 内容 / 三个按钮分别染色（按钮统一主题色 `#ff6a00`）
4. 兜底遍历：按 id 跳过按钮面板与 button1/2/3，其余 TextView 按字号区分标题（≥16sp）与内容
5. 单选 / 多选列表：底色透明、分隔线换 xtui 边框色、可见子项逐个染色，并挂 `OnScrollListener` 覆盖滚动复用
6. 给 `android.R.id.custom` 容器补最小 20dp 左右内边距，防止 `setView` 的内容贴边
7. 弹窗出现时 0.9 → 1.0 缩放，180ms 减速

### 4. 轻提示

```java
// 系统 Toast 的样式化包装（大多数场景用这个）
XtToast.show(this, "已保存");
XtToast.show(this, R.string.xxx);
XtToast.showLong(this, "较长的提示");

// 悬浮胶囊：用于无障碍服务等后台场景（系统 Toast 在后台常被拦截）
XtToast toast = new XtToast(accessibilityService, handler);
toast.showPill("已阻止卸载", 1500L);
toast.showPill(R.string.volume_press_count, 1500L, count);   // 带格式化参数

// 横幅：可点击，需手动收起
toast.showBanner("检测到风险", new XtToast.OnClick() {
    @Override public void onClick() { /* 跳转 */ }
});
toast.hideBanner();

// 页面销毁时清理
toast.cleanup();
```

pill 的动画：入场 180ms 淡入下滑，出场 150ms 淡出上移（动画结束才真正 `removeView`）。
淡出途中来了新提示会取消动画并复位，不会叠影。

---

## 三、XtUi API 速查

### 取色与换算

```java
XtUi.isDark(context)          // 当前是否深色
XtUi.forceDark = true;        // 强制深色（null 表示跟随系统）
XtUi.dp(context, 16f)         // dp → px

XtUi.colorBg(c)               // 页面底色
XtUi.colorCard(c)             // 卡片底色
XtUi.colorText(c)             // 正文色
XtUi.colorMuted(c)            // 次要文字色
XtUi.colorBorder(c)           // 描边色
```

### 骨架

```java
XtUi.column(c)                     // 纵向根容器（底色 + 16dp 内边距）
XtUi.scroll(c, root)               // root 装进 ScrollView，返回 ScrollView
XtUi.card(c)                       // 卡片（卡色 + 细描边 + 16dp 圆角 + 无阴影）
XtUi.cardWithSection(c, "小标题")   // 带小标题的卡片，小标题 20sp 居中主题色
```

### 文字

```java
XtUi.title(c, "大标题")     // 20sp 主题色居中
XtUi.tip(c, "提示")         // 12sp muted
XtUi.body(c, "正文")        // 14sp 正文色
```

### 按钮

```java
XtUi.button(c, "文字", listener)         // 卡色底 + 细描边
XtUi.primaryButton(c, "文字", listener)  // 主题色底 + 白字
XtUi.addButton(root, c, "文字", listener) // 加进容器并自带上下 6dp 间距

XtUi.attachScale(view)                    // 手动给任意 View 加按下 0.97 反馈
```

按钮返回的是 `TextView`，不是 `Button`。要改文字直接 `setText()`，要禁用用 `setEnabled(false)` + `setAlpha(0.4f)`。

### 开关与滑块

```java
XtUi.XtSwitch sw = XtUi.switchView(c, false);
sw.setOnCheckedChangeListener(listener);
sw.isChecked();
sw.setChecked(true);   // 注意：不触发监听，专门给代码回写状态用
sw.toggle();           // 会触发监听

root.addView(XtUi.row(c, "标签", sw));   // 左标签 + 右控件

SeekBar sb = XtUi.slider(c, max, progress);
// 已设主题色进度 / 滑块，左右各留 12dp 防止两端圆点被裁
```

**重要**：`XtSwitch.setChecked()` 刻意不触发监听（避免递归）。这与系统 `Switch` 的行为不同。
如果代码回写后还要同步其它 UI，自己调一下刷新方法。

### 状态栏与返回钮

```java
XtUi.applyStatusBar(activity);   // 状态栏与页面同色，深浅色自动切图标

XtUi.attachBack(activity);       // 左上角 40dp 圆形返回钮，点击 finish()
```

`attachBack` 的实现：把 `android.R.id.content` 里的页面视图取出，套一层 `FrameLayout`，
再把圆钮浮在左上角。**不动各页原有布局**，所以标题仍然居中，不受影响。

圆钮参数可调：

```java
XtUi.backSizeDp = 40f;     // 圆钮直径
XtUi.backIconDp = 24f;     // 图标尺寸
XtUi.backMarginDp = 12f;   // 距左上边距
XtUi.backIconRes = R.drawable.xxx;   // 手动指定图标（默认按名字自动查找）
```

**图标是可选的**。默认按名字查 `xt_ic_arrow_left`（drawable），查不到就回退成文本 `←`，
不会崩。想用图标就在 `res/drawable-nodpi/` 放一个 24×24 的 PNG，命名为 `xt_ic_arrow_left.png`。
图标本身无需着色，代码会用 `setColorFilter(colorText(), SRC_IN)` 按深浅动态染色。

---

## 四、XtDialog API 速查

```java
// 取色
XtDialog.colorTitle(c)      // 标题色
XtDialog.colorContent(c)    // 内容色

// 构造
XtDialog.builder(c, title, msg)
XtDialog.inputBuilder(c, title, msg, inputView)
XtDialog.input(c)           // 带细描边 + 16dp 圆角 + 12dp 内边距 + 44dp 最小高度的 EditText
XtDialog.content(c)         // 带 20dp 内边距的纵向容器，需要自定义内容时用

// 显示
XtDialog.show(c, title, msg)
XtDialog.show(c, title, msg, positive, onOk)
XtDialog.show(c, title, msg, positive, onOk, negative, onCancel)
XtDialog.show(c, title, msg, cancelable)

// 染色（builder 路径必须手动调）
XtDialog.tint(dlg)
XtDialog.tintSystemText(dlg)   // 只染文字
XtDialog.animateScale(dlg)     // 只播缩放动画

// 控件染色（自定义弹窗内容时用）
XtDialog.tintCheckBoxes(root)
XtDialog.tintListItems(listView)
XtDialog.tintListItem(view)
```

**`XtDialog.input()` 刻意不设任何外边距。** 宽度完全交给父容器，
这样放进任何布局都能自动对齐，不会出现「输入框比按钮窄一圈」或「输入框和按钮贴一起」。
防贴边由 `tint()` 里的 `applyCustomPadding` 给 `android.R.id.custom` 容器补内边距解决。

**关于 `android.R` 索引**：AIDE 等环境对 `android.R.id` 索引不全，直接写
`android.R.id.alertTitle` 会报「未知成员」。`XtDialog` 内部统一用反射按名字查，
所以它在这种环境下也能编译。

---

## 五、移植到其他项目

### 步骤

1. **复制目录**：把 `xiaote/xtui/` 整个复制到你项目的 `src/main/java/` 下（包名可以改，改完记得同步 import）。
2. **（可选）放图标**：需要返回钮有图标就在 `res/drawable-nodpi/` 放 `xt_ic_arrow_left.png`（24×24）。
3. **（可选）加主题**：如果想让页面不带系统 ActionBar，在 `res/values/themes.xml` 加：

```xml
<style name="AppTheme.NoActionBar" parent="@android:style/Theme.DeviceDefault.Light.NoActionBar">
    <item name="android:windowBackground">@color/xt_bg</item>
    <item name="android:colorAccent">@color/xt_primary</item>
</style>
```

并在 `values/colors.xml` / `values-night/colors.xml` 里定义 `xt_bg`、`xt_primary`。
**这一步不是必须的** —— `XtUi` 内部所有颜色都是运行时算出来的，不读资源，
加主题只是让系统窗口背景与强调色也对齐。

4. **改包名**：全局替换 `xiaote.xtui` 为你的包名。

### 需要注意的边界

| 项 | 说明 |
| --- | --- |
| `applyStatusBar` / `attachBack` | 参数是 `Activity`，必须在 Activity 里调 |
| `XtToast` 的 pill / banner | Context 必须是 `AccessibilityService`，因为走 `TYPE_ACCESSIBILITY_OVERLAY`，普通 Activity 拿不到这个窗口类型 |
| `XtToast` 静态 `show` | Android 11+ 会忽略自定义 View，圆角不生效但**不崩**，文字照常显示 |
| `attachBack` 的 id 查找 | 用 `getIdentifier("content", "id", "android")`，不直接写 `android.R.id.content`，同样是为了绕开索引不全 |
| `XtSwitch` | 自绘 View，不是系统 Switch，没有 `setTextOn/setTextOff`，标签请用 `XtUi.row()` 放左侧 |

### 最小依赖

`XtUi` 只依赖 `XtUi` 自己（`isDark` / `dp`）。
`XtDialog` 依赖 `XtUi`（取色、dp、描边宽度）。
`XtToast` 依赖 `XtUi`（`dp`、`isDark`）。

即三个文件是一个整体，**不能只复制其中一个**（除非把引用到的 `XtUi` 方法一起带过去）。

---

## 六、常见问题

**Q：页面一片白，卡片看不出边界？**
卡片靠 0.75dp 描边分层，不是靠阴影。如果底色和卡片色一样（本来就一样），只能看到细描边。
这是设计如此。想要更明显的层次，改 `XtUi.borderLight/borderDark` 加深一点。

**Q：`ScrollView can host only one direct child`？**
`XtUi.scroll()` 已经把 root 装进去了，后面不要再来一次 `scrollView.addView(root)`。

**Q：弹窗按钮不是主题色？**
检查是不是自己 new 的 `AlertDialog.Builder` 而不是走 `XtDialog`，并且 `show()` 之后**有没有调 `tint()`**。
`tint()` 里对 button1/2/3 做了单独的 id 匹配染色，兜底遍历里也按 id 跳过按钮，不会覆盖。

**Q：滑块拖到两端圆点缺一半？**
`XtUi.slider()` 已经设了左右各 12dp 内边距与 `setThumbOffset(0)`。
如果你之后又调了 `seek.setPadding(0, 0, 0, x)`，会把左右内边距覆盖掉，改成
`setPadding(XtUi.dp(c, 12), 0, XtUi.dp(c, 12), x)`。

**Q：开关拨动后状态没保存？**
`XtSwitch.setChecked()` 不触发监听。用户点击走 `toggle()`，会触发；
代码回写走 `setChecked()`，不会触发 —— 这是刻意设计，避免「回写状态导致再次写偏好」的递归。
