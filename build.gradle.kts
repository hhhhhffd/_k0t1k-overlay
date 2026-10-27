plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.k0t1k"
version = "0.2.0"

repositories {
    mavenCentral()
}

// The game supplies LWJGL, so the overlay only compiles against the oldest one it runs on (Minecraft
// 1.13). That matters beyond API: from 3.2 on GL30 extends GL11, so compiling against a newer LWJGL
// would bind GL11 methods as GL30.glEnable etc., which 3.1 does not have.
val lwjgl = "3.1.6"

// Backends that only exist in newer games compile against the oldest LWJGL that has them, in their
// own source sets, and are loaded by name, so the main code never links against them.
// Vulkan: Minecraft 1.20+ (VulkanMod) and 26.2+ ship LWJGL 3.3.1 or newer.
val lwjglVulkan = "3.3.1"
// SDL: Minecraft 26.3+.
val lwjglSdl = "3.4.3"
// LWJGL 2 (Minecraft 1.12.2 and older, Java 8): another API, so another source set.
val lwjgl2 = "2.9.3"

sourceSets {
    create("vulkan") {
        compileClasspath += sourceSets.main.get().output
    }
    create("sdl") {
        compileClasspath += sourceSets.main.get().output
    }
    create("lwjgl2") {
        compileClasspath += sourceSets.main.get().output
    }
}

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.18.16")
    compileOnly("org.lwjgl:lwjgl:$lwjgl")
    compileOnly("org.lwjgl:lwjgl-glfw:$lwjgl")
    compileOnly("org.lwjgl:lwjgl-opengl:$lwjgl")
    compileOnly("org.lwjgl:lwjgl-stb:$lwjgl")
    "vulkanCompileOnly"("org.lwjgl:lwjgl:$lwjglVulkan")
    "vulkanCompileOnly"("org.lwjgl:lwjgl-vulkan:$lwjglVulkan")
    "sdlCompileOnly"("org.lwjgl:lwjgl:$lwjglSdl")
    "sdlCompileOnly"("org.lwjgl:lwjgl-sdl:$lwjglSdl")
    "sdlCompileOnly"("org.lwjgl:lwjgl-opengl:$lwjglSdl")
    "lwjgl2CompileOnly"("org.lwjgl.lwjgl:lwjgl:$lwjgl2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Minecraft 1.13-1.16 still runs on Java 8.
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Xlint:-classfile"))
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    from(sourceSets["vulkan"].output, sourceSets["sdl"].output, sourceSets["lwjgl2"].output)
    archiveBaseName = "k0t1k-overlay"
    archiveClassifier = ""
    // Mods may bring their own Byte Buddy; ours must never clash with it.
    relocate("net.bytebuddy", "dev.k0t1k.overlay.shadow.bytebuddy")
    exclude("META-INF/versions/*/module-info.class", "module-info.class", "META-INF/maven/**")
    manifest {
        attributes(
            "Premain-Class" to "dev.k0t1k.overlay.Agent",
            "Implementation-Version" to project.version,
        )
    }
}

// The SPIR-V in src/main/resources is committed, so building needs no shader compiler. After editing
// src/main/shaders, run this task (it needs glslc from shaderc on PATH).
val compileShaders by tasks.registering(Exec::class) {
    val out = "src/main/resources/dev/k0t1k/overlay/impl"
    commandLine("sh", "-c",
        "glslc -O src/main/shaders/overlay.vert -o $out/overlay.vert.spv && " +
            "glslc -O src/main/shaders/overlay.frag -o $out/overlay.frag.spv")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
