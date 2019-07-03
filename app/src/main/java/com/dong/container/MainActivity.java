package com.dong.container;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;


import com.dong.container.hook.AMSHookHelper;

import java.io.File;

/**
 * @author weishu
 * @date 16/1/7.
 */
public class MainActivity extends Activity {

    private static final String TAG = "dong";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);

        try {
            AMSHookHelper.hookActivityManagerNative();
            AMSHookHelper.hookActivityThreadHandler();
        } catch (Throwable throwable) {
            throw new RuntimeException("hook failed", throwable);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Button button = new Button(this);
        button.setText("启动TargetActivity");

        File file = new File("/sdcard/dong/targetapp-debug.apk");
        Log.d(TAG, String.format("StubActivity/onCreate:thread(%s) file(%s) exist(%s)",Thread.currentThread().getName(),file.getAbsolutePath(),file.exists()));

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 启动目标Activity; 注意这个Activity是没有在AndroidManifest.xml中显式声明的
                // 但是调用者并不需要知道, 就像一个普通的Activity一样
                Intent intent = new Intent();
                //Intent intent = new Intent(MainActivity.this,TargetActivity.class);
                PackageManager packageManager = getPackageManager();
                //Intent intent1 = packageManager.getLaunchIntentForPackage("com.example.targetapp");
                //intent.setComponent(new ComponentName("com.tencent.mm","com.tencent.mm.ui.LauncherUI"));
                //intent.setComponent(new ComponentName("com.example.targetapp","com.example.targetapp.TargetActivity"));
                intent.setComponent(new ComponentName("com.example.targetapp","com.example.targetapp.MainActivity"));
                //startActivity(new Intent(MainActivity.this, TargetActivity.class));
                Log.d(TAG, String.format("MainActivity/onClick:thread(%s) intent1(%s)",Thread.currentThread().getName(),intent.toString()));
                if (intent != null) {
                    startActivity(intent);
                } else {
                    Log.d(TAG, String.format("MainActivity/onClick:thread(%s)",Thread.currentThread().getName()));
                }
            }
        });
        setContentView(button);

    }
}
