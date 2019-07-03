package com.dong.container.util;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Created by dongjiangpeng on 2019/3/25 0025.
 */
public class CommonUtil {

    private static String mProcessName;


    public static boolean isMainProcess(Context context) {
        if (mProcessName == null) {
            mProcessName = getProcessName();
        }
        return mProcessName != null && mProcessName.equals(context.getPackageName());
    }

    private static String getProcessNameInternal() {
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

    public static String getProcessName() {
        if (TextUtils.isEmpty(mProcessName)) {
            mProcessName = getProcessNameInternal();
        }
        return mProcessName;
    }
}
