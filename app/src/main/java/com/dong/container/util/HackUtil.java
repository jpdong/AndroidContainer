package com.dong.container.util;

import android.content.pm.Signature;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

public class HackUtil {
    private static final String TAG = "dong";
    private static final boolean DEBUG = true;
    private static Map<String, ClassLoader> sLoaders = new HashMap<String, ClassLoader>();
    private static boolean isYunOs = false;

    private static final ClassLoader bootstrapLoader = Object.class.getClassLoader();
    private static final ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
    private static Class PathClassLoaderClass;
    private static Class BaseDexClassLoaderClass;
    private static Method ClassLoaderFindResources;
    private static Method ClassLoaderFindResource;
    private static String CLASSES_DEX = "classes.dex";

    static {
        try {
            PathClassLoaderClass = Class.forName("dalvik.system.PathClassLoader");
        } catch (Exception e) {
            PathClassLoaderClass = null;
        }
        try {
            BaseDexClassLoaderClass = Class.forName("dalvik.system.BaseDexClassLoader");
        } catch (Exception e) {
            BaseDexClassLoaderClass = null;
        }
        try {
            ClassLoaderFindResources = ClassLoader.class.getDeclaredMethod("findResources", new Class[]{String.class});
            ClassLoaderFindResources.setAccessible(true);
        } catch (Exception e) {
            ClassLoaderFindResources = null;
        }
        try {
            ClassLoaderFindResource = ClassLoader.class.getDeclaredMethod("findResource", new Class[]{String.class});
            ClassLoaderFindResource.setAccessible(true);
        } catch (Exception e) {
            ClassLoaderFindResource = null;
        }
    }

    public static synchronized ClassLoader addJarToClassLoaderList(String jarPath, String optimizedDirectory,
                                                                   String libraryPath, ClassLoader loader, boolean isAppend, boolean removeOldLib) {
        if (DEBUG) Log.d(TAG, "addJarToClassLoaderList: jarPath = " + jarPath + ", optimizedDirectory = " +
            optimizedDirectory + ", libraryPath = " + libraryPath + ", loader = " + loader + ", isAppend = " + isAppend
            + ", removeOldLib = " + removeOldLib);
        if (!copyNativeBinariesIfNeededLI(jarPath, libraryPath, removeOldLib)) {
            if (DEBUG) Log.e(TAG, "copyNativeBinariesIfNeededLI failed!");
        }

        ClassLoader newClassLoader = null;
        ClassLoader parent = loader.getParent();
        while (parent != null) {
            if (DEBUG) Log.d(TAG, " Searching newClassLoader, this one is " + parent);
            if (DexClassLoader.class.isInstance(parent)) {
                if (DEBUG) Log.d(TAG, "Found DexClassLoader @ " + parent);
                if (maybeOurClassLoader(parent)) {
                    newClassLoader = parent;
                    break;
                }
            } else if (PathClassLoaderClass != null && PathClassLoaderClass.isInstance(parent)) {
                if (DEBUG) Log.d(TAG, "Found PathClassLoader @ " + parent);
                if (maybeOurClassLoader(parent)) {
                    newClassLoader = parent;
                    break;
                }
            }
            parent = parent.getParent();
        }

        if (newClassLoader == null) {
            parent = loader.getParent();
            newClassLoader = getNewClassLoader(jarPath, optimizedDirectory, libraryPath, parent);
            if (DEBUG) Log.d(TAG, "Can't find newClassLoader in this list, new ClassLoader() @ " + newClassLoader);
            setObjectFieldRaw(loader, "parent", newClassLoader, true);
        } else if ((newClassLoader != null) && addJarToClassLoaderRaw(jarPath, optimizedDirectory,
                libraryPath, newClassLoader, isAppend)) {
            if (DEBUG) Log.d(TAG, "Add jarFile[" + jarPath + "] to newClassLoader[" + newClassLoader + "] success!");
        }

        return newClassLoader;
    }

    private static ClassLoader getNewClassLoader(String jarPath, String optimizedDirectory,
                                                 String libraryPath, ClassLoader parent) {
        ClassLoader newLoader = null;
        if (PathClassLoaderClass != null) {
            try {
                Constructor<?> constructor = PathClassLoaderClass.getDeclaredConstructor(new Class[] { String.class, ClassLoader.class });
                constructor.setAccessible(true);
                newLoader = (ClassLoader)constructor.newInstance(new Object[] { ".", parent });
                //Call ensureInit in 2.2,2.3
                Class.forName("xxx.yyy.zzz.ensureInit", false, newLoader);
            } catch (Exception e) {
                if (DEBUG) e.printStackTrace();
            }
            if (DEBUG) Log.d(TAG, "newClassLoader: try PathClassLoader first, newLoader = " + newLoader);
            if (newLoader == null || !addJarToClassLoaderRaw(jarPath, optimizedDirectory, libraryPath, newLoader, false)) {
                if (DEBUG) Log.d(TAG, "newClassLoader: try PathClassLoader failed!");
                newLoader = null;
            }
        }
        if (newLoader == null) {
            newLoader = new DexClassLoader(jarPath, optimizedDirectory, libraryPath, parent);
        }
        if (DEBUG) Log.d(TAG, "newClassLoader: the final newLoader = " + newLoader);
        return newLoader;
    }

    public static boolean addJarToClassLoader(String jarPath, String optimizedDirectory,
                                              String libraryPath, ClassLoader loader, boolean isAppend, boolean removeOldLib) {
        copyNativeBinariesIfNeededLI(jarPath, libraryPath, removeOldLib);
        return addJarToClassLoaderRaw(jarPath, optimizedDirectory, libraryPath, loader, isAppend);
    }

    public static synchronized boolean addJarToClassLoaderRaw(String jarPath, String optimizedDirectory,
                                                              String libraryPath, ClassLoader loader, boolean isAppend) {
        boolean success = false;
        ClassLoader tmpLoader = null;
        if (((tmpLoader = sLoaders.get(jarPath)) != null) && (tmpLoader == loader)) {
            if (DEBUG) Log.d(TAG, "jarPath: " + jarPath + " already in ClassLoader (" + loader + ")");
            return true;
        }
        success = addJarToClassLoaderICS(jarPath, optimizedDirectory, libraryPath, loader, isAppend);
        if (!success) {
            success = addJarToClassLoaderGB(jarPath, optimizedDirectory, libraryPath, loader, isAppend);
        }
        if (success) {
            sLoaders.put(jarPath, loader);
        }
        return success;
    }

    // Add this function to filter xposed xresources classloader.
    private static boolean maybeOurClassLoader(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        Enumeration<URL> resources = null;
        try {
            resources = ClassLoaderFindResources != null ?
                    (Enumeration<URL>) ClassLoaderFindResources.invoke(loader, new Object[]{CLASSES_DEX}) : null;
            if (resources == null || !resources.hasMoreElements()) {
                URL resource = ClassLoaderFindResource != null ?
                        (URL)ClassLoaderFindResource.invoke(loader, new Object[]{CLASSES_DEX}) : null;
                if (resource != null) {
                    ArrayList<URL> result = new ArrayList<URL>();
                    result.add(resource);
                    resources = Collections.enumeration(result);
                }
            }
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }

        if (resources == null || !resources.hasMoreElements()) {
            if (DEBUG) Log.w(TAG, "maybeOurClassLoader: no " + CLASSES_DEX + " found in this loader " + loader);
            return false;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            if (url != null) {
                JarFile jarFile = null;
                try {
                    JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
                    JarEntry jarEntry = jarConnection.getJarEntry();
                    jarFile = jarConnection.getJarFile();
                    String jarPath = jarFile.getName();
                    if (DEBUG) Log.d(TAG, "URL: " + url + ", jarEntry = " + jarEntry + ", jarFile = " + jarPath);
                } catch (Exception e) {
                    if (DEBUG) e.printStackTrace();
                }
            }
        }

        return true;
    }

    private static Object[] expandObjectArray(Class<?> clazz, Object[] orig, Object[] add, boolean isAppend) {
        Object[] newArray = (Object[]) Array.newInstance(clazz, orig.length + add.length);
        if (isAppend) {
            System.arraycopy(orig, 0, newArray, 0, orig.length);
            System.arraycopy(add, 0, newArray, orig.length, add.length);
        } else {
            System.arraycopy(add, 0, newArray, 0, add.length);
            System.arraycopy(orig, 0, newArray, add.length, orig.length);
        }
        return newArray;
    }

    private static String generateOutputName(String sourcePathName, String outputDir) {
        StringBuilder newStr = new StringBuilder(80);

        /* start with the output directory */
        newStr.append(outputDir);
        if (!outputDir.endsWith("/"))
            newStr.append("/");

        /* get the filename component of the path */
        String sourceFileName;
        int lastSlash = sourcePathName.lastIndexOf("/");
        if (lastSlash < 0)
            sourceFileName = sourcePathName;
        else
            sourceFileName = sourcePathName.substring(lastSlash+1);

        /*
         * Replace ".jar", ".zip", whatever with ".dex".  We don't want to
         * use ".odex", because the build system uses that for files that
         * are paired with resource-only jar files.  If the VM can assume
         * that there's no classes.dex in the matching jar, it doesn't need
         * to open the jar to check for updated dependencies, providing a
         * slight performance boost at startup.  The use of ".dex" here
         * matches the use on files in /data/dalvik-cache.
         */
        int lastDot = sourceFileName.lastIndexOf(".");
        if (lastDot < 0)
            newStr.append(sourceFileName);
        else
            newStr.append(sourceFileName, 0, lastDot);

        newStr.append(isYunOs ? ".lex" : ".dex");

        if (DEBUG) Log.d(TAG, "Output file will be " + newStr.toString());
        return newStr.toString();
    }

    private static boolean addJarToClassLoaderGB(String jarPath, String optimizedDirectory,
                                                 String libraryPath, ClassLoader loader, boolean isAppend) {
        Class<?> loaderClass = null;
        boolean isPathClassLoader = false;
        try {
            if (((PathClassLoaderClass != null) && PathClassLoaderClass.isInstance(loader))) {
                loaderClass = PathClassLoaderClass;
                isPathClassLoader = true;
            } else if (DexClassLoader.class.isInstance(loader)) {
                loaderClass = DexClassLoader.class;
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        if (loaderClass == null) {
            return false;
        }

        File pathFile = new File(jarPath);
        if (!pathFile.exists()) {
            return false;
        }

        try {
            Field mDexs = loaderClass.getDeclaredField("mDexs");
            mDexs.setAccessible(true);
            Object[] mDexsObj = (Object[])mDexs.get(loader);
            if (DEBUG) Log.d(TAG, "mDexs = " + mDexsObj);
            String outputName = null;
            if (!isPathClassLoader) {
                try {
                    Method generateOutputName = loaderClass.getDeclaredMethod("generateOutputName", String.class, String.class);
                    generateOutputName.setAccessible(true);
                    outputName = (String)generateOutputName.invoke(null, jarPath, optimizedDirectory);
                    if (DEBUG) Log.d(TAG, "Method generateOutputName() in class: " + loaderClass + " return " + outputName);
                } catch (Exception e) {
                    if (DEBUG) Log.d(TAG, "Can't found method generateOutputName() in class: "
                        + loaderClass + ", using default");
                }
            }
            if (outputName == null) {
                outputName = generateOutputName(jarPath, optimizedDirectory);
            }
            if (DEBUG) Log.d(TAG, "GB dex outputName: " + outputName);
            DexFile dexFile = DexFile.loadDex(jarPath, outputName, 0);
            Object[] newDexs = expandObjectArray(DexFile.class, mDexsObj, new DexFile[] {dexFile}, isAppend);

            Field mFiles = loaderClass.getDeclaredField("mFiles");
            mFiles.setAccessible(true);
            Object[] mFilesObj = (Object[])mFiles.get(loader);
            if (DEBUG) Log.d(TAG, "mFiles = " + mFilesObj);
            Object[] newFiles = expandObjectArray(File.class, mFilesObj, new File[] {pathFile}, isAppend);

            ZipFile zipFile = new ZipFile(pathFile);
            Field mZips = loaderClass.getDeclaredField("mZips");
            mZips.setAccessible(true);
            Object[] mZipsObj = (Object[])mZips.get(loader);
            if (DEBUG) Log.d(TAG, "mZips = " + mZipsObj);
            Object[] newZips = expandObjectArray(ZipFile.class, mZipsObj, new ZipFile[] {zipFile}, isAppend);

            try {
                Field libraryPathElements = loaderClass.getDeclaredField("libraryPathElements");
                libraryPathElements.setAccessible(true);
                List<String> libraryPathElementsObj = (List<String>)libraryPathElements.get(loader);
                if (DEBUG) Log.d(TAG, "libraryPathElementsObj = " + libraryPathElementsObj);

                if (!libraryPath.endsWith("/")) {
                    libraryPath += "/";
                }
                if (libraryPathElementsObj instanceof List) {
                    if (!isAppend) {
                        libraryPathElementsObj.add(0, libraryPath);
                    } else {
                        libraryPathElementsObj.add(libraryPath);
                    }
                    if (DEBUG) Log.d(TAG, "libraryPathElementsObj = " + libraryPathElementsObj);
                }
            } catch (Exception e) {
                Field mLibPaths = loaderClass.getDeclaredField("mLibPaths");
                mLibPaths.setAccessible(true);
                Object[] mLibPathsObj = (Object[])mLibPaths.get(loader);
                if (DEBUG) {
                    Log.d(TAG, "mLibPaths = " + mLibPathsObj);
                    String[] paths = (String[])mLibPathsObj;
                    for (String path: paths)
                        Log.d(TAG, "  path = " + path);
                }
                if (!libraryPath.endsWith("/")) {
                    libraryPath += "/";
                }
                Object[] newLibPaths = expandObjectArray(String.class, mLibPathsObj, new String[] {libraryPath}, isAppend);
                mLibPaths.set(loader, newLibPaths);
                if (DEBUG) {
                    Log.d(TAG, "newLibPaths = " + newLibPaths);
                    String[] paths = (String[])newLibPaths;
                    for (String path: paths)
                        Log.d(TAG, "  path = " + path);
                }
            }

            if (isPathClassLoader) {
                Field mPaths = loaderClass.getDeclaredField("mPaths");
                mPaths.setAccessible(true);
                Object[] mPathsObj = (Object[])mPaths.get(loader);
                if (DEBUG) Log.d(TAG, "mPaths = " + mPathsObj);
                Object[] newPaths = expandObjectArray(String.class, mPathsObj, new String[] {jarPath}, isAppend);
                mPaths.set(loader, newPaths);
            }

            mFiles.set(loader, newFiles);
            mZips.set(loader, newZips);
            mDexs.set(loader, newDexs);
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
            if (isPathClassLoader && !isYunOs) {
                try {
                    loaderClass.getDeclaredField("mLexs");
                    isYunOs = true;
                    if (DEBUG) Log.d(TAG, "Found field (mLexs) in class" + loaderClass + ", set isYunOs to true");
                } catch (Exception e1) {}
            }
            return false;
        }
        return true;
    }

    private static boolean addJarToClassLoaderICS(String jarPath, String optimizedDirectory,
                                                  String libraryPath, ClassLoader loader, boolean isAppend) {
        if (BaseDexClassLoaderClass == null || !BaseDexClassLoaderClass.isInstance(loader)) {
            return false;
        }

        File pathFile = new File(jarPath);
        if (!pathFile.exists()) {
            return false;
        }

        try {
            Field pathList = BaseDexClassLoaderClass.getDeclaredField("pathList");
            pathList.setAccessible(true);
            Object pathListObj = pathList.get(loader);
            if (DEBUG) Log.d(TAG, "pathList = " + pathListObj);

            final Class<?> DexPathList = Class.forName("dalvik.system.DexPathList");

            {
                // insert Dexs
                Field dexElements = DexPathList.getDeclaredField("dexElements");
                dexElements.setAccessible(true);
                Object[] dexElementsObj = (Object[]) dexElements.get(pathListObj);
                if (DEBUG) Log.d(TAG, "dexElements = " + dexElementsObj);

                Object[] newElements = null;
                ArrayList<File> newFiles = new ArrayList<File>();
                newFiles.add(pathFile);

                try {
                    /* andoird o */
                    final Method makeDexElements = DexPathList.getDeclaredMethod("makeDexElements", List.class, File.class, List.class, ClassLoader.class);
                    makeDexElements.setAccessible(true);
                    newElements = (Object[]) makeDexElements.invoke(null, newFiles,
                            optimizedDirectory != null ? new File(optimizedDirectory) : null, new ArrayList<IOException>(), loader);
                } catch (Exception ignoredO) {
                    try {
                        /* andoird m */
                        final Method makePathElements = DexPathList.getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                        makePathElements.setAccessible(true);
                        newElements = (Object[]) makePathElements.invoke(null, newFiles, new File(optimizedDirectory), new ArrayList<IOException>());
                    } catch (Exception ignoredM) {
                        try {
                            /* First, assume Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT */
                            final Method makeDexElements = DexPathList.getDeclaredMethod("makeDexElements", ArrayList.class, File.class, ArrayList.class);
                            makeDexElements.setAccessible(true);
                            newElements = (Object[]) makeDexElements.invoke(null, newFiles, new File(optimizedDirectory), new ArrayList<IOException>());
                        } catch (Exception ignoredK) {
                            final Method makeDexElements = DexPathList.getDeclaredMethod("makeDexElements", ArrayList.class, File.class);
                            makeDexElements.setAccessible(true);
                            newElements = (Object[]) makeDexElements.invoke(null, newFiles, new File(optimizedDirectory));
                        }
                    }
                }

                if (newElements == null) {
                    if (DEBUG) Log.d(TAG, "makeDexElements || newElements == null");
                    return false;
                }
                if (DEBUG) Log.d(TAG, "newElements = " + newElements);

                Class<?> Element = Class.forName("dalvik.system.DexPathList$Element");
                Object[] newArray = expandObjectArray(Element, dexElementsObj, newElements, isAppend);
                if (DEBUG) Log.d(TAG, "newArray = " + newArray + ", newSize = " + newArray.length);
                dexElements.set(pathListObj, newArray);
            }

            if (TextUtils.isEmpty(libraryPath)) {
                return true;
            }

            {
                // insert native library

                Field nativeLibraryDirectories = DexPathList.getDeclaredField("nativeLibraryDirectories");
                nativeLibraryDirectories.setAccessible(true);

                Object tmp = nativeLibraryDirectories.get(pathListObj);
                if (tmp instanceof Object[]) {
                    Object[] newNativeLibraryDirectories;
                    Object[] nativeLibraryDirectoriesObj = (Object[]) tmp;
                    if (DEBUG) Log.d(TAG, "nativeLibraryDirectories = " + nativeLibraryDirectoriesObj);
                    newNativeLibraryDirectories = expandObjectArray(File.class, nativeLibraryDirectoriesObj, new File[]{new File(libraryPath)}, false);
                    if (DEBUG) Log.d(TAG, "newNativeLibraryDirectories = " + newNativeLibraryDirectories);
                    nativeLibraryDirectories.set(pathListObj, newNativeLibraryDirectories);
                } else if (tmp instanceof List) {
                    List<Object> nativeLibraryDirectoriesObj = (List<Object>) tmp;
                    if (DEBUG) Log.d(TAG, "adding new nativeLibraryDirectory = " + libraryPath);
                    nativeLibraryDirectoriesObj.add(0, new File(libraryPath));
                    if (DEBUG) Log.d(TAG, "nativeLibraryDirectories = " + nativeLibraryDirectoriesObj);
                }

                Field nativeLibraryPathElements = null;
                try {
                    nativeLibraryPathElements = DexPathList.getDeclaredField("nativeLibraryPathElements");
                    nativeLibraryPathElements.setAccessible(true);
                } catch (Exception ignored) {
                }
                if (nativeLibraryPathElements == null) {
                    return true;
                }

                Class LibElementClass = null;
                Object[] newLibElements = null;
                final ArrayList<File> libPathList = new ArrayList<File>();
                libPathList.add(new File(libraryPath));

                try {
                    /* andoird O */
                    LibElementClass = Class.forName("dalvik.system.DexPathList$NativeLibraryElement");
                    final Method makePathElements = DexPathList.getDeclaredMethod("makePathElements", List.class);
                    makePathElements.setAccessible(true);
                    newLibElements = (Object[]) makePathElements.invoke(null, libPathList);
                } catch (Exception ignored) {
                    /* andoird m */
                    LibElementClass = Class.forName("dalvik.system.DexPathList$Element");
                    final Method makePathElements = DexPathList.getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                    makePathElements.setAccessible(true);
                    newLibElements = (Object[]) makePathElements.invoke(null, libPathList, null, new ArrayList<IOException>());
                }

                if (newLibElements == null || LibElementClass == null) {
                    return false;
                }

                try {
                    newLibElements = expandObjectArray(LibElementClass, (Object[]) nativeLibraryPathElements.get(pathListObj), newLibElements, false);
                    nativeLibraryPathElements.set(pathListObj, newLibElements);
                } catch (Exception ignored) {
                    if (DEBUG) Log.e(TAG, "update nativeLibraryPathElements failed!", ignored);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static final int S_IRWXU = 00700;
    public static final int S_IRUSR = 00400;
    public static final int S_IWUSR = 00200;
    public static final int S_IXUSR = 00100;

    public static final int S_IRWXG = 00070;
    public static final int S_IRGRP = 00040;
    public static final int S_IWGRP = 00020;
    public static final int S_IXGRP = 00010;

    public static final int S_IRWXO = 00007;
    public static final int S_IROTH = 00004;
    public static final int S_IWOTH = 00002;
    public static final int S_IXOTH = 00001;

    public static int setPermissions(String file, int mode, int uid, int gid) {
        try {
            Class<?> FileUtils = Class.forName("android.os.FileUtils");
            Method setPermissions = FileUtils.getDeclaredMethod("setPermissions", new Class[] { String.class, int.class, int.class, int.class });
            setPermissions.setAccessible(true);
            int result = (Integer)setPermissions.invoke(null, new Object[] { file, mode, uid, gid });
            return result;
        } catch (Exception e) {
        }
        return -1;
    }

    public static boolean copyToFile(InputStream inputStream, File destFile) {
        try {
            if (destFile.exists()) {
                destFile.delete();
            }
            OutputStream out = new FileOutputStream(destFile);
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) >= 0) {
                    out.write(buffer, 0, bytesRead);
                }
            } finally {
                out.close();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("[\\w%+,./=_-]+");
    public static boolean isFilenameSafe(File file) {
        // Note, we check whether it matches what's known to be safe,
        // rather than what's known to be unsafe.  Non-ASCII, control
        // characters, etc. are all unsafe by default.
        return SAFE_FILENAME_PATTERN.matcher(file.getPath()).matches();
    }

    // Remove the native binaries of a given package. This simply
    // gets rid of the files in the 'lib' sub-directory.
    private static void removeNativeBinariesLI(String libDir) {
        File binaryDir = new File(libDir);

        if (DEBUG) {
            Log.w(TAG,"Deleting native binaries from: " + binaryDir.getPath());
        }

        // Just remove any file in the directory. Since the directory
        // is owned by the 'system' UID, the application is not supposed
        // to have written anything there.
        //
        if (binaryDir.exists()) {
            File[]  binaries = binaryDir.listFiles();
            if (binaries != null) {
                for (int nn=0; nn < binaries.length; nn++) {
                    if (DEBUG) {
                        Log.d(TAG,"    Deleting " + binaries[nn].getName());
                    }
                    if (!binaries[nn].delete()) {
                        Log.w(TAG,"Could not delete native binary: " +
                              binaries[nn].getPath());
                    }
                }
            }
            // Do not delete 'lib' directory itself, or this will prevent
            // installation of future updates.
        }
    }

    // The following constants are returned by cachePackageSharedLibsForAbiLI
    // to indicate if native shared libraries were found in the package.
    // Values are:
    //    PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES => native libraries found and installed
    //    PACKAGE_INSTALL_NATIVE_NO_LIBRARIES     => no native libraries in package
    //    PACKAGE_INSTALL_NATIVE_ABI_MISMATCH     => native libraries for another ABI found
    //                                        in package (and not installed)
    //
    private static final int PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES = 0;
    private static final int PACKAGE_INSTALL_NATIVE_NO_LIBRARIES = 1;
    private static final int PACKAGE_INSTALL_NATIVE_ABI_MISMATCH = 2;
    private static void cacheNativeBinaryLI(ZipFile zipFile, ZipEntry entry,
                                            File binaryDir,
                                            File binaryFile) throws IOException {
        InputStream inputStream = zipFile.getInputStream(entry);
        try {
            File tempFile = File.createTempFile("tmp", "tmp", binaryDir);
            String tempFilePath = tempFile.getPath();
            // XXX package manager can't change owner, so the executable files for
            // now need to be left as world readable and owned by the system.
            if (! copyToFile(inputStream, tempFile) ||
                    ! tempFile.setLastModified(entry.getTime()) ||
                    setPermissions(tempFilePath,
                                   S_IRUSR|S_IWUSR|S_IRGRP
                                   |S_IXUSR|S_IXGRP|S_IXOTH
                                   |S_IROTH, -1, -1) != 0 ||
                    ! tempFile.renameTo(binaryFile)) {
                // Failed to properly write file.
                tempFile.delete();
                throw new IOException("Couldn't create cached binary "
                                      + binaryFile + " in " + binaryDir);
            }
        } finally {
            inputStream.close();
        }
    }

    private static int cachePackageSharedLibsForAbiLI(String libDir,
                                                      File scanFile, String cpuAbi) throws IOException, ZipException {
        File sharedLibraryDir = new File(libDir);
        final String apkLib = "lib/";
        final int apkLibLen = apkLib.length();
        final int cpuAbiLen = cpuAbi.length();
        final String libPrefix = "lib";
        final int libPrefixLen = libPrefix.length();
        final String libSuffix = ".so";
        final int libSuffixLen = libSuffix.length();
        boolean hasNativeLibraries = false;
        boolean installedNativeLibraries = false;

        // the minimum length of a valid native shared library of the form
        // lib/<something>/lib<name>.so.
        final int minEntryLen  = apkLibLen + 2 + libPrefixLen + 1 + libSuffixLen;

        ZipFile zipFile = new ZipFile(scanFile);
        Enumeration<ZipEntry> entries =
            (Enumeration<ZipEntry>) zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            // skip directories
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();
            if(entryName != null && entryName.contains("../")){
                continue;
            }

            // check that the entry looks like lib/<something>/lib<name>.so
            // here, but don't check the ABI just yet.
            //
            // - must be sufficiently long
            // - must end with libSuffix, i.e. ".so"
            // - must start with apkLib, i.e. "lib/"
            if (entryName.length() < minEntryLen ||
                    !entryName.endsWith(libSuffix) ||
                    !entryName.startsWith(apkLib) ) {
                continue;
            }

            // file name must start with libPrefix, i.e. "lib"
            int lastSlash = entryName.lastIndexOf('/');

            if (lastSlash < 0 ||
                    !entryName.regionMatches(lastSlash+1, libPrefix, 0, libPrefixLen) ) {
                continue;
            }

            hasNativeLibraries = true;

            // check the cpuAbi now, between lib/ and /lib<name>.so
            //
            if (lastSlash != apkLibLen + cpuAbiLen ||
                    !entryName.regionMatches(apkLibLen, cpuAbi, 0, cpuAbiLen) )
                continue;

            // extract the library file name, ensure it doesn't contain
            // weird characters. we're guaranteed here that it doesn't contain
            // a directory separator though.
            String libFileName = entryName.substring(lastSlash+1);
            if (!isFilenameSafe(new File(libFileName))) {
                continue;
            }

            installedNativeLibraries = true;

            // Always extract the shared library
            String sharedLibraryFilePath = sharedLibraryDir.getPath() +
                                           File.separator + libFileName;
            File sharedLibraryFile = new File(sharedLibraryFilePath);

            if (DEBUG) {
                Log.d(TAG, "Caching shared lib " + entry.getName());
            }
            sharedLibraryDir.mkdirs();
            cacheNativeBinaryLI(zipFile, entry, sharedLibraryDir, sharedLibraryFile);
        }

        if (!hasNativeLibraries)
            return PACKAGE_INSTALL_NATIVE_NO_LIBRARIES;

        if (!installedNativeLibraries)
            return PACKAGE_INSTALL_NATIVE_ABI_MISMATCH;

        return PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES;
    }

    private static String[] selectBestCpuAbis() {
        String[] abiList = null;
        if (Build.VERSION.SDK_INT < 21 /*Build.VERSION_CODES.LOLLIPOP*/) {
            // some architectures are capable of supporting several CPU ABIs
            // for example, 'armeabi-v7a' also supports 'armeabi' native code
            // this is indicated by the definition of the ro.product.cpu.abi2
            // system property.
            String cpuAbi2 = null;
            try {
                //final String cpuAbi2 = SystemProperties.get("ro.product.cpu.abi2",null);
                Class<?> SystemProperties = Class.forName("android.os.SystemProperties");
                Method get = SystemProperties.getDeclaredMethod("get", new Class[] { String.class, String.class});
                get.setAccessible(true);
                cpuAbi2 = (String)get.invoke(null, new Object[] { "ro.product.cpu.abi2", null });
            } catch (Exception e) {}
            abiList = new String[] { Build.CPU_ABI, cpuAbi2 };
        } else {
            try {
                Class<?> VMRuntime = Class.forName("dalvik.system.VMRuntime");
                Method getRuntime = VMRuntime.getDeclaredMethod("getRuntime");
                Object runtime = getRuntime.invoke(null);
                Method is64Bit = VMRuntime.getDeclaredMethod("is64Bit");
                boolean result = (Boolean)is64Bit.invoke(runtime);
                if (result) {
                    abiList = (String[])getStaticObjectFieldRaw(Build.class.getName(), "SUPPORTED_64_BIT_ABIS", false);
                } else {
                    abiList = (String[])getStaticObjectFieldRaw(Build.class.getName(), "SUPPORTED_32_BIT_ABIS", false);;
                }
            } catch (Exception e) {
                abiList = new String[] { Build.CPU_ABI };
            }
        }
        if (DEBUG && abiList != null) {
            Log.d(TAG, "selectBestCpuAbis: final abiList:");
            for (String cpuAbi : abiList) {
                Log.d(TAG, " " + cpuAbi);
            }
        }
        return abiList;
    }

    // extract shared libraries stored in the APK as lib/<cpuAbi>/lib<name>.so
    // and copy them to /data/data/<appname>/lib.
    //
    // This function will first try the main CPU ABI defined by Build.CPU_ABI
    // (which corresponds to ro.product.cpu.abi), and also try an alternate
    // one if ro.product.cpu.abi2 is defined.
    //
    private static boolean cachePackageSharedLibsLI(String libDir, File scanFile, boolean removeOldLib) {
        // Remove all native binaries from a directory. This is used when upgrading
        // a package: in case the new .apk doesn't contain a native binary that was
        // in the old one (and thus installed), we need to remove it from
        // /data/data/<appname>/lib
        //
        // The simplest way to do that is to remove all files in this directory,
        // since it is owned by "system", applications are not supposed to write
        // anything there.
        if (removeOldLib) {
            removeNativeBinariesLI(libDir);
        }

        try {
            String cpuAbis[] = selectBestCpuAbis();
            if (cpuAbis != null) {
                for (String cpuAbi : cpuAbis) {
                    if (DEBUG) Log.d(TAG, "cachePackageSharedLibsLI: try cpuAbi " + cpuAbi);
                    if (cpuAbi == null) continue;
                    int result = cachePackageSharedLibsForAbiLI(libDir, scanFile, cpuAbi);
                    if (result == PACKAGE_INSTALL_NATIVE_ABI_MISMATCH) {
                        if (DEBUG) Log.w(TAG,"Native ABI mismatch from package file, try next cpuAbi");
                        continue;
                    }
                    if (result == PACKAGE_INSTALL_NATIVE_FOUND_LIBRARIES) {
                        if (DEBUG) Log.i(TAG, "Found matched native library, cpuAbi = " + cpuAbi);
                        break;
                    }
                }
            }
        } catch (ZipException e) {
            Log.w(TAG, "Failed to extract data from package file", e);
            return false;
        } catch (IOException e) {
            Log.w(TAG, "Failed to cache package shared libs", e);
            return false;
        } catch (Exception e1) {
            e1.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean copyNativeBinariesIfNeededLI(String jarPath, String libraryPath, boolean removeOldLib) {
        File pathFile = new File(jarPath);
        if (!pathFile.exists()) return false;
        if (libraryPath == null) return true;
        if (pathFile.getParent().equals(libraryPath)) {
            throw new RuntimeException("ERROR: libraryPath shouldn't be the same as jarPath,"
                + "please using other private directory for libraryPath!");
        }
        try {
            Class<?> NativeLibraryHelper = Class.forName("com.android.internal.content.NativeLibraryHelper");
            Method copyNativeBinariesIfNeededLI = NativeLibraryHelper.getDeclaredMethod("copyNativeBinariesIfNeededLI", File.class, File.class);
            copyNativeBinariesIfNeededLI.invoke(null, pathFile, new File(libraryPath));
        } catch (Exception e1) {
            //e1.printStackTrace();
            try {
                Class<?> NativeLibraryHelper = Class.forName("com.android.internal.content.NativeLibraryHelper");
                Method copyNativeBinariesLI = NativeLibraryHelper.getDeclaredMethod("copyNativeBinariesLI", File.class, File.class);
                copyNativeBinariesLI.invoke(null, pathFile, new File(libraryPath));
            } catch (Exception e2) {
                return cachePackageSharedLibsLI(libraryPath, new File(jarPath), removeOldLib);
            }
            return true;
        }
        return true;
    }

    private static Certificate[] loadCertificates(JarFile jarFile, JarEntry je,
                                                  byte[] readBuffer) {
        if(je != null){
            String name = je.getName();
            if(name != null && name.contains("../")){
                return null;
            }
        }
        try {
            // We must read the stream for the JarEntry to retrieve
            // its certificates.
            InputStream is = new BufferedInputStream(jarFile.getInputStream(je));
            while (is.read(readBuffer, 0, readBuffer.length) != -1) {
                // not using
            }
            is.close();
            return je != null ? je.getCertificates() : null;
        } catch (IOException e) {
            Log.w(TAG, "Exception reading " + je.getName() + " in " + jarFile.getName(), e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Exception reading " + je.getName() + " in " + jarFile.getName(), e);
        }
        return null;
    }

    public static Signature[] getEntrySignatures(String archiveSourcePath, String entryName) {
        byte[] readBuffer = new byte[8192];
        Signature[] signatures = null;

        try {
            JarFile jarFile = new JarFile(archiveSourcePath);
            Certificate[] certs = null;
            JarEntry jarEntry = jarFile.getJarEntry(entryName);
            if(jarEntry != null){
                String name = jarEntry.getName();
                if(name != null && name.contains("../")){
                    return null;
                }
            }
            certs = loadCertificates(jarFile, jarEntry, readBuffer);
            if (certs == null) {
                Log.e(TAG, "Package " + archiveSourcePath + " has no certificates at entry " + jarEntry.getName() + "; ignoring!");
                jarFile.close();
                return null;
            }

            jarFile.close();

            if (certs != null && certs.length > 0) {
                final int N = certs.length;
                signatures = new Signature[certs.length];
                for (int i=0; i<N; i++) {
                    signatures[i] = new Signature(certs[i].getEncoded());
                }
                return signatures;
            } else {
                Log.e(TAG, "Package " + archiveSourcePath + " has no certificates; ignoring!");
                return null;
            }
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }

        return null;
    }

    public static void doDexOpt(String jarPath, String optimizedDirectory, String libraryPath) {
        try {
            copyNativeBinariesIfNeededLI(jarPath, libraryPath, true);
            String outputName = generateOutputName(jarPath, optimizedDirectory);
            if (DEBUG) Log.d(TAG, "doDexOpt: dex outputName: " + outputName);
            File oldDex = new File(outputName);
            if (oldDex != null && oldDex.exists()) {
                oldDex.delete();
            }
            DexFile dexFile = DexFile.loadDex(jarPath, outputName, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Field findFieldRecursiveImpl(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            while (true) {
                clazz = clazz.getSuperclass();
                if (clazz == null || clazz.equals(Object.class))
                    break;

                try {
                    return clazz.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {}
            }
            throw e;
        }
    }

    public static Object getObjectFieldRaw(Object thiz, String fieldName, boolean quiet) {
        try {
            Field field = findFieldRecursiveImpl(thiz.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(thiz);
        } catch (IllegalAccessException e) {
            // should not happen
            throw new IllegalAccessError(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw e;
        }  catch (Exception e) {
            if (!quiet) e.printStackTrace();
        }
        return null;
    }

    public static void setObjectFieldRaw(Object thiz, String fieldName, Object obj, boolean quiet) {
        try {
            Field field = findFieldRecursiveImpl(thiz.getClass(), fieldName);
            field.setAccessible(true);
            field.set(thiz, obj);
        } catch (IllegalAccessException e) {
            // should not happen
            throw new IllegalAccessError(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw e;
        }  catch (Exception e) {
            if (!quiet) e.printStackTrace();
        }
    }

    public static Object getStaticObjectFieldRaw(String clzName, String fieldName, boolean quiet) {
        try {
            Class<?> cls = Class.forName(clzName);
            Field field = findFieldRecursiveImpl(cls, fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (IllegalAccessException e) {
            // should not happen
            throw new IllegalAccessError(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw e;
        }  catch (Exception e) {
            if (!quiet) e.printStackTrace();
        }
        return null;
    }
}
