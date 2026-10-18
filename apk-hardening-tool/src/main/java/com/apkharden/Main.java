package com.apkharden;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java -jar apk-hardener.jar <input.apk> <output.apk>");
            System.exit(1);
        }
        String inputApk = args[0];
        String outputApk = args[1];
        ApkHardener hardener = new ApkHardener();
        try {
            String result = hardener.hardenApk(inputApk, outputApk);
            System.out.println(result);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
