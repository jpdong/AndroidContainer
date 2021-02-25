package com.dong.container.hook;

import android.content.pm.PackageInfo;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * @author weishu
 * @date 16/3/29
 */
public class IPackageManagerHookHandler implements InvocationHandler {

    private static final String TAG = "dong";

    private Object mBase;

    public IPackageManagerHookHandler(Object base) {
        mBase = base;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getName().equals("getPackageInfo")) {
            Log.d(TAG, String.format("IPackageManagerHookHandler/invoke:thread(%s) args(%s)",Thread.currentThread().getName(),args));
            //temp add empty package info,will create really package info by PackageParser
            return new PackageInfo();
        }
        return method.invoke(mBase, args);
    }
}
