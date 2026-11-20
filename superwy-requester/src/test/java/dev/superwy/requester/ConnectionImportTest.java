package dev.superwy.requester;

import dev.superwy.protocol.ConnectionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionImportTest {
    @TempDir Path dir;
    @Test void importSavesHttpConnectionAndPreservesMaterialPreferences()throws Exception{
        var c=new RequesterConfig();c.defaultCount=12;c.defaultItem="minecraft:iron_ingot";
        var code=ConnectionCode.encode(new ConnectionCode.Settings("http://example.com:54321","a".repeat(43),"Cargo","Bot","main"));
        var draft=c.withConnection(code);draft.save(dir.resolve("config.json"));var loaded=RequesterConfig.load(dir.resolve("config.json"));
        assertEquals("http",loaded.transport);assertEquals("Cargo",loaded.fakePlayerName);assertEquals("Bot",loaded.botName);assertEquals(12,loaded.defaultCount);assertEquals("minecraft:iron_ingot",loaded.defaultItem);loaded.validateOrder();assertEquals("http",c.transport);
    }
    @Test void invalidImportLeavesExistingConfigUnchanged(){var c=new RequesterConfig();String before=RequesterConfig.GSON.toJson(c);assertThrows(IllegalArgumentException.class,()->c.withConnection("swc1.broken"));assertEquals(before,RequesterConfig.GSON.toJson(c));}
    @Test void unboundConnectionDefaultsToSfAndDoesNotPersistBeforeConfirmation()throws Exception{
        var c=new RequesterConfig();c.fakePlayerName="OldCargo";var path=dir.resolve("config.json");c.save(path);
        String code=ConnectionCode.encode(new ConnectionCode.Settings("http://example.com:23456","b".repeat(43),"","Bot","main"));
        var draft=c.withConnection(code);assertEquals("sf",draft.fakePlayerName);assertEquals("OldCargo",RequesterConfig.load(path).fakePlayerName);
        draft.fakePlayerName="ChosenCargo";draft.save(path);assertEquals("ChosenCargo",RequesterConfig.load(path).fakePlayerName);
        var changed=RequesterConfig.load(path);changed.fakePlayerName="OtherCargo";changed.save(path);assertEquals("OtherCargo",RequesterConfig.load(path).fakePlayerName);
    }
}
