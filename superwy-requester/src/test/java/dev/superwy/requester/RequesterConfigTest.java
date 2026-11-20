package dev.superwy.requester;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RequesterConfigTest {
    @Test void nativeUnboundAndTemporaryConflictsSurviveReload()throws Exception{
        var file=dir.resolve("native.json");var config=new RequesterConfig();config.nativeHotkeysInitialized=true;
        config.pickupKey=RequestHotkey.UNBOUND;config.confirmKey=RequestHotkey.UNBOUND;config.queueKey=RequestHotkey.scanCode(44);config.save(file);
        var loaded=RequesterConfig.load(file);assertEquals(RequestHotkey.UNBOUND,loaded.pickupKey);assertTrue(RequestHotkey.isScanCode(loaded.queueKey));
        assertDoesNotThrow(loaded::validateDistinctHotkeys);
        loaded.pickupKey=71;loaded.confirmKey=71;assertDoesNotThrow(loaded::validateSettings);assertThrows(IllegalArgumentException.class,loaded::validateDistinctHotkeys);
        loaded.save(file);assertDoesNotThrow(()->RequesterConfig.load(file));
    }
    @Test void customHotkeysPersistAndOldSettingsUseDefaults()throws Exception{
        var file=dir.resolve("keys.json");Files.writeString(file,"{}");var config=RequesterConfig.load(file);
        assertEquals(-3,config.pickupKey);assertEquals(257,config.confirmKey);assertEquals(92,config.queueKey);
        config.pickupKey=71;config.confirmKey=-5;config.queueKey=86;config.save(file);var restored=RequesterConfig.load(file);
        assertEquals(71,restored.pickupKey);assertEquals(-5,restored.confirmKey);assertEquals(86,restored.queueKey);
        restored.queueKey=71;assertThrows(IllegalArgumentException.class,restored::validateSettings);
    }
    @TempDir Path dir;
    @Test void ordersAlwaysUseHttpAndMeshModeIsOffByDefault()throws Exception{
        var config=RequesterConfig.load(dir.resolve("config.json"));assertEquals("http",config.transport);assertFalse(config.mesh);assertEquals(64,config.defaultCount);
        assertThrows(IllegalArgumentException.class,config::validateOrder,"an HTTP token is required");
        var legacy=dir.resolve("legacy.json");Files.writeString(legacy,"{\"transport\":\"chat\",\"botName\":\"WarehouseBot\"}");
        var loaded=RequesterConfig.load(legacy);assertEquals("http",loaded.transport,"old private-message configs switch to HTTP");
        loaded.mesh=true;loaded.save(legacy);assertTrue(RequesterConfig.load(legacy).mesh);
    }
    @Test void gameSettingsAndOptionalKeySurviveRestart()throws Exception{
        var file=dir.resolve("config.json");var config=new RequesterConfig();config.botName="WarehouseBot";config.fakePlayerName="Cargo";config.shortKey="Ab123";config.defaultItem="minecraft:iron_ingot";config.defaultCount=12;config.save(file);
        var loaded=RequesterConfig.load(file);assertEquals("WarehouseBot",loaded.botName);assertEquals("Ab123",loaded.shortKey);assertEquals(12,loaded.defaultCount);
        loaded.shortKey="";loaded.transport="http";loaded.token="test-fixture-token-12345678901234567890";loaded.save(file);assertDoesNotThrow(RequesterConfig.load(file)::validateOrder);
    }
    @Test void invalidNamesAndNonFiveCharacterKeysNeverPersist(){
        var config=new RequesterConfig();config.shortKey="1234";assertThrows(IllegalArgumentException.class,config::validateSettings);
        config.shortKey="123456";assertThrows(IllegalArgumentException.class,config::validateSettings);config.shortKey="";config.botName="@a";assertThrows(IllegalArgumentException.class,config::validateSettings);
    }
}
