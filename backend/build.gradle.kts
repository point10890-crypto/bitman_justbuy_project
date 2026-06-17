plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.bitman"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // [v2.8.1 slim] springdoc-openapi 제거 — 프로덕션에서 Swagger UI 노출 불필요.
    // 이전엔 springdoc-openapi-starter-webmvc-ui:2.8.0 사용 (~6MB) → 전체 제거.
    // 개발용 API 문서는 Postman 컬렉션 또는 별도 Markdown으로 대체.

    // Security + JWT
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // JPA + PostgreSQL (배포) + H2 (로컬 개발)
    // H2: Oracle Cloud 배포 시 prod 프로파일에서 DataSource URL 로 사용 안 함 (postgres 로 전환).
    //     JAR에는 포함되지만 (~2.6MB), 로컬 dev 필수라 유지.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

// jackson-dataformat-yaml 자동 의존성 제외 (미사용, ~2.5MB 절감)
configurations.all {
    exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// .env 파일에서 환경변수 로드 (개발용)
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    environment(key, value)
                }
            }
    }
}
