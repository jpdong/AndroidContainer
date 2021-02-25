package com.dong.container.launch

import java.io.FileNotFoundException

/**
 * Created by dongjiangpeng on 2021/2/22 0022.
 */
class LaunchAppActivity {

    fun main() {
        test()
    }

    fun test() {
        throw FileNotFoundException("")
    }
}