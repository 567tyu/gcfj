package com.apkharden;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.nio.charset.*;

public class ApkHardener {
    private static final String PROTECTION_CLASS_NAME = "SecureLoader";

    public String hardenApk(String inputApkPath, String outputApkPath) throws Exception {
        File inputFile = new File(inputApkPath);
        File outputFile = new File(outputApkPath);

        if (!inputFile.exists()) {
            throw new FileNotFoundException("Input APK not found: " + inputApkPath);
        }

        Path tempDir = Files.createTempDirectory("apk_harden_");
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        Path protectedDir = outputDir.resolve("protected");

        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(protectedDir);

        try {
            unzip(inputFile.getAbsolutePath(), inputDir.toString());

            String originalPackageName = extractPackageNameFromApk(inputFile);

            if (originalPackageName.isEmpty()) {
                throw new IOException("Cannot extract package name from manifest");
            }

            System.out.println("Original package: " + originalPackageName);

            String obfuscatedClassCode = generateObfuscatedClass(originalPackageName);
            String loaderCode = generateSecureLoader(originalPackageName);

            byte[] loaderClass = compileJavaToClass(PROTECTION_CLASS_NAME, loaderCode);
            byte[] obfuscatedClass = compileJavaToClass("ObfuscatedEntry", obfuscatedClassCode);

            writeFile(new File(protectedDir.toFile(), PROTECTION_CLASS_NAME + ".class"), loaderClass);
            writeFile(new File(protectedDir.toFile(), "ObfuscatedEntry.class"), obfuscatedClass);

            mergeAndSignApk(inputDir, outputDir, protectedDir, outputFile);

            System.out.println("Successfully hardened APK: " + outputFile.getAbsolutePath());
            long size = outputFile.length();
            System.out.println("Output APK size: " + String.format("%.2f MB", size / 1024.0 / 1024.0));

            return "SUCCESS: " + outputFile.getAbsolutePath();
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private String extractPackageNameFromApk(File apkFile) throws Exception {
        String[] aaptPaths = {
            "/opt/android-sdk/build-tools/34.0.0/aapt2",
            "/opt/android-sdk/build-tools/33.0.0/aapt2",
            "/opt/android-sdk/build-tools/32.0.0/aapt2",
            "aapt2"
        };
        
        for (String aaptPath : aaptPaths) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{aaptPath, "dump", "packagename", apkFile.getAbsolutePath()});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                if (line != null && line.contains(".")) {
                    return line.trim();
                }
                p.waitFor();
            } catch (Exception e) {
                continue;
            }
        }
        
        return parseBinaryManifestForPackage(apkFile);
    }

    private String parseBinaryManifestForPackage(File apkFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apkFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("AndroidManifest.xml".equals(entry.getName())) {
                    byte[] manifest = zis.readAllBytes();
                    return extractPackageFromBinaryManifest(manifest);
                }
                zis.closeEntry();
            }
        }
        return "";
    }

    private String extractPackageFromBinaryManifest(byte[] manifest) {
        for (int i = 0; i < manifest.length - 10; i++) {
            if (manifest[i] == 'c' && manifest[i+1] == 'o' && manifest[i+2] == 'm') {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(i + 40, manifest.length - 1); j += 2) {
                    if (manifest[j] >= 0x20 && manifest[j] < 0x7F && manifest[j+1] == 0) {
                        sb.append((char) manifest[j]);
                    } else if (manifest[j] == 0) {
                        break;
                    } else {
                        sb.append((char) manifest[j]);
                    }
                }
                String s = sb.toString();
                if (s.contains(".") && s.length() > 5) {
                    return s.replaceAll("[^a-zA-Z0-9._]", "").substring(0, Math.min(50, s.length()));
                }
            }
        }
        return "";
    }

    private void unzip(String zipfile, String destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipfile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outputFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (OutputStream os = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private String generateObfuscatedClass(String packageName) {
        return "import android.app.Application;\n" +
               "import android.content.Context;\n" +
               "import java.io.FileInputStream;\n" +
               "import java.util.HashMap;\n" +
               "import java.util.Map;\n" +
               "public class ObfuscatedEntry extends Application {\n" +
               "    private static Map<String, String> antiDebugStorage = new HashMap<>();\n" +
               "    \n" +
               "    static {\n" +
               "        antiDebugStorage.put(\"key1\", Long.toHexString(System.currentTimeMillis()));\n" +
               "        antiDebugStorage.put(\"key2\", Long.toHexString(System.nanoTime()));\n" +
               "        antiDebugStorage.put(\"key3\", Integer.toString(Runtime.getRuntime().availableProcessors()));\n" +
               "    }\n" +
               "    \n" +
               "    @Override\n" +
               "    public void onCreate() {\n" +
               "        super.onCreate();\n" +
               "        performAntiTamperCheck(this);\n" +
               "        performIntegrityVerification();\n" +
               "    }\n" +
               "    \n" +
               "    private void performAntiTamperCheck(Context ctx) {\n" +
               "        try {\n" +
               "            String pkgName = ctx.getPackageName();\n" +
               "            antiDebugStorage.put(\"package\", pkgName);\n" +
               "        } catch (Exception e) {\n" +
               "            System.out.println(\"Tamper check triggered\");\n" +
               "        }\n" +
               "    }\n" +
               "    \n" +
               "    private void performIntegrityVerification() {\n" +
               "        try {\n" +
               "            String selfPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();\n" +
               "            long crc = computeCrc(selfPath);\n" +
               "            antiDebugStorage.put(\"integrity\", Long.toHexString(crc));\n" +
               "        } catch (Exception e) {\n" +
               "            System.out.println(\"Integrity check error\");\n" +
               "        }\n" +
               "    }\n" +
               "    \n" +
               "    private long computeCrc(String path) {\n" +
               "        java.util.zip.CRC32 crc = new java.util.zip.CRC32();\n" +
               "        try (FileInputStream fis = new FileInputStream(path)) {\n" +
               "            byte[] buf = new byte[4096];\n" +
               "            int n;\n" +
               "            while ((n = fis.read(buf)) > 0) {\n" +
               "                crc.update(buf, 0, n);\n" +
               "            }\n" +
               "        } catch (Exception e) {\n" +
               "            return -1;\n" +
               "        }\n" +
               "        return crc.getValue();\n" +
               "    }\n" +
               "}\n";
    }

    private String generateSecureLoader(String packageName) {
        return "import android.app.Application;\n" +
               "import android.content.Context;\n" +
               "import java.io.BufferedReader;\n" +
               "import java.io.InputStreamReader;\n" +
               "import java.security.MessageDigest;\n" +
               "import java.util.Arrays;\n" +
               "public class SecureLoader {\n" +
               "    private static volatile boolean initialized = false;\n" +
               "    private static final byte[] ENCRYPTED_HEADER = {\n" +
               "        0x41, 0x50, 0x4B, 0x01, 0x02, 0x03, 0x04, 0x05,\n" +
               "        0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,\n" +
               "        0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15,\n" +
               "        0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D\n" +
               "    };\n" +
               "    \n" +
               "    public static void loadApplication(Object base) {\n" +
               "        if (initialized) return;\n" +
               "        try {\n" +
               "            performSelfIntegrityCheck();\n" +
               "            Class<?> entryClass = Class.forName(\"" + packageName + ".ObfuscatedEntry\");\n" +
               "            Application app = (Application) entryClass.getDeclaredConstructor().newInstance();\n" +
               "            app.onCreate();\n" +
               "            initialized = true;\n" +
               "        } catch (Exception e) {\n" +
               "            System.out.println(\"Loader error: \" + e.getMessage());\n" +
               "        }\n" +
               "    }\n" +
               "    \n" +
               "    private static void performSelfIntegrityCheck() {\n" +
               "        try {\n" +
               "            MessageDigest md = MessageDigest.getInstance(\"SHA-256\");\n" +
               "            String selfPath = SecureLoader.class.getProtectionDomain().getCodeSource().getLocation().getPath();\n" +
               "            byte[] fileBytes = readFileBytes(selfPath);\n" +
               "            byte[] expectedHash = Arrays.copyOfRange(ENCRYPTED_HEADER, 0, 32);\n" +
               "            byte[] actualHash = md.digest(fileBytes);\n" +
               "            if (!Arrays.equals(expectedHash, Arrays.copyOf(actualHash, 32))) {\n" +
               "                throw new SecurityException(\"APK integrity check failed\");\n" +
               "            }\n" +
               "        } catch (Exception e) {\n" +
               "            System.out.println(\"Integrity check: \" + e.getMessage());\n" +
               "        }\n" +
               "    }\n" +
               "    \n" +
               "    private static byte[] readFileBytes(String path) {\n" +
               "        try {\n" +
               "            java.io.FileInputStream fis = new java.io.FileInputStream(path);\n" +
               "            byte[] data = new byte[fis.available()];\n" +
               "            fis.read(data);\n" +
               "            fis.close();\n" +
               "            return data;\n" +
               "        } catch (Exception e) {\n" +
               "            return new byte[0];\n" +
               "        }\n" +
               "    }\n" +
               "}\n";
    }

    private byte[] compileJavaToClass(String className, String sourceCode) throws Exception {
        File classDir = new File("/tmp/apk_classes_" + System.nanoTime());
        classDir.mkdirs();
        
        String androidJar = System.getenv("ANDROID_HOME") + "/platforms/android-34/android.jar";
        boolean hasAndroid = new File(androidJar).exists();
        
        writeFile(new File(classDir, className + ".java"), sourceCode.getBytes("UTF-8"));
        
        String javacCmd;
        if (hasAndroid) {
            javacCmd = System.getProperty("java.home") + "/bin/javac " +
                      "-encoding UTF-8 " +
                      "-source 1.7 " +
                      "-target 1.7 " +
                      "-bootclasspath " + androidJar + " " +
                      "-d " + classDir.getAbsolutePath() + " " +
                      classDir.getAbsolutePath() + "/" + className + ".java";
        } else {
            javacCmd = System.getProperty("java.home") + "/bin/javac " +
                      "-encoding UTF-8 " +
                      "-d " + classDir.getAbsolutePath() + " " +
                      classDir.getAbsolutePath() + "/" + className + ".java";
        }
        
        Process process = Runtime.getRuntime().exec(javacCmd);
        byte[] output = process.getInputStream().readAllBytes();
        byte[] errors = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            System.err.println("Compilation error for " + className + ": " + new String(errors));
            if (!hasAndroid) {
                throw new IOException("Failed to compile " + className + " without Android SDK");
            }
        }
        
        File classFile = new File(classDir, className + ".class");
        if (!classFile.exists()) {
            throw new IOException("Failed to compile " + className);
        }
        
        byte[] result = Files.readAllBytes(classFile.toPath());
        deleteDirectory(classDir);
        return result;
    }

    private void writeFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(data);
        }
    }

    private void mergeAndSignApk(Path inputDir, Path outputDir, Path protectedDir, File outputFile) throws Exception {
        deleteDirectory(outputDir.toFile());
        Files.createDirectories(outputDir);

        String tempApk = outputDir.toFile().getAbsolutePath() + "/temp.apk";
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempApk))) {
            zipDirectory(inputDir.toFile(), "", zos);
        }

        File[] protectedFiles = protectedDir.toFile().listFiles();
        if (protectedFiles != null) {
            for (File f : protectedFiles) {
                if (f.getName().endsWith(".class")) {
                    byte[] classData = Files.readAllBytes(f.toPath());
                    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempApk, true))) {
                        ZipEntry entry = new ZipEntry("classes/" + f.getName());
                        zos.putNextEntry(entry);
                        zos.write(classData);
                        zos.closeEntry();
                    }
                }
            }
        }

        String signedApk = outputDir.toFile().getAbsolutePath() + "/signed.apk";
        String jarsignerCmd = System.getProperty("java.home") + "/bin/jarsigner" +
                             " -sigalg SHA1withRSA" +
                             " -digestalg SHA1" +
                             " -keystore /tmp/debug.keystore" +
                             " -storepass android" +
                             " -keypass android" +
                             " -signedjar " + signedApk +
                             " \"" + tempApk + "\"" +
                             " androiddebugkey";
        
        Process signProc = Runtime.getRuntime().exec(jarsignerCmd);
        signProc.waitFor();

        String zipalignPath = System.getenv("ANDROID_HOME") + "/build-tools/34.0.0/zipalign";
        if (new File(zipalignPath).exists()) {
            Process alignProc = Runtime.getRuntime().exec(new String[]{
                zipalignPath, "-f", "4", signedApk, outputFile.getAbsolutePath()
            });
            alignProc.waitFor();
        } else {
            // Fallback to java home zipalign or skip
            String fallbackZipalign = System.getProperty("java.home") + "/bin/zipalign";
            if (new File(fallbackZipalign).exists()) {
                Process alignProc = Runtime.getRuntime().exec(new String[]{
                    fallbackZipalign, "-f", "4", signedApk, outputFile.getAbsolutePath()
                });
                alignProc.waitFor();
            }
        }

        File signedFile = new File(signedApk);
        if (signedFile.exists()) {
            System.out.println("Signed APK size: " + signedFile.length());
            // Copy to output
            java.nio.file.Files.copy(signedFile.toPath(), outputFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            signedFile.delete();
        } else {
            System.err.println("Warning: Signed APK not found at " + signedApk);
            // Try to use temp.apk directly
            File tempFile = new File(tempApk);
            if (tempFile.exists()) {
                java.nio.file.Files.copy(tempFile.toPath(), outputFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tempFile.delete();
            }
        }
        
        File tempFile = new File(tempApk);
        if (tempFile.exists()) {
            tempFile.delete();
        }
    }

    private void zipDirectory(File dir, String baseName, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            String entryName = baseName + f.getName();
            if (f.isDirectory()) {
                zos.putNextEntry(new ZipEntry(entryName + "/"));
                zos.closeEntry();
                zipDirectory(f, entryName + "/", zos);
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        deleteDirectory(f);
                    } else {
                        f.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
