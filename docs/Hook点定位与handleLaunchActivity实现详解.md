# Hook点定位与handleLaunchActivity实现详解

> 基于 AndroidContainer 项目与 Android Framework 源码的深度分析
> 项目路径: D:\Work\Android\learn\AndroidContainer

---

## 目录

- [一、Hook点定位原理](#一hook点定位原理)
- [二、AMS Hook实现详解](#二ams-hook实现详解)
- [三、handleLaunchActivity实现详解](#三handlelaunchactivity实现详解)
- [四、底层原理与Framework源码分析](#四底层原理与framework源码分析)

---

## 一、Hook点定位原理

### 1.1 什么是Hook点？

**Hook点**是指可以被拦截和修改的方法调用位置。选择合适的Hook点是插件化技术的核心。

```
┌────────────────────────────────────────────────────────────┐
│                    Hook点三要素                              │
├────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐    拦截    ┌─────────────┐               │
│  │ 原始调用点  │ ─────────→ │ Hook处理器  │               │
│  │ (Hook Point)│           │             │               │
│  └─────────────┘            └─────────────┘               │
│         │                           │                      │
│         │        替换/修改           │                      │
│         ↓                           ↓                      │
│  ┌─────────────┐              ┌─────────────┐            │
│  │ 原始行为    │              │ 自定义行为  │            │
│  └─────────────┘              └─────────────┘            │
│                                                             │
└────────────────────────────────────────────────────────────┘
```

### 1.2 Hook点选择的四个原则

#### 原则一：必须是单例或静态变量

**原因：** 单例和静态变量在进程内唯一，替换后全局生效。

**Framework中的单例示例：**
```java
// ActivityManager.java (Android 8.0+)
public class ActivityManager {
    // 单例：IActivityManagerSingleton
    private static final Singleton<IActivityManager> IActivityManagerSingleton =
            new Singleton<IActivityManager>() {
                @Override
                protected IActivityManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
                    final IActivityManager am = IActivityManager.Stub.asInterface(b);
                    return am;
                }
            };
}

// ActivityThread.java
public final class ActivityThread {
    // 静态变量：当前ActivityThread实例
    private static volatile ActivityThread sCurrentActivityThread;

    // 静态变量：PackageManager代理
    static IPackageManager sPackageManager;
}
```

#### 原则二：必须有公开接口

**原因：** 动态代理只能代理接口类型。

```java
// IActivityManager是接口，可以被代理
public interface IActivityManager extends IInterface {
    public int startActivity(IApplicationThread caller, String callingPackage,
                             Intent intent, String resolvedType, IBinder resultTo,
                             String resultWho, int requestCode, int flags,
                             ProfilerInfo profilerInfo, Bundle options) throws RemoteException;
    // ... 其他方法
}

// 可以创建动态代理
Object proxy = Proxy.newProxyInstance(
    classLoader,
    new Class[]{IActivityManager.class},  // 接口类型
    handler
);
```

#### 原则三：Hook点位置要早

**原因：** 越早拦截，越能避免后续验证失败。

```
Activity启动流程中的Hook点时机：

startActivity() 调用
    ↓
Instrumentation.execStartActivity()  ← Hook点1 (太早，不推荐)
    ↓
ActivityManager.getService()  ← Hook点2 (推荐)
    ↓
IActivityManager.startActivity()  ← Hook点3 (核心Hook点)
    ↓
AMS验证 (Manifest检查)
    ↓
ActivityThread.H.LAUNCH_ACTIVITY  ← Hook点4 (恢复真实Activity)
    ↓
Activity创建
```

#### 原则四：跨版本稳定性

**原因：** Framework API会变化，需要选择相对稳定的Hook点。

| Android版本 | Hook点变化 |
|------------|-----------|
| API < 26 | `ActivityManagerNative.gDefault` |
| API >= 26 | `ActivityManager.IActivityManagerSingleton` |
| API >= 28 | 部分隐藏API加强限制 |

### 1.3 Activity启动完整流程与Hook点

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Activity启动流程详解                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  [客户端进程]                    [系统进程(AMS)]                          │
│                                                                         │
│  1. Activity.startActivity()                                           │
│     ↓                                                                   │
│  2. Instrumentation.execStartActivity()                                 │
│     ↓                                                                   │
│  3. ActivityManager.getService()                                       │
│     │                                                                   │
│     │ ┌──────────────────────────────────────┐                          │
│     │ │  Hook点1: 获取AMS单例                 │                          │
│     │ │  ActivityManager.IActivityManager    │                          │
│     │ │         .Singleton                   │                          │
│     │ └──────────────────────────────────────┘                          │
│     ↓                                                                   │
│  4. IActivityManager.startActivity()  ──→ 5. AMS.startActivity()       │
│     │                                        ↓                         │
│     │                                   6. 解析Intent                    │
│     │                                   7. 验证Manifest                  │
│     │                                   8. 创建ActivityRecord           │
│     │                                        ↓                         │
│     │  9. 返回结果  ←───────────────────  9. 通知客户端                  │
│     ↓                                                                   │
│ 10. 进入等待队列                                                         │
│     ↓                                                                   │
│  11. ActivityThread.H.sendMessage(LAUNCH_ACTIVITY)                      │
│     │                                                                   │
│     │ ┌──────────────────────────────────────┐                          │
│     │ │  Hook点2: Handler.mCallback           │                          │
│     │ │  拦截LAUNCH_ACTIVITY消息              │                          │
│     │ └──────────────────────────────────────┘                          │
│     ↓                                                                   │
│  12. Handler.handleLaunchActivity()                                    │
│     ↓                                                                   │
│  13. Activity.onCreate()                                               │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 二、AMS Hook实现详解

### 2.1 核心源码分析

#### 2.1.1 Android Framework源码：ActivityManager

```java
// frameworks/base/core/java/android/app/ActivityManager.java

public class ActivityManager {
    // Android 8.0+ (API 26+)
    private static final Singleton<IActivityManager> IActivityManagerSingleton =
            new Singleton<IActivityManager>() {
                @Override
                protected IActivityManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
                    // 通过Binder获取远程服务代理
                    final IActivityManager am = IActivityManager.Stub.asInterface(b);
                    return am;
                }
            };

    // 获取IActivityManager实例的公开方法
    public static IActivityManager getService() {
        return IActivityManagerSingleton.get();
    }
}
```

#### 2.1.2 Android Framework源码：Singleton

```java
// frameworks/base/core/java/android/util/Singleton.java

/**
 * 单例辅助类，用于延迟初始化
 *
 * 关键点：
 * 1. mInstance字段保存单例实例
 * 2. get()方法获取或创建实例
 * 3. create()是抽象方法，由子类实现
 */
public abstract class Singleton<T> {
    private T mInstance;

    protected abstract T create();

    public final T get() {
        synchronized (this) {
            if (mInstance == null) {
                mInstance = create();
            }
            return mInstance;
        }
    }
}
```

#### 2.1.3 Android Framework源码：ActivityThread.H

```java
// frameworks/base/core/java/android/app/ActivityThread.java

public final class ActivityThread {
    // 主Handler，处理所有Activity相关消息
    final H mH = new H();

    // 内部类H继承Handler
    class H extends Handler {
        // 消息类型常量
        public static final int LAUNCH_ACTIVITY = 100;
        public static final int PAUSE_ACTIVITY = 101;
        public static final int RESUME_ACTIVITY = 107;
        // ... 其他消息类型

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LAUNCH_ACTIVITY:
                    handleLaunchActivity((ActivityClientRecord) msg.obj, null);
                    break;
                // ... 其他case
            }
        }
    }
}
```

### 2.2 Hook AMS的完整实现

#### 步骤1：获取Singleton单例

```java
// AMSHookHelper.java hookActivityManagerNative()方法

// 根据Android版本选择不同的字段名
Field gDefaultField;
if (Build.VERSION.SDK_INT >= 26) {
    // Android 8.0+ 使用 IActivityManagerSingleton
    Class<?> activityManager = Class.forName("android.app.ActivityManager");
    gDefaultField = activityManager.getDeclaredField("IActivityManagerSingleton");
} else {
    // Android 8.0以下 使用 gDefault
    Class<?> activityManagerNativeClass =
        Class.forName("android.app.ActivityManagerNative");
    gDefaultField = activityManagerNativeClass.getDeclaredField("gDefault");
}

// 设置可访问性（打破封装）
gDefaultField.setAccessible(true);

// 获取Singleton对象（静态字段，传入null）
Object gDefault = gDefaultField.get(null);
```

**关键点解释：**

| 步骤 | 作用 | 底层原理 |
|------|------|----------|
| `getDeclaredField()` | 获取字段对象 | 反射获取Class中声明的字段，包括私有字段 |
| `setAccessible(true)` | 打破封装 | 禁用Java访问控制检查，允许访问私有成员 |
| `get(null)` | 获取静态字段值 | 静态字段不属于任何实例，传null获取值 |

#### 步骤2：获取原始的IActivityManager对象

```java
// 获取Singleton类的mInstance字段
Class<?> singletonClass = Class.forName("android.util.Singleton");
Field mInstanceField = singletonClass.getDeclaredField("mInstance");
mInstanceField.setAccessible(true);

// 从Singleton中取出原始的IActivityManager对象
Object rawIActivityManager = mInstanceField.get(gDefault);
```

**关键点解释：**

```
Singleton对象结构：
┌────────────────────────────────────┐
│      Singleton<IActivityManager>   │
├────────────────────────────────────┤
│  mInstance: IActivityManager       │  ← 我们要获取这个字段
│                                    │     它指向真正的AMS代理
│  get(): create() if null           │
│  create(): abstract                │
└────────────────────────────────────┘
         │
         │ mInstanceField.get(gDefault)
         ↓
┌────────────────────────────────────┐
│      IActivityManager              │
│      (原始对象)                     │
│  - startActivity()                 │
│  - startService()                  │
│  - registerReceiver()              │
└────────────────────────────────────┘
```

#### 步骤3：创建动态代理对象

```java
// 获取IActivityManager接口
Class<?> iActivityManagerInterface =
    Class.forName("android.app.IActivityManager");

// 创建动态代理
Object proxy = Proxy.newProxyInstance(
    Thread.currentThread().getContextClassLoader(),  // 类加载器
    new Class<?>[] { iActivityManagerInterface },    // 要实现的接口
    new IActivityManagerHandler(rawIActivityManager) // 调用处理器
);
```

**动态代理底层原理：**

```java
// Proxy.newProxyInstance()的内部实现（简化版）

public static Object newProxyInstance(ClassLoader loader,
                                      Class<?>[] interfaces,
                                      InvocationHandler h) {
    // 1. 动态生成代理类（运行时生成字节码）
    Class<?> proxyClass = Proxy.getProxyClass(loader, interfaces);

    // 2. 生成的代理类类似这样（编译后）：
    /*
    public final class $Proxy0 extends Proxy implements IActivityManager {
        public $Proxy0(InvocationHandler h) {
            super(h);
        }

        public int startActivity(...) throws RemoteException {
            try {
                // 所有方法调用都转发给InvocationHandler
                return h.invoke(this, m3, new Object[]{caller, callingPackage, ...});
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new UndeclaredThrowableException(e);
            }
        }
    }
    */

    // 3. 创建代理实例
    return proxyClass.getConstructor(
        new Class<?>[] { InvocationHandler.class }
    ).newInstance(h);
}
```

**IActivityManagerHandler实现：**

```java
class IActivityManagerHandler implements InvocationHandler {
    Object mBase;  // 保存原始的IActivityManager

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 获取方法名
        String methodName = method.getName();

        // 2. 判断是否是我们要拦截的方法
        if ("startActivity".equals(methodName)) {
            // 3. 执行我们的Hook逻辑
            // ... 替换Intent等操作

            // 4. 调用原始方法
            return method.invoke(mBase, args);
        }

        // 5. 其他方法直接调用原始实现
        return method.invoke(mBase, args);
    }
}
```

#### 步骤4：替换原始对象

```java
// 将代理对象设置回Singleton的mInstance字段
mInstanceField.set(gDefault, proxy);
```

**替换前后的对比：**

```
替换前：
┌─────────────────────────────────────────────┐
│  Singleton<IActivityManager>                │
│  ┌───────────────────────────────────────┐  │
│  │ mInstance → 原始IActivityManager      │  │
│  │             (AMS服务代理)              │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘

替换后：
┌─────────────────────────────────────────────┐
│  Singleton<IActivityManager>                │
│  ┌───────────────────────────────────────┐  │
│  │ mInstance → 动态代理对象               │  │
│  │             ↓                         │  │
│  │        IActivityManagerHandler        │  │
│  │             ↓                         │  │
│  │        原始IActivityManager (mBase)   │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

### 2.3 Hook ActivityThread的Handler

```java
// AMSHookHelper.java hookActivityThreadHandler()方法

// 步骤1：获取当前ActivityThread对象
Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
Field sCurrentActivityThreadField =
    activityThreadClass.getDeclaredField("sCurrentActivityThread");
sCurrentActivityThreadField.setAccessible(true);
Object currentActivityThread = sCurrentActivityThreadField.get(null);

// 步骤2：获取ActivityThread的mH字段（Handler）
Field mHField = activityThreadClass.getDeclaredField("mH");
mHField.setAccessible(true);
Handler mH = (Handler) mHField.get(currentActivityThread);

// 步骤3：设置Handler的mCallback
Field mCallbackField = Handler.class.getDeclaredField("mCallback");
mCallbackField.setAccessible(true);
mCallbackField.set(mH, new ActivityThreadHandlerCallback(mH));
```

**Handler源码分析（关键）：**

```java
// frameworks/base/core/java/android/os/Handler.java

public class Handler {
    // 回调接口
    Callback mCallback;

    public interface Callback {
        boolean handleMessage(Message msg);
    }

    // 分发消息的核心方法
    public void dispatchMessage(Message msg) {
        // 优先级1: 如果消息有callback，执行message.callback.run()
        if (msg.callback != null) {
            handleCallback(msg);
        } else {
            // 优先级2: 如果Handler有mCallback，执行mCallback.handleMessage()
            // ← 我们利用这个优先级实现Hook
            if (mCallback != null) {
                if (mCallback.handleMessage(msg)) {
                    return;  // 返回true表示消息已处理，不再继续
                }
            }
            // 优先级3: 执行Handler的handleMessage()
            handleMessage(msg);
        }
    }
}
```

**为什么Hook Handler.mCallback而不是直接继承Handler？**

```
方案对比：

方案A：直接继承Handler（不可行）
┌─────────────────────────────────────┐
│  class MyHandler extends Handler {   │
│      public void handleMessage() {  │
│          // 无法访问原始mH          │
│          // 无法完全复制原逻辑       │
│      }                               │
│  }                                    │
└─────────────────────────────────────┘
问题：无法访问ActivityThread.H的原始实现

方案B：Hook mCallback（可行）✓
┌─────────────────────────────────────┐
│  mH.mCallback = ourCallback          │
│  ↓                                   │
│  dispatchMessage优先调用mCallback    │
│  ↓                                   │
│  ourCallback.handleMessage()         │
│  ↓                                   │
│  处理完返回true，阻止原Handler       │
│  或返回false，继续原Handler          │
└─────────────────────────────────────┘
优势：可以完全控制消息处理流程
```

---

## 三、handleLaunchActivity实现详解

### 3.1 完整流程图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                handleLaunchActivity 完整执行流程                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  LAUNCH_ACTIVITY消息到达                                                │
│    ↓                                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 步骤1: 提取Intent信息                                            │   │
│  │  - 从msg.obj获取ActivityClientRecord                            │   │
│  │  - 从intent字段获取raw intent                                   │   │
│  │  - 从raw intent中提取:                                          │   │
│  │    * EXTRA_TARGET_INTENT (真实Intent)                           │   │
│  │    * EXTRA_TARGET_APKPATH (插件APK路径)                         │   │
│  │    * EXTRA_TARGET_PACKAGENAME (插件包名)                        │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│    ↓                                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 步骤2: 获取/创建PluginPackageParser                              │   │
│  │  - 从缓存中查找已有的Parser                                      │   │
│  │  - 如果不存在，创建新的Parser并解析APK                           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│    ↓                                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 步骤3: 获取ActivityInfo                                          │   │
│  │  - 从Parser获取真实Activity的ActivityInfo                        │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│    ↓                                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 步骤4: 预加载插件APK (preLoadApk)                                │   │
│  │  4.1 Hook PackageManager                                        │   │
│  │  4.2 创建插件目录结构                                            │   │
│  │  4.3 创建PluginClassLoader                                       │   │
│  │  4.4 获取/创建LoadedApk对象                                      │   │
│  │  4.5 替换LoadedApk的mClassLoader                                 │   │
│  │  4.6 调用makeApplication创建Application                         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│    ↓                                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 步骤5: 获取插件ClassLoader                                       │   │
│  │  - 从Application的ClassLoader获取                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│    ↓                                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 步骤6: 恢复真实Intent信息                                         │   │
│  │  6.1 设置raw的Component为真实Activity                            │   │
│  │  6.2 设置Intent的ClassLoader                                     │   │
│  │  6.3 替换ActivityClientRecord的activityInfo                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│    ↓                                                                    │
│  返回true，消息处理完成                                                 │
│    ↓                                                                    │
│  ActivityThread继续执行，创建真实Activity                               │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 步骤1：提取Intent信息

```java
// ActivityThreadHandlerCallback.java - handleLaunchActivity()

// msg.obj是ActivityClientRecord类型
Object obj = msg.obj;
Intent raw = null;
Intent target = null;
String apkPath = null;
String packageName = null;

try {
    // 通过反射获取ActivityClientRecord的intent字段
    Field intentField = obj.getClass().getDeclaredField("intent");
    intentField.setAccessible(true);
    raw = (Intent) intentField.get(obj);

    // 从raw intent中提取我们之前保存的信息
    target = raw.getParcelableExtra(AMSHookHelper.EXTRA_TARGET_INTENT);
    apkPath = raw.getStringExtra(AMSHookHelper.EXTRA_TARGET_APKPATH);
    packageName = raw.getStringExtra(AMSHookHelper.EXTRA_TARGET_PACKAGENAME);
} catch (Exception e) {
    e.printStackTrace();
}
```

**ActivityClientRecord结构（Framework源码）：**

```java
// frameworks/base/core/java/android/app/ActivityThread.java

public final class ActivityThread {
    // 内部类：Activity客户端记录
    static final class ActivityClientRecord {
        // 关键字段
        Intent intent;              // 启动Intent
        ActivityInfo activityInfo;  // Activity信息
        Activity activity;          // Activity实例
        Window window;              // 窗口
        // ... 其他字段
    }
}
```

**Intent中的数据结构：**

```
raw intent (StubActivity的intent)
├── Component: com.dong.container/.StubActivity
└── Extras:
    ├── EXTRA_TARGET_INTENT → 真实的Intent (PluginActivity)
    │   └── Component: com.example.plugin/.PluginActivity
    ├── EXTRA_TARGET_APKPATH → "/data/data/.../plugin.apk"
    └── EXTRA_TARGET_PACKAGENAME → "com.example.plugin"
```

### 3.3 步骤2：获取PluginPackageParser

```java
// 从缓存中获取Parser
PluginPackageParser parser = KotlinToolsKt.getAppParserMap().get(packageName);

if (parser == null) {
    try {
        // 创建新的Parser并解析APK
        parser = new PluginPackageParser(App.getInstance(), new File(apkPath));
        // 缓存Parser
        KotlinToolsKt.getAppParserMap().put(packageName, parser);
    } catch (Exception e) {
        e.printStackTrace();
    }
}
```

**PluginPackageParser的作用：**

```
PluginPackageParser
├── 解析APK的AndroidManifest.xml
├── 提取四大组件信息
├── 构建组件索引
└── 缓存PackageInfo

数据结构：
┌─────────────────────────────────────────────────┐
│  PluginPackageParser                             │
│  ├── PackageInfo mPackageInfo                    │
│  ├── HashMap<String, ActivityInfo> mActivities   │
│  ├── HashMap<String, ServiceInfo> mServices      │
│  ├── HashMap<String, ActivityInfo> mReceivers    │
│  └── HashMap<String, ProviderInfo> mProviders    │
└─────────────────────────────────────────────────┘
```

### 3.4 步骤4：preLoadApk详细分析

这是最核心的步骤，我们逐步拆解：

#### 4.1 Hook PackageManager

```java
private static void hookPackageManager() throws Exception {
    // 获取ActivityThread的sPackageManager静态字段
    Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
    Method currentActivityThreadMethod =
        activityThreadClass.getDeclaredMethod("currentActivityThread");
    currentActivityThreadMethod.setAccessible(true);
    Object currentActivityThread = currentActivityThreadMethod.invoke(null);

    // 获取原始的sPackageManager
    Field sPackageManagerField =
        activityThreadClass.getDeclaredField("sPackageManager");
    sPackageManagerField.setAccessible(true);
    Object sPackageManager = sPackageManagerField.get(currentActivityThread);

    // 创建代理对象
    Class<?> iPackageManagerInterface =
        Class.forName("android.content.pm.IPackageManager");
    Object proxy = Proxy.newProxyInstance(
        iPackageManagerInterface.getClassLoader(),
        new Class<?>[] { iPackageManagerInterface },
        new IPackageManagerHookHandler(sPackageManager)
    );

    // 替换sPackageManager
    sPackageManagerField.set(currentActivityThread, proxy);
}
```

**为什么要Hook PackageManager？**

```
问题：ActivityThread.getPackageInfoNoCheck()会调用PackageManager
      如果插件未安装，会抛出异常

Framework源码：
// ActivityThread.java
private LoadedApk getPackageInfo(ApplicationInfo aInfo, CompatibilityInfo compatInfo) {
    // ... 省略代码
    if (包含已安装检查) {
        pm.getPackageInfo(aInfo.packageName, ...);  // ← 这里会抛异常
    }
    // ...
}

解决方案：Hook sPackageManager，返回伪造的PackageInfo
```

#### 4.2 创建插件目录结构

```java
// 创建目录结构
File dexDir = new File(hostContext.getFilesDir().getAbsolutePath()
    + File.separator + "container"
    + File.separator + packageName
    + File.separator + "/dex/");
if (!dexDir.exists()) {
    dexDir.mkdirs();
}

File libDir = new File(hostContext.getFilesDir().getAbsolutePath()
    + File.separator + "container"
    + File.separator + packageName
    + File.separator + "/dex/lib");
if (!libDir.exists()) {
    libDir.mkdirs();
}
```

**目录结构：**

```
/data/data/com.dong.container/
├── files/
│   └── container/
│       └── [插件包名]/
│           ├── dex/                    ← dex优化输出目录
│           │   └── base-1.apk@classes.dex
│           └── dex/lib/               ← Native库解压目录
│               └── libnative.so
```

#### 4.3 获取/创建LoadedApk对象

```java
// 获取ActivityThread
Object currentActivityThread = ActivityThreadCompat.currentActivityThread();

// 获取mPackages字段 (Map<String, LoadedApk>)
Object mPackagesObj = FieldUtils.readField(currentActivityThread, "mPackages");

// 检查是否已存在
Boolean containsKey = (Boolean) MethodUtils.invokeMethod(
    mPackagesObj, "containsKey", pluginInfo.packageName);

if (!containsKey) {
    // 调用getPackageInfoNoCheck创建LoadedApk
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
        loadedApk = MethodUtils.invokeMethod(
            currentActivityThread,
            "getPackageInfoNoCheck",
            pluginInfo.applicationInfo,
            CompatibilityInfoCompat.DEFAULT_COMPATIBILITY_INFO()
        );
    } else {
        loadedApk = MethodUtils.invokeMethod(
            currentActivityThread,
            "getPackageInfoNoCheck",
            pluginInfo.applicationInfo
        );
    }
}
```

**LoadedApk的作用（Framework源码分析）：**

```java
// frameworks/base/core/java/android/app/ActivityThread.java

/**
 * LoadedApk是ActivityThread内部类，表示一个加载的APK
 *
 * 核心功能：
 * 1. 管理APK的ApplicationInfo
 * 2. 持有ClassLoader
 * 3. 创建Application实例
 */
public final class ActivityThread {
    static final class LoadedApk {
        // APK的应用信息
        ApplicationInfo applicationInfo;

        // 类加载器
        ClassLoader mClassLoader;

        // Application实例
        Application mApplication;

        // CompatibilityInfo
        CompatibilityInfo mCompatibilityInfo;

        // 创建Application
        public Application makeApplication(boolean forceDefaultApp,
                                          Instrumentation instrumentation) {
            if (mApplication != null) {
                return mApplication;
            }

            // 创建Application
            Application app = instrumentation.newApplication(
                mClassLoader,
                applicationInfo.className,
                this);

            // 调用Application.onCreate()
            instrumentation.callApplicationOnCreate(app);

            mApplication = app;
            return app;
        }
    }

    // 所有已加载的APK
    final ArrayMap<String, LoadedApk> mPackages = new ArrayMap<>();
}
```

#### 4.4 创建PluginClassLoader

```java
// 设置publicSourceDir
String apk = pluginInfo.applicationInfo.publicSourceDir;
if (TextUtils.isEmpty(apk)) {
    pluginInfo.applicationInfo.publicSourceDir =
        PluginDirHelper.getPluginApkFile(hostContext, pluginInfo.packageName);
    apk = pluginInfo.applicationInfo.publicSourceDir;
}

// 创建PluginClassLoader
ClassLoader classloader = new PluginClassLoader(
    apk,                      // APK路径
    optimizedDirectory,       // Dex优化目录
    libraryPath,              // Native库路径
    hostContext.getClassLoader().getParent()  // 父加载器
);

// 失败重试：清理优化目录后重新创建
if (classloader == null) {
    PluginDirHelper.cleanOptimizedDirectory(optimizedDirectory);
    classloader = new PluginClassLoader(
        apk, optimizedDirectory, libraryPath,
        hostContext.getClassLoader().getParent()
    );
}

// 替换LoadedApk的mClassLoader
synchronized (loadedApk) {
    FieldUtils.writeDeclaredField(loadedApk, "mClassLoader", classloader);
}

// 设置当前线程的ContextClassLoader
Thread.currentThread().setContextClassLoader(classloader);
```

**PluginClassLoader继承关系：**

```
java.lang.ClassLoader (抽象类)
    ↑
    │
java.security.SecureClassLoader
    ↑
    │
dalvik.system.BaseDexClassLoader
    ↑
    │
dalvik.system.DexClassLoader
    ↑
    │
com.dong.container.hook.PluginClassLoader
```

**DexClassLoader工作原理（Framework源码）：**

```java
// libcore/dalvik/src/main/java/dalvik/system/DexClassLoader.java

/**
 * 可以加载任意路径的jar/apk/dex中的类
 *
 * @param dexPath                dex/jar/apk路径
 * @param optimizedDirectory     dex优化输出目录
 * @param librarySearchPath      Native库搜索路径
 * @param parent                 父ClassLoader
 */
public class DexClassLoader extends BaseDexClassLoader {
    public DexClassLoader(String dexPath, String optimizedDirectory,
                         String librarySearchPath, ClassLoader parent) {
        super(dexPath, new File(optimizedDirectory), librarySearchPath, parent);
    }
}

// libcore/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java

public class BaseDexClassLoader extends ClassLoader {
    // DexPathList管理所有dex资源
    private final DexPathList pathList;

    public BaseDexClassLoader(String dexPath, File optimizedDirectory,
                             String librarySearchPath, ClassLoader parent) {
        super(parent);
        this.pathList = new DexPathList(
            this,
            dexPath,
            optimizedDirectory,
            librarySearchPath
        );
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // 从DexPathList中查找类
        return pathList.findClass(name);
    }
}
```

**DexPathList结构（关键）：**

```java
// libcore/dalvik/src/main/java/dalvik/system/DexPathList.java

/**
 * 管理Dex文件和Native库的列表
 */
public final class DexPathList {
    // Element数组，每个元素包含一个DexFile
    private Element[] dexElements;

    // Native库路径
    private final String[] nativeLibraryDirectories;

    public DexPathList(ClassLoader definingContext,
                      String dexPath,
                      File optimizedDirectory,
                      String librarySearchPath) {
        // ...
        // 解析dexPath，创建dexElements
        this.dexElements = makeDexElements(splitPaths(dexPath),
                                           optimizedDirectory);
    }

    public Class<?> findClass(String name) {
        // 遍历所有dexElements查找类
        for (Element element : dexElements) {
            Class<?> clazz = element.findClass(name);
            if (clazz != null) {
                return clazz;
            }
        }
        throw new ClassNotFoundException(name);
    }

    /**
     * Element是DexPathList的静态内部类
     */
    static class Element {
        private final File path;          // 文件路径
        private final DexFile dex;        // DexFile对象

        Class<?> findClass(String name) {
            return dex != null ? dex.loadClassBinaryName(name, definingContext)
                              : null;
        }
    }
}
```

#### 4.5 创建Application实例

```java
// 获取LoadedApk的mApplication字段
Object mApplication = FieldUtils.readField(loadedApk, "mApplication");

if (mApplication == null) {
    // 调用makeApplication创建Application
    MethodUtils.invokeMethod(
        loadedApk,
        "makeApplication",
        false,  // forceDefaultApp
        ActivityThreadCompat.getInstrumentation()
    );
}
```

**makeApplication流程（Framework源码）：**

```java
// frameworks/base/core/java/android/app/LoadedApk.java

public Application makeApplication(boolean forceDefaultApp,
                                  Instrumentation instrumentation) {
    if (mApplication != null) {
        return mApplication;
    }

    Application app = null;

    String appClass = applicationInfo.className;

    // 如果没有指定Application类，使用默认的
    if (forceDefaultApp || appClass == null) {
        appClass = "android.app.Application";
    }

    try {
        // 创建Application实例
        java.lang.ClassLoader cl = getClassLoader();
        if (!mPackageName.equals("android")) {
            // 初始化Java Context ClassLoader
            initializeJavaContextClassLoader();
        }

        // 通过Instrumentation创建Application
        app = instrumentation.newApplication(
            cl, appClass, this);

        // 保存Application实例
        mApplication = app;

    } catch (Exception e) {
        // ...
    }

    // 添加到ActivityThread的mAllApplications列表
    ActivityThread thread = ActivityThread.currentActivityThread();
    thread.mAllApplications.add(app);

    // 调用Application.onCreate()
    instrumentation.callApplicationOnCreate(app);

    return app;
}
```

### 3.5 步骤5：获取插件ClassLoader

```java
private ClassLoader getPluginClassLoader() throws Exception {
    // 获取当前ActivityThread
    Object at = ActivityThreadCompat.currentActivityThread();

    // 获取mAllApplications列表
    Object mAllApplications = FieldUtils.readField(at, "mAllApplications");

    if (mAllApplications instanceof List) {
        List apps = (List) mAllApplications;
        for (Object o : apps) {
            if (o instanceof Application) {
                Application app = (Application) o;
                // 从Application获取ClassLoader
                return app.getClassLoader();
            }
        }
    }
    return null;
}
```

### 3.6 步骤6：恢复真实Intent信息

```java
// 6.1 设置raw的Component为真实Activity
raw.setComponent(target.getComponent());

// 6.2 设置Intent的ClassLoader
setIntentClassLoader(raw, pluginClassLoader);
setIntentClassLoader(target, pluginClassLoader);

// 6.3 替换ActivityClientRecord的activityInfo
FieldUtils.writeDeclaredField(msg.obj, "activityInfo", activityInfo);
```

**setIntentClassLoader方法：**

```java
private void setIntentClassLoader(Intent intent, ClassLoader classLoader) {
    try {
        // 获取Intent的mExtras字段（Bundle类型）
        Bundle mExtras = (Bundle) FieldUtils.readField(intent, "mExtras");

        if (mExtras != null) {
            // 如果已存在，设置其ClassLoader
            mExtras.setClassLoader(classLoader);
        } else {
            // 如果不存在，创建新的Bundle并设置
            Bundle value = new Bundle();
            value.setClassLoader(classLoader);
            FieldUtils.writeField(intent, "mExtras", value);
        }
    } catch (Exception e) {
        e.printStackTrace();
    } finally {
        // 同时设置Intent的ExtrasClassLoader
        intent.setExtrasClassLoader(classLoader);
    }
}
```

**为什么要设置Intent的ClassLoader？**

```
问题：Intent中可能包含Parcelable对象
      这些对象是用插件的ClassLoader加载的

Framework源码：
// Intent.java
private Bundle mExtras;

public <T extends Parcelable> T getParcelableExtra(String key) {
    // 使用mExtras的ClassLoader来反序列化Parcelable
    return mExtras != null ? mExtras.getParcelable(key) : null;
}

// Bundle.java
public <T extends Parcelable> T getParcelable(String key) {
    // 使用ClassLoader来恢复对象
    Parcel parcel = mParcelledData;
    parcel.setDataClassLoader(classLoader);  // ← 这里使用ClassLoader
    return parcel.readParcelable(classLoader);
}

解决方案：设置Intent的ClassLoader为插件ClassLoader
         否则反序列化时会因为找不到类而失败
```

---

## 四、底层原理与Framework源码分析

### 4.1 Activity启动的完整Binder调用链

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Activity启动的Binder通信流程                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  [客户端进程]                    Binder IPC                [SystemServer]│
│                                                                         │
│  Instrumentation                                      ActivityManager   │
│       │                                                 │              │
│       ├── ActivityManager.getService()                   │              │
│       │         │                                         │              │
│       │         └──→ ServiceManager.getService() ───────→ 获取服务      │
│       │         │                                         │              │
│       │         ←─── 返回Service的IBinder ───────────────  │              │
│       │         │                                         │              │
│       │         └──→ IActivityManager.Stub.asInterface()  │              │
│       │                   │                               │              │
│       │                   ↓                               │              │
│       │  ┌──────────────────────────────┐                │              │
│       │  │ IActivityManager$Proxy       │                │              │
│       │  │ (本地代理对象)                │                │              │
│       │  └──────────────────────────────┘                │              │
│       │                   │                               │              │
│       ├──→ startActivity() ─────────────────────────────→  │              │
│       │                   │                              ↓               │
│       │                   │                       ActivityManagerService│
│       │                   │                               │              │
│       │                   │                    1. 权限检查                │
│       │                   │                    2. Manifest验证           │
│       │                   │                    3. Intent解析             │
│       │                   │                    4. 创建ActivityRecord     │
│       │                   │                               │              │
│       │  ←──────────────── 返回结果 ──────────────────────  │              │
│       │                   │                               │              │
│       ↓                   │                               │              │
│  进入等待状态              │                               │              │
│       │                   │                               │              │
│       │                   │                   ┌────────────┴────────────┐ │
│       │                   │                   │  ApplicationThreadProxy│ │
│       │                   │                   │  (目标进程代理)         │ │
│       │                   │                   └────────────┬────────────┘ │
│       │                   └──→ scheduleLaunchActivity()  │               │
│       │                                              ↓   │               │
│       │  ←────────────────────────────────────────  发送消息  ←─────────┤
│       │                                                     │           │
│       ↓                                                     ↓           │
│  ActivityThread.H                           ApplicationThread (目标进程)   │
│  handleMessage(LAUNCH_ACTIVITY)            scheduleLaunchActivity()      │
│       │                                                     │           │
│       ↓                                                     ↓           │
│  handleLaunchActivity()                         执行真正启动               │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Hook拦截点的Binder层面分析

```
┌─────────────────────────────────────────────────────────────────────────┐
│              Hook在不同层面的拦截位置                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  应用层                                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Activity.startActivity()                                      │   │
│  │     ↓                                                          │   │
│  │  Instrumentation.execStartActivity()                           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│         │                                                              │
│         ↓                                                              │
│  Framework层                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  ActivityManager.getService()                                   │   │
│  │     ↓                                                            │   │
│  │  IActivityManagerSingleton.get()   ← Hook点A: 替换单例           │   │
│  │     ↓                                                            │   │
│  │  返回IActivityManager$Proxy (我们的代理)                          │   │
│  │     ↓                                                            │   │
│  │  IActivityManager.startActivity()   ← Hook点B: 代理拦截          │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│         │                                                              │
│         ↓                                                              │
│  Binder层                                                              │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Binder.transact()                                              │   │
│  │     ↓                                                            │   │
│  │  BpBinder::transact()                                           │   │
│  │     ↓                                                            │   │
│  │  ─────────────────────── Binder IPC ───────────────────────→    │   │
│  │                                                                  │   │
│  │  ActivityManagerNative.onTransact()                             │   │
│  │     ↓                                                            │   │
│  │  ActivityManagerService.startActivity()                         │   │
│  │     ↓                                                            │   │
│  │  Manifest验证 ← 必须绕过这个验证                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│         │                                                              │
│         ↓                                                              │
│  服务器返回                                                            │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  scheduleLaunchActivity()                                        │   │
│  │     ↓                                                            │   │
│  │  ─────────────────────── Binder IPC ───────────────────────→    │   │
│  │                                                                  │   │
│  │  ApplicationThread.scheduleLaunchActivity()                      │   │
│  │     ↓                                                            │   │
│  │  sendMessage(H.LAUNCH_ACTIVITY)                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│         │                                                              │
│         ↓                                                              │
│  客户端进程                                                            │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  ActivityThread.H.handleMessage()                                │   │
│  │     ↓                                                            │   │
│  │  dispatchMessage()                                               │   │
│  │     ↓                                                            │   │
│  │  mCallback.handleMessage()  ← Hook点C: Handler回调              │   │
│  │     ↓                                                            │   │
│  │  恢复真实Intent                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│         │                                                              │
│         ↓                                                              │
│  Activity创建                                                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.3 核心数据结构完整图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Hook过程中的核心数据结构                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. Singleton<IActivityManager>                                        │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  class Singleton<T> {                                           │   │
│  │      private T mInstance;                                       │   │
│  │      public final T get() {                                    │   │
│  │          if (mInstance == null) mInstance = create();           │   │
│  │          return mInstance;                                     │   │
│  │      }                                                          │   │
│  │      protected abstract T create();                            │   │
│  │  }                                                              │   │
│  │                                                                  │   │
│  │  替换前: mInstance → 原始IActivityManager$Proxy                  │   │
│  │  替换后: mInstance → 我们的动态代理                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  2. ActivityThread                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  class ActivityThread {                                         │   │
│  │      static ActivityThread sCurrentActivityThread;              │   │
│  │      final H mH = new H();                                      │   │
│  │      ArrayMap<String, LoadedApk> mPackages;                     │   │
│  │      List<Application> mAllApplications;                        │   │
│  │      static IPackageManager sPackageManager;                    │   │
│  │  }                                                              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  3. LoadedApk                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  class LoadedApk {                                              │   │
│  │      ApplicationInfo applicationInfo;                           │   │
│  │      ClassLoader mClassLoader;        ← 替换为插件ClassLoader    │   │
│  │      Application mApplication;        ← 创建插件Application      │   │
│  │      String mPackageName;                                       │   │
│  │      CompatibilityInfo mCompatibilityInfo;                      │   │
│  │                                                                  │   │
│  │      Application makeApplication(boolean forceDefaultApp,      │   │
│  │                                   Instrumentation instr);       │   │
│  │  }                                                              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  4. ActivityClientRecord                                               │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  class ActivityClientRecord {                                   │   │
│  │      Intent intent;                  ← 恢复为真实Intent          │   │
│  │      ActivityInfo activityInfo;       ← 替换为插件ActivityInfo  │   │
│  │      Activity activity;                                        │   │
│  │      Window window;                                            │   │
│  │      // ... 其他字段                                           │   │
│  │  }                                                              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  5. PluginClassLoader                                                  │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  class PluginClassLoader extends DexClassLoader {              │   │
│  │      // 可以自定义类加载逻辑                                     │   │
│  │      protected Class<?> loadClass(String name, boolean resolve)│   │
│  │  }                                                              │   │
│  │                                                                  │   │
│  │  DexClassLoader结构:                                            │   │
│  │      └── BaseDexClassLoader                                     │   │
│  │          └── DexPathList                                        │   │
│  │              └── Element[] dexElements  ← dex文件列表           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.4 关键时序图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Hook完整时序图                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Client            AMSHookHelper      ActivityManager        AMS        │
│    │                    │                    │                    │       │
│    │ startActivity()   │                    │                    │       │
│    │──────────────────→│                    │                    │       │
│    │                    │                    │                    │       │
│    │                    │ hookActivityManagerNative()             │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 1. get IActivityManagerSingleton       │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 2. get mInstance                        │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 3. create Proxy                          │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 4. replace mInstance                    │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ hookActivityThreadHandler()             │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 5. get mH (Handler)                     │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 6. set mCallback                         │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │ IActivityManager   │                    │                    │       │
│    │ .startActivity()   │                    │                    │       │
│    │────────────────────────────────────────→│                    │       │
│    │                    │                    │                    │       │
│    │                    │  [Proxy拦截]       │                    │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 7. replace Intent with StubActivity     │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 8. invoke original                     │       │
│    │                    │────────────────────────────────────────→│       │
│    │                    │                    │                    │       │
│    │                    │                    │ 9. check Manifest  │       │
│    │                    │                    │    (StubActivity ✓)│       │
│    │                    │                    │                    │       │
│    │                    │                    │ 10. scheduleLaunch │       │
│    │                    │                    │────────────────────│       │
│    │                    │                    │                    │       │
│    │                    │                    │ 11. return OK      │       │
│    │                    │←───────────────────────────────────────│       │
│    │                    │                    │                    │       │
│    │                    │ 12. return OK                          │       │
│    │←───────────────────│                    │                    │       │
│    │                    │                    │                    │       │
│    │ [进入等待队列]      │                    │                    │       │
│    │                    │                    │                    │       │
│    │                    │   [AMS通过Binder发送LAUNCH_ACTIVITY]     │       │
│    │                    │                    │                    │       │
│    │                    │ ActivityThread.H    │                    │       │
│    │←────────────────────────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │  [mCallback拦截]    │                    │       │
│    │                    │←───────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 13. handleLaunchActivity                │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 14. get real Intent                     │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 15. preLoadApk                           │       │
│    │                    │────────────────────│                    │       │
│    │                    │   - create LoadedApk                    │       │
│    │                    │   - create ClassLoader                  │       │
│    │                    │   - create Application                  │       │
│    │                    │                    │                    │       │
│    │                    │ 16. restore real Intent                 │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │ 17. call mH.handleMessage               │       │
│    │                    │────────────────────│                    │       │
│    │                    │                    │                    │       │
│    │                    │                    │ 18. create Activity│       │
│    │                    │                    │────────────────────│       │
│    │                    │                    │                    │       │
│    │  [真实Plugin Activity启动完成]                                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 总结

### Hook点定位的核心要点

1. **选择单例/静态变量**：确保替换后全局生效
2. **选择有接口的对象**：方便使用动态代理
3. **Hook点要尽早**：在系统验证之前拦截
4. **考虑版本兼容**：选择相对稳定的API

### handleLaunchActivity的核心步骤

1. **提取Intent信息**：从msg.obj中获取原始Intent和插件信息
2. **获取PluginPackageParser**：从缓存获取或创建新的解析器
3. **获取ActivityInfo**：获取真实Activity的信息
4. **预加载插件APK**：
   - Hook PackageManager
   - 创建插件目录结构
   - 创建PluginClassLoader
   - 获取/创建LoadedApk对象
   - 替换LoadedApk的mClassLoader
   - 创建Application实例
5. **获取插件ClassLoader**：从Application获取
6. **恢复真实Intent**：设置Component、ClassLoader、ActivityInfo

### 底层原理

- **Binder通信**：客户端通过Binder与AMS通信
- **动态代理**：拦截IActivityManager的方法调用
- **反射**：访问和修改私有字段
- **Handler机制**：通过mCallback拦截消息处理
- **ClassLoader**：自定义类加载器加载插件类

---

*文档生成时间: 2026-03-01*
*项目路径: D:\Work\Android\learn\AndroidContainer*
