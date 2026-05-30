package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoaderTest {

    @Test
    void osTokens() {
        assertEquals("linux", NativeLoader.osToken("Linux"));
        assertEquals("windows", NativeLoader.osToken("Windows 11"));
        assertEquals("macos", NativeLoader.osToken("Mac OS X"));
    }

    @Test
    void archTokens() {
        assertEquals("x64", NativeLoader.archToken("amd64"));
        assertEquals("x64", NativeLoader.archToken("x86_64"));
        assertEquals("arm64", NativeLoader.archToken("aarch64"));
    }

    @Test
    void libFileNamePerOs() {
        assertEquals("liborge.so", NativeLoader.libFileName("linux"));
        assertEquals("orge.dll", NativeLoader.libFileName("windows"));
        assertEquals("liborge.dylib", NativeLoader.libFileName("macos"));
    }

    @Test
    void unknownOsThrows() {
        assertThrows(IllegalStateException.class, () -> NativeLoader.osToken("Plan9"));
    }
}
