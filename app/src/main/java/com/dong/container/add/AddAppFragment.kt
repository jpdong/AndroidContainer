package com.dong.container.add

import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.dong.container.R
import com.dong.container.installAndLaunch
import com.dong.container.model.add.LocalAppInfo
import kotlinx.coroutines.*

/**
 * Created by dongjiangpeng on 2021/2/22 0022.
 */
class AddAppFragment : Fragment(){

    private val TAG = "AddAppFragment"

    lateinit var viewModel:AddAppViewModel
    lateinit var recyclerView:RecyclerView
    lateinit var adapter:AddAppAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.add_app_fragment,container,false);
        setView(view)
        setData()
        return view
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun setView(view: View) {
        recyclerView = view.findViewById<RecyclerView>(R.id.rv_add_app_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = AddAppAdapter()
        recyclerView.adapter = adapter

    }

    fun setData() {
        Log.d(TAG, String.format("/setData:thread(%s)",Thread.currentThread().getName()));
        viewModel  = ViewModelProvider(activity!!,ViewModelProvider.AndroidViewModelFactory.getInstance(activity!!.application)).get(AddAppViewModel::class.java)
        MainScope().launch {
            Log.d(TAG, String.format("/setData:thread(%s) coroutine start",Thread.currentThread().getName()));
            val localAppList = viewModel.getLocalAppList()
            adapter.setData(localAppList)
            Log.d(TAG, String.format("/setData:thread(%s) coroutine finish ",Thread.currentThread().getName()));

        }
        Log.d(TAG, String.format("/setData:thread(%s) finish",Thread.currentThread().getName()));

    }

    class AddAppAdapter:RecyclerView.Adapter<AddAppViewHolder>() {

        private val TAG = "AddAppAdapter"

        var appList = emptyArray<LocalAppInfo>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddAppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.add_app_list_item,parent,false)
            return AddAppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AddAppViewHolder, position: Int) {
            val localAppInfo = appList[position]
            holder.setData(localAppInfo)
        }

        override fun getItemCount(): Int {
            return appList.size
        }

        fun setData(appList: Array<LocalAppInfo>) {
            Log.d(TAG, String.format("/setData:thread(%s)",Thread.currentThread().getName()));
            this.appList = appList
            notifyDataSetChanged()
        }

    }

    class AddAppViewHolder(itemView:View): RecyclerView.ViewHolder(itemView) {
        val appIcon:ImageView
        val appName:TextView
        val installText:TextView

        init {
            appIcon = itemView.findViewById<ImageView>(R.id.iv_app_icon)
            appName = itemView.findViewById<TextView>(R.id.tv_app_name)
            installText = itemView.findViewById(R.id.tv_install)
        }

        fun setData(appInfo: LocalAppInfo) {
            Glide.with(itemView).load(appInfo.iconPath).into(appIcon)
            appName.text = appInfo.appName
            installText.setOnClickListener({ view->
                MainScope().launch {
                    withContext(Dispatchers.IO) {
                        installAndLaunch(itemView.context,appInfo)
                    }
                }
            })
        }
    }

}