package com.dong.container.add

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.dong.container.R

/**
 * Created by dongjiangpeng on 2021/2/22 0022.
 */
class AddAppActivity : AppCompatActivity() {

    val TAG = "AddAppActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, String.format("/:thread(%s)",Thread.currentThread().getName()));
        setContentView(R.layout.empty_acitvity)
        val addAppFragment = AddAppFragment()
        supportFragmentManager.beginTransaction().replace(R.id.fl_content,addAppFragment).commitAllowingStateLoss()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}