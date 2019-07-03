package com.dong.container.util;


import java.io.File;


/**
 * @author Lody
 */

public class NativeLibraryHelper {
    public static Class<?> TYPE = RefClass.load(NativeLibraryHelper.class, "com.android.internal.content.NativeLibraryHelper");

    @MethodParams({Handle.class, File.class, String.class})
    public static RefStaticMethod<Integer> copyNativeBinaries;

    @MethodParams({Handle.class, String[].class})
    public static RefStaticMethod<Integer> findSupportedAbi;

    public static class Handle {
        public static Class<?> TYPE = RefClass.load(Handle.class, "com.android.internal.content.NativeLibraryHelper$Handle");

        @MethodParams({File.class})
        public static RefStaticMethod<Object> create;
    }
}
