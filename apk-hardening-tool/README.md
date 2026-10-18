# APK Hardening Tool

A pure Java APK hardening tool that provides anti-tamper, anti-debug, and code protection for Android applications.

## Features

- **Anti-Tamper Protection**: Detects if the APK has been modified after signing
- **Anti-Debug Protection**: Detects debugging tools and environments
- **Code Obfuscation**: Generates obfuscated entry points to protect business logic
- **Integrity Verification**: Performs self-integrity checks at runtime
- **Pure Java Implementation**: No native dependencies, runs on any platform with JDK 7+

## Prerequisites

- Java Development Kit (JDK) 17 or higher
- Apache Maven 3.6+
- Android SDK (for signing and alignment)

## Building

```bash
cd apk-hardening-tool
mvn clean package
```

The built JAR will be in `target/apk-hardener-1.0.0.jar`.

## Usage

```bash
# Basic usage
java -jar target/apk-hardener-1.0.0.jar input.apk output.apk

# With verbose output
java -jar target/apk-hardener-1.0.0.jar input.apk output.apk -v
```

## How It Works

1. Unpacks the input APK
2. Extracts package information from AndroidManifest.xml
3. Generates obfuscated entry point classes with anti-tamper checks
4. Creates a secure loader that verifies environment integrity
5. Merges protected classes into the APK
6. Signs the APK with debug keystore
7. Aligns the final APK for optimal performance

## Security Features

### Runtime Protection
- TracerPid detection via /proc/self/status
- NoNewPrivs flag detection
- APK integrity checksum verification
- CRC32-based file integrity checking

### Code Protection
- Dynamic class loading through reflection
- Encrypted runtime headers
- Multi-layer integrity verification

## License

MIT License
