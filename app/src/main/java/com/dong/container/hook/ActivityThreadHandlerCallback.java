package com.dong.container.hook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import com.dong.container.App;
import com.dong.container.KotlinToolsKt;
import com.dong.container.util.CommonUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author weishu
 * @date 16/1/7
 */
/* package */ class ActivityThreadHandlerCallback implements Handler.Callback {

    private static final String TAG = "dong";

    Handler mBase;

    public ActivityThreadHandlerCallback(Handler base) {
        mBase = base;
    }

    @Override
    public boolean handleMessage(Message msg) {

        switch (msg.what) {
            // ActivityThread里面 "LAUNCH_ACTIVITY" 这个字段的值是100
            // 本来使用反射的方式获取最好, 这里为了简便直接使用硬编码
            case 100:
                handleLaunchActivity(msg);
                break;
        }

        mBase.handleMessage(msg);
        return true;
    }

    private void handleLaunchActivity(Message msg) {
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/handleLaunchActivity:thread(%s) process(%s)", Thread.currentThread().getName(), CommonUtil.getProcessName()));
        Object obj = msg.obj;
        Intent raw = null;
        Intent target = null;
        String apkPath = null;
        String packageName = null;
        try {
            Field intent = obj.getClass().getDeclaredField("intent");
            intent.setAccessible(true);
            raw = (Intent) intent.get(obj);
            target = raw.getParcelableExtra(AMSHookHelper.EXTRA_TARGET_INTENT);
            apkPath = raw.getStringExtra(AMSHookHelper.EXTRA_TARGET_APKPATH);
            packageName = raw.getStringExtra(AMSHookHelper.EXTRA_TARGET_PACKAGENAME);
        } catch (Exception e) {
            e.printStackTrace();
        }

        /*PluginPackageParser parser = null;
        try {
            parser = new PluginPackageParser(App.getInstance(), new File(App.getInstance().getFilesDir().getAbsolutePath() + "/targetapp-debug.apk"));
        } catch (Exception e) {
            e.printStackTrace();
        }*/
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/handleLaunchActivity:thread(%s) KotlinToolsKt.getAppParserMap()(%s) ", Thread.currentThread().getName(), KotlinToolsKt.getAppParserMap().size()));
        PluginPackageParser parser = KotlinToolsKt.getAppParserMap().get(packageName);
        if (parser == null) {
            try {
                parser = new PluginPackageParser(App.getInstance(), new File(apkPath));
                KotlinToolsKt.getAppParserMap().put(packageName, parser);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        // 这里简单起见,直接取出TargetActivity;
        ActivityInfo activityInfo = null;
        try {
            activityInfo = parser.getActivityInfo(target.getComponent(), 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/handleLaunchActivity:thread(%s) activityInfo(%s)", Thread.currentThread().getName(), activityInfo));
        try {
            preLoadApk(App.getInstance(), activityInfo, packageName, apkPath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        ClassLoader pluginClassLoader = null;
        try {
            pluginClassLoader = getPluginClassLoader();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/handleLaunchActivity:thread(%s) pluginClassLoader(%s)", Thread.currentThread().getName(), pluginClassLoader));
        // 根据源码:
        // 这个对象是 ActivityClientRecord 类型
        // 我们修改它的intent字段为我们原来保存的即可.
        // switch (msg.what) {
        //      case LAUNCH_ACTIVITY: {
        //          Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityStart");
        //          final ActivityClientRecord r = (ActivityClientRecord) msg.obj;

        //          r.packageInfo = getPackageInfoNoCheck(
        //                  r.activityInfo.applicationInfo, r.compatInfo);
        //         handleLaunchActivity(r, null);


        try {
            // 把替身恢复成真身


            raw.setComponent(target.getComponent());
            setIntentClassLoader(raw, pluginClassLoader);
            setIntentClassLoader(target, pluginClassLoader);

            FieldUtils.writeDeclaredField(msg.obj, "activityInfo", activityInfo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setIntentClassLoader(Intent intent, ClassLoader classLoader) {
        try {
            Bundle mExtras = (Bundle) FieldUtils.readField(intent, "mExtras");
            if (mExtras != null) {
                mExtras.setClassLoader(classLoader);
            } else {
                Bundle value = new Bundle();
                value.setClassLoader(classLoader);
                FieldUtils.writeField(intent, "mExtras", value);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            intent.setExtrasClassLoader(classLoader);
        }
    }

    private ClassLoader getPluginClassLoader() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Object at = ActivityThreadCompat.currentActivityThread();
        Object mAllApplications = FieldUtils.readField(at, "mAllApplications");
        if (mAllApplications instanceof List) {
            List apps = (List) mAllApplications;
            for (Object o : apps) {
                if (o instanceof Application) {
                    Application app = (Application) o;
                    return app.getClassLoader();
                }
            }
        }
        return null;
    }

    public static void fixContextPackageManager(Context context) {
        try {
            Object currentActivityThread = ActivityThreadCompat.currentActivityThread();
            Object newPm = FieldUtils.readField(currentActivityThread, "sPackageManager");
            PackageManager pm = context.getPackageManager();
            Object mPM = FieldUtils.readField(pm, "mPM");
            if (mPM != newPm) {
                FieldUtils.writeField(pm, "mPM", newPm);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Map<String, Object> sLoadedApk = new HashMap<>();

    public static void preLoadApk(Context hostContext, ComponentInfo pluginInfo, String packageName, String apkPath) throws IOException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, PackageManager.NameNotFoundException, ClassNotFoundException {
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/preLoadApk:thread(%s)", Thread.currentThread().getName()));
        if (pluginInfo == null && hostContext == null) {
            return;
        }

        /*添加插件的LoadedApk对象到ActivityThread.mPackages*/

        Object loadedApk = null;
        Object object = ActivityThreadCompat.currentActivityThread();
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/preLoadApk:thread(%s) currentActivityThread(%s)", Thread.currentThread().getName(), object));
        if (object != null) {
            Object mPackagesObj = FieldUtils.readField(object, "mPackages");
            Object containsKeyObj = MethodUtils.invokeMethod(mPackagesObj, "containsKey", pluginInfo.packageName);
            if (containsKeyObj instanceof Boolean && !(Boolean) containsKeyObj) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    loadedApk = MethodUtils.invokeMethod(object, "getPackageInfoNoCheck", pluginInfo.applicationInfo, CompatibilityInfoCompat.DEFAULT_COMPATIBILITY_INFO());
                } else {
                    loadedApk = MethodUtils.invokeMethod(object, "getPackageInfoNoCheck", pluginInfo.applicationInfo);
                }
            }
            sLoadedApk.put(pluginInfo.packageName, loadedApk);
        }
        try {
            hookPackageManager();
        } catch (Exception e) {
            e.printStackTrace();
        }
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            Log.e(TAG, String.format("App/installTargetDex:thread(%s) apkFile(%s) not exist", Thread.currentThread().getName(), apkFile.getAbsolutePath()));
            return;
        }
        File dexDir = new File(hostContext.getFilesDir().getAbsolutePath() + File.separator + "container" + File.separator + packageName + File.separator + "/dex/");
        if (!dexDir.exists()) {
            dexDir.mkdirs();

        }
        File libDir = new File(hostContext.getFilesDir().getAbsolutePath() + File.separator + "container" + File.separator + packageName + File.separator + "/dex/lib");
        if (!libDir.exists()) {
            libDir.mkdirs();
        }
        String optimizedDirectory = dexDir.getAbsolutePath();
        String libraryPath = libDir.getAbsolutePath();
        String apk = pluginInfo.applicationInfo.publicSourceDir;
        if (TextUtils.isEmpty(apk)) {
            pluginInfo.applicationInfo.publicSourceDir = PluginDirHelper.getPluginApkFile(hostContext, pluginInfo.packageName);
            apk = pluginInfo.applicationInfo.publicSourceDir;
        }
        if (apk != null) {
            ClassLoader classloader = null;
            try {
                classloader = new PluginClassLoader(apk, optimizedDirectory, libraryPath, hostContext.getClassLoader().getParent());
            } catch (Exception e) {
            }
            if (classloader == null) {
                PluginDirHelper.cleanOptimizedDirectory(optimizedDirectory);
                classloader = new PluginClassLoader(apk, optimizedDirectory, libraryPath, hostContext.getClassLoader().getParent());
            }
            synchronized (loadedApk) {
                FieldUtils.writeDeclaredField(loadedApk, "mClassLoader", classloader);
            }
            Thread.currentThread().setContextClassLoader(classloader);
        }
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/preLoadApk:thread(%s) loadedApk(%s)", Thread.currentThread().getName(), loadedApk));
        if (loadedApk != null) {
            Object mApplication = FieldUtils.readField(loadedApk, "mApplication");
            if (mApplication != null) {
                return;
            }
            MethodUtils.invokeMethod(loadedApk, "makeApplication", false, ActivityThreadCompat.getInstrumentation());

        }

    }

    private static void hookPackageManager() throws Exception {

        // 这一步是因为 initializeJavaContextClassLoader 这个方法内部无意中检查了这个包是否在系统安装
        // 如果没有安装, 直接抛出异常, 这里需要临时Hook掉 PMS, 绕过这个检查.

        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThreadMethod.setAccessible(true);
        Object currentActivityThread = currentActivityThreadMethod.invoke(null);

        // 获取ActivityThread里面原始的 sPackageManager
        Field sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager");
        sPackageManagerField.setAccessible(true);
        Object sPackageManager = sPackageManagerField.get(currentActivityThread);

        // 准备好代理对象, 用来替换原始的对象
        Class<?> iPackageManagerInterface = Class.forName("android.content.pm.IPackageManager");
        Object proxy = Proxy.newProxyInstance(iPackageManagerInterface.getClassLoader(),
                new Class<?>[]{iPackageManagerInterface},
                new IPackageManagerHookHandler(sPackageManager));

        // 1. 替换掉ActivityThread里面的 sPackageManager 字段
        sPackageManagerField.set(currentActivityThread, proxy);
    }
}
