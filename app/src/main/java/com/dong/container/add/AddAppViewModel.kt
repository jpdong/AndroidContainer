package com.dong.container.add

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.dong.container.model.add.LocalAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Created by dongjiangpeng on 2021/2/22 0022.
 */
class AddAppViewModel(application: Application):AndroidViewModel(application) {

    private val TAG = "AddAppViewModel"

    suspend fun getLocalAppList(): Array<LocalAppInfo> {
        Log.d(TAG, String.format("/:thread(%s)",Thread.currentThread().getName()));
        return withContext(Dispatchers.IO) {
                return@withContext LocalAppRepository.getInstance(getApplication()).getLocalAppList()
        }
    }


}