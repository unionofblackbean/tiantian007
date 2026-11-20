package dev.superwy;

import dev.superwy.core.Config;
import java.nio.file.*;

/** Reads the separately packaged owner's preset without launching a game or opening a tunnel. */
public final class PrivatePresetCheck {
    public static void main(String[] args)throws Exception{
        Path path=Files.createTempDirectory(Path.of("build"),"private-preset-check-").resolve("config.json");
        var config=Config.load(path);
        if(!config.privatePresetApplied||!config.frp.enabled||config.frp.authToken.isEmpty()||!config.allowedHosts.contains(config.frp.serverAddr))throw new AssertionError("Private preset did not initialize FRP and allowed Host");
        String token=config.frp.authToken;config.frp.enabled=false;config.save(path);
        var restored=Config.load(path);if(restored.frp.enabled||!restored.frp.authToken.equals(token))throw new AssertionError("Private preset overwrote saved user settings");
        System.out.println("SUPERWY_PRIVATE_PRESET_OK: owner's jar initializes FRP credential once; allowed Host ready; saved stop state preserved; no network connections opened");
    }
}
