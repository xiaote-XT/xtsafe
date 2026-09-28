# DhizukuTool — 无 SDK 的 Dhizuku 客户端实现

> 面向 AI 助手/开发者的使用说明。目标：不依赖 Dhizuku-API AAR，纯手写 AIDL，直接调用 Dhizuku 服务端完成设备所有者级操作。

---

## 1. 它是什么

一套自己实现的 Dhizuku 客户端，位于包 `xiaote.dhizukutool`。

不引入 `Dhizuku-API-2.6.0.aar`，所有 AIDL 接口（`IDhizuku`、`IDhizukuRemoteProcess` 等）都是手写的 `Binder` + `Proxy` + `Parcel` 代码，常量值从官方 AAR 反编译提取，与服务端字节级兼容。

**用法**：把 `app/src/main/java/xiaote/dhizukutool/` 整个目录复制到目标工程，无需任何依赖（Java 8）。

---

## 2. 文件职责

| 文件 | 作用 |
|---|---|
| `Dhizuku.java` | **主入口**，全静态方法。init / 权限 / 进程 / binder 代理 / UserService / DelegatedScopes |
| `DhizukuVariables.java` | 全部常量（包名、authority、action、Bundle 键、transact 码 11） |
| `DhizukuDevicePolicy.java` | DevicePolicyManager 代理封装：`lockNow`、`setUninstallBlocked`、`isUninstallBlocked` |
| `DhizukuRemoteProcess.java` | `Process` 子类，包装远程进程的 stdin/stdout/stderr |
| `DhizukuBinderWrapper.java` | `IBinder` 包装器，把 transact 全部转发到 Dhizuku 服务端 |
| `DhizukuUserServiceArgs.java` | UserService 参数 Builder |
| `DhizukuClient.java` | 客户端身份桩（`VERSION_CODE = 1`） |
| `DhizukuRequestPermissionListener.java` | 权限回调基类，继承它实现 `onRequestPermission(int)` |
| `IDhizuku.java` | 服务端主接口（9 个方法） |
| `IDhizukuRemoteProcess.java` | 远程进程接口（8 个方法） |
| `IDhizukuClient.java` | 客户端标识接口（1 个方法） |
| `IDhizukuUserServiceConnection.java` | UserService 连接回调 |
| `IDhizukuRequestPermissionListener.java` | 权限回调接口 |

---

## 3. 快速调用流程

```java
// 1. 初始化（连接 Dhizuku 服务端）
if (!Dhizuku.init(context)) {
    // 失败：Dhizuku 未安装 / 未设为设备所有者
}

// 2. 检查权限
if (!Dhizuku.isPermissionGranted()) {
    // 3. 请求权限（跳 Dhizuku 授权页，结果通过 listener 回调）
    Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
        @Override
        public void onRequestPermission(int requestCode) {
            // requestCode == PackageManager.PERMISSION_GRANTED(0) 或 PERMISSION_DENIED(-1)
        }
    });
}

// 4. 执行操作（授权后）
```

所有特权调用都必须放在子线程，不能跑在主线程。

---

## 4. API 清单

### 4.1 Dhizuku（主入口）

```java
// --- 初始化 ---
static boolean init(Context)        // 主用这个
static boolean init()               // 反射取 ActivityThread.currentApplication

// --- 权限 ---
static boolean isPermissionGranted()
static void    requestPermission(DhizukuRequestPermissionListener)

// --- 服务端信息 ---
static int           getVersionCode()
static String        getVersionName()
static String        getOwnerPackageName()
static ComponentName getOwnerComponent()
static ComponentName getOwnerComponent(Context)

// --- 远程进程：以设备所有者身份执行命令 ---
static DhizukuRemoteProcess newProcess(String[] cmd, String[] env, File dir)

// --- Binder 代理：让任意系统服务调用以 DO 权限执行 ---
static IBinder binderWrapper(IBinder target)
static boolean remoteTransact(IBinder target, int code, Parcel data, Parcel reply, int flags)

// --- UserService（在 Dhizuku 进程内启动你自己的 Service）---
static void    startUserService(DhizukuUserServiceArgs)   // 注意：实现有误，见第 8 节
static void    stopUserService(DhizukuUserServiceArgs)
static boolean bindUserService(DhizukuUserServiceArgs, ServiceConnection)
static boolean unbindUserService(ServiceConnection)

// --- 委托范围 ---
static String[] getDelegatedScopes()
static void     setDelegatedScopes(String[] scopes)
```

### 4.2 DhizukuRemoteProcess（继承 java.lang.Process）

```java
OutputStream getOutputStream()
InputStream  getInputStream()
InputStream  getErrorStream()
int          waitFor()
boolean      waitFor(long, TimeUnit)
int          exitValue()
void         destroy()
boolean      isAlive()
```

### 4.3 DhizukuDevicePolicy（已封装的特权调用）

```java
// 取系统 DPM 背后的原始 binder（排查/扩展用）
static IBinder getDevicePolicyBinder(Context)

// 锁屏（已验证可用）
static boolean lockNow(Context)

// 禁止 / 允许卸载指定应用（对任意包名生效）
static void    setUninstallBlocked(Context, String packageName, boolean blocked)
static boolean isUninstallBlocked(Context, String packageName)
```

---

## 5. 能做什么

`remoteTransact` / `binderWrapper` 是**通用代理**，模型为：

```
你的进程 → Dhizuku 服务端（设备所有者）→ 目标系统服务 Binder.transact(code, data, reply)
```

只要满足三条，任意需要设备所有者权限的系统服务调用都能执行：

1. 能拿到目标系统服务的 `IBinder`（`getSystemService` 反射 `mService`）
2. 知道方法的 transact 码（反射 `IXxx$Stub.TRANSACTION_yyy`）
3. 知道 Parcel 参数写入顺序（按 AIDL 签名逐个 write）

**已封装可用**：`lockNow`（锁屏）、`setUninstallBlocked` / `isUninstallBlocked`（禁止卸载）。

**典型可扩展**：`wipeData`、`reboot`、`setCameraDisabled`、`setScreenCaptureDisabled`、`setApplicationHidden`、`setPasswordQuality`、`setStatusBarDisabled`、`resetPassword` 等。

**不能做**：

- `input keyevent` 这类需要 `INJECT_EVENTS` 的命令。Dhizuku 进程 UID 仍是普通应用，`input` 会静默失败并以 exit=0 返回，**必须检查 stderr**，不能只看退出码。
- 需要 root 的操作。
- 卸载 / 冻结 / 重启系统设置等破坏性操作。

---

## 6. 禁止卸载（setUninstallBlocked）详细说明

### 6.1 它做什么

让系统的卸载入口拒绝卸载指定应用（设置里点卸载、`pm uninstall` 会被拒）。应用本身照常运行、照常出现在桌面。这是「阻止卸载」，不是「隐藏」也不是「冻结」。

### 6.2 参数含义（关键，容易搞混）

AIDL 签名：

```
setUninstallBlocked(ComponentName admin, String callerPackageName, String packageName, boolean blocked) -> void
```

| 参数 | 填什么 | 能否换 |
|---|---|---|
| `admin` | **必须是 Dhizuku 的设备所有者组件**（`Dhizuku.getOwnerComponent()`） | 不能。system_server 拿它校验调用者是不是当前 DO |
| `callerPackageName` | **Dhizuku 自己的包名**（`Dhizuku.getOwnerPackageName()`） | 不能。走代理时实际调用方就是 Dhizuku 进程 |
| `packageName` | **目标应用包名** | 能，任意应用 |
| `blocked` | true / false | 能 |

**「针对哪个应用」由 `packageName` 控制，不是由 `admin` 控制。** `admin` 写死成 Dhizuku 组件是正确的，不代表只能操作 Dhizuku。

### 6.3 使用示例

```java
// 禁止卸载某应用
DhizukuDevicePolicy.setUninstallBlocked(context, "com.tencent.mm", true);

// 解除禁止
DhizukuDevicePolicy.setUninstallBlocked(context, "com.tencent.mm", false);

// 查询当前状态
boolean blocked = DhizukuDevicePolicy.isUninstallBlocked(context, "com.tencent.mm");
```

Demo 里输入框留空 = 操作自己（`getPackageName()`），填包名 = 操作那个应用。

### 6.4 边界

- **解除需要同一 DO 身份**：只要 Dhizuku 还在、权限还在，随时能解。不是不可逆操作。
- **不能拦 root / adb 绕过**：设备所有者能设的只是「系统卸载器拒绝」。有 root 或 `pm uninstall --user 0` 仍可能绕开。
- **对 Dhizuku 本体调用未必生效**：Dhizuku 本身是 DO，Android 对 DO 应用有单独保护逻辑。这条未实测。
- **包名不存在会抛异常**：`reply.readException()` 会把 `IllegalArgumentException` 抛成 `RuntimeException`，调用处要包 try-catch。

---

## 7. 扩展新指令的四步法

```java
// 步骤 1：拿目标服务的 IBinder
IBinder target = DhizukuDevicePolicy.getDevicePolicyBinder(context);
// 或其它服务：反射 context.getSystemService(X).getDeclaredField("mService")

// 步骤 2：包一层代理
IBinder wrapped = Dhizuku.binderWrapper(target);

// 步骤 3：按 AIDL 签名写参数，用对的 transact 码
Parcel data = Parcel.obtain();
Parcel reply = Parcel.obtain();
try {
    int code = transactCode("TRANSACTION_xxx");   // 反射 IDPm$Stub 的常量
    data.writeInterfaceToken("android.app.admin.IDevicePolicyManager");
    data.writeInt(...);        // 严格按 AIDL 参数顺序
    data.writeString(...);
    wrapped.transact(code, data, reply, 0);
    reply.readException();
    // 步骤 4：解析返回值（int/boolean/String/Parcelable）
} finally {
    data.recycle();
    reply.recycle();
}
```

### transact 码从哪来

**运行时反射当前设备 framework 的 `IXxx$Stub.TRANSACTION_yyy`**，不写死数字。原因：

- 不同 Android 版本 DPM 接口持续新增方法，编号会重排，硬编码换设备就错
- `DhizukuDevicePolicy.transactCode()` 反射失败时**直接抛 `IllegalStateException`**，不返回猜测值
- 错误的 transact 码会让服务端走 `super.onTransact`，表现为静默失败或返回 null，比直接报错难查得多

### Parcelable 参数怎么写

AIDL 生成的 `writeTypedObject` 线格式是「先写 int 非空标志(1/0)，非空才 writeToParcel」。`DhizukuDevicePolicy.writeTypedParcelable()` 就是这个：

```java
if (v != null) { p.writeInt(1); v.writeToParcel(p, 0); }
else { p.writeInt(0); }
```

不要直接 `writeParcelable`，线格式不同，服务端读出来会错位。

---

## 8. 关键常量（DhizukuVariables）

| 常量 | 值 |
|---|---|
| PACKAGE_NAME | `com.rosan.dhizuku` |
| PERMISSION_API | `com.rosan.dhizuku.permission.API` |
| Provider Authority | `com.rosan.dhizuku.server.provider` |
| 权限请求 Action | `com.rosan.dhizuku.action.request.permission` |
| BINDER_DESCRIPTOR | `com.rosan.dhizuku.server`（remoteTransact 用） |
| AIDL DESCRIPTOR | `com.rosan.dhizuku.aidl.IDhizuku` |
| Bundle 键：服务端 binder | `dhizuku_binder` |
| Bundle 键：客户端 | `client` |
| Bundle 键：UID | `uid` |
| TRANSACT_CODE_REMOTE_BINDER | 11 |

### IDhizuku 事务码（非连续，必须精确）

| 方法 | code |
|---|---|
| getVersionCode | 1 |
| getVersionName | 2 |
| isPermissionGranted | 3 |
| **remoteProcess** | **12** |
| bindUserService | 13 |
| unbindUserService | 14 |
| unbindUserServiceByConnection | 15 |
| getDelegatedScopes | 16 |
| setDelegatedScopes | 17 |

### IDhizukuRemoteProcess 事务码（连续）

`1=getOutputStream 2=getInputStream 3=getErrorStream 4=exitValue 5=destroy 6=alive 7=waitFor 8=waitForTimeout`

### DevicePolicyManager 事务码

不写死，运行时反射 `android.app.admin.IDevicePolicyManager$Stub` 的对应 `TRANSACTION_*` 字段。

---

## 9. 踩过的坑（改代码前必读）

1. **IDhizuku 的 transact 码不连续**：1~3 之后跳到 12。写成 4/5/6 会导致 `remoteProcess` 返回 null binder，报 `asBinder() on a null object reference`。
2. **remoteTransact 必须写 dataSize**：`writeInterfaceToken → writeStrongBinder → writeInt(code) → writeInt(data.dataSize()) → appendFrom(data)`。少了 `writeInt(dataSize)` 服务端解析错位。
3. **ParcelFileDescriptor 线格式**：官方 AIDL 用 `writeTypedObject`/`readTypedObject`（API 30 才公开）。老 SDK 编译不过，本项目用反射调用，失败才退回 `writeToParcel`/`CREATOR`。
4. **String[] 读取用 `createStringArray()`**：`Parcel` 没有无参 `readStringArray()`。
5. **`setUninstallBlocked` 参数顺序**：AIDL 是 `(admin, callerPackageName, packageName, blocked)`。把目标包名写到 callerPackageName 槽位会抛 `SecurityException: Caller with uid X is not <包名>`。
6. **`admin` 参数不能换成目标应用**：它必须是 Dhizuku 的 DO 组件，否则 system_server 校验不过。目标应用由 `packageName` 指定。
7. **`input keyevent` 不可用于锁屏**：见第 5 节。Dhizuku 进程 UID 是普通应用，`input` 静默失败且 exit=0。
8. **`lockNow(int flags)` vs `lockNow()`**：Android 10 (API 29) 起带 int 参数，之前无参。`lockNowHasFlags()` 反射判断，拿不到按 `SDK_INT >= 29` 兜底。
9. **AIDE 的 aapt 不认 `<queries>` 标签**：本工程 targetSdk=26，不受 Android 11 包可见性限制，删掉即可。若 targetSdk 提到 30+，必须加回并换构建工具。
10. **`Dhizuku.startUserService()` 实现有误**：内部走了 `bindUserService(null, args)`，语义不对。UserService 场景需单独修正；`newProcess` / `lockNow` 等路径不受影响。
11. **隐藏 API 反射限制**：本工程 targetSdk=26，不受 Android 9+ 隐藏 API 黑名单影响，可放心反射 `mService`、`TRANSACTION_*`。宿主 targetSdk ≥ 28 时需实测，见第 11 节。

---

## 10. 最小可运行示例

### 10.1 初始化 + 权限

```java
if (!Dhizuku.init(this)) return;
if (!Dhizuku.isPermissionGranted()) {
    Dhizuku.requestPermission(listener);
    return;
}
```

### 10.2 锁屏

```java
new Thread(() -> {
    boolean ok = DhizukuDevicePolicy.lockNow(MainActivity.this);
    // ok == true 表示 transact 成功返回
}).start();
```

### 10.3 禁止卸载

```java
new Thread(() -> {
    try {
        DhizukuDevicePolicy.setUninstallBlocked(this, "com.target.app", true);
        boolean ok = DhizukuDevicePolicy.isUninstallBlocked(this, "com.target.app");
    } catch (Throwable t) {
        // 看 t.getMessage()
    }
}).start();
```

完整 Demo 见 `app/src/main/java/xiaote/demo/dhizuku/api/MainActivity.java`：

- 锁屏：路线 A `lockNow()`，路线 B 兜底 `input keyevent 26` 并检查退出码与 stderr
- 禁止卸载：输入框填目标包名（留空 = 自己），点一次切换状态，先查再改再回读

---

## 11. 复制到其他工程

复制 `xiaote/dhizukutool/` 整个目录即可，不要复制 Demo 的 `MainActivity.java`（它引用 Demo 自己的 `R.layout` / `R.id`）。

### 必需声明

```xml
<uses-permission android:name="com.rosan.dhizuku.permission.API" />
```

### 构建配置

| 项 | 要求 |
|---|---|
| minSdk | ≥ 26（Dhizuku 本体要求） |
| Java | 1.8 |
| 依赖 | 无 |

### 按宿主 targetSdk 分三种情况

| 宿主 targetSdk | 需要做什么 |
|---|---|
| **≤ 27** | 只加权限声明，直接可用（当前 Demo 是 26） |
| **28 ~ 29** | 隐藏 API 反射可能被拦，`DhizukuDevicePolicy` 取 `mService` / `TRANSACTION_*` 可能抛异常，需实测 |
| **≥ 30** | 必须加下面的 `<queries>`，否则 `init()` 直接失败 |

```xml
<queries>
    <provider android:authorities="com.rosan.dhizuku.server.provider" />
    <intent>
        <action android:name="com.rosan.dhizuku.action.request.permission" />
    </intent>
</queries>
```

注意：若宿主用 AIDE 构建且 targetSdk ≥ 30，AIDE 的 aapt 不认 `<queries>`，会报 `Tag <provider> missing required attribute name`。两者不能兼得，只能降 targetSdk 或换 Gradle。

### 不需要改的

- **包名不用动**：代码里全是硬编码字符串，与宿主 `applicationId` 无关。
- **不用加混淆规则**：`DhizukuClient` 作为 Binder 传给服务端，服务端按 `DESCRIPTOR` 字符串查找，不依赖类名。

---

## 12. 环境要求

- minSdk 26（Dhizuku 本体要求）
- Java 8（`compileOptions` 已配）
- AndroidManifest 需声明 `<uses-permission android:name="com.rosan.dhizuku.permission.API" />`
- 设备需安装 Dhizuku 并已设为 Device Owner
- 所有特权调用放子线程
