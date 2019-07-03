package com.dong.container;

import android.app.Application;
import android.content.Context;
import android.util.Log;


import com.dong.container.hook.AMSHookHelper;
import com.dong.container.util.CommonUtil;
import com.dong.container.util.HackUtil;
import com.dong.container.util.NativeLibraryHelper;
import com.dong.container.util.Reflect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Created by dongjiangpeng on 2019/5/28 0028.
 */
public class App extends Application {

    private static final String TAG = "dong";

    private static App sInstance;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        String processName = getCurProcessName();
        if (processName.contains("child")) {
            installTargetDex(base);
        }
        //installTargetDex(base);
    }

    private void installTargetDex(Context context) {
        Log.d(TAG, String.format("App/installTargetDex:thread(%s)",Thread.currentThread().getName()));
        //File apkFile = new File("/data/app/com.example.targetapp-1.apk");
        File apkFile = new File(context.getFilesDir().getAbsolutePath() + "/targetapp-debug.apk");
        //File apkFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/targetapp-debug.apk");
        if (!apkFile.exists()) {
            Log.e(TAG, String.format("App/installTargetDex:thread(%s) apkFile(%s) not exist",Thread.currentThread().getName(),apkFile.getAbsolutePath()));
            return;
        }
        File dexDir = new File(context.getFilesDir().getAbsolutePath() + "/dex/");
        if (!dexDir.exists()) {
            dexDir.mkdir();
        }
        File libDir = new File(context.getFilesDir().getAbsolutePath() + "/dex/lib");
        if (!libDir.exists()) {
            libDir.mkdir();
        }
        /*File dexFile = new File(context.getFilesDir().getAbsolutePath() + "/dex/target.dex");
        if (!dexFile.exists()) {
            try {
                dexFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG,"App/installTargetDex:" + e.toString());
            }
        }
        Log.d(TAG, String.format("App/installTargetDex:thread(%s) dexfile(%s)",Thread.currentThread().getName(),dexFile));*/
        /*try {
            DexFile.loadDex(apkFile.getAbsolutePath(),
                    context.getFilesDir().getAbsolutePath() + "/dex/" , 0).close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG,"App/installTargetDex:" + e.toString());
        }*/
        //copyNativeBinariesBeforeL(apkFile,shareLibDir);
        Log.d(TAG, String.format("App/installTargetDex:thread(%s) before classloader (%s)",Thread.currentThread().getName(),App.class.getClassLoader()));
        /*HackUtil.addJarToClassLoaderRaw(context.getFilesDir().getAbsolutePath() + "/dex/target.jar",
                context.getFilesDir().getAbsolutePath() + "/dex/",
                null,
                App.class.getClassLoader(),
                false);*/
        HackUtil.addJarToClassLoaderRaw(apkFile.getAbsolutePath(),
                context.getFilesDir().getAbsolutePath() + "/dex/",
                null,
                App.class.getClassLoader(),
                false);
        Log.d(TAG, String.format("App/installTargetDex:thread(%s) classloader(%s)",Thread.currentThread().getName(),App.class.getClassLoader()));
    }

    private static int copyNativeBinariesBeforeL(File apkFile, File sharedLibraryDir) {
        try {
            return Reflect.on(NativeLibraryHelper.TYPE).call("copyNativeBinariesIfNeededLI", apkFile, sharedLibraryDir)
                    .get();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return -1;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        if (CommonUtil.getProcessName().contains("child")) {
            Log.d(TAG, String.format("App/onCreate:thread(%s) classloader(%s)",Thread.currentThread().getName(),getClassLoader()));
            try {
                AMSHookHelper.hookActivityManagerNative();
                AMSHookHelper.hookActivityThreadHandler();
            } catch (Throwable throwable) {
                throw new RuntimeException("hook failed", throwable);
            }
        }
    }

    private String getCurProcessName() {
        try {
            File file = new File("/proc/" + android.os.Process.myPid() + "/" + "cmdline");
            BufferedReader mBufferedReader = new BufferedReader(new FileReader(file));
            String processName = mBufferedReader.readLine().trim();
            mBufferedReader.close();
            return processName;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

    public static App getInstance() {
        return sInstance;
    }
}
