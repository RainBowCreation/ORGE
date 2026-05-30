package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineFactoryTest {

    @Test
    void createReturnsAnEngine() {
        OrgeEngine e = EngineFactory.create();
        assertNotNull(e);
        // On this linux-x64 sandbox the native is bundled, so we expect NativeEngine;
        // on a platform without it, the factory must still return a (Stub) engine.
        assertTrue(e instanceof NativeEngine || e instanceof StubEngine);
        if (NativeLoader.isLoaded()) assertInstanceOf(NativeEngine.class, e);
    }
}
