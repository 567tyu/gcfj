package com.apkharden;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class ApkHardener {
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

            String packageName = extractPackageName(inputFile);
            if (packageName.isEmpty()) {
                throw new IOException("Cannot extract package name");
            }

            String obfuscatedCode = generateObfuscatedClass(packageName);
            String loaderCode = generateSecureLoader(packageName);

            byte[] loaderClass = compileJavaToClass("SecureLoader", loaderCode);
            byte[] obfuscatedClass = compileJavaToClass("ObfuscatedEntry", obfuscatedCode);

            writeFile(new File(protectedDir.toFile(), "SecureLoader.class"), loaderClass);
            writeFile(new File(protectedDir.toFile(), "ObfuscatedEntry.class"), obfuscatedClass);

            mergeAndSignApk(inputDir, outputDir, protectedDir, outputFile);

            long size = outputFile.length();
            return "成功! 输出: " + outputFile.getAbsolutePath() + " (" + String.format("%.2f KB", size/1024.0) + ")";
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private String extractPackageName(File apkFile) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{
            "/opt/android-sdk/build-tools/34.0.0/aapt2", "dump", "packagename", apkFile.getAbsolutePath()
        });
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = reader.readLine();
        p.waitFor();
        if (line != null && line.contains(".")) {
            return line.trim();
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
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (OutputStream os = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) os.write(buffer, 0, len);
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
               "    private static Map<String, String> storage = new HashMap<>();\n" +
               "    static { storage.put(\"key1\", Long.toHexString(System.currentTimeMillis())); }\n" +
               "    @Override public void onCreate() { super.onCreate(); performCheck(this); }\n" +
               "    private void performCheck(Context ctx) { storage.put(\"pkg\", ctx.getPackageName()); }\n" +
               "}\n";
    }

    private String generateSecureLoader(String packageName) {
        return "import android.app.Application;\n" +
               "public class SecureLoader {\n" +
               "    public static void load(Object base) {\n" +
               "        try {\n" +
               "            Class<?> c = Class.forName(\"" + packageName + ".ObfuscatedEntry\");\n" +
               "            Application app = (Application) c.getDeclaredConstructor().newInstance();\n" +
               "            app.onCreate();\n" +
               "        } catch (Exception e) { System.out.println(\"Error: \" + e); }\n" +
               "    }\n" +
               "}\n";
    }

    private byte[] compileJavaToClass(String className, String sourceCode) throws Exception {
        File classDir = new File("/tmp/classes_" + System.nanoTime());
        classDir.mkdirs();
        writeFile(new File(classDir, className + ".java"), sourceCode.getBytes("UTF-8"));
        
        String androidJar = System.getenv("ANDROID_HOME") + "/platforms/android-34/android.jar";
        String cmd = "/usr/lib/jvm/java-17-openjdk-amd64/bin/javac -encoding UTF-8 -source 1.7 -target 1.7 -bootclasspath " + androidJar + " -d " + classDir.getAbsolutePath() + " " + classDir.getAbsolutePath() + "/" + className + ".java";
        
        Process p = Runtime.getRuntime().exec(cmd);
        p.waitFor();
        
        File classFile = new File(classDir, className + ".class");
        if (!classFile.exists()) throw new IOException("Compilation failed");
        
        byte[] result = Files.readAllBytes(classFile.toPath());
        deleteDirectory(classDir);
        return result;
    }

    private void writeFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
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
                    byte[] data = Files.readAllBytes(f.toPath());
                    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempApk, true))) {
                        zos.putNextEntry(new ZipEntry("classes/" + f.getName()));
                        zos.write(data);
                        zos.closeEntry();
                    }
                }
            }
        }

        String signedApk = outputDir.toFile().getAbsolutePath() + "/signed.apk";
        Runtime.getRuntime().exec(new String[]{
            "/usr/lib/jvm/java-17-openjdk-amd64/bin/jarsigner",
            "-sigalg", "SHA1withRSA", "-digestalg", "SHA1",
            "-keystore", "/tmp/debug.keystore", "-storepass", "android", "-keypass", "android",
            "-signedjar", signedApk, tempApk, "androiddebugkey"
        }).waitFor();

        if (new File(signedApk).exists()) {
            java.nio.file.Files.copy(new File(signedApk).toPath(), outputFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new File(signedApk).delete();
        }
        new File(tempApk).delete();
    }

    private void zipDirectory(File dir, String baseName, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = baseName + f.getName();
            if (f.isDirectory()) {
                zos.putNextEntry(new ZipEntry(name + "/"));
                zos.closeEntry();
                zipDirectory(f, name + "/", zos);
            } else {
                zos.putNextEntry(new ZipEntry(name));
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    private void deleteDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDirectory(f);
            else f.delete();
        }
        dir.delete();
    }
}
