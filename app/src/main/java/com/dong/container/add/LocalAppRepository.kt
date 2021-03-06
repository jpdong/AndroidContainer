package com.dong.container.add

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Environment
import android.util.Log
import com.dong.container.SingletonHolder
import com.dong.container.model.add.LocalAppInfo
import com.dong.container.saveBitmapToFile
import java.io.File


/**
 * Created by dongjiangpeng on 2021/2/22 0022.
 */
class LocalAppRepository private constructor(var context: Context) {

    private val TAG = "LocalAppRepository"

    companion object : SingletonHolder<LocalAppRepository, Context>(::LocalAppRepository)

    fun getLocalAppList(): Array<LocalAppInfo> {
        val pm = context.packageManager
        var appInfos: List<PackageInfo>? = null
        appInfos = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyArray()
        }
        val result = ArrayList<LocalAppInfo>()
        val myPkgname = context.packageName
        for (appInfo in appInfos!!) {
            if (appInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM <= 0) {
                val intent: Intent? = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (intent == null) {
                    continue
                } else {
                    val resolveInfo: ResolveInfo? = pm.resolveActivity(intent, 0)
                    if (resolveInfo == null || resolveInfo.activityInfo == null) {
                        continue
                    }
                }
                val packageName = appInfo.packageName
                if (myPkgname.contains(packageName) || packageName.contains(myPkgname)) {
                    continue
                }
                val applicationInfo = appInfo.applicationInfo
                val iconPath: String = "${context.filesDir}${File.separator}$packageName.png"
                val iconFile = File(iconPath)
                if (!iconFile.exists()) {
                    saveBitmapToFile(pm, applicationInfo, iconPath)
                }
                result.add(LocalAppInfo(iconPath, pm.getApplicationLabel(applicationInfo).toString(), packageName, applicationInfo.sourceDir))
            }
        }
        return result.toTypedArray() + getLocalApkList()
    }

    fun getLocalApkList(): Array<LocalAppInfo> {
        val pm = context.packageManager
        val result = ArrayList<LocalAppInfo>()
        val apkFilePaths = scanApkFile()
        apkFilePaths?.forEach { apkPath ->
            val packageInfo = pm.getPackageArchiveInfo(apkPath, 0);
            val applicationInfo = packageInfo.applicationInfo
            applicationInfo.sourceDir = apkPath
            applicationInfo.publicSourceDir = apkPath
            val packageName = packageInfo.packageName
            val iconPath: String = "${context.filesDir}${File.separator}$packageName.png"
            val iconFile = File(iconPath)
            if (!iconFile.exists()) {
                saveBitmapToFile(pm, applicationInfo, iconPath)
            }
            result.add(LocalAppInfo(iconPath, pm.getApplicationLabel(applicationInfo).toString(), packageInfo.packageName, apkPath))
        }

        Log.d(TAG, String.format("/getLocalApkList:thread(%s) size ${result.size}", Thread.currentThread().getName()));
        return result.toTypedArray()
    }

    fun scanApkFile(): List<String> {
        val result = ArrayList<String>()
        scanApkInternal(Environment.getExternalStorageDirectory(), result)
        return result
    }

    fun scanApkInternal(file: File, list: ArrayList<String>) {
        if (file.isDirectory) {
            val files = file.listFiles()
            files?.forEach { item ->
                scanApkInternal(item, list)
            }
        } else {
            if (file.name.endsWith(".apk")) {
                list.add(file.absolutePath)
            }
        }
    }

}