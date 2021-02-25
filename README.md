# AndroidContainer
Android插件化的具体表现就是在无需将应用包（apk）安装到操作系统，就可以启动的过程，我觉得与其叫插件化，不如叫App虚拟机更合适。
未安装就启动一个apk需要解决两大问题
1.  如何绕过系统限制，启动未在AndroidManifest.xml的Activity
2. 如何通过上下文环境找到apk里的资源  

因为Java语言的特殊性，上述两个问题在根本上都可以用反射和代理解决。
- 我们在问题一上，可以用ActivityManagerService的代理类拦截startActivity方法，把未注册的Activity替换进去。
- 关键在问题二上，实现过程远比问题一复杂。Android App里查找资源和包名最终是通过LoadedApk获取。要获取对应apk的LoadedApk，就需要用PackageParser解析整个apk。但由于不同的Android版本Api的变化，我们需要创建出适配的PackageParser。

大致流程是：
1. 安装apk
- 1.1代理各个android版本的PackageParser
- 1.2调用parsePackage(File file, int flags)，解析文件
- 1.3通过反射PackageParser里的activities、services、providers、receivers字段，获取文件中的组件信息，并缓存
- 1.4代理ActivityThread里的PackageManager，根据上述解析的结果返回对应方法的内容
2. 启动
- 2.1为四大组件分别新建注册大量的空类，用做占位，进程配置均独立且与容器进程不一样
- 2.1启动插件app的时候，根据parse出的内容找到intentFiler为Launcher的activity，找一个可用的占位Activity，将插件的组件信息、包名、文件路径存储到intent里面，intent真实启动占位activity
- 2.2根据源码，ActivityManagerService对占位Activity处理完成之后，会通过Binder调用插件进程
- 2.3为ActivityThread的Handler设置一个默认Callback，里面对关键消息（比如LAUNCH_ACTIVITY）Message进行拦截处理，取出插件信息，将插件的组件信息设置到intent里
- 2.4反射创建LoadedApk对象，新建ClassLoader加载插件的类路径，将插件classloader添加到LoadedApk和Message的intent里
- 2.5message替换完毕，走系统的message处理逻辑
- 2.6插件被启动
