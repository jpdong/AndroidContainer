package com.dong.container

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.dong.container.hook.AMSHookHelper
import com.dong.container.hook.PluginPackageParser
import com.dong.container.model.add.LocalAppInfo
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Created by dongjiangpeng on 2021/2/22 0022.
 */
open class SingletonHolder<out T, in A>(creator: (A) -> T) {
    private var creator: ((A) -> T)? = creator

    @Volatile
    private var instance: T? = null

    fun getInstance(arg: A): T {
        val i = instance
        if (i != null) {
            return i
        }

        return synchronized(this) {
            val i2 = instance
            if (i2 != null) {
                i2
            } else {
                val created = creator!!(arg)
                instance = created
                creator = null
                created
            }
        }
    }
}

fun saveBitmapToFile(pm: PackageManager?, applicationInfo: ApplicationInfo, filePath: String?) {
    var loadIcon: Drawable? = null
    loadIcon = try {
        applicationInfo.loadIcon(pm)
    } catch (e: OutOfMemoryError) {
        e.printStackTrace()
        System.gc()
        return
    }
    if (loadIcon != null) {
        var bitmap: Bitmap? = null
        bitmap = if (loadIcon is BitmapDrawable) {
            loadIcon.bitmap
        } else {
            getBitmapFromDrawable(loadIcon)
        }
        if (bitmap == null || filePath == null || filePath.length == 0) {
            return
        }
        val iconFile = File(filePath)
        if (iconFile != null && (!iconFile.exists() || !iconFile.isDirectory)) {
            iconFile.parentFile.mkdirs()
        }
        try {
            val os1: OutputStream = FileOutputStream(filePath)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os1)
            os1.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun getBitmapFromDrawable(drawable: Drawable?): Bitmap? {
    var bmp: Bitmap? = null
    if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
        bmp = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
    }
    return bmp
}

public val appParserMap = HashMap<String, PluginPackageParser>()
public val stubPackageMap = HashMap<String, ComponentName>()
private const val TAG = "KotlinTools"
fun installAndLaunch(context: Context, localAppInfo: LocalAppInfo) {
    Log.d(TAG, String.format("/installAndLaunch:thread(%s)", Thread.currentThread().getName()));
    val packageName = localAppInfo.packageName;
    var packageParser = appParserMap.get(packageName)
    if (packageParser == null) {
        packageParser = PluginPackageParser(App.getInstance(), File(localAppInfo.apkPath))
        appParserMap.put(packageName, packageParser)
    }


    val componentName = packageParser.getLaunchComponent(packageName);
    Log.d(TAG, String.format("/installAndLaunch:thread(%s) cn $componentName",Thread.currentThread().getName()));
    val intent = Intent()
    intent.setComponent(componentName)

    val newIntent = Intent();

    // 替身Activity的包名, 也就是我们自己的包名
    val stubPackage = "com.dong.container";

    // 这里我们把启动的Activity临时替换为 StubActivity
    val componentNameStub = ComponentName(stubPackage, "com.dong.container.StubActivity");
    newIntent.setComponent(componentNameStub);

    // 把我们原始要启动的TargetActivity先存起来
    newIntent.putExtra(AMSHookHelper.EXTRA_TARGET_INTENT, intent);
    newIntent.putExtra(AMSHookHelper.EXTRA_TARGET_APKPATH,localAppInfo.apkPath)
    newIntent.putExtra(AMSHookHelper.EXTRA_TARGET_PACKAGENAME,localAppInfo.packageName)

    // 替换掉Intent, 达到欺骗AMS的目的
    context.startActivity(newIntent)
}