# Publishing to Maven Central

This document describes how to publish durable-streams-java artifacts to Maven Central using the new Maven Central Portal.

## Background

As of June 30, 2025, **OSSRH (Sonatype OSS Repository Hosting) has been sunset**. All publishing must now go through the [Maven Central Portal](https://central.sonatype.com/).

## Setup

### 1. Register and Generate User Token

1. Log in to [Maven Central Portal](https://central.sonatype.com/)
2. Navigate to [User Tokens page](https://central.sonatype.com/usertoken)
3. Click "Generate User Token"
4. **Save the username and password** - these cannot be retrieved later!

### 2. Generate and Export GPG Key

If you don't have a GPG key yet:

```bash
# Generate a new GPG key (use your real name and email)
gpg --gen-key

# List your keys to find the key ID
gpg --list-secret-keys --keyid-format=long

# Export the private key in ASCII-armored format
gpg --armor --export-secret-keys YOUR_KEY_ID > private-key.asc
```

### 3. Configure GitHub Secrets

Add the following secrets to your GitHub repository (Settings → Secrets and variables → Actions → New repository secret):

| Secret Name | Description | How to Get |
|-------------|-------------|------------|
| `SIGNING_KEY` | Your GPG private key (ASCII armored) | Copy the **entire contents** of `private-key.asc` including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----` lines |
| `SIGNING_PASSWORD` | Your GPG key password | The passphrase you entered when creating the GPG key |
| `CENTRAL_PORTAL_USERNAME` | User token username from Portal | From https://central.sonatype.com/usertoken |
| `CENTRAL_PORTAL_PASSWORD` | User token password from Portal | From https://central.sonatype.com/usertoken |

**Important**: The `SIGNING_KEY` must be the full ASCII-armored text, not just the key ID. It should look like:
```
-----BEGIN PGP PRIVATE KEY BLOCK-----

lQdGBGb... (many lines of base64 text)
...
-----END PGP PRIVATE KEY BLOCK-----
```

## Publishing Configuration

### Gradle Plugin

We use the [`com.gradleup.nmcp`](https://github.com/GradleUp/nmcp) Gradle plugin for publishing to Maven Central Portal.

Configuration in `settings.gradle.kts`:
```kotlin
plugins {
    id("com.gradleup.nmcp.settings") version "1.4.3"
}

nmcpSettings {
    centralPortal {
        username.set(System.getenv("CENTRAL_PORTAL_USERNAME"))
        password.set(System.getenv("CENTRAL_PORTAL_PASSWORD"))
        publishingType.set("USER_MANAGED")
    }
}
```

The settings plugin automatically applies `com.gradleup.nmcp` to all subprojects that have `maven-publish` configured.

### Publishable Modules (11 total)

The following modules are published to Maven Central:

- `durable-streams-core`
- `durable-streams-client`
- `durable-streams-json-spi`
- `durable-streams-json-jackson`
- `durable-streams-server-spi`
- `durable-streams-server-core`
- `durable-streams-servlet`
- `durable-streams-spring-webflux`
- `durable-streams-micronaut`
- `durable-streams-quarkus`
- `durable-streams-ktor`

### Excluded Modules (6 total)

The following modules are **NOT** published:

- `durable-streams-conformance-runner` (testing infrastructure)
- `example-micronaut`
- `example-quarkus`
- `example-spring-webflux`
- `example-spring-webmvc`
- `example-ktor`

## Release Process

### Automated Release via GitHub Actions

1. Create and push a version tag:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

2. GitHub Actions will automatically:
   - Build all publishable modules
   - Generate javadoc and sources JARs
   - Sign all artifacts with GPG
   - Upload to Maven Central Portal
   - Wait for manual release (USER_MANAGED mode)

3. Artifacts will be available at:
   ```
   https://repo1.maven.org/maven2/io/github/clickin/
   ```

### Manual Local Publishing (for testing)

```bash
# Publish to local Maven repository
./gradlew publishToMavenLocal

# Verify artifacts
ls ~/.m2/repository/io/github/clickin/
```

## CI/CD Workflows

### publish-dryrun.yml

Runs on every push and PR to verify publishing setup:
- Publishes to local Maven repository
- Verifies all 11 publishable modules are present
- Verifies all 6 excluded modules are absent
- Lists all published artifacts

### release.yml

Triggers on version tags (`v*`):
- Derives version from tag name
- Publishes signed artifacts to Maven Central Portal
- Uses `AUTOMATIC` publishing mode for streamlined releases

## Artifact Structure

Each published module includes:

- Main JAR (`{module}-{version}.jar`)
- Sources JAR (`{module}-{version}-sources.jar`)
- Javadoc JAR (`{module}-{version}-javadoc.jar`)
- POM file (`{module}-{version}.pom`)
- GPG signatures (`.asc` files for all artifacts)

## POM Metadata

All published POMs include:

- **Group ID**: `io.github.clickin`
- **Name**: Module name
- **Description**: Java implementation of the Durable Streams protocol
- **URL**: https://github.com/durable-streams/durable-streams-java
- **License**: MIT License
- **SCM**: GitHub repository information
- **Developers**: Contributor information

## Troubleshooting

### "Could not read PGP secret key" Error

This means the `SIGNING_KEY` secret is not properly formatted. Check:

1. **Verify the key is ASCII-armored**:
   ```bash
   # Export the key properly
   gpg --armor --export-secret-keys YOUR_KEY_ID > private-key.asc
   
   # Verify it starts with the header
   head -1 private-key.asc
   # Should output: -----BEGIN PGP PRIVATE KEY BLOCK-----
   ```

2. **Copy the ENTIRE file contents** to GitHub Secrets:
   ```bash
   # On Linux/Mac
   cat private-key.asc | pbcopy  # or xclip -selection clipboard
   
   # On Windows
   Get-Content private-key.asc | Set-Clipboard
   ```

3. **Ensure no extra whitespace** - paste directly into GitHub Secrets without modification

4. **Test locally** before pushing:
   ```bash
   export ORG_GRADLE_PROJECT_signingKey="$(cat private-key.asc)"
   export ORG_GRADLE_PROJECT_signingPassword="your-passphrase"
   ./gradlew publishToMavenLocal
   ```

### Build Fails with Missing Credentials

Ensure environment variables are set:
```bash
export CENTRAL_PORTAL_USERNAME="your-token-username"
export CENTRAL_PORTAL_PASSWORD="your-token-password"
export ORG_GRADLE_PROJECT_signingKey="$(cat private-key.asc)"
export ORG_GRADLE_PROJECT_signingPassword="your-gpg-password"
```

### Publication Validation Fails

Check the [Central Portal Deployments page](https://central.sonatype.com/publishing/deployments) for detailed error messages.

Common issues:
- Missing or invalid GPG signatures
- POM missing required metadata
- Javadoc JAR missing or malformed

### Artifacts Not Appearing on Maven Central

After successful publication:
1. Initial sync takes **~15-30 minutes**
2. Check [Central Search](https://central.sonatype.com/search) for your artifacts
3. Verify at: `https://repo1.maven.org/maven2/io/github/clickin/`

## Migration from OSSRH

If you previously published via OSSRH:

1. **OSSRH has been sunset** (June 30, 2025)
2. All namespaces have been migrated to Central Portal
3. Use your OSSRH username/password to log in to Central Portal
4. Generate a new user token (old OSSRH tokens don't work)
5. Update your publishing configuration to use the Portal API

## References

- [Maven Central Portal](https://central.sonatype.com/)
- [Publishing Guide](https://central.sonatype.org/publish/publish-portal-guide/)
- [Portal API Documentation](https://central.sonatype.org/publish/publish-portal-api/)
- [OSSRH Sunset Announcement](https://central.sonatype.org/pages/ossrh-eol/)
- [GradleUp/nmcp Plugin](https://github.com/GradleUp/nmcp)
