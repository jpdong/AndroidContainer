package com.dong.container.hook;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;


import com.dong.container.App;
import com.dong.container.KotlinToolsKt;
import com.dong.container.util.CommonUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

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
        Log.d(TAG, String.format("ActivityThreadHandlerCallback/handleLaunchActivity:thread(%s) KotlinToolsKt.getAppParserMap()(%s) ",Thread.currentThread().getName(),KotlinToolsKt.getAppParserMap().size()));
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
            preLoadApk(App.getInstance(), activityInfo);
        } catch (Exception e) {
            e.printStackTrace();
        }

        ClassLoader pluginClassLoader = null;
        try {
            pluginClassLoader = getPluginClassLoader();
        }catch (Exception e) {
            e.printStackTrace();
        }

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
        } finally {
            intent.setExtrasClassLoader(classLoader);
        }
    }

    private ClassLoader getPluginClassLoader () throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
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

    public static void preLoadApk(Context hostContext, ComponentInfo pluginInfo) throws IOException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, PackageManager.NameNotFoundException, ClassNotFoundException {
        if (pluginInfo == null && hostContext == null) {
            return;
        }

        /*添加插件的LoadedApk对象到ActivityThread.mPackages*/

        Object loadedApk = null;
        Object object = ActivityThreadCompat.currentActivityThread();
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
        }
        /*File apkFile = new File(hostContext.getFilesDir().getAbsolutePath() + "/targetapp-debug.apk");
        //File apkFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/targetapp-debug.apk");
        if (!apkFile.exists()) {
            Log.e(TAG, String.format("App/installTargetDex:thread(%s) apkFile(%s) not exist",Thread.currentThread().getName(),apkFile.getAbsolutePath()));
            return;
        }
        File dexDir = new File(hostContext.getFilesDir().getAbsolutePath() + "/dex/");
        if (!dexDir.exists()) {
            dexDir.mkdir();
        }
        File libDir = new File(hostContext.getFilesDir().getAbsolutePath() + "/dex/lib");
        if (!libDir.exists()) {
            libDir.mkdir();
        }
        //String optimizedDirectory = PluginDirHelper.getPluginDalvikCacheDir(hostContext, pluginInfo.packageName);
        String optimizedDirectory = dexDir.getAbsolutePath();
        //String libraryPath = PluginDirHelper.getPluginNativeLibraryDir(hostContext, pluginInfo.packageName);
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
            if(classloader==null){
                PluginDirHelper.cleanOptimizedDirectory(optimizedDirectory);
                classloader = new PluginClassLoader(apk, optimizedDirectory, libraryPath, hostContext.getClassLoader().getParent());
            }
            synchronized (loadedApk) {
                FieldUtils.writeDeclaredField(loadedApk, "mClassLoader", classloader);
            }
            Thread.currentThread().setContextClassLoader(classloader);
        }*/
        synchronized (loadedApk) {
            FieldUtils.writeDeclaredField(loadedApk, "mClassLoader", App.getInstance().getClassLoader());
        }
        if (loadedApk != null) {
            Object mApplication = FieldUtils.readField(loadedApk, "mApplication");
            if (mApplication != null) {
                return;
            }
            MethodUtils.invokeMethod(loadedApk, "makeApplication", false, ActivityThreadCompat.getInstrumentation());

        }

    }
}
