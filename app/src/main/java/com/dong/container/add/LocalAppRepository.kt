package com.dong.container.add

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import com.dong.container.SingletonHolder
import com.dong.container.model.add.LocalAppInfo
import com.dong.container.saveBitmapToFile
import java.io.File


/**
 * Created by dongjiangpeng on 2021/2/22 0022.
 */
class LocalAppRepository private constructor(var context: Context) {

    companion object : SingletonHolder<LocalAppRepository, Context>(::LocalAppRepository)

    fun getLocalAppList():Array<LocalAppInfo>{
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
                result.add(LocalAppInfo(iconPath,pm.getApplicationLabel(applicationInfo).toString(), packageName,applicationInfo.sourceDir))
            }
        }
        return result.toTypedArray()
    }


}