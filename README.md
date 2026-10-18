# APK Hardening Tool

Pure Java APK hardening tool with anti-tamper, anti-debug, and code protection.

## Build

```bash
cd apk-hardening-tool
mvn clean package
```

## Usage

```bash
java -jar target/apk-hardener-1.0.0.jar input.apk output.apk
```

## Output

Only APK files are generated and committed. No source code is included in releases.
